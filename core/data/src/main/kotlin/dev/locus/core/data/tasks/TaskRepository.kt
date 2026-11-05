package dev.locus.core.data.tasks

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.workflow.WorkflowRender
import dev.locus.core.data.tasks.triggers.IncomingEvent
import dev.locus.core.data.tasks.triggers.TestEvents
import dev.locus.core.data.tasks.triggers.TriggerEngine
import dev.locus.core.data.tasks.triggers.TriggerState
import dev.locus.core.database.ScheduledTaskDao
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.database.TriggerEventDao
import dev.locus.core.database.TriggerEventEntity
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ModeNote
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.TriggerEventRecord
import dev.locus.core.model.TriggerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepository(
    private val dao: ScheduledTaskDao,
    private val eventDao: TriggerEventDao,
    /** WorkManager, or a fake in tests. */
    private val work: TaskWork,
    /** Where an ntfy trigger's access token is kept. */
    private val vault: SecretStore,
) {
    @Inject
    constructor(@ApplicationContext context: Context, dao: ScheduledTaskDao, eventDao: TriggerEventDao, vault: SecretStore) :
        this(dao, eventDao, WorkManagerTaskWork(context), vault)

    val tasks: Flow<List<ScheduledTask>> = dao.observeAll().map { list -> list.map(ScheduledTaskEntity::toModel) }

    /** Turns tasks' events into runs (docs/design/event-triggers.md). */
    val triggers = TriggerEngine(dao, eventDao, work)

    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * A new task; [conditions] are when it may run (Settings → Battery & heat has the defaults). With a
     * [trigger], events start it instead of the schedule; [token] is an ntfy topic's access token, kept in
     * the vault. A scheduled task's first run waits [firstRunDelayMillis]. Returns the task's id.
     */
    suspend fun create(
        title: String,
        prompt: String,
        intervalMinutes: Long,
        conditions: TaskConditions = TaskConditions(),
        trigger: TaskTrigger? = null,
        token: String? = null,
        firstRunDelayMillis: Long = 0,
    ): String {
        trigger?.problem?.let { throw IllegalArgumentException(it) }
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
            triggerJson = trigger?.toJson(),
            triggerStateJson = trigger?.let(::initialState),
        )
        if (trigger != null) setToken(task.id, token)
        dao.upsert(task)
        schedule(task, checkNow = true, firstRunDelayMillis = firstRunDelayMillis)
        return task.id
    }

    /**
     * Edits [id]'s name, prompt, period, conditions and trigger. A scheduled workflow keeps its plan:
     * [prompt] is the visible text ([WorkflowRender.visible]) and the plan note is carried over. An enabled
     * task is rescheduled. A changed trigger starts from now; [token] null keeps the saved access token,
     * empty removes it. False when the task is gone.
     */
    suspend fun update(
        id: String,
        title: String,
        prompt: String,
        intervalMinutes: Long,
        conditions: TaskConditions,
        trigger: TaskTrigger? = null,
        token: String? = null,
    ): Boolean {
        trigger?.problem?.let { throw IllegalArgumentException(it) }
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
        // A changed trigger starts again from now (a new cursor); an unchanged one keeps where its source stands.
        val json = trigger?.toJson()
        val changed = json != old.triggerJson
        if (changed) {
            dao.setTrigger(id, json)
            trigger?.let { dao.setTriggerState(id, initialState(it)) }
        }
        setToken(id, if (trigger?.source == TriggerSource.NTFY) token else "")
        if (task.enabled) schedule(task.copy(triggerJson = json), checkNow = changed || token != null)
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
        if (enabled) {
            schedule(task, checkNow = true)
        } else {
            work.cancel(id)
            work.unwatch(id)
        }
    }

    suspend fun delete(id: String) {
        work.cancel(id)
        work.unwatch(id)
        dao.delete(id)
        setToken(id, "")
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

    /** Whether a run an event started for [id] is waiting or running (null: none). */
    fun eventRuns(id: String): Flow<RunNowStatus?> = work.eventRuns(id)

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

    /** [id]'s latest trigger events, newest first (its history). */
    fun events(id: String, limit: Int = TriggerEngine.KEEP): Flow<List<TriggerEventRecord>> =
        eventDao.observeForTask(id, limit).map { rows -> rows.mapNotNull(TriggerEventEntity::toModel) }

    /** The event [eventId], for its run. */
    suspend fun event(eventId: String): TriggerEventEntity? = eventDao.get(eventId)

    /** Records that event [eventId] didn't run after all ([decision]: busy, paused, failed to start). */
    suspend fun recordEventSkipped(eventId: String, decision: TriggerDecision, detail: String) {
        eventDao.setDecision(eventId, decision.name, detail)
    }

    /** Records event [eventId]'s run: its chat, and once it ended, how and with what summary. */
    suspend fun recordEventRun(eventId: String, conversationId: String, outcome: TaskRunOutcome?, summary: String?) {
        eventDao.setRun(eventId, conversationId, outcome?.name, summary?.take(300))
    }

    /** Whether [id]'s ntfy trigger has an access token saved (the token itself is never shown again). */
    suspend fun hasToken(id: String): Boolean = vault.get(SecretStore.triggerToken(id)) != null

    /** Keeps where [id]'s trigger source stands. */
    suspend fun setTriggerState(id: String, state: TriggerState) {
        dao.setTriggerState(id, state.toJson())
    }

    /** One extra check of [id]'s trigger source after [delayMillis]. */
    suspend fun checkSoon(id: String, delayMillis: Long) {
        val trigger = TaskTrigger.fromJson(dao.get(id)?.triggerJson) ?: return
        if (trigger.polled) work.checkSoon(id, delayMillis, needsNetwork = trigger.source != TriggerSource.CALENDAR)
    }

    /**
     * "Send a test event": [text] as an event from [id]'s source, through its filter. A match runs even
     * when the task is paused, rate-limited or in quiet hours, and the run treats it like any event:
     * as untrusted text (docs/design/event-triggers.md).
     */
    suspend fun sendTestEvent(id: String, text: String): TriggerEngine.Outcome {
        val trigger = TaskTrigger.fromJson(dao.get(id)?.triggerJson) ?: return TriggerEngine.Outcome(null, null, emptyList())
        return triggers.deliver(id, listOf(TestEvents.of(trigger, text.trim().take(2_000), clock())), pushed = true, test = true)
    }

    /**
     * An event another app sent through `dev.locus.action.EVENT` (docs/design/android-bridge.md), given to
     * every task whose trigger waits for another app's events. [app] is the package Android named, or null.
     * Returns each listening task's title with what happened; empty when no task listens.
     */
    suspend fun appEvent(name: String, text: String?, app: String?, shown: String): List<Pair<String, TriggerEngine.Outcome>> {
        val listening = dao.observeAll().first().filter { TaskTrigger.fromJson(it.triggerJson)?.source == TriggerSource.APP }
        return listening.map { task ->
            val now = clock()
            val event = IncomingEvent(
                source = TriggerSource.APP,
                key = "$now",
                at = now,
                title = "$name · ${shown.take(60)}",
                text = listOfNotNull(name, text).joinToString("\n"),
                details = buildString {
                    appendLine("Source: an event another app sent to Locus")
                    appendLine("App: $shown")
                    appendLine("Event: $name")
                    text?.takeIf { it.isNotBlank() }?.let { appendLine("Text: $it") }
                }.trim(),
                eventName = name,
                app = app,
            )
            task.title to triggers.deliver(task.id, listOf(event), pushed = true)
        }
    }

    /** Stops every scheduled run and trigger check. Before a restore. */
    fun cancelAllScheduled() {
        work.cancelAll()
    }

    /** Schedules exactly the enabled tasks in the database, after a restore replaced it. */
    suspend fun rescheduleAll() {
        cancelAllScheduled()
        dao.observeAll().first().filter { it.enabled }.forEach { schedule(it) }
    }

    private suspend fun setToken(id: String, token: String?) {
        when {
            token == null -> Unit
            token.isBlank() -> vault.put(SecretStore.triggerToken(id), null)
            else -> vault.put(SecretStore.triggerToken(id), token.trim())
        }
    }

    /** Where a new trigger starts: an ntfy topic from now (messages from before aren't events); the others from their first check. */
    private fun initialState(trigger: TaskTrigger): String =
        TriggerState(cursor = if (trigger.source == TriggerSource.NTFY) (clock() / 1000).toString() else null).toJson()

    /**
     * A task on a schedule gets its periodic run; an event-triggered one instead gets its source checked
     * every 15 minutes (nothing for another app, which sends its events in), and with [checkNow] a first
     * check straight away, so the task shows at once whether its source works.
     */
    private fun schedule(task: ScheduledTaskEntity, checkNow: Boolean = false, firstRunDelayMillis: Long = 0) {
        val trigger = TaskTrigger.fromJson(task.triggerJson)
        if (trigger == null) {
            work.unwatch(task.id)
            work.schedule(task.id, task.intervalMinutes, task.conditions, firstRunDelayMillis)
            return
        }
        work.cancel(task.id)
        if (!trigger.polled) {
            work.unwatch(task.id)
            return
        }
        val network = trigger.source != TriggerSource.CALENDAR
        work.watch(task.id, network)
        if (checkNow) work.checkSoon(task.id, 0, network)
    }

    private companion object {
        /** WorkManager's minimum period. */
        const val MIN_INTERVAL = 15L
    }
}

