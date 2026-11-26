package dev.locus.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.search.ChatSearchHit
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ModelDirectory
import dev.locus.core.model.ModelRef
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Conversation
import dev.locus.core.model.effectiveModel
import dev.locus.core.model.overrideFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
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
    val interrupted: Boolean = false,
    /** The model id this chat uses (its override, else its provider's), for the model-family logo. */
    val model: String = "",
    /** A locked chat (docs/design/app-lock.md). */
    val locked: Boolean = false,
    /**
     * A locked chat this session hasn't unlocked: its title is blanked here and the screen says
     * "Locked chat"; it has no search hit, and renaming it waits for the unlock.
     */
    val redacted: Boolean = false,
    /** What the user typed in this chat and hasn't sent (#319); null when nothing. */
    val draft: String? = null,
) {
    /** A read-only chat imported from ChatGPT or Claude (docs/design/import-chats.md); null for Locus's own. */
    val imported: dev.locus.core.model.ChatImportSource? get() = dev.locus.core.model.ImportedChats.sourceOf(conversation.id)
}

/**
 * [row] as the list may show it under [lock]: a locked chat this session hasn't unlocked keeps its
 * place and its state, but its title is blanked, so nothing on screen can show it by mistake. Pure.
 */
internal fun redactedRow(row: ConversationRow, lock: dev.locus.core.model.AppLockStatus): ConversationRow {
    val id = row.conversation.id
    val redact = lock.redactInApp(id)
    return row.copy(
        locked = lock.isLockedChat(id),
        redacted = redact,
        conversation = if (redact) row.conversation.copy(title = "") else row.conversation,
        draft = if (redact) null else row.draft,
    )
}

/** Search hits without the locked chats this session hasn't unlocked: not even that one matched shows. Pure. */
internal fun visibleHits(hits: List<ChatSearchHit>, lock: dev.locus.core.model.AppLockStatus): List<ChatSearchHit> =
    hits.filterNot { lock.redactInApp(it.conversation.id) }

/**
 * The parts of a run's state the home list needs. Not the tool in use: that changes at every tool call
 * of every chat, and would rebuild every row each time ([ConversationsViewModel.activeTools] carries it for the featured card).
 */
internal data class RowRun(val running: Boolean, val approval: Boolean, val interrupted: Boolean)

/** [RowRun]s by chat id, emitted only when one of them changes (not on streamed text or tool calls). */
internal fun rowRuns(states: Flow<Map<String, RunState>>): Flow<Map<String, RowRun>> =
    states.map { all -> all.mapValues { (_, r) -> RowRun(r.running, r.pendingApproval != null, r.interrupted) } }.distinctUntilChanged()

/** The tool each running chat is using right now, by chat id. */
internal fun activeToolsOf(states: Flow<Map<String, RunState>>): Flow<Map<String, String>> =
    states.map { all -> all.mapNotNull { (id, r) -> r.activeTool?.let { id to it } }.toMap() }.distinctUntilChanged()

/**
 * What a chat search depends on in the chat list: which chats there are, their titles, and when each
 * last changed (a new message moves it). Room re-emits the whole list on any write to the table (a
 * helper chat's progress, a model switch), which needn't run the search again.
 */
internal fun searchInputs(chats: Flow<List<Conversation>>): Flow<List<Triple<String, String, Long>>> =
    chats.map { list -> list.map { Triple(it.id, it.title, it.updatedAt) } }.distinctUntilChanged()

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * A project on home's shelf: how many chats it has, when it was last active (its newest chat, or the
 * project itself when that's newer: just made or edited), and how many of its chats are working or
 * waiting for an approval right now.
 */
data class HomeProject(
    val project: dev.locus.core.model.Project,
    val chats: Int,
    val lastActive: Long,
    val running: Int = 0,
    val approvals: Int = 0,
)

/**
 * Home's projects in the order a person most likely wants them next: those with a chat waiting for an
 * approval, then those with a chat working, then by last activity, newest first. [chats] are the chats
 * home lists (no empty ones); [running] and [approvals] are chat ids.
 */
