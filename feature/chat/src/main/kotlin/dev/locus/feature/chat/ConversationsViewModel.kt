package dev.locus.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Conversation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationRow(
    val conversation: Conversation,
    val running: Boolean,
    val needsApproval: Boolean,
    val providerName: String = "",
    val providerUrl: String = "",
    val onDevice: Boolean = false,
    /** The tool in use right now, while running. */
    val activeTool: String? = null,
    val interrupted: Boolean = false,
    /** The model id this chat uses (its override, else its provider's), for the model-family logo. */
    val model: String = "",
)

/** The parts of a run's state the home list needs. */
private data class RowRun(val running: Boolean, val approval: Boolean, val activeTool: String?, val interrupted: Boolean)

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** The default model, shown in the home status line and the ask bar. */
data class DefaultModel(val providerName: String, val providerUrl: String, val model: String, val onDevice: Boolean)

/** Small facts for the home shortcuts, so each one says something about its state. */
data class HomeCounts(
    val memories: Int = 0,
    val skills: Int = 0,
    val toReview: Int = 0,
    val tasksActive: Int = 0,
    val tasksTotal: Int = 0,
    val recentFiles: List<dev.locus.core.data.repository.WorkspaceEntry> = emptyList(),
    /** The most recent scheduled-task answer from the last day, shown on home. */
    val latestResult: TaskResult? = null,
)

/** The home composer's state. */
internal data class HomeComposer(
    val settings: ComposerSettings = ComposerSettings(),
    val attachments: List<dev.locus.core.model.Part.Attachment> = emptyList(),
)

data class TaskResult(val title: String, val summary: String, val at: Long)

