package dev.locus.feature.chat

import dev.locus.core.model.TaskBudget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ModelDirectory
import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.model.ModelRef
import dev.locus.core.data.repository.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.locus.core.agent.tools.Plans
import dev.locus.core.agent.tools.Todos
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.Branch
import dev.locus.core.model.Effort
import dev.locus.core.model.effectiveModel
import dev.locus.core.model.overrideFor
import dev.locus.core.model.ModeNote
import dev.locus.core.model.startsTurn
import dev.locus.core.model.ProposedPlan
import dev.locus.core.model.TodoItem

/** One row in the transcript. Tool calls are paired with their results for display. */
sealed interface ChatItem {
    val key: String

    /** [branch] is set when this message has alternatives (earlier or later edits of it). */
    data class User(
        override val key: String,
        val text: String,
        val attachments: List<Part.Attachment> = emptyList(),
        val planMode: Boolean = false,
        val branch: Branch? = null,
        /** Sent while the agent worked and read at a step boundary, within the task (see [ModeNote.MID_TASK]). */
        val midTask: Boolean = false,
    ) : ChatItem

    /**
     * The reply that ends a turn (text after the turn's last tool call), with what the whole turn cost.
     * [turn] says where the turn branches; set on the reply that ends a turn.
     */
    data class Assistant(
        override val key: String,
        val text: String,
        val usage: TurnUsage? = null,
        val turn: TurnBranch? = null,
        /** For an on-device reply: backend and speed, e.g. "GPU · ≈18 tok/s". Not saved; recent replies only. */
        val speed: String? = null,
        /** When a backup model answered: "Answered by gpt-5.2 · claude-opus-5-5 (Anthropic) couldn't answer". */
        val fallbackNote: String? = null,
    ) : ChatItem

    /** The end of a turn that has no reply text (it stopped, failed or ended on a step): its branch controls. */
    data class TurnEnd(override val key: String, val turn: TurnBranch) : ChatItem

    /** Narration between tool steps ("Let me look at…"): shown as a quiet step note, not a reply. */
    data class Note(override val key: String, val text: String) : ChatItem

    /** Where older messages were summarised; the model only sees the summary from here on. */
    data class Checkpoint(override val key: String, val summary: String, val replaced: Int) : ChatItem
    data class ToolCall(override val key: String, val name: String, val argumentsJson: String, val result: Part.ToolResult?) : ChatItem

    /**
     * What the model showed of its reasoning for one step. [inSteps] when that step went on to call
     * tools (it sits in the steps card); otherwise it sits just above the reply.
     */
    data class Thought(override val key: String, val text: String, val durationMs: Long, val inSteps: Boolean) : ChatItem

    /** A plan the agent proposed (plan_propose), with what became of it. */
    data class Plan(override val key: String, val plan: ProposedPlan, val status: PlanStatus) : ChatItem

    /**
     * A workspace file the agent handed to the user (send_file), shown as a file card at the end of
     * its turn. [file] is what's on disk now; null once the file was moved or deleted.
     */
    data class SentFile(override val key: String, val path: String, val note: String?, val file: SentFileInfo? = null) : ChatItem

    /**
     * A build or test command the agent ran (see [dev.locus.core.model.Verification]): pass or fail at a
     * glance, with the raw output one tap away. [call] is the run_command call it came from.
     */
    data class Verification(override val key: String, val call: ToolCall, val outcome: dev.locus.core.model.Verification.Outcome) : ChatItem

    /** The workspace files a turn changed, at the end of the turn: counts, diffs and undo. [turnId] keys the turn. */
    data class Changes(override val key: String, val turnId: String, val files: List<dev.locus.core.data.repository.TrackedChange>) : ChatItem

    /**
     * A finished task's receipt, under its last turn (see docs/design/task-receipt.md): one line,
     * expandable. UI only; it's built from stored data and never reaches the model.
     */
    data class Receipt(override val key: String, val receipt: dev.locus.core.model.TaskReceipt) : ChatItem {
        /** The changes card it links to (the first turn that changed files). */
        val changesKey: String? get() = receipt.changeTurns.firstOrNull()?.let { "$it:changes" }

        /** The verification card it links to: the first failing check, else the first. */
        val checkKey: String? get() = (receipt.checks.firstOrNull { it.passed != true } ?: receipt.checks.firstOrNull())?.let { "${it.messageId}:${it.callId}" }
    }
}

/**
 * Where a turn sits among its alternatives. [from] is the message the turn answers (a user message or
 * checkpoint): regenerating runs from there. [branch] is null while the turn has no alternatives.
 */
data class TurnBranch(val from: String, val branch: Branch?, val last: Boolean)

/** A sent file as it is on disk now. */
data class SentFileInfo(val name: String, val size: Long, val absolutePath: String, val mimeType: String, val relativePath: String)

