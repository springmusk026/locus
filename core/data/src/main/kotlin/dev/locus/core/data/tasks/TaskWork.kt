package dev.locus.core.data.tasks

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.locus.core.data.tasks.triggers.TriggerWorker
import dev.locus.core.model.TaskConditions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Where a "Run now" stands, from its WorkManager work (see [runNowStatus]). */
enum class RunNowStatus {
    /** Waiting to start: Run now needs a network connection. */
    QUEUED,

    /** Running now. */
    RUNNING,

    /** Ran; its outcome is on the task (lastOutcome, lastSummary). */
    DONE,

    /** Didn't run: another run of the same task was still going. */
    SKIPPED,

    /** Failed (the agent hit an error), or was cancelled before it finished. */
    FAILED,
    ;

    /** Still waiting or running: nothing to report yet. */
    val active: Boolean get() = this == QUEUED || this == RUNNING
}

/**
 * The status of a unique run-now work from its [state] and the [result] its worker returned
 * ([TaskWorker.KEY_RESULT], a [ScheduledRun.Result] name); null when there is no such work.
 */
internal fun runNowStatus(state: WorkInfo.State?, result: String?): RunNowStatus? = when (state) {
    null -> null
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> RunNowStatus.QUEUED
    WorkInfo.State.RUNNING -> RunNowStatus.RUNNING
    WorkInfo.State.SUCCEEDED -> if (result == ScheduledRun.Result.SKIPPED.name) RunNowStatus.SKIPPED else RunNowStatus.DONE
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> RunNowStatus.FAILED
}

/**
 * The WorkManager side of scheduled tasks, behind an interface so [TaskRepository] (and the screens
 * built on it) run in JVM tests without WorkManager.
 */
interface TaskWork {
    /**
     * Schedules [taskId] every [intervalMinutes] under [conditions], replacing its current schedule. The
     * first run waits [initialDelayMillis] (a template's "every morning at 7").
     */
    fun schedule(taskId: String, intervalMinutes: Long, conditions: TaskConditions, initialDelayMillis: Long = 0)

    /** Stops [taskId]'s schedule. */
    fun cancel(taskId: String)

    /** Stops every scheduled run. */
    fun cancelAll()

    /** Enqueues a run of [taskId] now (kept if one is already waiting); returns the unique work name. */
    fun runNow(taskId: String, fromApp: String?): String

    /** Waits for the run-now work [name] (from [runNow]) to finish; true when it ran and wasn't cancelled. */
    suspend fun awaitRunNow(name: String): Boolean

    /** [taskId]'s latest run-now work as it changes; null when there is none. */
    fun runNowStatus(taskId: String): Flow<RunNowStatus?>

    /**
     * Checks [taskId]'s trigger source every 15 minutes (WorkManager's shortest period), replacing any
     * earlier watch; [needsNetwork] for ntfy and mail (docs/design/event-triggers.md).
     */
    fun watch(taskId: String, needsNetwork: Boolean)

    /** One extra check of [taskId]'s source after [delayMillis] (a calendar entry due before the next periodic check, or right after saving). */
    fun checkSoon(taskId: String, delayMillis: Long, needsNetwork: Boolean)

    /** Stops checking [taskId]'s source. */
    fun unwatch(taskId: String)

    /**
     * Enqueues the run event [eventId] started for [taskId], under the task's [conditions]. A [test] run
     * waits only for a network and isn't held while the phone is hot, like Run now.
     */
    fun fireEvent(taskId: String, eventId: String, conditions: TaskConditions, test: Boolean)

    /** Where [taskId]'s event runs stand: RUNNING while one runs, QUEUED while one waits, null when none does. */
    fun eventRuns(taskId: String): Flow<RunNowStatus?>
}

/** [TaskWork] on WorkManager. */
internal class WorkManagerTaskWork(private val context: Context) : TaskWork {
    private val work get() = WorkManager.getInstance(context)

