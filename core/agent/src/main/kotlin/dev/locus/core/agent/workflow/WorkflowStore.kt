package dev.locus.core.agent.workflow

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowParam
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Workflows as files in agent-home, like skills: `workflows/<id>.json` for every chat, and
 * `projects/<project id>/workflows/<id>.json` for one project. Agent-home is in the app's private
 * storage and in backups; the agent has no tool that writes here, so it can't widen its own limits.
 */
class WorkflowStore(private val home: AgentHome) {

    private val dir get() = File(home.dir, DIR)

    private fun dirFor(projectId: String?) = if (projectId == null) dir else File(home.projectDir(projectId), DIR)

    /** Every workflow: the shared ones, then each project's. Unreadable files are skipped. */
    fun all(): List<SavedWorkflow> {
        val shared = load(dir).map { SavedWorkflow(it, null) }
        val projects = home.projectsDir.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.name }.flatMap { p ->
            load(File(p, DIR)).map { SavedWorkflow(it, p.name) }
        }
        return shared + projects
    }

    /** The workflows a chat in [projectId] can run: its project's first, then the shared ones. */
    fun forChat(projectId: String?): List<SavedWorkflow> = all().filter { it.projectId == projectId }.let { own ->
        if (projectId == null) own else own + all().filter { it.projectId == null }
    }

    fun get(id: String, projectId: String?): Workflow? = read(File(dirFor(projectId), "$id.json"))

    /**
     * Saves [workflow] (checked with [validate]) and returns what was written. A new workflow whose id
     * is taken gets a numbered one; [replacing] is the id it was opened with, to save over it.
     */
    fun save(workflow: Workflow, projectId: String?, replacing: String? = null, now: Long = System.currentTimeMillis()): Workflow {
        validate(workflow)
        val folder = dirFor(projectId).apply { mkdirs() }
        var id = workflow.id
        if (id != replacing) {
            var n = 2
            while (File(folder, "$id.json").exists()) id = "${workflow.id.take(60)}-${n++}"
        }
        val saved = workflow.copy(schema = Workflow.SCHEMA, id = id, createdAt = workflow.createdAt.takeIf { it > 0 } ?: now, updatedAt = now)
        val file = File(folder, "$id.json")
        val tmp = File(folder, "$id.json.tmp")
        tmp.writeText(json.encodeToString(Workflow.serializer(), saved))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        if (replacing != null && replacing != id) File(folder, "$replacing.json").delete()
        return saved
    }

    fun delete(id: String, projectId: String?): Boolean {
        require(Workflow.ID.matches(id)) { "Not a workflow id: $id" }
        return File(dirFor(projectId), "$id.json").delete()
    }

    private fun load(folder: File): List<Workflow> =
        folder.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull(::read).sortedBy { it.name.lowercase() }

    private fun read(file: File): Workflow? = runCatching { json.decodeFromString(Workflow.serializer(), file.readText()) }.getOrNull()
        ?.takeIf { Workflow.ID.matches(it.id) && it.id == file.nameWithoutExtension }

    companion object {
        const val DIR = "workflows"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; coerceInputValues = true }

        /** Throws [IllegalArgumentException] with a message for the user when [w] can't be saved. */
        fun validate(w: Workflow) {
            require(Workflow.ID.matches(w.id) && w.id.length <= 64) { "The workflow's id must be 1–64 lowercase letters, digits and single hyphens." }
            require(w.name.isNotBlank()) { "Give the workflow a name." }
            require(w.goal.isNotBlank()) { "Say what the workflow does." }
            require(w.parameters.size <= Workflow.MAX_PARAMS) { "A workflow has at most ${Workflow.MAX_PARAMS} inputs." }
            require(w.steps.size <= Workflow.MAX_STEPS) { "A workflow has at most ${Workflow.MAX_STEPS} steps." }
            w.parameters.forEach { p ->
                require(WorkflowParam.NAME.matches(p.name)) { "“${p.name}” isn't a valid input name: use lowercase letters, digits and underscores." }
            }
            require(w.parameters.map { it.name }.toSet().size == w.parameters.size) { "Two inputs have the same name." }
        }
    }
}
