package dev.locus.core.data.repository

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.workflow.RenderedWorkflow
import dev.locus.core.agent.workflow.WorkflowDraft
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.agent.workflow.WorkflowStore
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.UsageSource
import dev.locus.core.model.Workflow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved workflows (docs/design/workflows.md): drafting one from a finished task, keeping them as
 * files in agent-home, and running one as a normal chat turn.
 */
@Singleton
class WorkflowRepository @Inject constructor(
    home: AgentHome,
    private val helper: HelperModel,
    private val conversations: ConversationRepository,
    private val receipts: TaskReceiptRepository,
    private val changes: ChangeRepository,
    private val settings: SettingsRepository,
    private val runner: AgentRunner,
    private val tasks: dev.locus.core.data.tasks.TaskRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val store = WorkflowStore(home)
    private val _all = MutableStateFlow<List<SavedWorkflow>>(emptyList())

    /** Every saved workflow, shared ones first. */
    val all: StateFlow<List<SavedWorkflow>> = _all.asStateFlow()

    suspend fun refresh() {
        _all.value = withContext(io) { runCatching { store.all() }.getOrDefault(emptyList()) }
    }

    /**
     * A draft of a workflow from task [taskId] of [conversationId], for the review sheet. The helper
     * model words it; the task's record decides its tools, approval points and checks. Never null:
     * without a helper model the draft comes from the record alone.
     */
    suspend fun draft(conversationId: String, taskId: String): Workflow = withContext(io) {
        val path = conversations.history(conversationId)
        val tracked = changes.observe(conversationId).first()
        val receipt = receipts.inputs(conversationId).first().receipts(path, tracked).firstOrNull { it.taskId == taskId }
            ?: throw IllegalStateException("That task's record isn't available any more.")
        val messages = path.filter { it.createdAt in receipt.startedAt..receipt.endedAt }
        val standing = ReceiptInputs.standing(tracked)
        val changed = receipt.changeTurns.flatMap { standing[it].orEmpty() }.distinct()
        val trace = WorkflowDraft.Trace.of(messages, receipt.checks, changed, conversationId, taskId)
        val conversation = conversations.get(conversationId)
        val provider = settings.current().provider(conversation?.providerId)
        val raw = helper.complete(provider, WorkflowDraft.prompt(trace), maxOutputTokens = 1_500, timeoutMs = 60_000, source = UsageSource.OTHER, conversationId = conversationId)
        WorkflowDraft.draft(trace, raw, System.currentTimeMillis())
    }

    /** Saves [workflow] for every chat ([projectId] null) or one project; [replacing] is the id it was opened with. */
    suspend fun save(workflow: Workflow, projectId: String?, replacing: String? = null): Workflow =
        withContext(io) { store.save(workflow, projectId, replacing) }.also { refresh() }

    suspend fun delete(saved: SavedWorkflow) {
        withContext(io) { store.delete(saved.workflow.id, saved.projectId) }
        refresh()
    }

    /** The workflows a chat in [projectId] can run. */
    fun forChat(projectId: String?): List<SavedWorkflow> = _all.value.filter { it.projectId == null || it.projectId == projectId }
        .sortedBy { if (it.projectId == null) 1 else 0 }

    /** Fills in [workflow] with [values]; throws [dev.locus.core.agent.workflow.WorkflowInputException] for a bad value. */
    fun render(workflow: Workflow, values: Map<String, String>): RenderedWorkflow = WorkflowRender.render(workflow, values)

    /** Runs [rendered] in [conversationId]; false when that chat is busy with another task. */
    fun run(conversationId: String, rendered: RenderedWorkflow): Boolean = runner.sendWorkflow(conversationId, rendered)

    /**
     * Schedules [saved] with fixed [values] every [intervalMinutes] (at least 15): a scheduled task
     * whose prompt carries the plan. It runs read-only, like every scheduled task, so its approval
     * points and changes are refused when it runs ([dev.locus.core.agent.workflow.WorkflowScope.unattended]).
     */
    suspend fun schedule(saved: SavedWorkflow, values: Map<String, String>, intervalMinutes: Long) {
        val rendered = WorkflowRender.render(saved.workflow, values, saved.projectId)
        tasks.create(saved.workflow.name, WorkflowRender.stored(rendered), intervalMinutes)
    }

    /** Runs [rendered] in a new chat (in [projectId], for a project's workflow) and returns the chat's id. */
    suspend fun runInNewChat(rendered: RenderedWorkflow, projectId: String?): String {
        val chat = conversations.create(null, projectId)
        runner.sendWorkflow(chat.id, rendered)
        return chat.id
    }
}