sealed interface ConversationsUiState {
    data object Loading : ConversationsUiState
    data class Ready(
        val rows: List<ConversationRow>,
        val hasProvider: Boolean,
        val defaultModel: DefaultModel? = null,
        val counts: HomeCounts = HomeCounts(),
        /** Set while searching; [rows] then holds only the matches. */
        val query: String? = null,
    ) : ConversationsUiState
}

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val runner: AgentRunner,
    library: LibraryRepository,
    tasks: TaskRepository,
    private val workspace: dev.locus.core.data.repository.WorkspaceRepository,
) : ViewModel() {

    /** What the home composer has chosen for the next chat; reset once that chat starts. */
    private data class Draft(
        val providerId: String? = null,
        val model: String? = null,
        val effort: dev.locus.core.model.Effort? = null,
        val planMode: Boolean = false,
        val disabledTools: Set<String> = emptySet(),
        val attachments: List<dev.locus.core.model.Part.Attachment> = emptyList(),
    )

    private val draft = MutableStateFlow(Draft())
    private val models = MutableStateFlow<Map<String, ModelList>>(emptyMap())

    /** The home composer: the same controls as a chat's, applied to the chat the message starts. */
    internal val composer: StateFlow<HomeComposer> = combine(draft, settings.settings, models, library.library) { d, s, m, lib ->
        val base = s.provider(d.providerId ?: s.defaultProviderId) ?: s.providers.firstOrNull()
        val provider = base?.let(::chatProvider)
        HomeComposer(
            settings = ComposerSettings(
                provider = provider?.let { p -> d.model?.let { p.copy(model = it) } ?: p },
                providers = s.providers.map(::chatProvider),
                controls = ChatControls(
                    model = d.model ?: base?.model.orEmpty(),
                    modelOverridden = d.model != null,
                    effort = d.effort ?: dev.locus.core.model.Effort.fromWire(base?.effort),
                    effortOverridden = d.effort != null,
                    planMode = d.planMode,
                    disabledTools = d.disabledTools,
                ),
                connectors = lib.connectors.filterNot { it.draft }.map { ChatConnector(it.connector.id, it.connector.name, it.connector.kind == "mcp") },
                models = m,
            ),
            attachments = d.attachments,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeComposer())

    fun setModel(providerId: String, model: String?) = viewModelScope.launch {
        val provider = settings.current().providers.firstOrNull { it.id == providerId }
        draft.value = draft.value.copy(providerId = providerId, model = model?.takeIf { it != provider?.model })
    }

    fun setEffort(effort: dev.locus.core.model.Effort?) { draft.value = draft.value.copy(effort = effort) }

    fun setPlanMode(on: Boolean) { draft.value = draft.value.copy(planMode = on) }

    fun setToolEnabled(key: String, enabled: Boolean) {
        val current = draft.value.disabledTools
        draft.value = draft.value.copy(disabledTools = if (enabled) current - key else current + key)
    }

    fun attach(uris: List<android.net.Uri>) = viewModelScope.launch {
        val added = uris.mapNotNull { runCatching { workspace.importAttachment(it) }.getOrNull() }
        draft.value = draft.value.copy(attachments = draft.value.attachments + added)
    }

    fun removeAttachment(a: dev.locus.core.model.Part.Attachment) {
        draft.value = draft.value.copy(attachments = draft.value.attachments - a)
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
                provider.format == ApiFormat.ON_DEVICE -> ModelList.Loaded(listOf(provider.model))
                else -> runCatching { settings.listModels(provider, null).map { it.id } }
                    .fold({ ModelList.Loaded(it) }, { ModelList.Failed(it.message ?: "Couldn't load models.") })
            }
            models.value = models.value + (providerId to result)
        }
    }

    /** Re-read after chats change (that's when the agent writes files), at most once a second. */
    @OptIn(FlowPreview::class)
    private val recentFiles = conversations.all.debounce(1_000).map { runCatching { workspace.recent(3) }.getOrDefault(emptyList()) }.onStart { emit(emptyList()) }

    private val counts = combine(library.library, tasks.tasks, recentFiles) { lib, list, files ->
        HomeCounts(
            memories = lib.memory.size,
            skills = lib.skills.count { !it.draft },
            toReview = lib.skills.count { it.draft } + lib.connectors.count { it.draft },
            tasksActive = list.count { it.enabled },
            tasksTotal = list.size,
            recentFiles = files,
            latestResult = list.filter { it.lastRunAt != null && !it.lastSummary.isNullOrBlank() }
                .maxByOrNull { it.lastRunAt!! }
                ?.takeIf { System.currentTimeMillis() - it.lastRunAt!! < 24 * 60 * 60 * 1000L }
                ?.let { TaskResult(it.title, it.lastSummary!!.trim(), it.lastRunAt!!) },
        )
    }

    private val query = MutableStateFlow<String?>(null)

    /** Chat ids matching the query (title or message text), searched in the database as you type. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val matches = combine(query, conversations.all) { q, _ -> q } // re-run as new messages arrive
        .debounce { if (it.isNullOrBlank()) 0L else 200L }
        .mapLatest { q -> q?.takeIf { it.isNotBlank() }?.let { q to conversations.search(it) } }

    val uiState: StateFlow<ConversationsUiState> = combine(
        // Chats with no messages (a "New chat" opened and left) are hidden, unless a run just started in one.
        combine(conversations.all, conversations.emptyIds, matches, query) { list, empty, ids, q -> Quad(list, empty, ids, q) },
        // Only what the list shows, so streamed text doesn't rebuild every row.
        runner.states.map { all -> all.mapValues { (_, r) -> RowRun(r.running, r.pendingApproval != null, r.activeTool, r.interrupted) } }.distinctUntilChanged(),
        settings.settings,
        counts,
    ) { (everything, empty, ids, q), states, s, counts ->
        val all = everything.filter { it.id !in empty || states[it.id]?.running == true || states[it.id]?.approval == true }
        val default = s.provider(s.defaultProviderId) ?: s.providers.firstOrNull()
        // Until the matches for the current query arrive, show none rather than every chat.
        val list = when {
            q.isNullOrBlank() -> all
            ids == null || ids.first != q -> emptyList()
            else -> all.filter { it.id in ids.second }
        }
        ConversationsUiState.Ready(
            query = q,
            rows = list.map { c ->
                val state = states[c.id]
                val provider = s.provider(c.providerId)
                ConversationRow(
                    c, state?.running == true, state?.approval == true,
                    provider?.name.orEmpty(), provider?.baseUrl.orEmpty(), provider?.format == ApiFormat.ON_DEVICE,
                    activeTool = state?.activeTool,
                    interrupted = state?.interrupted == true && state.running != true,
                    model = c.modelOverride ?: provider?.model.orEmpty(),
                )
            },
            hasProvider = s.providers.isNotEmpty(),
            defaultModel = default?.let { DefaultModel(it.name, it.baseUrl, it.model, it.format == ApiFormat.ON_DEVICE) },
            counts = counts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState.Loading)

    fun newConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val providerId = settings.current().defaultProviderId
            onCreated(conversations.create(providerId).id)
        }
    }

    /**
     * Starts a chat straight from the home composer: creates it with the composer's model, thinking,
     * plan mode and tools, sends the message (and attachments), opens it. The composer then resets.
     */
    fun start(text: String, onCreated: (String) -> Unit) {
        val d = draft.value
        if (text.isBlank() && d.attachments.isEmpty()) return
        draft.value = Draft()
        viewModelScope.launch {
            val providerId = d.providerId ?: settings.current().defaultProviderId
            val id = conversations.create(providerId).id
            if (d.providerId != null) conversations.setModel(id, d.providerId, d.model)
            if (d.effort != null) conversations.setEffort(id, d.effort)
            if (d.planMode) conversations.setPlanMode(id, true)
            if (d.disabledTools.isNotEmpty()) conversations.setDisabledTools(id, d.disabledTools)
            runner.send(id, text.trim(), d.attachments)
            onCreated(id)
        }
    }

    fun search(text: String?) {
        query.value = text
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { conversations.setPinned(id, pinned) }
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().take(120)
        if (clean.isNotEmpty()) viewModelScope.launch { conversations.rename(id, clean) }
    }

    fun delete(id: String) {
        viewModelScope.launch { conversations.delete(id) }
    }
}
