package dev.locus.core.data.tasks

import androidx.work.WorkInfo
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.integrations.ReachMe
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.TaskRunOutcome
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A run that sent its prompt and whose worker hasn't returned yet: the per-run idempotency marker. */
data class RunMarker(val workId: String, val conversationId: String, val startedAt: Long)

/** What a starting run does, given the task's [RunMarker] (see [decideStart]). */
internal sealed interface RunStart {
    /** Nothing of this run has gone out yet: send the prompt. [abandoned] is another run's stale marker, to close first. */
    data class Send(val abandoned: RunMarker?) : RunStart

    /** This work already sent the prompt (WorkManager retried it after a stop, or the process died): never send it again. */
    data class AlreadySent(val marker: RunMarker) : RunStart

    /** Another run of the same task (Run now next to the schedule) is still going: leave this one out. */
    data object Busy : RunStart
}

/**
 * The idempotency rule. The marker is written just before the prompt goes out and cleared when the
 * worker returns, so a marker for the same work id means a retry of a run whose prompt was sent.
 * Another work's marker counts until it is older than [ScheduledRun.MARKER_STALE]: no worker runs that
 * long (WorkManager stops it at ten minutes), so an older one was left by a run that died.
 */
internal fun decideStart(marker: RunMarker?, workId: String, now: Long): RunStart = when {
    marker == null -> RunStart.Send(null)
    marker.workId == workId -> RunStart.AlreadySent(marker)
    now - marker.startedAt < ScheduledRun.MARKER_STALE.inWholeMilliseconds -> RunStart.Busy
    else -> RunStart.Send(marker)
}

/** Why Android stopped a worker, for "Stopped by Android (…)". Values are WorkInfo's (JobParameters') stop reasons. */
internal fun stopReasonText(reason: Int): String = when (reason) {
    WorkInfo.STOP_REASON_TIMEOUT -> "time limit for background work"
    WorkInfo.STOP_REASON_QUOTA -> "background work quota used up"
    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "network lost"
    WorkInfo.STOP_REASON_DEVICE_STATE -> "device state changed"
    WorkInfo.STOP_REASON_PREEMPT -> "preempted by other work"
    WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background use restricted for Locus"
    WorkInfo.STOP_REASON_APP_STANDBY -> "app standby"
    WorkInfo.STOP_REASON_USER -> "stopped from system settings"
    WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system busy"
    WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "device idle"
    WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery low"
    WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "not charging"
    WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage low"
    WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "app launch expected"
    WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground service time limit"
    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled"
    else -> "reason unknown"
}

/**
 * One scheduled task's run, outside the Android worker so it can be tested: a fresh chat, read-only
 * tools, a wall-clock budget below WorkManager's ten minutes, and a recorded outcome with a
 * notification however it ends, stopped by Android included (docs/design/scheduled-runs.md).
 */
