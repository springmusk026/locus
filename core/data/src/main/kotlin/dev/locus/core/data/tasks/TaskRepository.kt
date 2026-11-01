package dev.locus.core.data.tasks

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.database.ScheduledTaskDao
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ScheduledTaskDao,
) {
    val tasks: Flow<List<ScheduledTask>> = dao.observeAll().map { list ->
        list.map {
            ScheduledTask(
                it.id, it.title, it.prompt, it.intervalMinutes, it.enabled, it.lastRunAt, it.lastSummary,
                lastOutcome = it.lastOutcome?.let { name -> TaskRunOutcome.entries.firstOrNull { o -> o.name == name } },
                conditions = it.conditions,
            )
        }
    }

    private val work get() = WorkManager.getInstance(context)

    /** A new task; [conditions] are when it may run (Settings → Battery & heat has the defaults). */
    suspend fun create(title: String, prompt: String, intervalMinutes: Long, conditions: TaskConditions = TaskConditions()) {
        val task = ScheduledTaskEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            prompt = prompt.trim(),
            // WorkManager's minimum period is 15 minutes.
            intervalMinutes = intervalMinutes.coerceAtLeast(15),
            enabled = true,
            createdAt = System.currentTimeMillis(),
            lastRunAt = null,
            lastConversationId = null,
            lastSummary = null,
            requiresCharging = conditions.requiresCharging,
            unmeteredOnly = conditions.unmeteredOnly,
            batteryNotLow = conditions.batteryNotLow,
        )
        dao.upsert(task)
        schedule(task)
    }

    /** Changes when [id] may run; an enabled task is rescheduled with the new constraints, keeping its period. */
    suspend fun setConditions(id: String, conditions: TaskConditions) {
        val task = dao.get(id)?.copy(
            requiresCharging = conditions.requiresCharging,
            unmeteredOnly = conditions.unmeteredOnly,
            batteryNotLow = conditions.batteryNotLow,
        ) ?: return
        dao.upsert(task)
        if (task.enabled) schedule(task)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val task = dao.get(id)?.copy(enabled = enabled) ?: return
        dao.upsert(task)
        if (enabled) schedule(task) else work.cancelUniqueWork(workName(id))
    }

    suspend fun delete(id: String) {
        work.cancelUniqueWork(workName(id))
        dao.delete(id)
    }

    fun runNow(id: String) {
        work.enqueueUniqueWork(
            "${workName(id)}-now",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TaskWorker>()
                .setInputData(workDataOf(TaskWorker.KEY_TASK_ID to id))
                .setConstraints(TaskConstraints.runNow())
                .addTag(TaskWorker.TAG_RUN_NOW)
                .build(),
        )
    }

    suspend fun recordRun(id: String, conversationId: String, summary: String?, outcome: TaskRunOutcome = TaskRunOutcome.DONE) {
        val task = dao.get(id) ?: return
        dao.upsert(
            task.copy(lastRunAt = System.currentTimeMillis(), lastConversationId = conversationId, lastSummary = summary?.take(500), lastOutcome = outcome.name),
        )
    }

    /** The task's run marker, if a run sent its prompt and its worker hasn't returned yet (see [ScheduledTaskEntity.runWorkId]). */
    suspend fun marker(id: String): RunMarker? = dao.get(id)?.let { t ->
        val work = t.runWorkId ?: return@let null
        RunMarker(work, t.runConversationId.orEmpty(), t.runStartedAt ?: 0)
    }

    /** Marks a run as having sent its prompt: written before the prompt goes out. */
    suspend fun markRun(id: String, marker: RunMarker) {
        val task = dao.get(id) ?: return
        dao.upsert(task.copy(runWorkId = marker.workId, runConversationId = marker.conversationId, runStartedAt = marker.startedAt))
    }

    /** Clears the marker, only if it is still [workId]'s (another run may have taken over a stale one). */
    suspend fun clearMarker(id: String, workId: String) {
        val task = dao.get(id) ?: return
        if (task.runWorkId == workId) dao.upsert(task.copy(runWorkId = null, runConversationId = null, runStartedAt = null))
    }

    suspend fun get(id: String) = dao.get(id)

    /** Stops every scheduled run (WorkManager tags each request with its worker's class name). Before a restore. */
    fun cancelAllScheduled() {
        work.cancelAllWorkByTag(TaskWorker::class.java.name)
    }

    /** Schedules exactly the enabled tasks in the database, after a restore replaced it. */
    suspend fun rescheduleAll() {
        cancelAllScheduled()
        dao.observeAll().first().filter { it.enabled }.forEach(::schedule)
    }

    private fun schedule(task: ScheduledTaskEntity) {
        work.enqueueUniquePeriodicWork(
            workName(task.id),
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TaskWorker>(task.intervalMinutes, TimeUnit.MINUTES)
                .setInputData(workDataOf(TaskWorker.KEY_TASK_ID to task.id))
                .setConstraints(TaskConstraints.of(task.conditions))
                .build(),
        )
    }

    private fun workName(id: String) = "task-$id"
}

/** The run conditions stored on the task. */
internal val ScheduledTaskEntity.conditions: TaskConditions
    get() = TaskConditions(requiresCharging = requiresCharging, unmeteredOnly = unmeteredOnly, batteryNotLow = batteryNotLow)