internal fun homeProjects(
    projects: List<dev.locus.core.model.Project>,
    chats: List<Conversation>,
    running: Set<String>,
    approvals: Set<String>,
): List<HomeProject> {
    val byProject = chats.filter { it.projectId != null }.groupBy { it.projectId }
    return projects.map { p ->
        val mine = byProject[p.id].orEmpty()
        HomeProject(
            project = p,
            chats = mine.size,
            lastActive = maxOf(p.updatedAt, mine.maxOfOrNull { it.updatedAt } ?: 0L),
            running = mine.count { it.id in running && it.id !in approvals },
            approvals = mine.count { it.id in approvals },
        )
    }.sortedWith(
        compareByDescending<HomeProject> { it.approvals > 0 }
            .thenByDescending { it.running > 0 }
            .thenByDescending { it.lastActive },
    )
}

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
        /** Set while searching; [rows] then holds only the matching chats, in result order (newest match first). */
        val query: String? = null,
        /** While searching: what matched in each chat of [rows], by chat id. */
        val hits: Map<String, ChatSearchHit> = emptyMap(),
        /** The results for [query] haven't arrived yet. */
        val searchPending: Boolean = false,
        /**
         * Steps Quick start skipped that are still to do, in order (docs/design/onboarding-quick-start.md).
         * Home shows the first as a setup card; empty for everyone who didn't use Quick start.
         */
        val setup: List<dev.locus.core.model.SetupItem> = emptyList(),
        /** The phone has a screen lock, so a chat can be locked (docs/design/app-lock.md). */
        val chatLockAvailable: Boolean = false,
    ) : ConversationsUiState
}

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val directory: ModelDirectory,
    private val runner: AgentRunner,
    library: LibraryRepository,
    tasks: TaskRepository,
    private val workspace: dev.locus.core.data.repository.WorkspaceRepository,
    /** Projects for the home strip and the All chats filter; null in tests that don't need them. */
    projectRepository: dev.locus.core.data.repository.ProjectRepository? = null,
    /** Quick start's deferred steps, shown as a setup card; null in tests that don't need them. */
    private val checklist: dev.locus.core.data.setup.SetupChecklist? = null,
    /** Lock Locus and locked chats; null in tests that don't need it (nothing is locked then). */
    private val appLock: dev.locus.core.data.lock.AppLock? = null,
    /** Needs you (docs/design/needs-you.md); null in tests that don't need it (the section is then empty). */
    private val needsYou: dev.locus.core.data.needsyou.NeedsYou? = null,
    /** The agent's browser, whose tabs and state for a deleted chat go with it (#338); null in tests that don't need it. */
    private val browser: dev.locus.core.browser.AgentBrowser? = null,
    /** Each chat's unsent composer text, shown as "Draft:" in its row (#319); null in tests that don't need it. */
    private val drafts: dev.locus.core.data.repository.ChatDrafts? = null,
) : ViewModel() {
    /** Approvals, questions and budget pauses waiting now, proposals, and unread results, for home's Needs you. */
    val needsYouFeed: StateFlow<dev.locus.core.data.needsyou.NeedsYouFeed> =
        (needsYou?.feed ?: kotlinx.coroutines.flow.flowOf(dev.locus.core.data.needsyou.NeedsYouFeed()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), dev.locus.core.data.needsyou.NeedsYouFeed())

    /** What a tap on a proposal did, for the screen's one feedback line. */
    private val _needsYouEvents = kotlinx.coroutines.flow.MutableSharedFlow<Pair<dev.locus.core.designsystem.UiText, dev.locus.core.designsystem.Tone>>(extraBufferCapacity = 4)
    internal val needsYouEvents: kotlinx.coroutines.flow.SharedFlow<Pair<dev.locus.core.designsystem.UiText, dev.locus.core.designsystem.Tone>> = _needsYouEvents

    /** Approve on a proposal's card: it runs once, and the screen says how it went. */
    fun approveProposal(id: String) {
        val n = needsYou ?: return
        viewModelScope.launch { _needsYouEvents.emit(approvalMessage(n.approve(id))) }
    }

    fun declineProposal(id: String) {
        val n = needsYou ?: return
        viewModelScope.launch {
            val message = if (n.decline(id)) dev.locus.core.designsystem.UiText.Res(R.string.chat_needs_you_declined) else dev.locus.core.designsystem.UiText.Res(R.string.chat_needs_you_gone)
            _needsYouEvents.emit(message to dev.locus.core.designsystem.Tone.Neutral)
        }
    }

    fun dismissProposal(id: String) {
        val n = needsYou ?: return
        viewModelScope.launch { n.dismiss(id) }
    }

    /** A live approval answered from home, exactly as in its chat. */
    fun answerApproval(callId: String, answer: dev.locus.core.agent.ApprovalAnswer) {
        needsYou?.answer(callId, answer) ?: runner.answer(callId, answer)
    }

    fun markResultRead(taskId: String) {
        val n = needsYou ?: return
        viewModelScope.launch { n.markRead(taskId) }
    }

    fun markAllResultsRead() {
        val n = needsYou ?: return
        viewModelScope.launch { n.markAllRead() }
    }

    init {
        // A deferred step set up anywhere (Settings, the Workspace, the agent) is cleared for good, so its
        // card doesn't come back if, say, the machine is later removed.
        checklist?.let { c -> viewModelScope.launch { c.settled.collect { c.clear(it) } } }
    }

    /** The close button on a setup card: that step's card doesn't come back. */
    fun dismissSetup(item: dev.locus.core.model.SetupItem) {
        val c = checklist ?: return
        viewModelScope.launch { c.clear(setOf(item)) }
    }

    /** Only what the list shows of each run, so streamed text doesn't rebuild every row. */
    private val runs = rowRuns(runner.states)

    /** The tool each running chat is using, for the featured card's status line only (see [RowRun]). */
    val activeTools: StateFlow<Map<String, String>> = activeToolsOf(runner.states)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The user's chats, only when they changed: Room re-emits equal lists on unrelated writes (helper chats). */
    private val chats = conversations.all.distinctUntilChanged()

    /**
     * Every project with its chat count, last activity and live state, the ones that need the user
     * first, then most recently active ([homeProjects]). From the chats already loaded for the list.
     */
    val projects: StateFlow<List<HomeProject>> = combine(
        projectRepository?.all ?: kotlinx.coroutines.flow.flowOf(emptyList()),
        chats,
        conversations.emptyIds,
        runs,
    ) { list, chats, empty, states ->
        val running = states.filterValues { it.running }.keys
        val approvals = states.filterValues { it.approval }.keys
        // The same chats home lists: none left empty, unless a run just started in one.
        homeProjects(list, chats.filter { it.id !in empty || it.id in running || it.id in approvals }, running, approvals)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What the home composer has chosen for the next chat; reset once that chat starts. */
    private data class Draft(
        val providerId: String? = null,
        val model: String? = null,
        val effort: dev.locus.core.model.Effort? = null,
        val planMode: Boolean = false,
        val disabledTools: Set<String> = emptySet(),
        val attachments: List<dev.locus.core.model.Part.Attachment> = emptyList(),
        /** Start it as a temporary chat (docs/design/temporary-chats.md). */
        val temporary: Boolean = false,
    )

    private val draft = MutableStateFlow(Draft())

    /** The home composer: the same controls as a chat's, applied to the chat the message starts. */
    internal val composer: StateFlow<HomeComposer> = combine(draft, settings.settings, combine(directory.lists, directory.catalog, ::Pair), library.library) { d, s, (m, catalog), lib ->
        val base = s.provider(d.providerId ?: s.defaultProviderId) ?: s.providers.firstOrNull()
        // Resolved against the provider as it is now, the same way the chat will resolve it.
        val model = base?.effectiveModel(base.overrideFor(d.model)).orEmpty()
        HomeComposer(
            settings = ComposerSettings(
                provider = base?.let(::chatProvider)?.copy(model = model),
                providers = s.providers.map(::chatProvider),
                controls = ChatControls(
                    model = model,
                    modelName = onDeviceModelName(base?.let(::chatProvider), model, m),
                    modelOverridden = base != null && model != base.model,
                    effort = d.effort ?: base?.let { dev.locus.core.model.Effort.ofProvider(it.effort, it.format, it.baseUrl, model) } ?: dev.locus.core.model.Effort.HIGH,
                    effortOverridden = d.effort != null,
                    planMode = d.planMode,
                    disabledTools = d.disabledTools,
                    defaultBudget = s.taskBudget,
                    costNote = costNote(s, base, model, catalog.takeIf { s.modelCatalog }),
                    // The chat this starts follows the model picked: one known not to call tools starts it without them.
                    chatOnly = base != null && model.isNotBlank() &&
                        capabilitiesOf(chatProvider(base), model, m, catalog.takeIf { s.modelCatalog }).tools == dev.locus.core.llm.Support.NO,
                    temporary = d.temporary,
                ),
                temporaryExplained = s.temporaryChatExplained,
                connectors = lib.connectors.filterNot { it.draft }.map { ChatConnector(it.connector.id, it.connector.name, it.connector.kind == "mcp") },
                picker = ModelPickerData(m, s.favoriteModels, s.recentModels, s.modelCatalog, catalog.takeIf { s.modelCatalog }),
            ),
            attachments = d.attachments,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeComposer())

    /**
     * When Telegram remote mode switches itself off, or null while it's off. Home shows it while it's on:
     * it keeps a connection open, which uses battery. The screen ticks against the time.
     */
    val remoteModeUntil: StateFlow<Long?> = settings.settings.map { it.telegramRemote.switchesOffAt }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Synchronous on purpose: a pick followed at once by Send must start the chat with that pick (it
     * used to land after a settings read, so it could miss this chat and leak into the next one).
     * Whether it equals the provider's default is decided when the draft is used.
     */
    fun setModel(providerId: String, model: String?) {
        draft.value = draft.value.copy(providerId = providerId, model = model?.trim()?.takeIf { it.isNotEmpty() })
        viewModelScope.launch {
            val provider = settings.current().providers.firstOrNull { it.id == providerId } ?: return@launch
            settings.recordRecentModel(ModelRef(providerId, model?.trim()?.takeIf { it.isNotEmpty() } ?: provider.model))
        }
    }

    fun setEffort(effort: dev.locus.core.model.Effort?) { draft.value = draft.value.copy(effort = effort) }

    fun setPlanMode(on: Boolean) { draft.value = draft.value.copy(planMode = on) }

    /** The next chat starts temporary (or not); fixed once it's created. */
    fun setTemporary(on: Boolean) { draft.value = draft.value.copy(temporary = on) }

    /** The temporary-chat explanation was seen: turning it on later doesn't show it by itself. */
    fun temporaryExplained() {
        viewModelScope.launch { if (!settings.current().temporaryChatExplained) settings.setTemporaryChatExplained() }
    }

    /** The next chat's own task budget (kept with its tool switches); null uses the default. */
    fun setBudget(budget: dev.locus.core.model.TaskBudget?) {
        draft.value = draft.value.copy(disabledTools = dev.locus.core.model.TaskBudget.withOverride(draft.value.disabledTools, budget))
    }

    fun setMode(mode: dev.locus.core.model.ChatMode) {
        draft.value = draft.value.copy(disabledTools = dev.locus.core.model.ChatMode.withMode(draft.value.disabledTools, mode))
    }

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

    /** The model sheet opened: list every provider's models (shared, cached) and the catalogue if on. */
    fun prepareModels() = directory.prepare()

    fun toggleFavorite(ref: ModelRef) = viewModelScope.launch { settings.toggleFavoriteModel(ref) }

    fun enableCatalog() = directory.enableCatalog()

    /** Re-read after chats change (that's when the agent writes files), at most once a second. */
    @OptIn(FlowPreview::class)
    private val recentFiles = chats.debounce(1_000).map { runCatching { workspace.recent(3) }.getOrDefault(emptyList()) }.onStart { emit(emptyList()) }

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

    /**
     * Chats matching the query (title or message text), from the full-text index. Debounced while typing;
     * the index itself is only written when messages are, so it re-runs only when a chat's title or last
     * activity changes, or chats come and go ([searchInputs]).
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val matches = combine(query, searchInputs(chats)) { q, _ -> q } // re-run as new messages arrive
        .debounce { if (it.isNullOrBlank()) 0L else 200L }
        .mapLatest { q -> q?.takeIf { it.isNotBlank() }?.let { q to conversations.search(it) } }

    val uiState: StateFlow<ConversationsUiState> = combine(
        // Chats with no messages (a "New chat" opened and left) are hidden, unless a run just started in one.
        combine(chats, conversations.emptyIds.distinctUntilChanged(), matches, query) { list, empty, ids, q -> Quad(list, empty, ids, q) },
        combine(runs, drafts?.drafts ?: kotlinx.coroutines.flow.flowOf(emptyMap()), ::Pair),
        settings.settings,
        combine(
            counts,
            checklist?.pending ?: kotlinx.coroutines.flow.flowOf(emptyList()),
            appLock?.status ?: kotlinx.coroutines.flow.flowOf(null),
            ::Triple,
        ),
    ) { (everything, empty, ids, q), (states, unsent), s, (counts, setup, lockStatus) ->
        val lock = lockStatus ?: dev.locus.core.model.AppLockStatus()
        val all = everything.filter { it.id !in empty || states[it.id]?.running == true || states[it.id]?.approval == true }
        val default = s.provider(s.defaultProviderId) ?: s.providers.firstOrNull()
        // Until the matches for the current query arrive, show none rather than every chat.
        val pending = !q.isNullOrBlank() && (ids == null || ids.first != q)
        val hits = if (q.isNullOrBlank() || pending || ids == null) emptyList() else visibleHits(ids.second, lock)
        val list = when {
            q.isNullOrBlank() -> all
            else -> all.associateBy { it.id }.let { byId -> hits.mapNotNull { byId[it.conversation.id] } }
        }
        ConversationsUiState.Ready(
            query = q,
            rows = list.map { c ->
                val state = states[c.id]
                val provider = s.provider(c.providerId)
                val imported = dev.locus.core.model.ImportedChats.sourceOf(c.id)
                redactedRow(
                    if (imported != null) {
                        // An archive has no provider: its badge is where it came from (that family's logo).
                        ConversationRow(c, running = false, needsApproval = false, providerName = imported.displayName, model = importedLogoHint(imported))
                    } else ConversationRow(
                        c, state?.running == true, state?.approval == true,
                        provider?.name.orEmpty(), provider?.baseUrl.orEmpty(), provider?.format == ApiFormat.ON_DEVICE,
                        interrupted = state?.interrupted == true && state.running != true,
                        model = provider?.effectiveModel(c.modelOverride) ?: c.modelOverride.orEmpty(),
                        draft = unsent[c.id],
                    ),
                    lock,
                )
            },
            hits = hits.associateBy { it.conversation.id },
            searchPending = pending,
            hasProvider = s.providers.isNotEmpty(),
            defaultModel = default?.let { DefaultModel(it.name, it.baseUrl, it.model, it.format == ApiFormat.ON_DEVICE) },
            counts = counts,
            setup = setup,
            chatLockAvailable = lock.available,
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
            val current = settings.current()
            val providerId = d.providerId ?: current.defaultProviderId
            val id = conversations.create(providerId, temporary = d.temporary).id
            if (d.providerId != null) conversations.setModel(id, d.providerId, current.providers.firstOrNull { it.id == d.providerId }?.overrideFor(d.model))
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

    /**
     * Deletes chat [id]. Its run and helpers stop first, and nothing about it stays in the runner, the browser
     * or the drafts (#338): a running task would otherwise keep spending tokens and acting for a chat that's gone.
     */
    fun delete(id: String) {
        viewModelScope.launch {
            runner.forget(id)
            browser?.forget(id)
            drafts?.clear(id)
            conversations.delete(id)
            appLock?.forgetChat(id)
        }
    }
}

/** A model id whose family logo stands for [source] on an imported chat's badge. */
internal fun importedLogoHint(source: dev.locus.core.model.ChatImportSource): String = when (source) {
    dev.locus.core.model.ChatImportSource.CHATGPT -> "gpt"
    dev.locus.core.model.ChatImportSource.CLAUDE -> "claude"
}
