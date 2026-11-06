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
import kotlinx.coroutines.flow.update
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
        /**
         * The receipt of a task that finished normally with this reply: its time and cost join the reply's
         * meta line, and tapping that line opens the receipt. UI only.
         */
        val receipt: dev.locus.core.model.TaskReceipt? = null,
        /**
         * Written after the chat read outside content (docs/design/taint.md, "Links in replies"): its links
         * to sites the user didn't name ask before opening. UI only.
         */
        val outsideContent: Boolean = false,
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
     * A provider the agent drafted (provider_draft), shown as a card at the end of its turn that links to
     * the review in Settings → Models (docs/design/provider-drafts.md). [name] and [host] are what the call asked for.
     */
    data class ProviderDraft(override val key: String, val name: String, val host: String) : ChatItem

    /**
     * A build or test command the agent ran (see [dev.locus.core.model.Verification]): pass or fail at a
     * glance, with the raw output one tap away. [call] is the run_command call it came from.
     */
    data class Verification(override val key: String, val call: ToolCall, val outcome: dev.locus.core.model.Verification.Outcome) : ChatItem

    /** The workspace files a turn changed, at the end of the turn: counts, diffs and undo. [turnId] keys the turn. */
    data class Changes(override val key: String, val turnId: String, val files: List<dev.locus.core.data.repository.TrackedChange>) : ChatItem

    /**
     * A finished task's receipt, under its last turn (see docs/design/task-receipt.md). UI only; it's
     * built from stored data and never reaches the model. [attached] when its turn's reply carries it
     * ([Assistant.receipt]): the transcript then shows nothing more for it, only exports and links use it.
     * Otherwise (failed, stopped, paused, or no reply) it's a compact status line.
     */
    data class Receipt(override val key: String, val receipt: dev.locus.core.model.TaskReceipt, val attached: Boolean = false) : ChatItem {
        /** The changes card it links to (the first turn that changed files). */
        val changesKey: String? get() = receipt.changeTurns.firstOrNull()?.let { "$it:changes" }

        /** The verification card it links to: the first failing check, else the first. */
        val checkKey: String? get() = (receipt.checks.firstOrNull { !it.confirmed } ?: receipt.checks.firstOrNull())?.let { "${it.messageId}:${it.callId}" }
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
    /** Whether the composer's effort control applies (Anthropic, OpenAI Responses, Chat Completions on hosts that take one). */
    val supportsEffort: Boolean = false,
    /** The provider's own effort setting, shown as the default: its saved level, else the model's own. */
    val defaultEffort: Effort = Effort.HIGH,
    /** How the default reads in the effort sheet, e.g. "Medium, the model's own default." */
    val defaultEffortNote: String = "",
    val format: dev.locus.core.model.ApiFormat = dev.locus.core.model.ApiFormat.OPENAI_CHAT,
)

