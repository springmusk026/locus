package dev.locus.core.data.tasks.triggers

import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * One event as a source delivered it, before the trigger's filter. Every field came from outside Locus
 * (a calendar entry's author, an ntfy sender, a mail's sender, another app) and is untrusted.
 */
data class IncomingEvent(
    val source: TriggerSource,
    /** The source's own id for it (an ntfy message id, a mail UID, a calendar instance), so it is handled once. */
    val key: String,
    val at: Long,
    /** A short line for the history: "Backup failed", a mail's subject. */
    val title: String,
    /** The text the filter's words are looked for in. */
    val text: String,
    /** The event as its run gets it, inside untrusted markers (core/agent `EventNote`). */
    val details: String,
    /** ntfy: the message's priority (1–5; ntfy's default is 3). */
    val priority: Int? = null,
    /** Calendar: the calendar's name. */
    val calendar: String? = null,
    /** Mail: the sender. */
    val from: String? = null,
    /** Another app: the event name it sent. */
    val eventName: String? = null,
    /** Another app: its package, only when Android named it. */
    val app: String? = null,
)

/** Where a trigger's source stands, kept on the task as JSON. Holds no secret. */
@Serializable
data class TriggerState(
    /** ntfy: the last message id (or, before the first, the time the trigger was set, in seconds). Mail: "uidvalidity:uid". */
    val cursor: String? = null,
    val health: TriggerHealth = TriggerHealth.WAITING,
    val message: String? = null,
    val checkedAt: Long? = null,
    /** Calendar: the instances already handled ("eventId:begin"), so each starts at most one run. */
    val handled: List<String> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun fromJson(value: String?): TriggerState =
            value?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: TriggerState()
    }
}

/**
 * The trigger's rules, kept free of Android so they are tested on the JVM: does an event match the
 * filter, and does a match start a run now (docs/design/event-triggers.md).
 */
object TriggerRules {
    /** ntfy's priority when a message sets none ("default"). */
    const val NTFY_DEFAULT_PRIORITY = 3

    /** Why [event] doesn't match [trigger]'s filter, or null when it does. */
    fun mismatch(trigger: TaskTrigger, event: IncomingEvent): String? {
        missingWords(trigger.contains, event.text)?.let { return "it doesn't mention “$it”" }
        return when (trigger.source) {
            TriggerSource.CALENDAR -> if (trigger.calendar.isNotBlank() && !trigger.calendar.trim().equals(event.calendar?.trim(), ignoreCase = true)) {
                "it's in another calendar"
            } else {
                null
            }
            TriggerSource.NTFY -> {
                val priority = event.priority ?: NTFY_DEFAULT_PRIORITY
                if (priority < trigger.minPriority) "its priority is $priority, below ${trigger.minPriority}" else null
            }
            TriggerSource.MAIL -> if (trigger.from.isNotBlank() && event.from?.contains(trigger.from.trim(), ignoreCase = true) != true) {
                "it's from someone else"
            } else {
                null
            }
            TriggerSource.APP -> if (trigger.eventName.isNotBlank() && !trigger.eventName.trim().equals(event.eventName?.trim(), ignoreCase = true)) {
                "it's another event (“${event.eventName.orEmpty().take(60)}”)"
            } else {
                null
            }
        }
    }

    /**
     * Whether another app may fire [trigger]: with an allow-list, only a package Android named (never one
     * written in the intent) that is on it. Without a list, any app Locus's automation settings let through.
     */
    fun appAllowed(trigger: TaskTrigger, app: String?): Boolean =
        trigger.allowedApps.isEmpty() || (app != null && app in trigger.allowedApps)

    /** The first word of [contains] that [text] lacks (any case), or null when it has them all. */
    fun missingWords(contains: String, text: String): String? =
        contains.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { !text.contains(it, ignoreCase = true) }

    /**
     * What a matching event does at [now]. A paused task starts nothing; a test always runs (as Run now
     * ignores the schedule); then quiet hours, then the rate limit: at most one run every
     * [TaskTrigger.minGapMinutes] since [lastFiredAt].
     */
    fun decide(trigger: TaskTrigger, enabled: Boolean, now: Long, zone: ZoneId, lastFiredAt: Long?, test: Boolean = false): TriggerDecision = when {
        test -> TriggerDecision.TEST
        !enabled -> TriggerDecision.DISABLED
        trigger.quietHours?.contains(minuteOfDay(now, zone)) == true -> TriggerDecision.QUIET_HOURS
        lastFiredAt != null && now - lastFiredAt < trigger.minGapMinutes * 60_000L -> TriggerDecision.RATE_LIMITED
        else -> TriggerDecision.FIRED
    }

    /** Why a [decision] didn't start a run, for the history; null when it did. */
    fun detail(trigger: TaskTrigger, decision: TriggerDecision, now: Long, lastFiredAt: Long?): String? = when (decision) {
        TriggerDecision.FIRED, TriggerDecision.TEST -> null
        TriggerDecision.DISABLED -> "The task is paused."
        TriggerDecision.QUIET_HOURS -> "Quiet from ${trigger.quietHours?.label}."
        TriggerDecision.RATE_LIMITED -> {
            val wait = lastFiredAt?.let { ((it + trigger.minGapMinutes * 60_000L - now) / 60_000L).coerceAtLeast(1) }
            "At most one run every ${gapLabel(trigger.minGapMinutes)}" + (wait?.let { "; the next can start in $it min." } ?: ".")
        }
        TriggerDecision.NO_MATCH -> "It didn't match the filter."
        TriggerDecision.NOT_ALLOWED -> "That app isn't on the trigger's list, or Android didn't say which app it was."
        TriggerDecision.BUSY -> "Another run of this task was still going."
        TriggerDecision.FAILED -> "Locus couldn't start the run."
    }

    fun gapLabel(minutes: Int): String = when {
        minutes % 60 == 0 && minutes >= 60 -> if (minutes == 60) "hour" else "${minutes / 60} hours"
        minutes == 1 -> "minute"
        else -> "$minutes minutes"
    }

    private fun minuteOfDay(now: Long, zone: ZoneId): Int = Instant.ofEpochMilli(now).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    /** At most this much event text goes to one run. */
    const val MAX_DETAILS = 6_000

    /** [events] as one run's details: each event's own, in order, cut to [MAX_DETAILS]. */
    fun combine(events: List<IncomingEvent>): String {
        if (events.size == 1) return events.single().details.take(MAX_DETAILS)
        val out = StringBuilder("${events.size} events:\n")
        for ((i, e) in events.withIndex()) {
            val next = "\n[${i + 1}] ${e.details}\n"
            if (out.length + next.length > MAX_DETAILS) {
                out.append("\n[and ${events.size - i} more, left out]")
                break
            }
            out.append(next)
        }
        return out.toString()
    }

    /** The history line for [events]: the first one's title, and how many more. */
    fun title(events: List<IncomingEvent>): String {
        val first = events.first().title.replace(Regex("\\s+"), " ").trim().ifEmpty { events.first().source.label }.take(120)
        return if (events.size > 1) "$first (+${events.size - 1} more)" else first
    }
}