enum class PlanStatus {
    /** The latest thing in the chat: waiting for Approve / Edit / Cancel. */
    Waiting,
    Approved,

    /** The user replied with something else (changes, or a new request). */
    Answered,
}

/**
 * Tokens for one turn (a user message up to the next): the last request's prompt size, and the
 * output and cache reads summed over every request the turn made.
 */
data class TurnUsage(val lastInput: Int, val output: Long, val input: Long, val cacheRead: Long)

/** A provider as the chat header shows it: badge, name and model. */
data class ChatProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val onDevice: Boolean,
    val contextWindow: Int? = null,
    /** Whether the composer's effort control applies (Anthropic, OpenAI Responses). */
    val supportsEffort: Boolean = false,
    /** The provider's own effort setting, shown as the default. */
    val defaultEffort: Effort = Effort.HIGH,
    val format: dev.locus.core.model.ApiFormat = dev.locus.core.model.ApiFormat.OPENAI_CHAT,
)

/** The composer's per-chat settings. */
data class ChatControls(
    /** The model this chat uses (the provider's, or the chat's own choice). */
    val model: String = "",
    val modelOverridden: Boolean = false,
    val effort: Effort = Effort.HIGH,
    val effortOverridden: Boolean = false,
    val planMode: Boolean = false,
    val disabledTools: Set<String> = emptySet(),
    /** The task budget from Settings, used when the chat has none of its own. */
    val defaultBudget: TaskBudget = TaskBudget.OFF,
    /** Why the cost limit can't apply to this chat's model (no prices known), or null when it can. */
    val costNote: String? = null,
) {
    /** Tool groups and connectors switched off, for the tools chip's count (the mode and budget entries share the set). */
    val disabledCount: Int get() = disabledTools.count { !TaskBudget.isBudgetKey(it) && !dev.locus.core.model.ChatMode.isModeKey(it) }

    /** Read-only, Workspace or Full; kept in the same per-chat set as the switched-off tools. */
    val mode: dev.locus.core.model.ChatMode get() = dev.locus.core.model.ChatMode.of(disabledTools)

    /** The chat's own task budget, or null when it uses [defaultBudget]. */
    val ownBudget: TaskBudget? get() = TaskBudget.overrideIn(disabledTools)

    /** The budget this chat's next task runs with. */
    val budget: TaskBudget get() = ownBudget ?: defaultBudget
}

/**
 * Why a cost limit can't apply to [model] on [provider], or null when its prices are known: the
 * models.dev catalogue is off, hasn't loaded, or lists no price for it.
 */
internal fun costNote(s: dev.locus.core.model.AppSettings, provider: dev.locus.core.model.ProviderConfig?, model: String, catalog: ModelCatalogIndex?): String? = when {
    provider == null -> null
    !s.modelCatalog -> "Cost needs model prices: turn on model details (models.dev) in Settings → Models."
    catalog == null -> "Model prices haven't loaded yet; open the model list once to fetch them."
    catalog.lookup(provider.baseUrl, provider.format, model)?.price == null -> "models.dev lists no price for $model, so cost can't be estimated."
    else -> null
}

/** A connector the tools menu can switch off for this chat. */
data class ChatConnector(val id: String, val name: String, val mcp: Boolean)

/** What the model sheet lists besides the providers: their models, favourites, recents and catalogue facts. */
data class ModelPickerData(
    val lists: Map<String, ModelList> = emptyMap(),
    val favorites: List<ModelRef> = emptyList(),
    val recents: List<ModelRef> = emptyList(),
    /** Whether the models.dev catalogue is switched on (it may not have arrived yet). */
    val catalogOn: Boolean = false,
    val catalog: ModelCatalogIndex? = null,
)

/** Token accounting for one chat: how full the context is now, and what all its requests used. */
data class ChatTokens(
    /** Prompt + output of the latest request: how much of the context window is in use. */
    val context: Int = 0,
    val window: Int? = null,
    val requests: Int = 0,
    val totalInput: Long = 0,
    val totalOutput: Long = 0,
    val totalCached: Long = 0,
    val totalCacheWritten: Long = 0,
    /** The latest requests' cache use, oldest first, so cache hits can be checked step by step. */
    val recentSteps: List<StepCache> = emptyList(),
) {
    val fraction: Float? get() = window?.takeIf { it > 0 }?.let { (context.toFloat() / it).coerceIn(0f, 1f) }
}

