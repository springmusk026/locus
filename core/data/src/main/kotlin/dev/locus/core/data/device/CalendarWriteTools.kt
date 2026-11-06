package dev.locus.core.data.device

import dev.locus.core.agent.ApprovalPreview
import dev.locus.core.agent.ApprovalScope
import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.DeviceIntegrations
import dev.locus.core.model.PhoneSettings
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A calendar on the phone that Locus could write to: synced, visible, with at least contributor access. */
data class WritableCalendar(val id: Long, val name: String, val account: String, val primary: Boolean = false)

/** An event as stored (the Events row, not one occurrence). Times are epoch ms; [rrule] is empty for a one-off. */
data class StoredEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String = "",
    val description: String = "",
    val rrule: String = "",
    val attendees: List<String> = emptyList(),
) {
    val recurring: Boolean get() = rrule.isNotBlank()
}

/**
 * What a new event holds, ready for CalendarContract: [start] and [end] in epoch ms (UTC midnights for an
 * all-day event), [zone] the time zone to store (UTC for all-day, as the provider requires).
 */
data class NewEvent(
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val zone: String,
    val location: String = "",
    val notes: String = "",
    val rrule: String = "",
    val attendees: List<String> = emptyList(),
    val reminderMinutes: Int? = null,
)

/** The fields a change sets; null leaves a field as it is. [times] sets start, end, all-day and zone together. */
data class EventChange(
    val title: String? = null,
    val times: EventTimes? = null,
    val location: String? = null,
    val notes: String? = null,
    val rrule: String? = null,
    val addAttendees: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = title == null && times == null && location == null && notes == null && rrule == null && addAttendees.isEmpty()
}

data class EventTimes(val start: Long, val end: Long, val allDay: Boolean, val zone: String)

/**
 * The calendar provider, behind an interface so the tools are tested on the JVM ([AndroidCalendar] is
 * the real one). Every method is blocking and assumes READ_CALENDAR and WRITE_CALENDAR are held.
 */
interface CalendarData {
    /** Whether Locus holds both calendar permissions now (no prompt). */
    fun hasWriteAccess(): Boolean

    fun writableCalendars(): List<WritableCalendar>

    /** The event [id], with its guests; null when there's none (or it was deleted). */
    fun event(id: Long): StoredEvent?

    /** Inserts [event] into [calendarId] with its guests and reminder; the new event's id. */
    fun insert(calendarId: Long, event: NewEvent): Long

    /** Applies [change] to [event] (the whole series for a repeating one). */
    fun update(event: StoredEvent, change: EventChange)

    /** Deletes [id] as an app does: the provider marks it deleted and the sync adapter removes it on the server. */
    fun delete(id: Long)
}

/** Reads and saves the calendar choice in Settings → Phone. */
interface CalendarChoice {
    suspend fun current(): PhoneSettings

    suspend fun save(calendar: WritableCalendar)
}

/** The calendar write tools (docs/design/phone-integrations.md). */
fun calendarWriteTools(
    phone: PhoneIntegrations,
    data: CalendarData,
    choice: CalendarChoice,
    zone: () -> ZoneId = ZoneId::systemDefault,
    now: () -> Long = System::currentTimeMillis,
): List<Tool> {
    val writer = CalendarWriter(phone, data, choice, zone, now)
    return listOf(CalendarCreate(writer), CalendarUpdate(writer), CalendarDelete(writer))
}

