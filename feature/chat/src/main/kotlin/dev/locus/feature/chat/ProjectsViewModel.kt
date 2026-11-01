package dev.locus.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.ProjectRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.model.Conversation
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Project
import dev.locus.core.model.ProjectPolicy
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A project in the list: how many chats it has and when one was last active. */
data class ProjectRow(val project: Project, val chats: Int, val lastActive: Long?, val modelLabel: String = "Your default model")

/** The Projects screen: every project with its chats, and creating one. See docs/design/projects.md. */
@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val conversations: ConversationRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val rows: StateFlow<List<ProjectRow>?> = combine(projects.all, conversations.all, conversations.emptyIds, settings.settings) { list, chats, empty, s ->
        list.map { p ->
            val mine = chats.filter { it.projectId == p.id && it.id !in empty }
            val provider = s.providers.firstOrNull { it.id == p.providerId }
            val label = if (provider == null) "Your default model" else "${provider.name} · ${p.model ?: provider.model}"
            ProjectRow(p, mine.size, mine.maxOfOrNull { it.updatedAt }, label)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A project's chats, newest first, without empty ones. */
    fun chats(projectId: String): StateFlow<List<Conversation>> = combine(conversations.all, conversations.emptyIds) { chats, empty ->
        chats.filter { it.projectId == projectId && it.id !in empty }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Starts a chat in [projectId] with its default model, then opens it. */
    fun newChat(projectId: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        val chat = conversations.create(null, projectId)
        onCreated(chat.id)
    }
}

/** One permission row in the project editor. */
data class ProjectToolRow(
    val name: String,
    val description: String,
    /** The global policy (the user's setting or the tool's default). */
    val global: ToolPolicy,
    /** The project's own setting, or null for "same as everywhere". */
    val own: ToolPolicy?,
    /** What applies in the project's chats. */
    val effective: ToolPolicy,
)

/** A connector in the editor's allow-list. */
data class ProjectConnectorRow(val id: String, val name: String, val allowed: Boolean)

data class ProjectEditorState(
    val loading: Boolean = true,
    /** Null while creating a new project. */
    val project: Project? = null,
    val name: String = "",
    val folder: String = "",
    val instructions: String = "",
    val memoryScope: MemoryScope = MemoryScope.PROJECT_AND_GLOBAL,
    val providerId: String? = null,
    val model: String = "",
    val helperProviderId: String? = null,
    val helperModel: String = "",
    val providers: List<Pair<String, String>> = emptyList(),
    val tools: List<ProjectToolRow> = emptyList(),
    val connectors: List<ProjectConnectorRow> = emptyList(),
    /** Null: every connector is allowed. */
    val allowedConnectors: Set<String>? = null,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val isNew: Boolean get() = project == null
}

/**
 * Creates or edits a project. Name, models, instructions and memory scope are saved together;
 * permissions are saved as they change, into the sealed settings.
 */
@HiltViewModel(assistedFactory = ProjectEditorViewModel.Factory::class)
class ProjectEditorViewModel @AssistedInject constructor(
    @Assisted private val projectId: String?,
    private val projects: ProjectRepository,
    private val settings: SettingsRepository,
    private val tools: ToolCatalog,
    private val library: LibraryRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(projectId: String?): ProjectEditorViewModel
    }

    private val _state = MutableStateFlow(ProjectEditorState())
    val state: StateFlow<ProjectEditorState> = _state

    init {
        viewModelScope.launch {
            val project = projects.get(projectId)
            val s = settings.current()
            _state.value = ProjectEditorState(
                loading = false,
                project = project,
                name = project?.name.orEmpty(),
                folder = project?.folder.orEmpty(),
                instructions = project?.let { projects.instructions(it.id) }.orEmpty(),
                memoryScope = project?.memoryScope ?: MemoryScope.PROJECT_AND_GLOBAL,
                providerId = project?.providerId,
                model = project?.model.orEmpty(),
                helperProviderId = project?.helperProviderId,
                helperModel = project?.helperModel.orEmpty(),
                providers = s.providers.map { it.id to it.name },
            )
            refreshPermissions()
        }
    }

    private suspend fun refreshPermissions() {
        val s = settings.current()
        val policy = projectId?.let { s.projectPolicies[it] } ?: ProjectPolicy()
        val rows = tools.registry.all.map { t ->
            val global = tools.policy(t, s)
            ProjectToolRow(t.spec.name, t.spec.description, global, policy.toolPolicies[t.spec.name], policy.effective(t.spec.name, global))
        }
        val connectors = library.library.first().connectors.filter { !it.draft }.map { e ->
            ProjectConnectorRow(e.connector.id, e.connector.name.ifBlank { e.connector.id }, policy.allowsConnector(e.connector.id))
        }
        _state.value = _state.value.copy(tools = rows, connectors = connectors, allowedConnectors = policy.allowedConnectors)
    }

    fun setName(v: String) = edit { copy(name = v) }
    fun setFolder(v: String) = edit { copy(folder = v) }
    fun setInstructions(v: String) = edit { copy(instructions = v) }
    fun setMemoryScope(v: MemoryScope) = edit { copy(memoryScope = v) }
    fun setModel(providerId: String?, model: String) = edit { copy(providerId = providerId, model = model) }
    fun setHelper(providerId: String?, model: String) = edit { copy(helperProviderId = providerId, helperModel = model) }

    /** Applies a field edit; any earlier error goes away once the user changes something. */
    private fun edit(change: ProjectEditorState.() -> ProjectEditorState) {
        _state.value = _state.value.copy(error = null).change()
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(error = message)
    }

    /** Saves and reports the project's id; a new project gets its folder now. */
    fun save(onSaved: (String) -> Unit) = viewModelScope.launch {
        val st = _state.value
        val name = st.name.trim()
        if (name.isEmpty()) return@launch fail("Give the project a name.")
        val base = st.project ?: run {
            val folder = st.folder.trim().ifEmpty { ProjectPolicy.defaultFolder(name) }
            if (ProjectPolicy.cleanFolder(folder) == null) return@launch fail("Use a folder inside the workspace, like projects/garden (no .. or hidden folders).")
            projects.create(name, folder, st.instructions, st.memoryScope)
                ?: return@launch fail("Another project already uses that folder.")
        }
        projects.update(
            base.copy(
                name = name, providerId = st.providerId, model = st.model.takeIf { st.providerId != null },
                helperProviderId = st.helperProviderId, helperModel = st.helperModel.takeIf { st.helperProviderId != null },
                memoryScope = st.memoryScope,
            ),
        )
        if (st.project != null) projects.setInstructions(base.id, st.instructions)
        _state.value = st.copy(saved = true)
        onSaved(base.id)
    }

    /**
     * Sets [tool]'s policy in this project; null goes back to the global one. Ask and Deny only narrow.
     * Allow is a widening: it's only applied when [widen] is true, which the screen sets after the user
     * confirms.
     */
    fun setToolPolicy(tool: String, policy: ToolPolicy?, widen: Boolean = false) = updatePolicy { p ->
        when {
            policy == null -> p.copy(toolPolicies = p.toolPolicies - tool, widened = p.widened - tool)
            policy == ToolPolicy.ALLOW && !widen -> p
            else -> p.copy(toolPolicies = p.toolPolicies + (tool to policy), widened = if (widen) p.widened + tool else p.widened - tool)
        }
    }

    /** Allows or blocks one connector; the first change turns "every connector" into an explicit list. */
    fun setConnectorAllowed(id: String, allowed: Boolean) = updatePolicy { p ->
        val all = _state.value.connectors.map { it.id }.toSet()
        val current = p.allowedConnectors ?: all
        val next = if (allowed) current + id else current - id
        p.copy(allowedConnectors = if (next.containsAll(all)) null else next)
    }

    private fun updatePolicy(change: (ProjectPolicy) -> ProjectPolicy) = viewModelScope.launch {
        val id = projectId ?: return@launch
        projects.setPolicy(id, change(projects.policy(id)))
        refreshPermissions()
    }

    /** Deletes the project; its chats and files stay. */
    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        projectId?.let { projects.delete(it) }
        onDeleted()
    }
}