data class ChatUiState(
    val title: String = "",
    /** When the chat started (epoch ms), for exports. */
    val createdAt: Long = 0,
    val provider: ChatProvider? = null,
    val providers: List<ChatProvider> = emptyList(),
    val items: List<ChatItem> = emptyList(),
    /** Run state without [RunState.streamingText]; that streams separately ([ChatViewModel.streamingText]). */
    val run: RunState = RunState(),
    val loading: Boolean = true,
    /** Set while the agent waits for the user to do something in the browser. */
    val browserHandoff: String? = null,
    /** Files picked in the composer, already copied into the workspace, waiting to be sent. */
    val pendingAttachments: List<Part.Attachment> = emptyList(),
    val tokens: ChatTokens = ChatTokens(),
    /** Whether the agent's browser has a page to show in the live panel. */
    val browserHasPage: Boolean = false,
    /** The browser's current page, for the live panel's header. */
    val browserTitle: String = "",
    val browserUrl: String = "",
    val controls: ChatControls = ChatControls(),
    val connectors: List<ChatConnector> = emptyList(),
    /** The checklist of the current turn (latest todo_write), or null when it has none. */
    val todos: List<TodoItem>? = null,
    val picker: ModelPickerData = ModelPickerData(),
) {
    val pendingApproval: ApprovalRequest? get() = run.pendingApproval

    /** The agent's open question (`ask_user`) in this chat, oldest first. */
    val pendingQuestion: dev.locus.core.agent.tools.UserQuestion? get() = run.pendingQuestion
}

