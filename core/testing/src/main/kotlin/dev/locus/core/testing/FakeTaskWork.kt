package dev.locus.core.testing

import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.data.tasks.TaskWork
import dev.locus.core.model.TaskConditions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * WorkManager for tasks, in memory: what is scheduled, and each task's Run now, which stays
 * [RunNowStatus.QUEUED] until a test moves it on with [setRunNow].
 */
class FakeTaskWork : TaskWork {
    /** A task's schedule: its period and conditions. */
    data class Scheduled(val intervalMinutes: Long, val conditions: TaskConditions)

    val scheduled = mutableMapOf<String, Scheduled>()
    val runNowCalls = mutableListOf<String>()
    private val runNow = MutableStateFlow<Map<String, RunNowStatus>>(emptyMap())

    /** Moves [taskId]'s Run now to [status] (null: as if WorkManager pruned it). */
    fun setRunNow(taskId: String, status: RunNowStatus?) {
        runNow.value = if (status == null) runNow.value - taskId else runNow.value + (taskId to status)
    }

    override fun schedule(taskId: String, intervalMinutes: Long, conditions: TaskConditions) {
        scheduled[taskId] = Scheduled(intervalMinutes, conditions)
    }

    override fun cancel(taskId: String) {
        scheduled.remove(taskId)
    }

    override fun cancelAll() = scheduled.clear()

    override fun runNow(taskId: String, fromApp: String?): String {
        runNowCalls += taskId
        // KEEP: a run already waiting or running stays as it is.
        if (runNow.value[taskId]?.active != true) setRunNow(taskId, RunNowStatus.QUEUED)
        return taskId
    }

    override suspend fun awaitRunNow(name: String): Boolean =
        runNow.map { it[name] }.filterNotNull().first { !it.active } != RunNowStatus.FAILED

    override fun runNowStatus(taskId: String): Flow<RunNowStatus?> = runNow.map { it[taskId] }.distinctUntilChanged()
}
