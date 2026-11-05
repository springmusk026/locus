package dev.locus.core.data.tasks.triggers

import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerSource

/**
 * "Send a test event" (docs/design/event-triggers.md): the user's [text] shaped as an event from the
 * trigger's own source, so it goes through the same filter, history and run as a real one. Only the text
 * is the user's to choose; the rest is set so the filter's other parts pass (the trigger's calendar,
 * priority, sender, event name and first allowed app), which makes a test say whether the words match.
 */
object TestEvents {
    fun of(trigger: TaskTrigger, text: String, now: Long): IncomingEvent {
        val body = text.ifBlank { "Test event" }
        val header = "Source: a test event sent from Locus's Tasks screen, shaped as a ${trigger.source.noun}"
        return when (trigger.source) {
            TriggerSource.CALENDAR -> IncomingEvent(
                TriggerSource.CALENDAR, "test:$now", now, body, body,
                "$header\nTitle: $body\nStarts: in ${trigger.minutesBefore} min",
                calendar = trigger.calendar.ifBlank { null },
            )
            TriggerSource.NTFY -> {
                val priority = maxOf(TriggerRules.NTFY_DEFAULT_PRIORITY, trigger.minPriority)
                IncomingEvent(TriggerSource.NTFY, "test:$now", now, body, body, "$header\nPriority: $priority\nMessage: $body", priority = priority)
            }
            TriggerSource.MAIL -> {
                val from = trigger.from.ifBlank { "test@example.invalid" }
                IncomingEvent(TriggerSource.MAIL, "test:$now", now, body, body, "$header\nFrom: $from\nSubject: $body", from = from)
            }
            TriggerSource.APP -> {
                val name = trigger.eventName.ifBlank { "test" }
                IncomingEvent(
                    TriggerSource.APP, "test:$now", now, "$name · test", "$name\n$body", "$header\nEvent: $name\nText: $body",
                    eventName = name, app = trigger.allowedApps.firstOrNull(),
                )
            }
        }
    }
}
