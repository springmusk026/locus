package dev.locus.core.data.tasks.triggers

import dev.locus.core.data.tasks.TaskWork
import dev.locus.core.data.tasks.conditions
import dev.locus.core.database.ScheduledTaskDao
import dev.locus.core.database.TriggerEventDao
import dev.locus.core.database.TriggerEventEntity
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.TriggerSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID

/**
 * Turns events into runs (docs/design/event-triggers.md): the filter, then the task's state, quiet hours
 * and rate limit ([TriggerRules]), a history row for what happened, and for a match that may run, a
 * one-time [TaskWork.fireEvent] that runs the task through the same [dev.locus.core.data.tasks.ScheduledRun]
 * as its schedule would: the atomic claim, the budget, receipts, notifications and the hot-phone hold.
 */
class TriggerEngine(
    private val tasks: ScheduledTaskDao,
    private val events: TriggerEventDao,
    private val work: TaskWork,
) {
    internal var clock: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault
    internal var ids: () -> String = { UUID.randomUUID().toString() }

    /** One decision at a time, so two deliveries can't both pass the rate limit. */
    private val lock = Mutex()

    /** What one delivery did with its matching events, and why the others didn't match. */
    data class Outcome(val decision: TriggerDecision?, val eventId: String?, val unmatched: List<String>)

    /**
     * Handles [incoming] for task [taskId]. Matching events count as one: a check that finds three
     * messages starts one run with all three. [pushed] events (another app, a test) that don't match are
     * recorded one by one with the reason; a polled source's non-matches as one line, so a busy topic or
     * inbox doesn't fill the history. [test] runs a match even when paused, rate-limited or in quiet hours.
     */
    suspend fun deliver(taskId: String, incoming: List<IncomingEvent>, pushed: Boolean = false, test: Boolean = false): Outcome = lock.withLock {
        val task = tasks.get(taskId) ?: return Outcome(null, null, emptyList())
        val trigger = TaskTrigger.fromJson(task.triggerJson) ?: return Outcome(null, null, emptyList())
        if (incoming.isEmpty()) return Outcome(null, null, emptyList())
        val now = clock()
        val reasons = mutableListOf<String>()

        // Another app off the trigger's list: never a match, whatever it sent.
        val (allowed, refused) = incoming.partition { it.source != TriggerSource.APP || TriggerRules.appAllowed(trigger, it.app) }
        refused.forEach { e ->
            record(taskId, e, now, TriggerDecision.NOT_ALLOWED, TriggerRules.detail(trigger, TriggerDecision.NOT_ALLOWED, now, null), payload = "")
            reasons += "the app isn't allowed"
        }

        val (matched, unmatched) = allowed.partition { TriggerRules.mismatch(trigger, it) == null }
        if (unmatched.isNotEmpty()) {
            if (pushed || test) {
                unmatched.forEach { e ->
                    val why = TriggerRules.mismatch(trigger, e).orEmpty()
                    reasons += why
                    record(taskId, e, now, TriggerDecision.NO_MATCH, why.replaceFirstChar { it.uppercase() } + ".", payload = "")
                }
            } else {
                val why = unmatched.mapNotNull { TriggerRules.mismatch(trigger, it) }.distinct().take(3).joinToString("; ")
                reasons += why
                val n = unmatched.size
                record(
                    taskId, unmatched.first(), now, TriggerDecision.NO_MATCH,
                    why.replaceFirstChar { it.uppercase() } + ".", payload = "",
                    title = if (n == 1) TriggerRules.title(unmatched) else "$n ${trigger.source.noun}s",
                )
            }
        }

        var decision: TriggerDecision? = null
        var eventId: String? = null
        if (matched.isNotEmpty()) {
            val lastFired = events.lastFiredAt(taskId)
            decision = TriggerRules.decide(trigger, task.enabled, now, zone(), lastFired, test)
            eventId = ids()
            events.insert(
                TriggerEventEntity(
                    id = eventId,
                    taskId = taskId,
                    source = trigger.source.name,
                    receivedAt = now,
                    title = TriggerRules.title(matched),
                    // Only a run needs the text; a skipped event keeps just its title.
                    payload = if (decision.ran) TriggerRules.combine(matched) else "",
                    decision = decision.name,
                    detail = TriggerRules.detail(trigger, decision, now, lastFired),
                ),
            )
            if (decision.ran) {
                try {
                    work.fireEvent(taskId, eventId, task.conditions, test = decision == TriggerDecision.TEST)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    decision = TriggerDecision.FAILED
                    events.setDecision(eventId, TriggerDecision.FAILED.name, "Locus couldn't queue the run: ${e.message?.take(120) ?: e::class.java.simpleName}.")
                }
            }
        }
        events.prune(taskId, KEEP)
        Outcome(decision, eventId, reasons)
    }

    private suspend fun record(taskId: String, e: IncomingEvent, now: Long, decision: TriggerDecision, detail: String?, payload: String, title: String = TriggerRules.title(listOf(e))) {
        events.insert(TriggerEventEntity(ids(), taskId, e.source.name, now, title, payload, decision.name, detail))
    }

    companion object {
        /** How many events a task keeps in its history. */
        const val KEEP = 50
    }
}
