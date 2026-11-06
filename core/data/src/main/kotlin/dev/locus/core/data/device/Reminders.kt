package dev.locus.core.data.device

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.DeviceIntegrations
import dev.locus.core.model.Reminder
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a reminder's alarm is set (docs/design/phone-integrations.md, "Timing"). [AndroidReminderAlarms] uses
 * AlarmManager: an exact alarm when the user granted "Alarms & reminders", otherwise a 10-minute window.
 */
interface ReminderAlarms {
    /** Whether exact alarms are allowed now (`canScheduleExactAlarms`). */
    fun exactAllowed(): Boolean

    /** Sets (or replaces) [reminder]'s alarm for [Reminder.at]; a time already past fires at once. */
    fun schedule(reminder: Reminder)

    fun cancel(id: String)
}

/** Shows a due reminder as a notification with Snooze and Done. */
interface ReminderNotifier {
    /** Whether Locus may post notifications now. */
    fun canNotify(): Boolean

    /** [late]: it fires well after its time (the phone was off or asleep). */
    fun show(reminder: Reminder, late: Boolean)

    fun dismiss(id: String)
}

/**
 * Reminders set with `remind_me` (docs/design/phone-integrations.md): kept in the settings store, which
 * survives restarts, with an alarm each. Firing posts a notification and runs no model. Alarms are set
 * again after a reboot, an app update or a change to the exact-alarm permission ([rescheduleAll]),
 * because Android clears them on reboot.
 */
@Singleton
class Reminders @Inject constructor(
    private val settings: SettingsRepository,
    private val alarms: ReminderAlarms,
    private val notifier: ReminderNotifier,
) {
    /** The clock; tests and previews set it. */
    var clock: () -> Long = System::currentTimeMillis
    internal var ids: () -> String = { UUID.randomUUID().toString() }

    /** Every reminder, soonest first: those still to come, then those shown and not yet answered. */
    val all: Flow<List<Reminder>> = settings.settings.map { s -> sorted(s.reminders) }.distinctUntilChanged()

    suspend fun current(): List<Reminder> = sorted(settings.current().reminders)

    fun exactTiming(): Boolean = alarms.exactAllowed()

    fun canNotify(): Boolean = notifier.canNotify()

    /** A new reminder for [at]; throws [IllegalArgumentException] for a time in the past or too far ahead. */
    suspend fun add(text: String, at: Long, conversationId: String? = null): Reminder {
        val now = clock()
        val clean = text.trim().take(MAX_TEXT)
        require(clean.isNotEmpty()) { "A reminder needs some text." }
        require(at > now - GRACE_MS) { "That time has passed." }
        require(at <= now + MAX_AHEAD_MS) { "Reminders can be at most a year ahead." }
        val reminder = Reminder(ids(), clean, at.coerceAtLeast(now), createdAt = now, conversationId = conversationId)
        settings.updateReminders { list ->
            require(list.count { it.upcoming } < Reminder.MAX) { "There are already ${Reminder.MAX} reminders; cancel some first." }
            prune(list + reminder, now)
        }
        alarms.schedule(reminder)
        return reminder
    }

    /** Removes [id] (from the list, its alarm and any notification); the reminder, or null when there was none. */
    suspend fun cancel(id: String): Reminder? {
        var gone: Reminder? = null
        settings.updateReminders { list -> gone = list.firstOrNull { it.id == id }; list.filterNot { it.id == id } }
        alarms.cancel(id)
        notifier.dismiss(id)
        return gone
    }

    /** The alarm went off: shows the notification and records it as shown. Does nothing for a reminder that's gone or not due. */
    suspend fun fire(id: String) {
        val now = clock()
        var due: Reminder? = null
        settings.updateReminders { list ->
            list.map { r ->
                if (r.id == id && r.upcoming && r.at <= now + EARLY_MS) r.copy(shownAt = now).also { due = r } else r
            }
        }
        val reminder = due ?: return
        notifier.show(reminder, late = now - reminder.at > LATE_MS)
    }

    /** Snooze from the notification: due again [Reminder.SNOOZE_MS] from now. */
    suspend fun snooze(id: String) {
        val now = clock()
        var next: Reminder? = null
        settings.updateReminders { list ->
            list.map { r -> if (r.id == id) r.copy(at = now + Reminder.SNOOZE_MS, shownAt = null, snoozes = r.snoozes + 1).also { next = it } else r }
        }
        notifier.dismiss(id)
        next?.let(alarms::schedule)
    }

    /** Done (or the notification swiped away): the reminder leaves the list. */
    suspend fun done(id: String) {
        settings.updateReminders { list -> list.filterNot { it.id == id } }
        alarms.cancel(id)
        notifier.dismiss(id)
    }

    /**
     * Sets every alarm again: after a reboot (Android clears alarms), an app update, the exact-alarm
     * permission being granted, or app start. Reminders whose time passed while the phone was off fire
     * at once, as late ones. Old shown reminders are dropped.
     */
    suspend fun rescheduleAll() {
        val now = clock()
        val kept = settings.updateReminders { prune(it, now) }
        kept.filter { it.upcoming }.forEach(alarms::schedule)
    }

    private fun prune(list: List<Reminder>, now: Long): List<Reminder> {
        val fresh = list.filter { it.upcoming || (it.shownAt ?: 0) > now - Reminder.KEEP_SHOWN_MS }
        if (fresh.size <= Reminder.MAX) return fresh
        // Over the cap: the oldest shown ones go first, never one still to come.
        val shown = fresh.filterNot { it.upcoming }.sortedBy { it.shownAt }
        val drop = shown.take(fresh.size - Reminder.MAX).map { it.id }.toSet()
        return fresh.filterNot { it.id in drop }
    }

    companion object {
        const val MAX_TEXT = 300
        const val MAX_AHEAD_MS: Long = 366L * 24 * 60 * 60 * 1000

        /** A time up to this far in the past (a model rounding "now") still counts: it fires at once. */
        const val GRACE_MS: Long = 2 * 60 * 1000L

        /** An inexact alarm may come a little early on some phones; still fire it. */
        private const val EARLY_MS: Long = 60 * 1000L

        /** Fired this long after its time, the notification says it's late. */
        const val LATE_MS: Long = 15 * 60 * 1000L

        internal fun sorted(list: List<Reminder>) = list.sortedWith(compareBy<Reminder> { !it.upcoming }.thenBy { it.at })
    }
}

