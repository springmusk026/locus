package dev.locus.feature.chat

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
import dev.locus.core.model.Effort
import dev.locus.core.model.effectiveModel
import dev.locus.core.model.overrideFor
import dev.locus.core.model.ModeNote
import dev.locus.core.model.ProposedPlan
import dev.locus.core.model.TodoItem

/** One row in the transcript. Tool calls are paired with their results for display. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<Part.Attachment> = emptyList(), val planMode: Boolean = false) : ChatItem
    /** The reply that ends a turn (text after the turn's last tool call), with what the whole turn cost. */
    data class Assistant(override val key: String, val text: String, val usage: TurnUsage? = null) : ChatItem

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
}

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
) {
    /** Tool groups and connectors switched off, for the tools chip's count. */
    val disabledCount: Int get() = disabledTools.size
}

/** A connector the tools menu can switch off for this chat. */
data class ChatConnector(val id: String, val name: String, val mcp: Boolean)

/** A provider's model list in the picker sheet, fetched when the sheet opens. */
sealed interface ModelList {
    data object Loading : ModelList
    data class Loaded(val ids: List<String>) : ModelList
    data class Failed(val message: String) : ModelList
}

/** Token accounting for one chat: how full the context is now, and what all its requests used. */
data class ChatTokens(
    /** Prompt + output of the latest request: how much of the context window is in use. */
    val context: Int = 0,
    val window: Int? = null,
    val requests: Int = 0,
    val totalInput: Long = 0,
    val totalOutput: Long = 0,
    val totalCached: Long = 0,
) {
    val fraction: Float? get() = window?.takeIf { it > 0 }?.let { (context.toFloat() / it).coerceIn(0f, 1f) }
}

data class ChatUiState(
    val title: String = "",
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
    val models: Map<String, ModelList> = emptyMap(),
) {
    val pendingApproval: ApprovalRequest? get() = run.pendingApproval
}