@Singleton
class ScheduledRun @Inject constructor(
    private val tasks: TaskRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val runner: AgentRunner,
    private val secrets: SecretResolver,
    private val http: HttpClient,
) {
    /** How long a run may take; tests shorten it. */
    internal var budget: Duration = RUN_BUDGET

    internal var clock: () -> Long = System::currentTimeMillis

    enum class Result { DONE, FAILED, SKIPPED }

    /**
     * Runs task [taskId] as work [workId]. [stopReason] tells why the worker was stopped when it is
     * cancelled (WorkInfo.STOP_REASON_*); [notify] posts the result. A cancellation is rethrown after
     * the stop is recorded.
     */
    suspend fun run(
        taskId: String,
        workId: String,
        runNow: Boolean,
        stopReason: () -> Int,
        notify: (title: String, text: String) -> Unit,
    ): Result {
        val task = tasks.get(taskId) ?: return Result.FAILED
        if (!task.enabled && !runNow) return Result.SKIPPED
        when (val start = decideStart(tasks.marker(taskId), workId, clock())) {
            RunStart.Busy -> return Result.SKIPPED
            is RunStart.AlreadySent -> {
                closeInterrupted(taskId, task.title, start.marker, notify)
                tasks.clearMarker(taskId, workId)
                return Result.SKIPPED
            }
            is RunStart.Send -> start.abandoned?.let { closeInterrupted(taskId, task.title, it, notify = null) }
        }

        val conversation = conversations.create(settings.current().defaultProviderId)
        conversations.rename(conversation.id, "Scheduled: ${task.title} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(clock()))}")
        // Written before the prompt goes out: from here on, a retry of this work never sends it again.
        tasks.markRun(taskId, RunMarker(workId, conversation.id, clock()))
        try {
            val run = withTimeoutOrNull(budget) { runner.runInBackground(conversation.id, prompt(task.prompt)) }
            val (outcome, summary) = when {
                run == null -> TaskRunOutcome.STOPPED to "Stopped: it took longer than ${budget.inWholeMinutes} minutes, the limit for a scheduled run. The chat has what it found so far."
                run.error != null -> TaskRunOutcome.FAILED to "Failed: ${run.error}"
                else -> TaskRunOutcome.DONE to (run.text ?: "Finished with no answer.")
            }
            tasks.recordRun(taskId, conversation.id, summary, outcome)
            notify(title(task.title, outcome), withReceipt(plain(summary), conversation.id))
            // Also to the user's reach-me channels (ntfy, Telegram) when any is set up, so the answer reaches their other devices.
            // ReachMe catches each channel's failure; this guards the rest.
            runCatching { ReachMe.send(http, secrets, title(task.title, outcome), summary) }
            tasks.clearMarker(taskId, workId)
            return if (outcome == TaskRunOutcome.FAILED) Result.FAILED else Result.DONE
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val reason = stopReason()
                if (reason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                    // Turned off or deleted: nothing will retry this work, so the marker goes too.
                    tasks.recordRun(taskId, conversation.id, "Stopped: the task was turned off.", TaskRunOutcome.STOPPED)
                    tasks.clearMarker(taskId, workId)
                } else {
                    val summary = "Stopped by Android (${stopReasonText(reason)}). It runs again at its next time; the chat has what it found so far."
                    tasks.recordRun(taskId, conversation.id, summary, TaskRunOutcome.STOPPED)
                    notify(title(task.title, TaskRunOutcome.STOPPED), withReceipt(summary, conversation.id))
                    // The marker stays: WorkManager retries a stopped worker, and that retry must not send the prompt again.
                }
            }
            throw e
        } catch (e: Exception) {
            val summary = "Failed: ${e.message ?: e::class.java.simpleName}"
            tasks.recordRun(taskId, conversation.id, summary, TaskRunOutcome.FAILED)
            notify(title(task.title, TaskRunOutcome.FAILED), withReceipt(summary, conversation.id))
            tasks.clearMarker(taskId, workId)
            return Result.FAILED
        }
    }

    /** Records a run whose prompt went out but whose end was never recorded (the process died), and tells the user. */
    private suspend fun closeInterrupted(taskId: String, title: String, marker: RunMarker, notify: ((String, String) -> Unit)?) {
        val task = tasks.get(taskId) ?: return
        if (task.lastConversationId == marker.conversationId) return // Its stop was recorded already.
        val summary = "Stopped: Locus was closed before the run finished. It runs again at its next time; the chat has what it found so far."
        tasks.recordRun(taskId, marker.conversationId, summary, TaskRunOutcome.STOPPED)
        notify?.invoke(title(title, TaskRunOutcome.STOPPED), summary)
    }

    /** [text] with the run's task receipt as one more line ("Done · 42s · ≈$0.01"), when there is one. */
    private suspend fun withReceipt(text: String, conversationId: String): String {
        val line = try {
            runner.receiptLine(conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return text
        return "$text\n$line"
    }

    private fun prompt(text: String) =
        "$text\n\n(This is a scheduled task running in the background. You can open and read web pages, files and connectors, but not click, type, write or send anything. Reply with a short plain-text summary the user will see in a notification.)"

    private fun title(task: String, outcome: TaskRunOutcome) = when (outcome) {
        TaskRunOutcome.DONE -> task
        TaskRunOutcome.FAILED -> "$task: failed"
        TaskRunOutcome.STOPPED -> "$task: stopped"
    }

    companion object {
        /**
         * The wall-clock budget: WorkManager gives a worker at most ten minutes, and this leaves time to
         * record the outcome and notify before it would be stopped.
         */
        val RUN_BUDGET: Duration = 8.minutes

        /** A marker older than this was left by a run that died: no worker lives past ten minutes. */
        val MARKER_STALE: Duration = 15.minutes

        /** Notifications show plain text, so drop Markdown emphasis, code and link syntax. */
        internal fun plain(markdown: String) = markdown
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("[*_`#>]+"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}