/** What the three tools share: access, the one calendar Locus may change, parsing and wording. */
internal class CalendarWriter(
    val phone: PhoneIntegrations,
    val data: CalendarData,
    val choice: CalendarChoice,
    val zone: () -> ZoneId,
    val now: () -> Long,
) {
    /** Asks for calendar access when it isn't held (only with Locus on screen); why it can't go on, or null. */
    suspend fun access(): String? {
        if (data.hasWriteAccess()) return null
        return when (phone.calendarWriteAccess()) {
            is UserPick.Picked -> null
            UserPick.Cancelled -> "The user didn't allow Locus to change the calendar. Don't ask again unless they bring it up; " +
                "they can allow it in Settings → Phone."
            UserPick.NotOnScreen -> NOT_ON_SCREEN_WRITE
        }
    }

    /**
     * The calendar Locus may change: the one picked in Settings → Phone, else the phone's primary writable
     * calendar, which is then saved as the choice. Throws with the reason when there's none.
     */
    suspend fun target(): WritableCalendar {
        val calendars = data.writableCalendars()
        val picked = choice.current()
        picked.calendarId?.let { id ->
            return calendars.firstOrNull { it.id == id } ?: throw ToolException(
                "The calendar picked in Settings → Phone (“${picked.calendarName.ifBlank { "#$id" }}”) is gone or can't be changed now. " +
                    "Ask the user to pick another one there.",
            )
        }
        val first = calendars.firstOrNull { it.primary } ?: calendars.firstOrNull()
            ?: throw ToolException("No calendar on this phone can be changed (each one is read-only or not synced). Use device_calendar_event to let the user add it in their calendar app.")
        choice.save(first)
        return first
    }

    /** The event [id] in the target calendar, or the reason it can't be changed. */
    fun eventIn(calendar: WritableCalendar, id: Long): StoredEvent {
        val event = data.event(id) ?: throw ToolException("There's no event with id $id. Use calendar_read to find its id.")
        if (event.calendarId != calendar.id) {
            throw ToolException(
                "That event isn't in “${calendar.name}”, the only calendar Locus may change (Settings → Phone). " +
                    "Locus can't change it; the user can, in their calendar app.",
            )
        }
        return event
    }

    /** Refuses a single occurrence of a repeating [event]: only the whole series, when [series] says so. */
    fun requireSeries(event: StoredEvent, series: Boolean?, verb: String) {
        if (!event.recurring) return
        if (series == true) return
        throw ToolException(
            "“${event.title}” repeats (${describeRule(event.rrule)}). Locus can only $verb the whole series: call again with " +
                "series: true if the user means every occurrence. To $verb just one occurrence, ask the user to do it in their " +
                "calendar app; Locus doesn't make single-occurrence exceptions.",
        )
    }

    fun when_(start: Long, end: Long, allDay: Boolean): String = formatWhen(start, end, allDay, zone())

    companion object {
        const val NOT_ON_SCREEN_WRITE = "Locus isn't on screen, so it can't ask the user for calendar access now. Ask them to open Locus and try again."
    }
}

internal class CalendarCreate(private val w: CalendarWriter) : Tool {
    // Writes sync to the calendar's server.
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ASK