    override fun schedule(taskId: String, intervalMinutes: Long, conditions: TaskConditions, initialDelayMillis: Long) {
        work.enqueueUniquePeriodicWork(
            workName(taskId),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TaskWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(TaskWorker.KEY_TASK_ID to taskId))
                .setConstraints(TaskConstraints.of(conditions))
                .setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    override fun cancel(taskId: String) {
        work.cancelUniqueWork(workName(taskId))
    }

    // WorkManager tags each request with its worker's class name.
    override fun cancelAll() {
        work.cancelAllWorkByTag(TaskWorker::class.java.name)
        work.cancelAllWorkByTag(TriggerWorker::class.java.name)
    }

    override fun watch(taskId: String, needsNetwork: Boolean) {
        work.enqueueUniquePeriodicWork(
            watchName(taskId),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TriggerWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(TriggerWorker.KEY_TASK_ID to taskId))
                .setConstraints(checkConstraints(needsNetwork))
                .build(),
        )
    }

    override fun checkSoon(taskId: String, delayMillis: Long, needsNetwork: Boolean) {
        work.enqueueUniqueWork(
            "${watchName(taskId)}-soon",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TriggerWorker>()
                .setInputData(workDataOf(TriggerWorker.KEY_TASK_ID to taskId))
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(checkConstraints(needsNetwork))
                .build(),
        )
    }

    override fun unwatch(taskId: String) {
        work.cancelUniqueWork(watchName(taskId))
        work.cancelUniqueWork("${watchName(taskId)}-soon")
    }

    override fun fireEvent(taskId: String, eventId: String, conditions: TaskConditions, test: Boolean) {
        val request = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInputData(workDataOf(TaskWorker.KEY_TASK_ID to taskId, TaskWorker.KEY_EVENT_ID to eventId))
            .setConstraints(if (test) TaskConstraints.runNow() else TaskConstraints.of(conditions))
            .addTag(TaskWorker.TAG_EVENT)
            .addTag(eventTag(taskId))
        if (test) request.addTag(TaskWorker.TAG_RUN_NOW)
        work.enqueueUniqueWork("${workName(taskId)}-event-$eventId", ExistingWorkPolicy.KEEP, request.build())
    }

    /** A check needs only what its source needs: a network for ntfy and mail, nothing for the calendar. */
    private fun checkConstraints(needsNetwork: Boolean) =
        Constraints.Builder().setRequiredNetworkType(if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build()

    override fun eventRuns(taskId: String): Flow<RunNowStatus?> =
        work.getWorkInfosByTagFlow(eventTag(taskId))
            .map { infos ->
                when {
                    infos.any { it.state == WorkInfo.State.RUNNING } -> RunNowStatus.RUNNING
                    infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> RunNowStatus.QUEUED
                    else -> null
                }
            }
            .distinctUntilChanged()

    private fun watchName(id: String) = "trigger-$id"

    private fun eventTag(id: String) = "task-events-$id"

    override fun runNow(taskId: String, fromApp: String?): String {
        val name = runNowName(taskId)
        work.enqueueUniqueWork(
            name,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TaskWorker>()
                .setInputData(workDataOf(TaskWorker.KEY_TASK_ID to taskId, TaskWorker.KEY_FROM_APP to fromApp))
                .setConstraints(TaskConstraints.runNow())
                .addTag(TaskWorker.TAG_RUN_NOW)
                .build(),
        )
        return name
    }

    override suspend fun awaitRunNow(name: String): Boolean {
        val infos = work.getWorkInfosForUniqueWorkFlow(name).first { list -> list.isNotEmpty() && list.all { it.state.isFinished } }
        return infos.any { it.state == WorkInfo.State.SUCCEEDED }
    }

    override fun runNowStatus(taskId: String): Flow<RunNowStatus?> =
        work.getWorkInfosForUniqueWorkFlow(runNowName(taskId))
            .map { infos -> infos.lastOrNull().let { runNowStatus(it?.state, it?.outputData?.getString(TaskWorker.KEY_RESULT)) } }
            .distinctUntilChanged()

    private fun workName(id: String) = "task-$id"

    private fun runNowName(id: String) = "${workName(id)}-now"
}