@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val conversationId: String,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val directory: ModelDirectory,
    private val runner: AgentRunner,
    val browser: AgentBrowser,
    private val workspace: WorkspaceRepository,
    library: LibraryRepository,
    private val files: dev.locus.core.agent.tools.Workspace,
    private val onDevice: dev.locus.core.ondevice.OnDeviceEngines,
    private val sentRequests: dev.locus.core.data.agent.SentRequests,
    private val sideQuestions: dev.locus.core.data.agent.SideQuestions,
    private val changes: dev.locus.core.data.repository.ChangeRepository,
    @param:dev.locus.core.data.di.DefaultDispatcher private val compute: kotlinx.coroutines.CoroutineDispatcher,
    private val receipts: dev.locus.core.data.repository.TaskReceiptRepository,
    /** Projects, for the header chip and moving an empty chat; null in tests that don't need them. */
    private val projectRepository: dev.locus.core.data.repository.ProjectRepository? = null,
) : ViewModel() {
    /** This chat's project and the others it could move to or continue in (docs/design/projects.md). */
    val projectInfo: StateFlow<ChatProjectInfo> = kotlinx.coroutines.flow.combine(
        conversations.observe(conversationId),
        projectRepository?.all ?: kotlinx.coroutines.flow.flowOf(emptyList()),
    ) { chat, all -> ChatProjectInfo(all.firstOrNull { it.id == chat?.projectId }, all) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), ChatProjectInfo())

    /** Moves this chat into [projectId] (null: out of projects). Only works before its first message. */
    fun moveToProject(projectId: String?) = viewModelScope.launch {
        if (!conversations.moveToProject(conversationId, projectId)) {
            _notice.value = "This chat has already started, so it stays where it is. Start a new chat in the project instead."
        }
    }

    /** Starts a chat in [projectId] that picks up from this one, through chat_history_read. */
    fun continueInProject(projectId: String, onCreated: (id: String, draft: String) -> Unit) = viewModelScope.launch {
        val title = conversations.get(conversationId)?.title.orEmpty()
        val chat = conversations.create(null, projectId)
        onCreated(chat.id, "Continue from our earlier chat “$title” (id $conversationId): read it with chat_history_read first.")
    }

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-off note about a project change that couldn't be made. */
    val projectNotice: StateFlow<String?> = _notice

    fun dismissProjectNotice() { _notice.value = null }

    @AssistedFactory
    interface Factory {
        fun create(conversationId: String): ChatViewModel
    }

    private val _sent = MutableStateFlow<SentState?>(null)

    /** The "What was sent" sheet: null when closed. */
    val sent: StateFlow<SentState?> = _sent

    /** Opens "What was sent" for the request behind reply [replyId], or the chat's latest request when null. */
    fun showSent(replyId: String?) {
        _sent.value = SentState.Loading
        viewModelScope.launch {
            val view = runCatching { sentRequests.view(conversationId, replyId) }.getOrNull()
            // Closed while it was loading: stay closed.
            if (_sent.value == SentState.Loading) _sent.value = view?.let { SentState.Ready(it) } ?: SentState.Missing
        }
    }

    /** Whether "What was sent" can show the request behind reply [replyId]: it's kept in memory only, for recent requests. */
    fun sentKept(replyId: String): Boolean = sentRequests.hasReply(conversationId, replyId)

    fun hideSent() {
        _sent.value = null
    }

    private val pending = MutableStateFlow<List<Part.Attachment>>(emptyList())

    init {
        // The saved catalogue, when it's on, so the budget sheet knows whether this model's prices are known.
        viewModelScope.launch { directory.index() }
    }

    private val runState = runner.states.map { it[conversationId] ?: RunState() }

    /**
     * The streamed reply so far, apart from [uiState]: it changes many times a second, and only the live
     * bubble reads it, so the rest of the screen doesn't recompose (or re-derive the transcript) per delta.
     */
    val streamingText: StateFlow<String> = runState.map { it.streamingText }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** The live reasoning, apart from [uiState] for the same reason as [streamingText]. */
    val reasoningText: StateFlow<String> = runState.map { it.reasoning }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Transcript rows and token totals depend only on the saved messages; derived once per change, off the main thread. */
    private val transcript = combine(conversations.observeThread(conversationId), changes.observe(conversationId), receipts.inputs(conversationId)) { thread, changed, inputs ->
        val receiptList = inputs.receipts(thread.messages, changed)
        Transcript(
            toItems(thread.messages, thread.branches, changed.groupBy { it.turnId }, receiptList),
            tokens(thread.messages, window = null),
            currentTodos(thread.messages),
        )
    }.flowOn(compute)

    /** Whether change [id] can be undone, and whether its file changed since; asked before confirming. */
    suspend fun checkUndo(id: Long): dev.locus.core.data.repository.UndoCheck = changes.check(id)

    /** [checkUndo] for a whole turn ("Undo all"). */
    suspend fun checkUndoTurn(turnId: String): dev.locus.core.data.repository.UndoCheck = changes.checkTurn(conversationId, turnId)

    /**
     * Undoes changes [ids] (one file) or, with [turnId], every change of that turn still standing. The
     * agent is told before its next request, and must read those files again before changing them.
     */
    fun undo(ids: List<Long> = emptyList(), turnId: String? = null) {
        viewModelScope.launch {
            val paths = runCatching { if (turnId != null) changes.undoTurn(conversationId, turnId) else changes.undo(ids) }.getOrDefault(emptyList())
            runner.noteUndo(conversationId, paths)
        }
    }

    val uiState: StateFlow<ChatUiState> = combine(
        conversations.observe(conversationId),
        // With recent on-device reply speeds, which live in memory only.
        transcript.combine(onDevice.recentTurns, ::Pair),
        // Without the streamed text, so this only emits when something other than the text changes.
        runState.map { it.copy(streamingText = "", reasoning = "") }.distinctUntilChanged(),
        settings.settings,
        combine(browser.state, pending, directory.lists, directory.catalog, library.library) { b, p, m, cat, lib -> Extras(b, p, m, cat, lib.connectors.filterNot { it.draft }.map { ChatConnector(it.connector.id, it.connector.name, it.connector.kind == "mcp") }) },
    ) { conversation, (transcript, speeds), run, s, extras ->
        val (transcriptItems, tokens, todos) = transcript
        val browserState = extras.browser
        val attachments = extras.pending
        val provider = s.provider(conversation?.providerId)
        // The same rule the runner uses for requests, so header, sheet and requests agree.
        val model = provider?.effectiveModel(conversation?.modelOverride).orEmpty()
        val items = if (provider?.format == dev.locus.core.model.ApiFormat.ON_DEVICE) transcriptItems.withSpeeds(speeds) else transcriptItems
        ChatUiState(
            title = conversation?.title.orEmpty(),
            createdAt = conversation?.createdAt ?: 0,
            // A chat-chosen model's window isn't known; better no ring than a wrong one.
            tokens = tokens.copy(window = provider?.contextWindow?.takeIf { model == provider.model }),
            provider = provider?.let(::chatProvider)?.copy(model = model),
            providers = s.providers.map(::chatProvider),
            controls = ChatControls(
                model = model,
                modelOverridden = provider != null && model != provider.model,
                effort = conversation?.effort ?: Effort.fromWire(provider?.effort),
                effortOverridden = conversation?.effort != null,
                planMode = conversation?.planMode == true,
                disabledTools = conversation?.disabledTools.orEmpty(),
                defaultBudget = s.taskBudget,
                costNote = costNote(s, provider, model, extras.catalog.takeIf { s.modelCatalog }),
            ),
            connectors = extras.connectors,
            todos = todos,
            picker = ModelPickerData(extras.models, s.favoriteModels, s.recentModels, s.modelCatalog, extras.catalog.takeIf { s.modelCatalog }),
            items = items.markWaitingPlan(waiting = !run.running),
            run = run,
            loading = false,
            browserHandoff = browserState.handoffReason.takeIf { run.running },
            pendingAttachments = attachments,
            browserHasPage = browserState.hasPage,
            browserTitle = browserState.title,
            browserUrl = browserState.url,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String) {
        // While the agent waits on a question, a typed message is the answer (attachments stay for later).
        val question = runner.states.value[conversationId]?.pendingQuestion
        if (question != null && text.isNotBlank()) {
            runner.answerQuestion(question.callId, dev.locus.core.agent.tools.QuestionAnswer(text = text))
            return
        }
        // While the agent works, this queues for its next step boundary (steer and queue); see AgentRunner.send.
        runner.send(conversationId, text, pending.value)
        pending.value = emptyList()
    }

    /**
     * This chat's side questions (`/btw`): answered apart from the chat, never added to it, and never
     * stopping or reaching the running task. Null until the first one.
     */
    val aside: StateFlow<dev.locus.core.data.agent.SideThread?> = sideQuestions.threads.map { it[conversationId] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A side question (`/btw <question>`), or `/btw` alone to show the last one again. */
    internal fun askAside(command: SideCommand) = when (command) {
        is SideCommand.Ask -> sideQuestions.ask(conversationId, command.question)
        SideCommand.Reopen -> sideQuestions.reopen(conversationId)
    }

    fun dismissAside() = sideQuestions.dismiss(conversationId)

    fun clearAside() = sideQuestions.clear(conversationId)

    /** Changes a queued message. If it was sent meanwhile, the new text goes as a message of its own, so nothing typed is lost. */
    fun editQueued(id: String, text: String) {
        if (text.isBlank()) return
        if (!runner.editQueued(conversationId, id, text)) runner.send(conversationId, text)
    }

    /** Cancels a queued message before it's sent. */
    fun cancelQueued(id: String) {
        runner.cancelQueued(conversationId, id)
    }

    /** Throws away the messages still queued (after Stop, an error or a restart). */
    fun discardQueued() = runner.discardQueued(conversationId)

    fun attach(uris: List<android.net.Uri>) = viewModelScope.launch {
        pending.value = pending.value + uris.mapNotNull { runCatching { workspace.importAttachment(it) }.getOrNull() }
    }

    fun removeAttachment(a: Part.Attachment) {
        pending.value = pending.value - a
    }

    fun stop() = runner.cancel(conversationId)

    fun compactNow() = runner.compactNow(conversationId)

    fun resume() = runner.resume(conversationId)

    /** Uses [model] of [providerId] for this chat; null means that provider's default. */
    fun setModel(providerId: String, model: String?) = viewModelScope.launch {
        val provider = settings.current().providers.firstOrNull { it.id == providerId } ?: return@launch
        conversations.setModel(conversationId, providerId, provider.overrideFor(model))
        settings.recordRecentModel(ModelRef(providerId, model ?: provider.model))
    }

    /** The model sheet opened: list every provider's models (shared, cached) and the catalogue if on. */
    fun prepareModels() = directory.prepare()

    fun toggleFavorite(ref: ModelRef) = viewModelScope.launch { settings.toggleFavoriteModel(ref) }

    fun enableCatalog() = directory.enableCatalog()

    fun setEffort(effort: Effort?) = viewModelScope.launch { conversations.setEffort(conversationId, effort) }

    fun setPlanMode(on: Boolean) = viewModelScope.launch { conversations.setPlanMode(conversationId, on) }

    /** This chat's own task budget; null goes back to the default from Settings. Applies from the next task. */
    fun setBudget(budget: TaskBudget?) = viewModelScope.launch {
        val current = conversations.get(conversationId)?.disabledTools.orEmpty()
        conversations.setDisabledTools(conversationId, TaskBudget.withOverride(current, budget))
    }

    /** Continue (one more increment) or Stop at a budget pause. */
    fun answerBudget(continueTask: Boolean) = runner.answerBudget(conversationId, continueTask)

    /** Switches a tool group or connector ([ToolGroup.key] / [ToolGroup.connectorKey]) on or off for this chat. */
    fun setToolEnabled(key: String, enabled: Boolean) = viewModelScope.launch {
        val current = conversations.get(conversationId)?.disabledTools.orEmpty()
        conversations.setDisabledTools(conversationId, if (enabled) current - key else current + key)
    }

    /** Read-only, Workspace or Full for this chat; applies from the next tool call, even mid-run. */
    fun setMode(mode: dev.locus.core.model.ChatMode) = viewModelScope.launch {
        val current = conversations.get(conversationId)?.disabledTools.orEmpty()
        conversations.setDisabledTools(conversationId, dev.locus.core.model.ChatMode.withMode(current, mode))
    }

    fun approvePlan() = runner.approvePlan(conversationId)

    /** Cancel on a proposed plan: nothing runs, and plan mode goes off so the card stops asking. */
    fun cancelPlan() = viewModelScope.launch { conversations.setPlanMode(conversationId, false) }

    fun answer(callId: String, approved: Boolean) = runner.answer(callId, approved)

    fun answerQuestion(callId: String, answer: dev.locus.core.agent.tools.QuestionAnswer) {
        runner.answerQuestion(callId, answer)
    }

    fun runAnyway(callId: String) = runner.runAnyway(conversationId, callId)

    fun dismissReviewerBlock(callId: String) = runner.dismissReviewerBlock(conversationId, callId)

    fun dismissMessages() = runner.dismissMessages(conversationId)

    /** Runs the turn after [fromMessageId] again, as a new branch beside the old reply. */
    fun regenerate(fromMessageId: String) = runner.regenerate(conversationId, fromMessageId)

    /** Sends [text] as an edit of the user message [messageId], on a new branch; composer attachments are added. */
    fun edit(messageId: String, text: String) {
        runner.edit(conversationId, messageId, text, pending.value)
        pending.value = emptyList()
    }

    /** Shows the branch through [messageId] (a sibling from a ‹ i/n › switcher). */
    fun switchBranch(messageId: String) = runner.switchBranch(conversationId, messageId)

    /**
     * Starts a new chat from this one: up to and including the reply [messageId], or for a user message
     * everything before it, with its text handed back as a draft. Calls [onCreated] with the new chat.
     */
    fun fork(messageId: String, userMessage: Boolean, onCreated: (id: String, draft: String?) -> Unit) = viewModelScope.launch {
        val draft = if (userMessage) conversations.history(conversationId).firstOrNull { it.id == messageId }?.userText else null
        conversations.fork(conversationId, messageId, inclusive = !userMessage)?.let { onCreated(it.id, draft) }
    }

    /** Copies a chat export into the workspace's `exports` folder (never overwriting); returns its workspace path. */
    suspend fun saveExport(name: String, source: java.io.File): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var target = files.resolve("exports/$name")
        var n = 2
        while (target.exists()) target = files.resolve("exports/$stem (${n++}).$extension")
        target.parentFile?.mkdirs()
        source.copyTo(target)
        files.relative(target)
    }

    private fun toItems(
        messages: List<Message>,
        branches: Map<String, Branch>,
        changed: Map<String, List<dev.locus.core.data.repository.TrackedChange>>,
        receiptList: List<dev.locus.core.model.TaskReceipt>,
    ): List<ChatItem> =
        transcriptItems(messages, branches, changed, receiptList).map { if (it is ChatItem.SentFile) it.copy(file = sentFileInfo(it.path)) else it }

    /** Stats a sent file (on the transcript's background thread); null when it's gone or unsafe. */
    private fun sentFileInfo(path: String): SentFileInfo? = runCatching {
        val f = files.resolve(path)
        if (!f.isFile) return@runCatching null
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        SentFileInfo(f.name, f.length(), f.absolutePath, mime, files.relative(f))
    }.getOrNull()
}