@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val conversationId: String,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val runner: AgentRunner,
    val browser: AgentBrowser,
    private val workspace: WorkspaceRepository,
    library: LibraryRepository,
    private val files: dev.locus.core.agent.tools.Workspace,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(conversationId: String): ChatViewModel
    }

    private val pending = MutableStateFlow<List<Part.Attachment>>(emptyList())
    private val models = MutableStateFlow<Map<String, ModelList>>(emptyMap())

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
    private val transcript = conversations.observeMessages(conversationId)
        .map { messages -> Transcript(toItems(messages), tokens(messages, window = null), currentTodos(messages)) }
        .flowOn(Dispatchers.Default)

    val uiState: StateFlow<ChatUiState> = combine(
        conversations.observe(conversationId),
        transcript,
        // Without the streamed text, so this only emits when something other than the text changes.
        runState.map { it.copy(streamingText = "", reasoning = "") }.distinctUntilChanged(),
        settings.settings,
        combine(browser.state, pending, models, library.library) { b, p, m, lib -> Extras(b, p, m, lib.connectors.filterNot { it.draft }.map { ChatConnector(it.connector.id, it.connector.name, it.connector.kind == "mcp") }) },
    ) { conversation, transcript, run, s, extras ->
        val (items, tokens, todos) = transcript
        val browserState = extras.browser
        val attachments = extras.pending
        val provider = s.provider(conversation?.providerId)
        // The same rule the runner uses for requests, so header, sheet and requests agree.
        val model = provider?.effectiveModel(conversation?.modelOverride).orEmpty()
        ChatUiState(
            title = conversation?.title.orEmpty(),
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
            ),
            connectors = extras.connectors,
            todos = todos,
            models = extras.models,
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
        runner.send(conversationId, text, pending.value)
        pending.value = emptyList()
    }

    fun attach(uris: List<android.net.Uri>) = viewModelScope.launch {
        pending.value = pending.value + uris.mapNotNull { runCatching { workspace.importAttachment(it) }.getOrNull() }
    }

    fun removeAttachment(a: Part.Attachment) {
        pending.value = pending.value - a
    }

    fun stop() = runner.cancel(conversationId)

    fun compactNow() = runner.compactNow(conversationId)

    fun resume() = runner.resume(conversationId)

    fun setProvider(providerId: String) = viewModelScope.launch { conversations.setModel(conversationId, providerId, null) }

    /** Uses [model] of [providerId] for this chat; null means that provider's default. */
    fun setModel(providerId: String, model: String?) = viewModelScope.launch {
        val provider = settings.current().providers.firstOrNull { it.id == providerId } ?: return@launch
        conversations.setModel(conversationId, providerId, provider.overrideFor(model))
    }

    /** Fetches [providerId]'s models for the picker, once per screen. */
    fun loadModels(providerId: String) {
        val current = models.value[providerId]
        if (current is ModelList.Loading || current is ModelList.Loaded) return
        models.value = models.value + (providerId to ModelList.Loading)
        viewModelScope.launch {
            val provider = settings.current().providers.firstOrNull { it.id == providerId }
            val result = when {
                provider == null -> ModelList.Failed("This provider was removed.")
                provider.format == dev.locus.core.model.ApiFormat.ON_DEVICE -> ModelList.Loaded(listOf(provider.model))
                else -> runCatching { settings.listModels(provider, null).map { it.id } }
                    .fold({ ModelList.Loaded(it) }, { ModelList.Failed(it.message ?: "Couldn't load models.") })
            }
            models.value = models.value + (providerId to result)
        }
    }

    fun setEffort(effort: Effort?) = viewModelScope.launch { conversations.setEffort(conversationId, effort) }

    fun setPlanMode(on: Boolean) = viewModelScope.launch { conversations.setPlanMode(conversationId, on) }

    /** Switches a tool group or connector ([ToolGroup.key] / [ToolGroup.connectorKey]) on or off for this chat. */
    fun setToolEnabled(key: String, enabled: Boolean) = viewModelScope.launch {
        val current = conversations.get(conversationId)?.disabledTools.orEmpty()
        conversations.setDisabledTools(conversationId, if (enabled) current - key else current + key)
    }

    fun approvePlan() = runner.approvePlan(conversationId)

    /** Cancel on a proposed plan: nothing runs, and plan mode goes off so the card stops asking. */
    fun cancelPlan() = viewModelScope.launch { conversations.setPlanMode(conversationId, false) }

    fun answer(callId: String, approved: Boolean) = runner.answer(callId, approved)

    fun runAnyway(callId: String) = runner.runAnyway(conversationId, callId)

    fun dismissReviewerBlock(callId: String) = runner.dismissReviewerBlock(conversationId, callId)

    fun dismissMessages() = runner.dismissMessages(conversationId)

    /**
     * One user message to the next is one turn. Text the model wrote before a later tool call in the
     * same turn is narration ([ChatItem.Note]); only text after the turn's last tool call is the reply.
     */
    private fun toItems(messages: List<Message>): List<ChatItem> {
        val results = messages.filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
        val out = mutableListOf<ChatItem>()
        val turn = mutableListOf<Message>()
        fun flushTurn() {
            out += turnItems(turn, results)
            turn.clear()
        }
        messages.forEach { message ->
            when (message.role) {
                Role.USER -> {
                    flushTurn()
                    out += message.parts.filterIsInstance<Part.Checkpoint>().firstOrNull()
                        ?.let { ChatItem.Checkpoint(message.id, it.summary, it.replacedMessages) }
                        ?: ChatItem.User(message.id, message.userText, message.attachments, planMode = message.parts.any { it is Part.Text && ModeNote.isNote(it.text) })
                }
                Role.TOOL -> Unit
                Role.ASSISTANT -> turn += message
            }
        }
        flushTurn()
        return resolvePlans(out).map { if (it is ChatItem.SentFile) it.copy(file = sentFileInfo(it.path)) else it }
    }

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
    val connectors: List<ChatConnector>,
)

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

/** The checklist of the latest turn: written after the last user message. Older turns' lists are history. */
internal fun currentTodos(messages: List<Message>): List<TodoItem>? {
    val lastUser = messages.indexOfLast { it.role == Role.USER && it.parts.none { p -> p is Part.Checkpoint } }
    return Todos.latest(messages.drop(lastUser + 1))?.takeIf { it.isNotEmpty() }
}

internal fun turnItems(turn: List<Message>, results: Map<String, Part.ToolResult>): List<ChatItem> {
    if (turn.isEmpty()) return emptyList()
    // Files handed to the user go at the end of the turn, under the reply.
    val sent = mutableListOf<ChatItem.SentFile>()
    val entries = buildList<ChatItem> {
        turn.forEach { message ->
            message.reasoning?.let { r -> add(ChatItem.Thought("${message.id}:thought", r.content, r.durationMs, inSteps = message.toolCalls.isNotEmpty())) }
            if (message.text.isNotBlank()) add(ChatItem.Assistant(message.id, message.text))
            message.toolCalls.forEach { call ->
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
                    else -> add(ChatItem.ToolCall(key, call.name, call.argumentsJson, results[call.id]))
                }
            }
        }
    }
    val lastTool = entries.indexOfLast { it is ChatItem.ToolCall }
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

internal fun chatProvider(p: dev.locus.core.model.ProviderConfig) =
    ChatProvider(
        p.id, p.name, p.baseUrl, p.model, p.format == dev.locus.core.model.ApiFormat.ON_DEVICE, p.contextWindow,
        supportsEffort = Effort.supports(p.format),
        defaultEffort = Effort.fromWire(p.effort),
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
    )
}
