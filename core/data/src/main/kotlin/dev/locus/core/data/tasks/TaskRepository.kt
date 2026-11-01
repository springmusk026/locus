package dev.locus.core.data.tasks

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.database.ScheduledTaskDao
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.ScheduledTask
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
            )
        }
    }

    private val work get() = WorkManager.getInstance(context)

    suspend fun create(title: String, prompt: String, intervalMinutes: Long) {
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
        )
        dao.upsert(task)
        schedule(task)
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
                .setConstraints(networkConstraint)
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
                .setConstraints(networkConstraint)
                .build(),
        )
    }

    private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun workName(id: String) = "task-$id"
}