private data class Transcript(val items: List<ChatItem>, val tokens: ChatTokens, val todos: List<TodoItem>?)

private data class Extras(
    val browser: dev.locus.core.browser.BrowserState,
    val pending: List<Part.Attachment>,
    val models: Map<String, ModelList>,
    val catalog: ModelCatalogIndex?,
    val connectors: List<ChatConnector>,
)

/**
 * One user message to the next is one turn. Text the model wrote before a later tool call in the
 * same turn is narration ([ChatItem.Note]); only text after the turn's last tool call is the reply.
 * [branches] (from the chat's thread) puts switchers on user messages and turns that have alternatives.
 */
internal fun transcriptItems(
    messages: List<Message>,
    branches: Map<String, Branch> = emptyMap(),
    changes: Map<String, List<dev.locus.core.data.repository.TrackedChange>> = emptyMap(),
    /** Finished tasks' receipts; each goes under the turn holding its [dev.locus.core.model.TaskReceipt.afterMessageId]. */
    receipts: List<dev.locus.core.model.TaskReceipt> = emptyList(),
): List<ChatItem> {
    val results = messages.filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
    val receiptsAfter = receipts.groupBy { it.afterMessageId }
    // Receipts whose task ended in the turn being collected; they go after everything that turn shows.
    val endedHere = mutableListOf<dev.locus.core.model.TaskReceipt>()
    val out = mutableListOf<ChatItem>()
    val turn = mutableListOf<Message>()
    // The message the current turn answers; a turn's first step is where regenerating branches.
    var from: String? = null
    var turnStart: String? = null
    val lastTurnStart = messages.lastOrNull { it.startsTurn() }?.let { last -> messages.drop(messages.indexOf(last) + 1).firstOrNull()?.id }
    fun flushTurn() {
        val start = turnStart
        val answered = from
        val info = if (start != null && answered != null) TurnBranch(answered, branches[start], last = start == lastTurnStart) else null
        // The files this turn changed, after everything else it showed.
        val changed = start?.let { changes[it] }?.takeIf { it.isNotEmpty() }?.let { ChatItem.Changes("$start:changes", start, it) }
        val items = turnItems(turn, results) + listOfNotNull(changed)
        val reply = items.indexOfLast { it is ChatItem.Assistant }
        out += when {
            info == null -> items
            reply >= 0 && items.drop(reply + 1).all { it is ChatItem.SentFile || it is ChatItem.Changes } ->
                items.mapIndexed { i, item -> if (i == reply) (item as ChatItem.Assistant).copy(turn = info) else item }
            info.branch != null || info.last -> items + ChatItem.TurnEnd("${start}:end", info)
            else -> items
        }
        out += endedHere.map { ChatItem.Receipt("receipt:${it.taskId}", it) }
        endedHere.clear()
        turn.clear()
        turnStart = null
    }
    messages.forEach { message ->
        val ends = receiptsAfter[message.id].orEmpty()
        // A note from Locus (an undo the agent was told about mid-task) belongs to the turn it joined; nothing to show.
        if (ModeNote.isAgentNote(message)) {
            endedHere += ends
            return@forEach
        }
        when (message.role) {
            Role.USER -> {
                flushTurn()
                from = message.id
                out += message.parts.filterIsInstance<Part.Checkpoint>().firstOrNull()
                    ?.let { ChatItem.Checkpoint(message.id, it.summary, it.replacedMessages) }
                    ?: ChatItem.User(
                        message.id, message.userText, message.attachments,
                        planMode = ModeNote.isPlanMode(message),
                        branch = branches[message.id],
                        midTask = ModeNote.isMidTask(message),
                    )
            }
            Role.TOOL -> if (turnStart == null) turnStart = message.id
            Role.ASSISTANT -> {
                if (turnStart == null) turnStart = message.id
                turn += message
            }
        }
        endedHere += ends
    }
    flushTurn()
    return resolvePlans(out)
}

