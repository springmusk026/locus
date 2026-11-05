package dev.locus.core.data.automation

import dev.locus.core.agent.workflow.WorkflowInputException
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.ProjectRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.WorkflowRepository
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.model.ModeNote
import dev.locus.core.model.TaskRunOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How a request ended, for the caller ([AutomationContract]'s result extras). */
data class AutomationResult(
    val status: AutomationContract.Status,
    val text: String? = null,
    val error: String? = null,
    val chatId: String? = null,
    val requestId: String? = null,
)

/** The request started ([chatId] is the chat to open, when there is one), or why it couldn't. */
sealed interface AutomationStart {
    data class Started(val chatId: String?) : AutomationStart
    data class Failed(val reason: String) : AutomationStart
}

/** Finds a saved item by its id, or else by a name that only one item has (ignoring case). */
object AutomationLookup {
    fun <T> find(items: List<T>, query: String, id: (T) -> String, name: (T) -> String): T? {
        val q = query.trim()
        items.firstOrNull { id(it) == q }?.let { return it }
        return items.filter { name(it).trim().equals(q, ignoreCase = true) }.singleOrNull()
    }
}

/**
 * Runs what another app asked for, once Locus has decided it may ([AutomationAccess]): each in a new
 * chat, tainted as untrusted input from that app ([ModeNote.external]), with the chat's normal
 * approvals. [reply] gets the outcome when the task ends (docs/design/android-bridge.md).
 */
@Singleton
class AutomationBridge @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val runner: AgentRunner,
    private val workflows: WorkflowRepository,
    private val tasks: TaskRepository,
    private val projects: ProjectRepository,
) {
    suspend fun start(request: AutomationRequest, caller: Caller, reply: (AutomationResult) -> Unit): AutomationStart = try {
        when (request) {
            is AutomationRequest.StartChat -> startChat(request, caller, reply)
            is AutomationRequest.RunWorkflow -> runWorkflow(request, caller, reply)
            is AutomationRequest.RunTask -> runTask(request, caller, reply)
            is AutomationRequest.Event -> event(request, caller, reply)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AutomationStart.Failed(e.message ?: "Locus couldn't start it.")
    }

    private suspend fun startChat(request: AutomationRequest.StartChat, caller: Caller, reply: (AutomationResult) -> Unit): AutomationStart {
        val projectId = request.project?.let { query ->
            AutomationLookup.find(projects.all.first(), query, { it.id }, { it.name })?.id
                ?: return AutomationStart.Failed("No project called “$query”.")
        }
        val chat = conversations.create(settings.current().defaultProviderId, projectId)
        val run = runner.sendFromApp(chat.id, request.prompt, ModeNote.external(caller.shown, textFromApp = true))
            ?: return AutomationStart.Failed("Locus couldn't start the chat.")
        follow(chat.id, run, request.requestId, reply)
        return AutomationStart.Started(chat.id)
    }

    private suspend fun runWorkflow(request: AutomationRequest.RunWorkflow, caller: Caller, reply: (AutomationResult) -> Unit): AutomationStart {
        workflows.refresh()
        val saved = AutomationLookup.find(workflows.all.value, request.workflow, { it.workflow.id }, { it.workflow.name })
            ?: return AutomationStart.Failed("No saved workflow called “${request.workflow}”.")
        val rendered = try {
            WorkflowRender.render(saved.workflow, request.params, saved.projectId)
        } catch (e: WorkflowInputException) {
            return AutomationStart.Failed("Input “${e.param}”: ${e.message}")
        }
        val chat = conversations.create(null, saved.projectId)
        // The inputs came from the app, so the message's text counts as the app's, not the user's.
        val run = runner.sendWorkflow(chat.id, rendered, listOf(ModeNote.external(caller.shown, textFromApp = true)))
            ?: return AutomationStart.Failed("Locus couldn't start the workflow.")
        follow(chat.id, run, request.requestId, reply)
        return AutomationStart.Started(chat.id)
    }

    private suspend fun runTask(request: AutomationRequest.RunTask, caller: Caller, reply: (AutomationResult) -> Unit): AutomationStart {
        val task = AutomationLookup.find(tasks.tasks.first(), request.task, { it.id }, { it.title })
            ?: return AutomationStart.Failed("No scheduled task called “${request.task}”.")
        val work = tasks.runNow(task.id, fromApp = caller.shown)
        scope.launch {
            val result = try {
                val ran = tasks.awaitRunNow(work)
                val row = tasks.get(task.id)
                val failed = !ran || row?.lastOutcome == TaskRunOutcome.FAILED.name || row?.lastOutcome == TaskRunOutcome.STOPPED.name
                AutomationResult(
                    if (failed) AutomationContract.Status.FAILED else AutomationContract.Status.DONE,
                    text = row?.lastSummary.takeIf { !failed },
                    error = if (failed) row?.lastSummary ?: "The task didn't run." else null,
                    chatId = row?.lastConversationId,
                    requestId = request.requestId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AutomationResult(AutomationContract.Status.FAILED, error = e.message, requestId = request.requestId)
            }
            reply(result)
        }
        return AutomationStart.Started(null)
    }

    /**
     * Hands an event to the tasks waiting for one from another app (docs/design/event-triggers.md). Each
     * task's own filter and allow-list decide; the caller is only ever the package Android named. The reply
     * says at once what each task did (ran, skipped and why); the runs report through their notifications.
     */
    private suspend fun event(request: AutomationRequest.Event, caller: Caller, reply: (AutomationResult) -> Unit): AutomationStart {
        val outcomes = tasks.appEvent(request.name, request.text, caller.packageName, caller.shown)
        if (outcomes.isEmpty()) return AutomationStart.Failed("No task waits for events from other apps. Set one up in Tasks: When… → Another app.")
        val text = outcomes.joinToString("\n") { (title, outcome) ->
            "$title: " + (outcome.decision?.label?.lowercase() ?: "didn't match (${outcome.unmatched.firstOrNull() ?: "its filter"})")
        }
        val ran = outcomes.any { it.second.decision?.ran == true }
        reply(AutomationResult(if (ran) AutomationContract.Status.DONE else AutomationContract.Status.REFUSED, text = text.take(AutomationContract.MAX_RESULT_CHARS), requestId = request.requestId))
        return AutomationStart.Started(null)
    }

    /**
     * Waits for run [runId] to end, then replies with its own answer; a run that ended without finishing
     * (stopped, refused, out of budget) is a failure that says how it ended, never DONE with no text.
     */
    private fun follow(chatId: String, runId: String, requestId: String?, reply: (AutomationResult) -> Unit) {
        scope.launch {
            val run = runner.awaitRun(runId)
            val end = if (run == null) "Locus lost track of the task." else run.endLine
            reply(
                if (end != null) {
                    AutomationResult(AutomationContract.Status.FAILED, error = end, chatId = chatId, requestId = requestId)
                } else {
                    AutomationResult(AutomationContract.Status.DONE, text = run?.answer?.take(AutomationContract.MAX_RESULT_CHARS), chatId = chatId, requestId = requestId)
                },
            )
        }
    }
}
