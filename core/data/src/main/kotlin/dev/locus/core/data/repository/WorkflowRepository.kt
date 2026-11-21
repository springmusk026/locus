package dev.locus.core.data.repository

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.workflow.RenderedWorkflow
import dev.locus.core.agent.workflow.ResearchWorkflow
import dev.locus.core.agent.workflow.WorkflowDraft
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.agent.workflow.WorkflowStore
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.TaskBudget
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
    /** Whether a new chat starts on a model without tool calling; workflows need tools (docs/design/deep-research.md). */
    private val chatOnly: ChatOnlyRule = ChatOnlyRule.Never,
) {
    private val store = WorkflowStore(home)
    private val _all = MutableStateFlow(BUILT_IN)

    /** Every workflow: the built-in ones first, then the saved ones, shared before each project's. */
    val all: StateFlow<List<SavedWorkflow>> = _all.asStateFlow()

    suspend fun refresh() {
        _all.value = BUILT_IN + withContext(io) { runCatching { store.all() }.getOrDefault(emptyList()) }
    }

    /**
     * Whether a workflow can't run in [conversationId] (null: a new chat on the default model) because
     * the model has no tool calling ([ChatOnlyRule], docs/design/model-capabilities.md). A workflow is
     * carried out with tools, so the run sheet explains instead of starting a run that can't work.
     */
    suspend fun lacksTools(conversationId: String?): Boolean {
        if (conversationId == null) return chatOnly.startsChatOnly(null, null)
        val chat = conversations.get(conversationId) ?: return false
        return chat.chatOnly
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

    /** Removes a saved workflow; a built-in one can't be removed (Library offers no Remove for it). */
    suspend fun delete(saved: SavedWorkflow) {
        require(!saved.builtIn) { "A built-in workflow can't be removed." }
        withContext(io) { store.delete(saved.workflow.id, saved.projectId) }
        refresh()
    }

    /** The workflows a chat in [projectId] can run. */
    fun forChat(projectId: String?): List<SavedWorkflow> = _all.value.filter { it.projectId == null || it.projectId == projectId }
        .sortedBy { if (it.projectId == null) 1 else 0 }

    /** Fills in [workflow] with [values]; throws [dev.locus.core.agent.workflow.WorkflowInputException] for a bad value. */
    fun render(workflow: Workflow, values: Map<String, String>): RenderedWorkflow = WorkflowRender.render(workflow, values)

    /**
     * Fills in [saved] with [values] for a run. The built-in research workflow words its plan for the
     * chosen depth and carries [budget] (null: the depth's default); see [ResearchWorkflow.render].
     */
    fun render(saved: SavedWorkflow, values: Map<String, String>, budget: TaskBudget? = null): RenderedWorkflow =
        if (saved.builtIn && ResearchWorkflow.isResearch(saved.workflow)) ResearchWorkflow.render(values, budget) else WorkflowRender.render(saved.workflow, values)

    /** Runs [rendered] in [conversationId]; false when that chat is busy with another task. */
    fun run(conversationId: String, rendered: RenderedWorkflow): Boolean = runner.sendWorkflow(conversationId, rendered) != null

    /**
     * Schedules [saved] with fixed [values] every [intervalMinutes] (at least 15): a scheduled task
     * whose prompt carries the plan. It runs read-only, like every scheduled task, so its approval
     * points and changes are refused when it runs ([dev.locus.core.agent.workflow.WorkflowScope.unattended]).
     */
    suspend fun schedule(saved: SavedWorkflow, values: Map<String, String>, intervalMinutes: Long) {
        val rendered = if (saved.builtIn && ResearchWorkflow.isResearch(saved.workflow)) {
            ResearchWorkflow.render(values, projectId = saved.projectId)
        } else {
            WorkflowRender.render(saved.workflow, values, saved.projectId)
        }
        // A scheduled research digest is named after its question, the user's own words.
        val name = if (saved.builtIn) rendered.plan.inputs[ResearchWorkflow.QUESTION]?.take(80)?.ifBlank { null } ?: saved.workflow.name else saved.workflow.name
        tasks.create(name, WorkflowRender.stored(rendered), intervalMinutes, settings.current().power.taskDefaults)
    }

    /** Runs [rendered] in a new chat (in [projectId], for a project's workflow) and returns the chat's id. */
    suspend fun runInNewChat(rendered: RenderedWorkflow, projectId: String?): String {
        val chat = conversations.create(null, projectId)
        runner.sendWorkflow(chat.id, rendered)
        return chat.id
    }

    companion object {
        /** The workflows that ship with Locus, listed before the saved ones. */
        val BUILT_IN: List<SavedWorkflow> = listOf(SavedWorkflow(ResearchWorkflow.workflow, projectId = null, builtIn = true))
    }
}