/**
 * A plan's fate is decided by what follows it: the approval message, some other reply, or nothing yet.
 * Plans still waiting are marked [PlanStatus.Waiting] here; [markWaitingPlan] keeps that only while no
 * run is going.
 */
internal fun resolvePlans(items: List<ChatItem>): List<ChatItem> = items.mapIndexed { i, item ->
    if (item !is ChatItem.Plan) return@mapIndexed item
    val reply = items.drop(i + 1).firstOrNull { it is ChatItem.User } as ChatItem.User?
    item.copy(
        status = when {
            reply == null -> PlanStatus.Waiting
            reply.text.trim() == AgentControlTools.PLAN_APPROVED -> PlanStatus.Approved
            else -> PlanStatus.Answered
        },
    )
}

/** While a run is going, no plan is waiting on the user. */
private fun List<ChatItem>.markWaitingPlan(waiting: Boolean): List<ChatItem> =
    if (waiting) this else map { if (it is ChatItem.Plan && it.status == PlanStatus.Waiting) it.copy(status = PlanStatus.Answered) else it }

/**
 * The checklist of the latest turn: written after the last user message. Older turns' lists are history.
 * A message told mid-task belongs to the task it joined, so it doesn't start a new list.
 */
internal fun currentTodos(messages: List<Message>): List<TodoItem>? {
    val lastUser = messages.indexOfLast { it.startsTurn() && it.parts.none { p -> p is Part.Checkpoint } && !ModeNote.isMidTask(it) }
    return Todos.latest(messages.drop(lastUser + 1))?.takeIf { it.isNotEmpty() }
}