/** The composer's per-chat settings. */
data class ChatControls(
    /** The model this chat uses (the provider's, or the chat's own choice). */
    val model: String = "",
    /** An on-device [model]'s name from its list ("Qwen3.5 0.8B"), as the model sheet shows it; null to shorten the id ([onDeviceModelName]). */
    val modelName: String? = null,
    val modelOverridden: Boolean = false,
    val effort: Effort = Effort.HIGH,
    val effortOverridden: Boolean = false,
    val planMode: Boolean = false,
    val disabledTools: Set<String> = emptySet(),
    /** The task budget from Settings, used when the chat has none of its own. */
    val defaultBudget: TaskBudget = TaskBudget.OFF,
    /** Why the cost limit can't apply to this chat's model (no prices known), or null when it can. */
    val costNote: String? = null,
    /** The chat declares no tools: it started on a model that can't use them ([dev.locus.core.model.Conversation.chatOnly]). */
    val chatOnly: Boolean = false,
    /** Nothing has been sent yet, so [chatOnly] still follows the model picked. */
    val empty: Boolean = true,
    /**
     * A temporary chat ([dev.locus.core.model.Conversation.temporary]): in a chat, fixed since it was
     * created; on home, the switch for the chat the ask bar starts next.
     */
    val temporary: Boolean = false,
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
    /** What this chat needs from a model ([chatNeeds]); the sheet marks models that can't serve it. Null outside a chat. */
    val needs: dev.locus.core.llm.ModelRequirements? = null,
    /** Settings → Models → Backup models, offered (after favourites and recents) when the chat's model can't take a message. */
    val backups: List<ModelRef> = emptyList(),
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
    /** [items] as the transcript list shows them: steps grouped into cards. */
    val rows: List<TranscriptRow> = emptyList(),
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
    /** Settings → Voice: read-aloud speed and voice mode (docs/design/voice-mode.md). */
    val voice: dev.locus.core.model.VoiceSettings = dev.locus.core.model.VoiceSettings(),
    /** Links in replies: what the chat has read and the hosts the user named or set up ([LinkContext]). */
    val links: LinkContext = LinkContext(outsideContent = false),
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
    private val library: LibraryRepository,
    private val files: dev.locus.core.agent.tools.Workspace,
    private val onDevice: dev.locus.core.ondevice.OnDeviceEngines,
    private val sentRequests: dev.locus.core.data.agent.SentRequests,
    private val sideQuestions: dev.locus.core.data.agent.SideQuestions,
    private val changes: dev.locus.core.data.repository.ChangeRepository,
    @param:dev.locus.core.data.di.DefaultDispatcher private val compute: kotlinx.coroutines.CoroutineDispatcher,
    private val receipts: dev.locus.core.data.repository.TaskReceiptRepository,
    /** Projects, for the header chip and moving an empty chat; null in tests that don't need them. */
    private val projectRepository: dev.locus.core.data.repository.ProjectRepository? = null,
    /** At critical heat, running Linux services to suggest stopping; null in tests that don't need it. */
    private val linuxHeat: dev.locus.core.data.device.LinuxHeatAdvice? = null,
    /** Remote execution, for the "Running on build-server" chip; null in tests that don't need it. */
    private val remoteExecution: dev.locus.core.data.remote.RemoteExecution? = null,
    /** Deletes a temporary chat once this screen is left (docs/design/temporary-chats.md); null in tests that don't need it. */
    private val temporaryChats: dev.locus.core.data.repository.TemporaryChats? = null,
) : ViewModel() {
    /** Services to suggest stopping while the phone is critically hot (never stopped without the user). */
    val heatServices: StateFlow<List<String>> = linuxHeat?.suggestion ?: MutableStateFlow(emptyList())

    fun stopServicesForHeat() = linuxHeat?.stopAll()

    /** Where this chat's provider drafts stand, for their cards (docs/design/provider-drafts.md). */
    val providerDrafts: StateFlow<ProviderDraftStatus> = settings.settings
        .map { s -> ProviderDraftStatus.of(s, conversationId) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProviderDraftStatus())

    private val _forgottenMemory = MutableStateFlow<Set<dev.locus.core.agent.home.MemoryRef>>(emptySet())

    /** Memory topics this chat read that are no longer saved (forgotten here, in Library or elsewhere). */
    val forgottenMemory: StateFlow<Set<dev.locus.core.agent.home.MemoryRef>> = _forgottenMemory

    /** Forget, from the steps card: the topic goes from the memory this chat uses. The transcript is unchanged. */
    fun forgetMemoryRead(ref: dev.locus.core.agent.home.MemoryRef) {
        viewModelScope.launch {
            library.forgetMemoryRead(ref, projectRepository?.scopeOf(conversations.get(conversationId)))
            _forgottenMemory.update { it + ref }
        }
    }

    /** Marks which of [refs] (a steps card's topics) are no longer saved, so the card says so instead of offering Forget. */
    fun checkMemoryReads(refs: List<dev.locus.core.agent.home.MemoryRef>) {
        viewModelScope.launch {
            val saved = library.memoryStillSaved(refs, projectRepository?.scopeOf(conversations.get(conversationId)))
            val gone = refs.toSet() - saved
            _forgottenMemory.update { (it - saved) + gone }
        }
    }

    fun dismissHeatServices() = linuxHeat?.dismiss()

    /** This chat is on screen: the full-screen browser shows its tabs (each chat has its own browser). */
    fun focusBrowser() = browser.focus(conversationId)

    /** The machine this chat's project runs its commands on, or null on the phone (docs/design/remote-execution.md). */
    val remoteInfo: StateFlow<ChatRemoteInfo?> = kotlinx.coroutines.flow.combine(
        conversations.observe(conversationId),
        settings.settings,
        remoteExecution?.activity ?: kotlinx.coroutines.flow.flowOf(emptyMap()),
    ) { chat, s, activity ->
        val projectId = chat?.projectId ?: return@combine null
        val linked = s.projectRemotes[projectId] ?: return@combine null
        val alias = s.sshHosts.firstOrNull { it.id == linked.hostId }?.alias ?: "a removed machine"
        ChatRemoteInfo(projectId, alias, linked.folder, activity[projectId])
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), null)

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
        // While this screen is open, a temporary chat isn't deleted.
        temporaryChats?.opened(conversationId)
    }

    override fun onCleared() {
        // Leaving a temporary chat deletes it (once a running task ends); other chats are untouched.
        temporaryChats?.left(conversationId)
        super.onCleared()
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

    /** Keep unchanged items and rows the same instances across emissions, so Compose skips them (see [InstanceKeeper]). */
    private val itemKeeper = InstanceKeeper<ChatItem> { it.key }
    private val rowKeeper = InstanceKeeper<TranscriptRow> { it.key }

    /** Whether the chat's provider runs on the device: its replies get a speed line ([withSpeeds]). */
    private val onDeviceChat = combine(conversations.observe(conversationId), settings.settings) { conversation, s ->
        s.provider(conversation?.providerId)?.format == dev.locus.core.model.ApiFormat.ON_DEVICE
    }.distinctUntilChanged()

    /**
     * Transcript rows and token totals depend only on the saved messages (and recent on-device speeds);
     * derived once per change of this chat, off the main thread. See docs/design/transcript-reads.md.
     */
    private val transcript = combine(
        conversations.observeThread(conversationId),
        // Both re-emit on writes to their tables from any chat; equal results change nothing here.
        changes.observe(conversationId).distinctUntilChanged(),
        receipts.inputs(conversationId).distinctUntilChanged(),
        // With recent on-device reply speeds, which live in memory only.
        onDevice.recentTurns,
        onDeviceChat,
    ) { thread, changed, inputs, speeds, onDeviceProvider ->
        val receiptList = inputs.receipts(thread.messages, changed)
        // A project chat's sent files live in its folder, as send_file resolved them (ChatScope).
        val scope = projectRepository?.scopeOf(conversations.get(conversationId))
        val built = toItems(thread.messages, thread.branches, changed.groupBy { it.turnId }, receiptList, scope)
            .markOutsideContent(dev.locus.core.agent.LinkGuard.repliesAfterOutsideContent(thread.messages))
        val items = itemKeeper.keep(if (onDeviceProvider) built.withSpeeds(speeds) else built)
        Transcript(
            items,
            rowKeeper.keep(groupSteps(items)),
            tokens(thread.messages, window = null),
            currentTodos(thread.messages),
            chatNeeds(thread.messages),
            outsideContent = dev.locus.core.agent.Taint.sourcesIn(thread.messages).isNotEmpty(),
            typedHosts = dev.locus.core.agent.LinkGuard.namedHosts(thread.messages, emptyList()),
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
        transcript,
        // Without the streamed text, so this only emits when something other than the text changes.
        runState.map { it.copy(streamingText = "", reasoning = "") }.distinctUntilChanged(),
        settings.settings,
        combine(browser.stateOf(conversationId), pending, directory.lists, directory.catalog, library.library) { b, p, m, cat, lib ->
            Extras(
                b, p, m, cat, lib.connectors.filterNot { it.draft }.map { ChatConnector(it.connector.id, it.connector.name, it.connector.kind == "mcp") },
                connectorAddresses = lib.connectors.filterNot { it.draft }.map { it.connector.baseUrl },
            )
        },
    ) { conversation, transcript, run, s, extras ->
        val (items, rows, tokens, todos, savedNeeds) = transcript
        val browserState = extras.browser
        val attachments = extras.pending
        val provider = s.provider(conversation?.providerId)
        // The same rule the runner uses for requests, so header, sheet and requests agree.
        val model = provider?.effectiveModel(conversation?.modelOverride).orEmpty()
        ChatUiState(
            title = conversation?.title.orEmpty(),
            createdAt = conversation?.createdAt ?: 0,
            // A chat-chosen model's window isn't known; better no ring than a wrong one.
            tokens = tokens.copy(window = provider?.contextWindow?.takeIf { model == provider.model }),
            provider = provider?.let(::chatProvider)?.copy(model = model),
            providers = s.providers.map(::chatProvider),
            controls = ChatControls(
                model = model,
                modelName = onDeviceModelName(provider?.let(::chatProvider), model, extras.models),
                modelOverridden = provider != null && model != provider.model,
                effort = conversation?.effort ?: provider?.let { Effort.ofProvider(it.effort, it.format, it.baseUrl, model) } ?: Effort.HIGH,
                effortOverridden = conversation?.effort != null,
                planMode = conversation?.planMode == true,
                disabledTools = conversation?.disabledTools.orEmpty(),
                defaultBudget = s.taskBudget,
                costNote = costNote(s, provider, model, extras.catalog.takeIf { s.modelCatalog }),
                chatOnly = conversation?.chatOnly == true,
                empty = items.isEmpty(),
                temporary = conversation?.temporary == true,
            ),
            connectors = extras.connectors,
            todos = todos,
            picker = ModelPickerData(
                extras.models, s.favoriteModels, s.recentModels, s.modelCatalog, extras.catalog.takeIf { s.modelCatalog },
                // A chat-only chat declares no tools, so any model can take it as far as tools go.
                needs = savedNeeds.withComposer(attachments, conversation?.effort).let { if (conversation?.chatOnly == true) it.copy(tools = false) else it },
                backups = s.fallbackModels,
            ),
            items = items.markWaitingPlan(waiting = !run.running),
            rows = rows.markWaitingPlanRows(waiting = !run.running),
            run = run,
            loading = false,
            browserHandoff = browserState.handoffReason.takeIf { run.running },
            pendingAttachments = attachments,
            browserHasPage = browserState.hasPage,
            browserTitle = browserState.title,
            browserUrl = browserState.url,
            voice = s.voice,
            links = LinkContext(
                outsideContent = transcript.outsideContent,
                // Hosts the user typed in this chat, plus model providers and connectors they set up: taint's trusted hosts.
                namedHosts = transcript.typedHosts + dev.locus.core.agent.LinkGuard.namedHosts(emptyList(), s.providers.map { it.baseUrl } + extras.connectorAddresses),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun send(text: String) {
        // While the agent waits on a question, a typed message is the answer (attachments stay for later).
        val question = runner.states.value[conversationId]?.pendingQuestion
        if (question != null && text.isNotBlank()) {
            runner.answerQuestion(question.callId, dev.locus.core.agent.tools.QuestionAnswer(text = text))
            return
        }
        // When the chat's model is known not to take this message (images without vision, a context past its
        // window), say so before sending instead of letting the request fail (docs/design/model-capabilities.md).
        warningFor(uiState.value, text)?.let {
            _sendWarning.value = it
            return
        }
        // An on-device reply while the phone is hot or in battery saver: ask first (docs/design/battery-thermal.md).
        heatHoldFor(uiState.value, text)?.let {
            _heatHold.value = it
            return
        }
        sendNow(text)
    }

    private val power = settings.settings.map { it.power }
        .stateIn(viewModelScope, SharingStarted.Eagerly, dev.locus.core.model.PowerSettings())

    /** The user chose for this chat while the phone stays hot or in battery saver; asked again once it's back to normal. */
    private var heatAnswered = false

    private val _heatHold = MutableStateFlow<HeatHold?>(null)

    /** A message held before an on-device reply because the phone is hot or in battery saver; null when none. */
    internal val heatHold: StateFlow<HeatHold?> = _heatHold

    private fun heatHoldFor(state: ChatUiState, text: String): HeatHold? {
        if (state.provider?.onDevice != true) return null
        val conditions = onDevice.conditions
        val warning = dev.locus.core.model.PowerPolicy.localWarning(
            conditions.state.value, power.value, conditions.headroom(),
            onGpu = onDevice.status.value.backend?.let { it == dev.locus.core.ondevice.Accelerator.GPU },
        )
        if (warning == null) {
            heatAnswered = false
            return null
        }
        if (heatAnswered || onDevice.lightMode.value) return null
        return HeatHold(warning, text)
    }

    /** Light mode on (until the phone is back to normal), then sends the held message. */
    fun sendLight() {
        val hold = _heatHold.value ?: return
        _heatHold.value = null
        heatAnswered = true
        onDevice.setLightMode(true)
        sendNow(hold.text)
    }

    /** Sends the held message as it is. */
    fun sendDespiteHeat() {
        val hold = _heatHold.value ?: return
        _heatHold.value = null
        heatAnswered = true
        sendNow(hold.text)
    }

    /** Not now: nothing is sent; the screen puts the text back in the composer. */
    fun dismissHeatHold() {
        _heatHold.value = null
    }

    /** Runs this chat's side jobs held for heat or battery saver now. */
    fun runDeferredNow() = runner.runDeferredNow(conversationId)

    private fun sendNow(text: String) {
        // While the agent works, this queues for its next step boundary (steer and queue); see AgentRunner.send.
        runner.send(conversationId, text, pending.value)
        pending.value = emptyList()
    }

    private val _sendWarning = MutableStateFlow<SendWarning?>(null)

    /** The pre-send warning: the chat's model can't take the message the user just sent. Null when none. */
    internal val sendWarning: StateFlow<SendWarning?> = _sendWarning

    /** Sends the held message on the chat's own model anyway. */
    fun sendAnyway() {
        val w = _sendWarning.value ?: return
        _sendWarning.value = null
        sendNow(w.text)
    }

    /** Switches this chat to the suggested model, then sends the held message on it. */
    fun switchAndSend() {
        val w = _sendWarning.value ?: return
        val target = w.suggestion ?: return
        _sendWarning.value = null
        viewModelScope.launch {
            setModel(target.providerId, target.model).join()
            sendNow(w.text)
        }
    }

    /** Closes the warning without sending; the screen puts the text back in the composer. */
    fun dismissSendWarning() {
        _sendWarning.value = null
    }

    private fun warningFor(state: ChatUiState, text: String): SendWarning? {
        val provider = state.provider ?: return null
        val picker = state.picker
        val needs = (picker.needs ?: return null).let { it.copy(contextTokens = it.contextTokens + (text.length + 3) / 4) }
        val byId = state.providers.associateBy { it.id }
        val current = capabilitiesOf(byId[provider.id] ?: provider, provider.model, picker.lists, picker.catalog)
        val refs = (picker.favorites + picker.recents + picker.backups + state.providers.map { ModelRef(it.id, it.model) })
            .distinct()
            .filterNot { it.providerId == provider.id && it.model == provider.model }
        val candidates = refs.mapNotNull { ref ->
            val p = byId[ref.providerId] ?: return@mapNotNull null
            val title = (picker.lists[p.id] as? ModelList.Loaded)?.models?.firstOrNull { it.id == ref.model }?.displayName?.takeIf { it != ref.model }
                ?: picker.catalog?.lookup(p.baseUrl, p.format, ref.model)?.name
                ?: ref.model
            Triple(ref, "$title (${p.name})", capabilitiesOf(p, ref.model, picker.lists, picker.catalog))
        }
        return sendWarning(needs, current, provider.model, candidates, text)
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
        // Imported first, then added to what is pending now: reading pending before the (suspending) import
        // wrote back a stale list, bringing back attachments just sent or removed.
        val added = uris.mapNotNull { runCatching { workspace.importAttachment(it) }.getOrNull() }
        pending.update { it + added }
    }

    fun removeAttachment(a: Part.Attachment) {
        pending.update { it - a }
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

    /** Allow, and remember it for this chat or always (docs/design/remembered-approvals.md). */
    fun answerRemembering(callId: String, answer: dev.locus.core.agent.ApprovalAnswer) = runner.answer(callId, answer)

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
        while (dev.locus.core.model.FileTrees.exists(target)) target = files.resolve("exports/$stem (${n++}).$extension")
        // Never through a link a Linux program left at the name or on the way.
        files.write(target) { out -> source.inputStream().use { it.copyTo(out) } }
        files.relative(target)
    }

    /** Writes an artifact's source into the workspace's `artifacts` folder (never overwriting); returns its workspace path. */
    suspend fun saveArtifact(name: String, content: String): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "html")
        var target = files.resolve("artifacts/$stem.$extension")
        var n = 2
        while (target.exists()) target = files.resolve("artifacts/$stem ($n).$extension").also { n++ }
        // Never through a link a Linux program left at the name or on the way.
        files.writeText(target, content)
        files.relative(target)
    }

    private fun toItems(
        messages: List<Message>,
        branches: Map<String, Branch>,
        changed: Map<String, List<dev.locus.core.data.repository.TrackedChange>>,
        receiptList: List<dev.locus.core.model.TaskReceipt>,
        scope: dev.locus.core.model.ChatScopeSpec?,
    ): List<ChatItem> =
        transcriptItems(messages, branches, changed, receiptList).map { if (it is ChatItem.SentFile) it.copy(file = sentFileInfo(files, scope, it.path)) else it }
}

/**
 * Stats a file send_file sent (on the transcript's background thread); null when it's gone or unsafe.
 * [path] resolves as send_file resolved it: in a project chat ([scope]) inside the project's folder,
 * otherwise in the whole workspace, never outside either. [SentFileInfo.relativePath] is relative to the
 * whole workspace, as the Workspace screen and the documents provider name files.
 */
internal fun sentFileInfo(
    files: dev.locus.core.agent.tools.Workspace,
    scope: dev.locus.core.model.ChatScopeSpec?,
    path: String,
    mimeOf: (extension: String) -> String? = { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): SentFileInfo? = runCatching {
    val f = dev.locus.core.agent.ChatScope.with(scope) { files.resolve(path) }
    if (!f.isFile) return@runCatching null
    val mime = runCatching { mimeOf(f.extension.lowercase()) }.getOrNull() ?: "application/octet-stream"
    SentFileInfo(f.name, f.length(), f.absolutePath, mime, files.stored(f))
}.getOrNull()

private data class Transcript(
    val items: List<ChatItem>,
    /** [items] grouped as the list shows them ([groupSteps]). */
    val rows: List<TranscriptRow>,
    val tokens: ChatTokens,
    val todos: List<TodoItem>?,
    /** What the saved messages need from a model, before the composer's additions. */
    val needs: dev.locus.core.llm.ModelRequirements = dev.locus.core.llm.ModelRequirements(tools = true),
    /** Whether the chat has read outside content anywhere ([dev.locus.core.agent.Taint.sourcesIn]). */
    val outsideContent: Boolean = false,
    /** Hosts the user typed in the chat. */
    val typedHosts: Set<String> = emptySet(),
)

private data class Extras(
    val browser: dev.locus.core.browser.BrowserState,
    val pending: List<Part.Attachment>,
    val models: Map<String, ModelList>,
    val catalog: ModelCatalogIndex?,
    val connectors: List<ChatConnector>,
    /** The active connectors' addresses: hosts the user set up, so links to them open in one tap. */
    val connectorAddresses: List<String> = emptyList(),
)

/** Replies in [ids] marked as written after the chat read outside content. */
internal fun List<ChatItem>.markOutsideContent(ids: Set<String>): List<ChatItem> =
    if (ids.isEmpty()) this else map { if (it is ChatItem.Assistant && it.key in ids) it.copy(outsideContent = true) else it }

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
        var items = turnItems(turn, results) + listOfNotNull(changed)
        val reply = items.indexOfLast { it is ChatItem.Assistant }
        // A task that ended normally with this reply puts its receipt on the reply's meta line; anything
        // else (failed, stopped, paused, no reply) keeps a status line of its own.
        val onReply = endedHere.lastOrNull { it.outcome == dev.locus.core.model.TaskOutcome.DONE }?.takeIf { reply >= 0 }
        if (onReply != null) items = items.mapIndexed { i, item -> if (i == reply) (item as ChatItem.Assistant).copy(receipt = onReply) else item }
        out += when {
            info == null -> items
            reply >= 0 && items.drop(reply + 1).all { it is ChatItem.SentFile || it is ChatItem.ProviderDraft || it is ChatItem.Changes } ->
                items.mapIndexed { i, item -> if (i == reply) (item as ChatItem.Assistant).copy(turn = info) else item }
            info.branch != null || info.last -> items + ChatItem.TurnEnd("${start}:end", info)
            else -> items
        }
        out += endedHere.map { ChatItem.Receipt("receipt:${it.taskId}", it, attached = it === onReply) }
        endedHere.clear()
        turn.clear()
        turnStart = null
    }
    messages.forEach { message ->
        val ends = receiptsAfter[message.id].orEmpty()
        // A note from Locus belongs to the turn it joined: an undo the agent was told about mid-task shows
        // nothing; a call the user ran anyway mid-task shows as a step of that turn.
        if (ModeNote.isAgentNote(message)) {
            if (ModeNote.ranAnyway(message) != null) turn += message
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
    return "Answered by ${reply.model?.let(dev.locus.core.model.ModelNames::short) ?: "a backup model"} · $from couldn't answer"
}

internal fun turnItems(turn: List<Message>, results: Map<String, Part.ToolResult>): List<ChatItem> {
    if (turn.isEmpty()) return emptyList()
    // Files handed to the user go at the end of the turn, under the reply.
    val sent = mutableListOf<ChatItem>()
    val entries = buildList<ChatItem> {
        turn.forEach { message ->
            if (message.role == Role.USER) {
                ModeNote.ranAnyway(message)?.let { add(ChatItem.Note("${message.id}:ran", "You ran the blocked call anyway: $it")) }
                return@forEach
            }
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
                    dev.locus.core.model.ProviderDrafts.TOOL -> {
                        val result = results[call.id]
                        val drafted = dev.locus.core.model.ProviderDrafts.call(call.argumentsJson)
                        // The step stays in the steps card; the card joins the end of the turn, like a sent file.
                        add(ChatItem.ToolCall(key, call.name, call.argumentsJson, result))
                        if (drafted != null && result != null && !result.isError) sent += ChatItem.ProviderDraft("$key:draft", drafted.name, drafted.host)
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
        supportsEffort = Effort.supports(p.format, p.baseUrl),
        defaultEffort = Effort.ofProvider(p.effort, p.format, p.baseUrl, p.model),
        defaultEffortNote = Effort.describeProviderDefault(p.effort, p.format, p.baseUrl, p.model),
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