    override val spec = ToolSpec(
        DeviceIntegrations.CALENDAR_CREATE,
        "Add an event to the user's calendar on the phone (the calendar they picked for Locus in Settings → Phone; it syncs " +
            "to that calendar's account). Times are in the phone's time zone as ISO local date-times (2026-11-10T15:30), or " +
            "with an offset (2026-11-10T15:30+01:00); an all-day event takes dates (2026-11-10). Adding guests makes the " +
            "calendar's server email them invitations, so only add guests the user asked for.",
        ObjectSchema()
            .string("title", "Event title.")
            .string("start", "Start: 2026-11-10T15:30, or a date for an all-day event.")
            .string("end", "End (after start). For an all-day event the last day, inclusive. Default: one hour, or one day.", required = false)
            .integer("duration_minutes", "Length instead of end.", required = false)
            .boolean("all_day", "An all-day event.")
            .string("location", "Where.", required = false)
            .string("notes", "Description.", required = false)
            .string("recurrence", "Repeat rule (RFC 5545 RRULE), e.g. FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10. Supports FREQ, INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH.", required = false)
            .string("guests", "Email addresses to invite, comma-separated. They get an invitation by email.", required = false)
            .integer("reminder_minutes", "A notification this many minutes before (0–40320).", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Add “${args.str("title").orEmpty().take(80)}” to your calendar"

    override suspend fun mustConfirm(args: JsonObject): String? = guests(args.str("guests")).takeIf { it.isNotEmpty() }?.let(::guestsReason)

    override suspend fun precheck(args: JsonObject): String? = checked {
        event(args)
        w.access()?.let { return it }
        w.target()
    }

    override suspend fun rememberScope(args: JsonObject): ApprovalScope? = calendarScope(w, spec.name, args)

    override suspend fun preview(args: JsonObject): ApprovalPreview? = runCatching {
        val e = event(args)
        val calendar = if (w.data.hasWriteAccess()) w.target() else null
        ApprovalPreview(eventRows(e, calendar, w))
    }.getOrNull()

    override suspend fun execute(args: JsonObject): ToolResult {
        val e = event(args)
        w.access()?.let { return ToolResult.error(it) }
        val calendar = w.target()
        val id = w.data.insert(calendar.id, e)
        val guestNote = if (e.attendees.isEmpty()) "" else " Invited ${e.attendees.joinToString()}; the calendar's server sends the invitations."
        return ToolResult("Added “${e.title}” to ${calendar.name}: ${w.when_(e.start, e.end, e.allDay)} (event id $id).$guestNote")
    }

    private fun event(args: JsonObject): NewEvent {
        val title = args.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolException("Give the event a title.")
        val times = parseTimes(args.str("start"), args.str("end"), args.int("duration_minutes"), args.bool("all_day") == true, w.zone(), w.now())
        val rrule = args.str("recurrence")?.takeIf { it.isNotBlank() }?.let(Recurrence::normalize).orEmpty()
        val reminder = args.int("reminder_minutes")?.also { if (it !in 0..MAX_REMINDER) throw ToolException("reminder_minutes must be 0–$MAX_REMINDER.") }
        return NewEvent(
            title = title.take(MAX_TITLE),
            start = times.start,
            end = times.end,
            allDay = times.allDay,
            zone = times.zone,
            location = args.str("location")?.trim().orEmpty(),
            notes = args.str("notes")?.trim().orEmpty(),
            rrule = rrule,
            attendees = guests(args.str("guests")),
            reminderMinutes = reminder,
        )
    }
}

internal class CalendarUpdate(private val w: CalendarWriter) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ASK

    override val spec = ToolSpec(
        DeviceIntegrations.CALENDAR_UPDATE,
        "Change an event in the calendar Locus may change (Settings → Phone). Find its id with calendar_read. Only the " +
            "fields given change. A repeating event can only be changed as a whole series (series: true); Locus doesn't " +
            "change single occurrences. New guests get invitations by email.",
        ObjectSchema()
            .integer("event_id", "The event's id from calendar_read.", required = true)
            .boolean("series", "For a repeating event: true to change every occurrence.")
            .string("title", "New title.", required = false)
            .string("start", "New start (2026-11-10T15:30, or a date for all-day). Give end or duration_minutes too, or the length is kept.", required = false)
            .string("end", "New end.", required = false)
            .integer("duration_minutes", "New length instead of end.", required = false)
            .boolean("all_day", "Whether the new times are all-day (with start).")
            .string("location", "New place; empty clears it.", required = false)
            .string("notes", "New description; empty clears it.", required = false)
            .string("recurrence", "New repeat rule (RRULE), for a series.", required = false)
            .string("add_guests", "Email addresses to invite, comma-separated.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Change a calendar event"

    override suspend fun mustConfirm(args: JsonObject): String? = guests(args.str("add_guests")).takeIf { it.isNotEmpty() }?.let(::guestsReason)

    override suspend fun precheck(args: JsonObject): String? = checked {
        w.access()?.let { return it }
        val calendar = w.target()
        val event = w.eventIn(calendar, eventId(args))
        w.requireSeries(event, args.bool("series"), "change")
        if (change(args, event).isEmpty) throw ToolException("Nothing to change: give at least one field.")
    }

    override suspend fun rememberScope(args: JsonObject): ApprovalScope? = calendarScope(w, spec.name, args)

    override suspend fun preview(args: JsonObject): ApprovalPreview? = runCatching {
        val calendar = w.target()
        val event = w.eventIn(calendar, eventId(args))
        val c = change(args, event)
        val rows = mutableListOf(
            ApprovalPreview.Row("Calendar", calendar.name),
            ApprovalPreview.Row("Event", "${event.title.ifBlank { "(no title)" }} · ${w.when_(event.start, event.end, event.allDay)}"),
        )
        if (event.recurring) rows += ApprovalPreview.Row("Applies to", "Every occurrence (${describeRule(event.rrule)})")
        c.title?.let { rows += ApprovalPreview.Row("New title", it) }
        c.times?.let { rows += ApprovalPreview.Row("New time", w.when_(it.start, it.end, it.allDay)) }
        c.location?.let { rows += ApprovalPreview.Row("New place", it.ifEmpty { "(cleared)" }) }
        c.notes?.let { rows += ApprovalPreview.Row("New notes", it.ifEmpty { "(cleared)" }) }
        c.rrule?.let { rows += ApprovalPreview.Row("New repeat", describeRule(it)) }
        if (c.addAttendees.isNotEmpty()) rows += ApprovalPreview.Row("Invites", c.addAttendees.joinToString(", "), outward = true)
        ApprovalPreview(rows)
    }.getOrNull()

    override suspend fun execute(args: JsonObject): ToolResult {
        w.access()?.let { return ToolResult.error(it) }
        val calendar = w.target()
        val event = w.eventIn(calendar, eventId(args))
        w.requireSeries(event, args.bool("series"), "change")
        val c = change(args, event)
        if (c.isEmpty) throw ToolException("Nothing to change: give at least one field.")
        w.data.update(event, c)
        val after = w.data.event(event.id) ?: event
        return ToolResult(
            "Changed “${after.title}” in ${calendar.name}: ${w.when_(after.start, after.end, after.allDay)}" +
                (if (event.recurring) " (every occurrence)" else "") + "." +
                (if (c.addAttendees.isEmpty()) "" else " Invited ${c.addAttendees.joinToString()}."),
        )
    }

    private fun change(args: JsonObject, event: StoredEvent): EventChange {
        val start = args.str("start")?.takeIf { it.isNotBlank() }
        val times = if (start != null) {
            val allDay = args.bool("all_day") ?: event.allDay
            // Without an end or a length, the event keeps its length.
            val minutes = args.int("duration_minutes") ?: if (args.str("end").isNullOrBlank()) ((event.end - event.start) / 60_000).toInt().coerceAtLeast(1) else null
            parseTimes(start, args.str("end"), if (allDay && args.str("end").isNullOrBlank()) null else minutes, allDay, w.zone(), w.now())
        } else if (!args.str("end").isNullOrBlank()) {
            throw ToolException("Give start with end, even when only the end changes.")
        } else null
        val rrule = args.str("recurrence")?.let { if (it.isBlank()) throw ToolException("recurrence can't be emptied here; ask the user to change it in their calendar app.") else Recurrence.normalize(it) }
        if (rrule != null && !event.recurring) throw ToolException("Locus doesn't turn a one-off event into a repeating one; create a new repeating event instead.")
        return EventChange(
            title = args.str("title")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE),
            times = times,
            location = args.str("location")?.trim(),
            notes = args.str("notes")?.trim(),
            rrule = rrule,
            addAttendees = guests(args.str("add_guests")).filter { it !in event.attendees },
        )
    }
}

internal class CalendarDelete(private val w: CalendarWriter) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ASK

    override val spec = ToolSpec(
        DeviceIntegrations.CALENDAR_DELETE,
        "Delete an event from the calendar Locus may change (Settings → Phone). Find its id with calendar_read. The user " +
            "always confirms. A repeating event can only be deleted as a whole series (series: true).",
        ObjectSchema()
            .integer("event_id", "The event's id from calendar_read.", required = true)
            .boolean("series", "For a repeating event: true to delete every occurrence.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Delete a calendar event"

    override suspend fun mustConfirm(args: JsonObject): String = "Deleting an event always needs your confirmation; it's removed on the calendar's server too."

    override suspend fun precheck(args: JsonObject): String? = checked {
        w.access()?.let { return it }
        val event = w.eventIn(w.target(), eventId(args))
        w.requireSeries(event, args.bool("series"), "delete")
    }

    override suspend fun preview(args: JsonObject): ApprovalPreview? = runCatching {
        val calendar = w.target()
        val event = w.eventIn(calendar, eventId(args))
        ApprovalPreview(
            listOfNotNull(
                ApprovalPreview.Row("Calendar", calendar.name),
                ApprovalPreview.Row("Deletes", event.title.ifBlank { "(no title)" }),
                ApprovalPreview.Row("When", w.when_(event.start, event.end, event.allDay)),
                event.rrule.takeIf { it.isNotBlank() }?.let { ApprovalPreview.Row("Repeats", "${describeRule(it)}: every occurrence goes") },
                event.location.takeIf { it.isNotBlank() }?.let { ApprovalPreview.Row("Where", it) },
                event.attendees.takeIf { it.isNotEmpty() }?.let { ApprovalPreview.Row("Guests", it.joinToString(", "), outward = true) },
            ),
        )
    }.getOrNull()

    override suspend fun execute(args: JsonObject): ToolResult {
        w.access()?.let { return ToolResult.error(it) }
        val calendar = w.target()
        val event = w.eventIn(calendar, eventId(args))
        w.requireSeries(event, args.bool("series"), "delete")
        w.data.delete(event.id)
        return ToolResult("Deleted “${event.title}” (${w.when_(event.start, event.end, event.allDay)}) from ${calendar.name}" + if (event.recurring) ", every occurrence." else ".")
    }
}

private const val MAX_TITLE = 300
private const val MAX_REMINDER = 40_320
private const val MAX_GUESTS = 20

/** [block]'s [ToolException] as a precheck's reason. */
private inline fun checked(block: () -> Unit): String? = try {
    block()
    null
} catch (e: ToolException) {
    e.message
}

private fun eventId(args: JsonObject): Long = args.str("event_id")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
    ?: throw ToolException("event_id must be the number calendar_read shows after “id”.")

// The guests first, so they show even where the card cuts the line short.
private fun guestsReason(guests: List<String>) =
    "Invites ${guests.joinToString()}: the calendar's server emails each of them an invitation."

/** "This calendar", for a call without guests in the calendar Settings → Phone names. */
private suspend fun calendarScope(w: CalendarWriter, tool: String, args: JsonObject): ApprovalScope? {
    if (guests(args.str("guests") ?: args.str("add_guests")).isNotEmpty()) return null
    val s = w.choice.current()
    val value = s.calendarScope ?: return null
    return ApprovalScope(tool, ApprovalScopeKind.CALENDAR, value, "events in ${s.calendarName.ifBlank { "this calendar" }}")
}

private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

/** Guest addresses, checked and de-duplicated; throws on anything that isn't one. */
internal fun guests(text: String?): List<String> {
    val all = text.orEmpty().split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    all.firstOrNull { !EMAIL.matches(it) }?.let { throw ToolException("“$it” isn't an email address. Guests are email addresses, comma-separated.") }
    if (all.size > MAX_GUESTS) throw ToolException("At most $MAX_GUESTS guests.")
    return all
}

internal fun eventRows(e: NewEvent, calendar: WritableCalendar?, w: CalendarWriter): List<ApprovalPreview.Row> = listOfNotNull(
    ApprovalPreview.Row("Calendar", calendar?.let { if (it.account.isNotBlank() && it.account != it.name) "${it.name} (${it.account})" else it.name } ?: "Asked once calendar access is allowed"),
    ApprovalPreview.Row("Title", e.title),
    ApprovalPreview.Row("When", w.when_(e.start, e.end, e.allDay)),
    e.rrule.takeIf { it.isNotBlank() }?.let { ApprovalPreview.Row("Repeats", describeRule(it)) },
    e.location.takeIf { it.isNotBlank() }?.let { ApprovalPreview.Row("Where", it) },
    e.notes.takeIf { it.isNotBlank() }?.let { ApprovalPreview.Row("Notes", it) },
    e.reminderMinutes?.let { ApprovalPreview.Row("Reminder", if (it == 0) "At the start" else "$it min before") },
    e.attendees.takeIf { it.isNotEmpty() }?.let { ApprovalPreview.Row("Invites", it.joinToString(", "), outward = true) },
)

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ROOT)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** "Tue 10 Nov 2026, 15:30–16:30 (Europe/Lisbon)"; all-day events by date, whatever the zone. */
internal fun formatWhen(start: Long, end: Long, allDay: Boolean, zone: ZoneId): String {
    if (allDay) {
        val first = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
        // The stored end is the midnight after the last day.
        val last = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
        return if (!last.isAfter(first)) "${first.format(DAY)}, all day" else "${first.format(DAY)} to ${last.format(DAY)}, all day"
    }
    val s = Instant.ofEpochMilli(start).atZone(zone)
    val e = Instant.ofEpochMilli(end).atZone(zone)
    val endText = if (e.toLocalDate() == s.toLocalDate()) e.format(TIME) else "${e.format(DAY)} ${e.format(TIME)}"
    return "${s.format(DAY)}, ${s.format(TIME)}–$endText (${zone.id})"
}

/**
 * Start and end from the tool's arguments. Local date-times are in [zone] (the phone's); one with an offset
 * or Z is taken as that instant. All-day events become UTC midnights with an exclusive end, as
 * CalendarContract requires ("If allDay is set to 1 eventTimezone must be UTC and the time must correspond
 * to a midnight boundary"). Times more than a year in the past or ten years ahead are refused as mistakes.
 */
internal fun parseTimes(start: String?, end: String?, durationMinutes: Int?, allDay: Boolean, zone: ZoneId, now: Long): EventTimes {
    val startText = start?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolException("Give a start, like 2026-11-10T15:30.")
    if (durationMinutes != null && durationMinutes !in 1..MAX_MINUTES) throw ToolException("duration_minutes must be 1–$MAX_MINUTES.")
    val times = if (allDay) {
        val first = date(startText, "start")
        val last = end?.trim()?.takeIf { it.isNotEmpty() }?.let { date(it, "end") }
            ?: durationMinutes?.let { first.plusDays(((it + 1439) / 1440 - 1).toLong()) } ?: first
        if (last.isBefore(first)) throw ToolException("The end date is before the start date.")
        if (last.toEpochDay() - first.toEpochDay() > MAX_DAYS) throw ToolException("An all-day event can last at most $MAX_DAYS days.")
        EventTimes(first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, "UTC")
    } else {
        val s = instant(startText, zone, "start")
        val e = end?.trim()?.takeIf { it.isNotEmpty() }?.let { instant(it, zone, "end") } ?: (s + (durationMinutes ?: 60) * 60_000L)
        if (e <= s) throw ToolException("The end must be after the start.")
        if (e - s > MAX_DAYS * 86_400_000L) throw ToolException("An event can last at most $MAX_DAYS days.")
        EventTimes(s, e, false, zone.id)
    }
    val year = 365L * 86_400_000L
    if (times.start < now - year || times.start > now + 10 * year) {
        throw ToolException("$startText is more than a year ago or ten years ahead; check the date (today is ${Instant.ofEpochMilli(now).atZone(zone).toLocalDate()}).")
    }
    return times
}

private const val MAX_MINUTES = 14 * 24 * 60
private const val MAX_DAYS = 31

private fun date(text: String, field: String): LocalDate = try {
    LocalDate.parse(text.take(10))
} catch (_: DateTimeParseException) {
    throw ToolException("$field must be a date like 2026-11-10 for an all-day event.")
}

private fun instant(text: String, zone: ZoneId, field: String): Long {
    runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
    runCatching { return Instant.parse(text).toEpochMilli() }
    return try {
        val local = LocalDateTime.parse(text)
        // A time the clocks skip (a DST gap) moves forward, as java.time and the calendar apps do.
        local.atZone(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        if (text.length == 10) throw ToolException("$field is a date; give a time too (2026-11-10T15:30), or set all_day.")
        throw ToolException("$field must be an ISO date-time like 2026-11-10T15:30 (the phone's time zone) or 2026-11-10T15:30+01:00.")
    }
}

/** Simple RFC 5545 repeat rules: the parts calendars share widely. Anything else is refused rather than half-understood. */
internal object Recurrence {
    private val FREQS = setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    private val DAY = Regex("[+-]?([1-5])?(MO|TU|WE|TH|FR|SA|SU)")
    private val UNTIL = Regex("\\d{8}(T\\d{6}Z?)?")

    /** [rule] checked and in canonical form (upper case, no `RRULE:`), or a [ToolException] saying what's wrong. */
    fun normalize(rule: String): String {
        val body = rule.trim().removePrefix("RRULE:").removePrefix("rrule:").trim().trimEnd(';')
        val parts = body.split(';').filter { it.isNotBlank() }.map { p ->
            val (k, v) = p.split('=', limit = 2).let { if (it.size != 2) throw ToolException("“$p” in recurrence isn't KEY=VALUE.") else it[0].trim().uppercase() to it[1].trim().uppercase() }
            k to v
        }
        val map = parts.toMap()
        if (map.size != parts.size) throw ToolException("recurrence names a part twice.")
        val freq = map["FREQ"] ?: throw ToolException("recurrence needs FREQ (DAILY, WEEKLY, MONTHLY or YEARLY).")
        if (freq !in FREQS) throw ToolException("FREQ must be DAILY, WEEKLY, MONTHLY or YEARLY.")
        for ((k, v) in map) when (k) {
            "FREQ" -> Unit
            "INTERVAL" -> if (v.toIntOrNull() !in 1..99) throw ToolException("INTERVAL must be 1–99.")
            "COUNT" -> if (v.toIntOrNull() !in 1..730) throw ToolException("COUNT must be 1–730.")
            "UNTIL" -> if (!UNTIL.matches(v)) throw ToolException("UNTIL must look like 20261231 or 20261231T235959Z.")
            "BYDAY" -> v.split(',').forEach { if (!DAY.matches(it)) throw ToolException("BYDAY takes days like MO,WE or 1MO.") }
            "BYMONTHDAY" -> v.split(',').forEach { d -> if (d.toIntOrNull()?.let { it != 0 && it in -31..31 } != true) throw ToolException("BYMONTHDAY takes days 1–31 (or -1 for the last).") }
            "BYMONTH" -> v.split(',').forEach { m -> if (m.toIntOrNull() !in 1..12) throw ToolException("BYMONTH takes months 1–12.") }
            else -> throw ToolException("recurrence part $k isn't supported; use FREQ, INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY or BYMONTH.")
        }
        if ("COUNT" in map && "UNTIL" in map) throw ToolException("Use COUNT or UNTIL, not both.")
        return parts.joinToString(";") { (k, v) -> "$k=$v" }
    }
}

/** "every week on MO,WE, 10 times" — a plain reading of a rule for the card and messages. */
internal fun describeRule(rule: String): String {
    val map = rule.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
    val interval = map["INTERVAL"]?.toIntOrNull()?.takeIf { it > 1 }
    val unit = when (map["FREQ"]) { "DAILY" -> "day"; "WEEKLY" -> "week"; "MONTHLY" -> "month"; "YEARLY" -> "year"; else -> return rule }
    return buildString {
        append(if (interval != null) "every $interval ${unit}s" else "every $unit")
        map["BYDAY"]?.let { append(" on ").append(it) }
        map["BYMONTHDAY"]?.let { append(" on day ").append(it) }
        map["COUNT"]?.let { append(", ").append(it).append(" times") }
        map["UNTIL"]?.let { append(", until ").append(it.take(8).let { d -> "${d.take(4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" }) }
    }
}

/** RFC 2445 duration for a recurring event (DURATION instead of DTEND): days for all-day, else seconds. */
internal fun rfcDuration(start: Long, end: Long, allDay: Boolean): String =
    if (allDay) "P${((end - start) / 86_400_000L).coerceAtLeast(1)}D" else "P${((end - start) / 1000).coerceAtLeast(60)}S"