/** The note under a reply a backup model wrote: which model answered, and which couldn't. */
internal fun fallbackNote(message: Message): String? {
    val reply = message.reply ?: return null
    val from = reply.fallbackFrom ?: return null
    return "Answered by ${reply.model ?: "a backup model"} · $from couldn't answer"
}

internal fun turnItems(turn: List<Message>, results: Map<String, Part.ToolResult>): List<ChatItem> {
    if (turn.isEmpty()) return emptyList()
    // Files handed to the user go at the end of the turn, under the reply.
    val sent = mutableListOf<ChatItem.SentFile>()
    val entries = buildList<ChatItem> {
        turn.forEach { message ->
            message.reasoning?.let { r -> add(ChatItem.Thought("${message.id}:thought", r.content, r.durationMs, inSteps = message.toolCalls.isNotEmpty())) }
            if (message.text.isNotBlank()) add(ChatItem.Assistant(message.id, message.text, fallbackNote = fallbackNote(message)))
            message.toolCalls.forEach { made ->
                // A tool run through call_tool shows as that tool (docs/design/tool-discovery.md).
                val call = dev.locus.core.model.DiscoveryTools.unwrap(made.name, made.argumentsJson)
                    .let { (name, args) -> if (name == made.name) made else made.copy(name = name, argumentsJson = args) }
                val key = "${message.id}:${call.id}"
                when (call.name) {
                    // The checklist shows in its own card above the composer, not as a step.
                    AgentControlTools.TODO_WRITE -> Unit
                    AgentControlTools.PLAN_PROPOSE -> {
                        val plan = Plans.parse(call.argumentsJson)
                        if (plan != null && results[call.id]?.isError != true) add(ChatItem.Plan(key, plan, PlanStatus.Waiting))
                        else add(ChatItem.ToolCall(key, call.name, call.argumentsJson, results[call.id]))
                    }
                    dev.locus.core.agent.tools.SendFile.NAME -> {
                        val result = results[call.id]
                        val parsed = dev.locus.core.agent.tools.SendFile.parse(call.argumentsJson)
                        if (parsed != null && result != null && !result.isError) sent += ChatItem.SentFile(key, parsed.first, parsed.second)
                        else add(ChatItem.ToolCall(key, call.name, call.argumentsJson, result))
                    }
                    else -> {
                        val item = ChatItem.ToolCall(key, call.name, call.argumentsJson, results[call.id])
                        add(verificationOf(item) ?: item)
                    }
                }
            }
        }
    }
    val lastTool = entries.indexOfLast { it is ChatItem.ToolCall || it is ChatItem.Verification }
    val lastText = entries.indexOfLast { it is ChatItem.Assistant }
    val usages = turn.mapNotNull { it.usage }
    val usage = usages.lastOrNull()?.let { last ->
        TurnUsage(
            lastInput = last.input,
            output = usages.sumOf { it.output.toLong() },
            input = usages.sumOf { it.input.toLong() },
            cacheRead = usages.sumOf { it.cacheRead.toLong() },
        )
    }
    return entries.mapIndexed { i, item ->
        when {
            item !is ChatItem.Assistant -> item
            i < lastTool -> ChatItem.Note(item.key, item.text)
            i == lastText -> item.copy(usage = usage)
            else -> item
        }
    } + sent
}

