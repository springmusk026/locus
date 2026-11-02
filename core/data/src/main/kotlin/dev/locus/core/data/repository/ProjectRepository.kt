package dev.locus.core.data.repository

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.ProjectPrompt
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.database.ConversationDao
import dev.locus.core.database.ProjectDao
import dev.locus.core.database.ProjectEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.Conversation
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Project
import dev.locus.core.model.ProjectPolicy
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Projects (docs/design/projects.md). The row (name, folder, models, memory scope) is in Room; the
 * instructions and memory are files in agent-home; the permissions are in the sealed settings.
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val projects: ProjectDao,
    private val conversations: ConversationDao,
    private val home: AgentHome,
    private val settings: SettingsRepository,
    private val workspace: Workspace,
) {
    val all: Flow<List<Project>> = projects.observeAll().map { list -> list.map { it.toModel() } }

    fun observe(id: String): Flow<Project?> = projects.observe(id).map { it?.toModel() }

    suspend fun get(id: String?): Project? = id?.let { projects.get(it)?.toModel() }

    suspend fun allOnce(): List<Project> = projects.all().map { it.toModel() }

    suspend fun instructions(id: String): String = withContext(Dispatchers.IO) { home.projectInstructions(id) }

    suspend fun policy(id: String): ProjectPolicy = settings.current().projectPolicies[id] ?: ProjectPolicy()

    /**
     * Creates a project with its folder in the workspace (made if missing). Returns null when [folder]
     * isn't a clean workspace-relative path or another project already uses it.
     */
    suspend fun create(
        name: String,
        folder: String = ProjectPolicy.defaultFolder(name),
        instructions: String = "",
        memoryScope: MemoryScope = MemoryScope.PROJECT_AND_GLOBAL,
    ): Project? {
        val clean = ProjectPolicy.cleanFolder(folder) ?: return null
        if (projects.all().any { it.folder == clean }) return null
        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            UUID.randomUUID().toString(), name.trim().ifEmpty { "Project" }.take(MAX_NAME), clean,
            providerId = null, model = null, helperProviderId = null, helperModel = null,
            memoryScope = memoryScope.name, createdAt = now, updatedAt = now,
        )
        withContext(Dispatchers.IO) {
            File(workspace.base, clean).mkdirs()
            if (instructions.isNotBlank()) home.writeProjectInstructions(entity.id, instructions)
        }
        projects.upsert(entity)
        return entity.toModel()
    }

    /** Marks the project as just used, so it sorts first. */
    suspend fun touch(id: String) {
        projects.get(id)?.let { projects.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /** Saves everything but the folder, which is fixed. Takes effect for new chats. */
    suspend fun update(project: Project) {
        val stored = projects.get(project.id) ?: return
        projects.upsert(
            stored.copy(
                name = project.name.trim().ifEmpty { stored.name }.take(MAX_NAME),
                providerId = project.providerId, model = project.model?.trim()?.takeIf { it.isNotEmpty() },
                helperProviderId = project.helperProviderId, helperModel = project.helperModel?.trim()?.takeIf { it.isNotEmpty() },
                memoryScope = project.memoryScope.name,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Applies to new chats only: existing chats keep the instructions frozen in their prompt. */
    suspend fun setInstructions(id: String, text: String) = withContext(Dispatchers.IO) { home.writeProjectInstructions(id, text) }

    suspend fun setPolicy(id: String, policy: ProjectPolicy) = settings.setProjectPolicy(id, policy)

    /**
     * Deletes the project: its chats move out of it (their prompts stay as they were), its instructions
     * and memory go to agent-home's history, its permissions are dropped. The folder's files stay.
     */
    suspend fun delete(id: String) {
        conversations.releaseProject(id)
        projects.delete(id)
        settings.setProjectPolicy(id, null)
        settings.setProjectRemote(id, null)
        withContext(Dispatchers.IO) { home.removeProject(id) }
    }

    /** The scope a new chat in [project] is frozen with. */
    fun scopeFor(project: Project): ChatScopeSpec = ChatScopeSpec(project.id, project.folder, project.memoryScope)

    /** What a new chat's prompt needs from [project]. */
    suspend fun promptFor(project: Project): ProjectPrompt = ProjectPrompt(scopeFor(project), policy(project.id).allowedConnectors)

    /**
     * The scope a chat runs with: its project's folder and the memory scope it started with. Null
     * outside projects, or when its project was deleted.
     */
    suspend fun scopeOf(conversation: Conversation?): ChatScopeSpec? {
        val projectId = conversation?.projectId ?: return null
        val project = get(projectId) ?: return null
        return ChatScopeSpec(project.id, project.folder, conversation.projectMemory ?: project.memoryScope)
    }

    companion object {
        const val MAX_NAME = 60

        /**
         * The policy for [tool] in a chat: the global policy, then the chat's project's overlay (which
         * only narrows unless the user widened that tool). See [ProjectPolicy.effective].
         */
        fun policyFor(tool: String, default: ToolPolicy, settings: AppSettings, projectId: String?): ToolPolicy {
            val global = settings.toolPolicies[tool] ?: default
            val project = projectId?.let { settings.projectPolicies[it] } ?: return global
            return project.effective(tool, global)
        }

        fun ProjectEntity.toModel() = Project(
            id, name, folder, providerId, model, helperProviderId, helperModel,
            MemoryScope.parse(memoryScope) ?: MemoryScope.PROJECT_AND_GLOBAL, createdAt, updatedAt,
        )
    }
}
