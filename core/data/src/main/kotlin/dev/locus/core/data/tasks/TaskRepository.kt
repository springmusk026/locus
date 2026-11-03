package dev.locus.core.data.tasks

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.database.ScheduledTaskDao
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.ModeNote
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository(
    private val dao: ScheduledTaskDao,
    /** WorkManager, or a fake in tests. */
    private val work: TaskWork,
) {
    @Inject
    constructor(@ApplicationContext context: Context, dao: ScheduledTaskDao) : this(dao, WorkManagerTaskWork(context))

    val tasks: Flow<List<ScheduledTask>> = dao.observeAll().map { list -> list.map(ScheduledTaskEntity::toModel) }

    /** A new task; [conditions] are when it may run (Settings → Battery & heat has the defaults). */
    suspend fun create(title: String, prompt: String, intervalMinutes: Long, conditions: TaskConditions = TaskConditions()) {
        val task = ScheduledTaskEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            prompt = prompt.trim(),
            // WorkManager's minimum period is 15 minutes.
            intervalMinutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL),
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

    /**
     * Edits [id]'s name, prompt, period and conditions. A scheduled workflow keeps its plan: [prompt] is
     * the visible text ([WorkflowRender.visible]) and the plan note is carried over. An enabled task is
     * rescheduled with the new period and constraints. False when the task is gone.
     */
    suspend fun update(id: String, title: String, prompt: String, intervalMinutes: Long, conditions: TaskConditions): Boolean {
        val old = dao.get(id) ?: return false
        val note = WorkflowRender.split(old.prompt).second
        val task = old.copy(
            title = title.trim(),
            prompt = prompt.trim() + (note?.let(ModeNote::wrap) ?: ""),
            intervalMinutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL),
            requiresCharging = conditions.requiresCharging,
            unmeteredOnly = conditions.unmeteredOnly,
            batteryNotLow = conditions.batteryNotLow,
        )
        // Column updates, not an upsert of the row read above: a run claiming the task meanwhile keeps its marker.
        if (dao.setDefinition(task.id, task.title, task.prompt, task.intervalMinutes, task.requiresCharging, task.unmeteredOnly, task.batteryNotLow) == 0) return false
        if (task.enabled) schedule(task)
        return true
    }

    /** Changes when [id] may run; an enabled task is rescheduled with the new constraints, keeping its period. */
    suspend fun setConditions(id: String, conditions: TaskConditions) {
        val task = dao.get(id)?.copy(
            requiresCharging = conditions.requiresCharging,
            unmeteredOnly = conditions.unmeteredOnly,
            batteryNotLow = conditions.batteryNotLow,
        ) ?: return
        dao.setDefinition(task.id, task.title, task.prompt, task.intervalMinutes, task.requiresCharging, task.unmeteredOnly, task.batteryNotLow)
        if (task.enabled) schedule(task)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val task = dao.get(id)?.copy(enabled = enabled) ?: return
        dao.setEnabled(id, enabled)
        if (enabled) schedule(task) else work.cancel(id)
    }

    suspend fun delete(id: String) {
        work.cancel(id)
        dao.delete(id)
    }

    /**
     * Runs [id] now. [fromApp] names another app that asked for it through the automation intents
     * (docs/design/android-bridge.md); the run is then tainted. Returns the unique work name to watch.
     */
    fun runNow(id: String, fromApp: String? = null): String = work.runNow(id, fromApp)

    /** Waits for the run-now work [name] (from [runNow]) to finish; true when it ran and wasn't cancelled. */
    suspend fun awaitRunNow(name: String): Boolean = work.awaitRunNow(name)

    /** Where [id]'s latest Run now stands (queued, running, how it ended); null when it has none. */
    fun runNowStatus(id: String): Flow<RunNowStatus?> = work.runNowStatus(id)

    suspend fun recordRun(id: String, conversationId: String, summary: String?, outcome: TaskRunOutcome = TaskRunOutcome.DONE) {
        dao.setLastRun(id, System.currentTimeMillis(), conversationId, summary?.take(500), outcome.name)
    }

    /**
     * The task's run marker, if a run holds it and its worker hasn't returned yet (see
     * [ScheduledTaskEntity.runWorkId]). [RunMarker.conversationId] is empty while the run has claimed the
     * task but not yet sent its prompt.
     */
    suspend fun marker(id: String): RunMarker? = dao.get(id)?.marker()

    /**
     * Claims [id] for work [workId], atomically in the database (see [dev.locus.core.database.CLAIM_RUN]):
     * of two workers starting the same task at once, exactly one gets it. Null when another live run holds
     * it; otherwise the claim, with the stale marker it replaced, if any, for the caller to close.
     */
    suspend fun claim(id: String, workId: String, now: Long): RunClaim? {
        val before = dao.claimRun(id, workId, now, staleBefore = now - ScheduledRun.MARKER_STALE.inWholeMilliseconds) ?: return null
        val replaced = before.marker()?.takeIf { it.workId != workId && it.conversationId.isNotEmpty() }
        return RunClaim(replaced)
    }

    /**
     * Records the chat [workId]'s claimed run sends its prompt to, just before it goes out: from then on a
     * retry of the same work never sends it again. False when [workId] no longer holds the task.
     */
    suspend fun markSent(id: String, workId: String, conversationId: String): Boolean = dao.setRunConversation(id, workId, conversationId) == 1

    /** Releases the claim, only while [workId] holds it (another run may have taken over a stale one). */
    suspend fun clearMarker(id: String, workId: String) {
        dao.releaseRun(id, workId)
    }

    suspend fun get(id: String) = dao.get(id)

    /** Stops every scheduled run. Before a restore. */
    fun cancelAllScheduled() {
        work.cancelAll()
    }

    /** Schedules exactly the enabled tasks in the database, after a restore replaced it. */
    suspend fun rescheduleAll() {
        cancelAllScheduled()
        dao.observeAll().first().filter { it.enabled }.forEach(::schedule)
    }

    private fun schedule(task: ScheduledTaskEntity) = work.schedule(task.id, task.intervalMinutes, task.conditions)

    private companion object {
        /** WorkManager's minimum period. */
        const val MIN_INTERVAL = 15L
    }
}

/** A successful [TaskRepository.claim]; [abandoned] is a dead run's marker it replaced, whose run was never recorded. */
data class RunClaim(val abandoned: RunMarker?)

private fun ScheduledTaskEntity.marker(): RunMarker? = runWorkId?.let { RunMarker(it, runConversationId.orEmpty(), runStartedAt ?: 0) }

/** The task as the app shows it: [ScheduledTask] with its outcome parsed and its last run's chat. */
internal fun ScheduledTaskEntity.toModel() = ScheduledTask(
    id = id,
    title = title,
    prompt = prompt,
    intervalMinutes = intervalMinutes,
    enabled = enabled,
    lastRunAt = lastRunAt,
    lastSummary = lastSummary,
    lastOutcome = lastOutcome?.let { name -> TaskRunOutcome.entries.firstOrNull { o -> o.name == name } },
    conditions = conditions,
    lastConversationId = lastConversationId,
)

/** The run conditions stored on the task. */
internal val ScheduledTaskEntity.conditions: TaskConditions
    get() = TaskConditions(requiresCharging = requiresCharging, unmeteredOnly = unmeteredOnly, batteryNotLow = batteryNotLow)