/** A successful [TaskRepository.claim]; [abandoned] is a dead run's marker it replaced, whose run was never recorded. */
data class RunClaim(val abandoned: RunMarker?)

private fun ScheduledTaskEntity.marker(): RunMarker? = runWorkId?.let { RunMarker(it, runConversationId.orEmpty(), runStartedAt ?: 0) }

/** The task as the app shows it: [ScheduledTask] with its outcome parsed, its last run's chat and its trigger. */
internal fun ScheduledTaskEntity.toModel(): ScheduledTask {
    val task = ScheduledTask(
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
        trigger = TaskTrigger.fromJson(triggerJson),
    )
    if (task.trigger == null) return task
    val state = TriggerState.fromJson(triggerStateJson)
    return task.copy(triggerHealth = state.health, triggerMessage = state.message, triggerCheckedAt = state.checkedAt)
}

/** A stored event as the history shows it; null for a row a newer version wrote that this one can't read. */
internal fun TriggerEventEntity.toModel(): TriggerEventRecord? = TriggerEventRecord(
    id = id,
    taskId = taskId,
    source = TriggerSource.entries.firstOrNull { it.name == source } ?: return null,
    receivedAt = receivedAt,
    title = title,
    decision = TriggerDecision.entries.firstOrNull { it.name == decision } ?: return null,
    detail = detail,
    conversationId = conversationId,
    outcome = outcome?.let { o -> TaskRunOutcome.entries.firstOrNull { it.name == o } },
)

/** The run conditions stored on the task. */
internal val ScheduledTaskEntity.conditions: TaskConditions
    get() = TaskConditions(requiresCharging = requiresCharging, unmeteredOnly = unmeteredOnly, batteryNotLow = batteryNotLow)