/** remind_me, reminders_list and reminder_cancel (docs/design/phone-integrations.md). */
fun reminderTools(reminders: Reminders, zone: () -> ZoneId = ZoneId::systemDefault, now: () -> Long = System::currentTimeMillis): List<Tool> =
    listOf(RemindMe(reminders, zone, now), RemindersList(reminders, zone), ReminderCancel(reminders, zone))

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ROOT)

internal fun reminderTime(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).format(WHEN)

internal class RemindMe(private val reminders: Reminders, private val zone: () -> ZoneId, private val now: () -> Long) : Tool {
    override val risk = ToolRisk.WRITE

    // It only posts a notification on this phone, to the user.
    override val defaultPolicy = ToolPolicy.ALLOW

    override val spec = ToolSpec(
        DeviceIntegrations.REMIND_ME,
        "Remind the user at a time with a Locus notification (Snooze and Done on it). Nothing runs when it fires: it just " +
            "shows the text. Give either at (the phone's local time, 2026-11-10T17:00) or in_minutes.",
        ObjectSchema()
            .string("text", "What to remind them of, as they'd want to read it (\"Call mum\").")
            .string("at", "When: 2026-11-10T17:00 in the phone's time zone, or with an offset.", required = false)
            .integer("in_minutes", "Or: this many minutes from now (1–525600).", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Remind you: “${args.str("text").orEmpty().take(80)}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolException("Give the reminder's text.")
        val at = time(args)
        val chat = currentCoroutineContext()[ToolCallContext]?.conversationId
        val reminder = try {
            reminders.add(text, at, chat)
        } catch (e: IllegalArgumentException) {
            throw ToolException(e.message ?: "Couldn't set the reminder.")
        }
        val notes = buildList {
            if (!reminders.canNotify()) add("Notifications for Locus are off, so it won't show until the user allows them (Settings → Phone).")
            if (!reminders.exactTiming()) add("It may show up to about 10 minutes late, since exact alarms aren't allowed (Settings → Phone).")
        }
        return ToolResult("Reminder set for ${reminderTime(reminder.at, zone())} (${zone().id}), id ${reminder.id.take(8)}." + notes.joinToString("") { " $it" })
    }

    private fun time(args: JsonObject): Long {
        val minutes = args.int("in_minutes")
        val at = args.str("at")?.trim()?.takeIf { it.isNotEmpty() }
        if ((minutes == null) == (at == null)) throw ToolException("Give either at or in_minutes.")
        if (minutes != null) {
            if (minutes !in 1..525_600) throw ToolException("in_minutes must be 1–525600.")
            return now() + minutes * 60_000L
        }
        runCatching { return OffsetDateTime.parse(at).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(at).toEpochMilli() }
        return runCatching { LocalDateTime.parse(at).atZone(zone()).toInstant().toEpochMilli() }.getOrNull()
            ?: throw ToolException("at must be a date-time like 2026-11-10T17:00 (the phone's time zone).")
    }
}

internal class RemindersList(private val reminders: Reminders, private val zone: () -> ZoneId) : Tool {
    override val risk = ToolRisk.READ

    override val spec = ToolSpec(
        DeviceIntegrations.REMINDERS_LIST,
        "List the user's Locus reminders still to come, with their ids.",
        ObjectSchema().build(),
    )

    override fun describe(args: JsonObject) = "Check your reminders"

    override suspend fun execute(args: JsonObject): ToolResult {
        val upcoming = reminders.current().filter { it.upcoming }
        if (upcoming.isEmpty()) return ToolResult("No reminders to come.")
        return ToolResult(
            "${upcoming.size} reminder${if (upcoming.size == 1) "" else "s"} to come (times in ${zone().id}):\n" +
                upcoming.joinToString("\n") { "- ${reminderTime(it.at, zone())} · ${it.text} · id ${it.id.take(8)}" },
        )
    }
}

internal class ReminderCancel(private val reminders: Reminders, private val zone: () -> ZoneId) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ASK

    override val spec = ToolSpec(
        DeviceIntegrations.REMINDER_CANCEL,
        "Cancel one of the user's Locus reminders by its id (from reminders_list or remind_me).",
        ObjectSchema().string("id", "The reminder's id (its first 8 characters are enough).").build(),
    )

    override fun describe(args: JsonObject) = "Cancel a reminder"

    override suspend fun execute(args: JsonObject): ToolResult {
        val prefix = args.str("id")?.trim()?.lowercase()?.takeIf { it.length >= 4 } ?: throw ToolException("Give the reminder's id.")
        val matches = reminders.current().filter { it.id.lowercase().startsWith(prefix) }
        val one = when (matches.size) {
            0 -> throw ToolException("No reminder has id $prefix. reminders_list shows them.")
            1 -> matches.single()
            else -> throw ToolException("More than one reminder starts with $prefix; give more of the id.")
        }
        reminders.cancel(one.id)
        return ToolResult("Cancelled the reminder “${one.text}” for ${reminderTime(one.at, zone())}.")
    }
}