/**
 * A finished run_command that built or tested something, as a verification card; null for anything else
 * (other commands, calls that didn't run, ones still running).
 */
internal fun verificationOf(call: ChatItem.ToolCall): ChatItem.Verification? {
    if (call.name != "run_command") return null
    val result = call.result ?: return null
    val outcome = dev.locus.core.model.Verification.ofRunCommand(call.argumentsJson, result.content, result.isError) ?: return null
    return ChatItem.Verification(call.key, call, outcome)
}

/** Puts the backend and speed under on-device replies this process produced (matched by their text). */
internal fun List<ChatItem>.withSpeeds(speeds: Map<String, dev.locus.core.ondevice.TurnSpeed>): List<ChatItem> =
    if (speeds.isEmpty()) this
    else map { item ->
        if (item !is ChatItem.Assistant) return@map item
        val line = speeds[item.text.trim()]?.let(dev.locus.core.ondevice.OnDeviceFormat::turnLine)
        if (line == null) item else item.copy(speed = line)
    }

internal fun chatProvider(p: dev.locus.core.model.ProviderConfig) =
    ChatProvider(
        p.id, p.name, p.baseUrl, p.model, p.format == dev.locus.core.model.ApiFormat.ON_DEVICE, p.contextWindow,
        supportsEffort = Effort.supports(p.format),
        defaultEffort = Effort.fromWire(p.effort),
        format = p.format,
    )

private fun tokens(messages: List<Message>, window: Int?): ChatTokens {
    val usages = messages.mapNotNull { it.usage }
    // After a compaction checkpoint the old size no longer applies; the next request reports the new one.
    val sinceCheckpoint = messages.drop(messages.indexOfLast { m -> m.parts.any { it is Part.Checkpoint } } + 1)
    return ChatTokens(
        context = sinceCheckpoint.mapNotNull { it.usage }.lastOrNull()?.context ?: 0,
        window = window,
        requests = usages.size,
        totalInput = usages.sumOf { it.input.toLong() },
        totalOutput = usages.sumOf { it.output.toLong() },
        totalCached = usages.sumOf { it.cacheRead.toLong() },
        totalCacheWritten = usages.sumOf { it.cacheWrite.toLong() },
        recentSteps = usages.withIndex().toList().takeLast(RECENT_STEPS).map { (i, u) -> StepCache(i + 1, u.input, u.cacheRead, u.cacheWrite, u.cacheWrite1h) },
    )
}

/** How many of the latest requests the context sheet lists with their cache use. */
private const val RECENT_STEPS = 8

/** One request's prompt and how much of it the provider read from or wrote to its cache. */
data class StepCache(val number: Int, val input: Int, val read: Int, val written: Int, val written1h: Int)
