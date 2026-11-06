package dev.locus.core.data.device

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.UntrustedContent
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.DeviceIntegrations
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a request that needs the user on screen came to. */
sealed interface UserPick<out T> {
    data class Picked<T>(val value: T) : UserPick<T>

    /** The user closed the picker or said no. */
    data object Cancelled : UserPick<Nothing>

    /** Nobody could be asked: Locus isn't on screen. */
    data object NotOnScreen : UserPick<Nothing>
}

/** One occurrence of a calendar event (recurring events are expanded). Times are epoch milliseconds. */
data class CalendarItem(
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String? = null,
    val calendar: String? = null,
    val description: String? = null,
    /** The event's id (`Instances.EVENT_ID`), which calendar_update and calendar_delete take. */
    val eventId: Long? = null,
    /** The event repeats: it can only be changed or deleted as a whole series. */
    val recurring: Boolean = false,
)

/** A contact the user picked, with the details the picker shared. */
data class PickedContact(val name: String, val phones: List<String> = emptyList(), val emails: List<String> = emptyList())

enum class ContactField(val wire: String) { PHONE("phone"), EMAIL("email"), ANY("any") }

/**
 * The phone side of the integration tools. [AndroidPhone] is the real one; tests pass fakes. Every
 * method that needs the user (a permission prompt, a picker) answers [UserPick.NotOnScreen] when Locus
 * isn't in front, so nothing is ever asked from the background.
 */
interface PhoneIntegrations {
    /** Upcoming events between [from] and [to], asking for READ_CALENDAR the first time. */
    suspend fun calendar(from: Long, to: Long, limit: Int): UserPick<List<CalendarItem>>

    /** A contact the user picks in the system picker; no contacts permission is held. */
    suspend fun pickContact(field: ContactField): UserPick<PickedContact>

    /** Photos the user picks (at most [max]), copied into the workspace's [folder]; their workspace paths. */
    suspend fun pickPhotos(max: Int, folder: String): UserPick<List<String>>

    /**
     * READ_CALENDAR and WRITE_CALENDAR, asked for together after Locus's own rationale when either is
     * missing (docs/design/phone-integrations.md). [UserPick.Picked] once both are held.
     */
    suspend fun calendarWriteAccess(): UserPick<Unit> = UserPick.NotOnScreen

    /** Opens the Contacts app's new-contact screen filled in with [contact]; the user saves it there. */
    suspend fun insertContact(contact: NewContact): UserPick<Unit> = UserPick.NotOnScreen

    /**
     * The system "Save as" picker for [file], suggesting [name] and [mime]; the file is copied to where the
     * user picks. [UserPick.Picked] carries the name the provider gave it (it never overwrites).
     */
    suspend fun saveAs(file: java.io.File, name: String, mime: String): UserPick<String> = UserPick.NotOnScreen

    /** Puts [text] on the clipboard, marked sensitive; false when that failed. */
    suspend fun copy(text: String, label: String): Boolean = false
}

/** What device_contact_add fills in on the Contacts app's new-contact screen. */
data class NewContact(
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val company: String? = null,
    val note: String? = null,
)

/** The read-only phone integrations, as agent tools (docs/design/android-bridge.md). */
fun deviceIntegrationTools(phone: PhoneIntegrations, zone: () -> ZoneId = ZoneId::systemDefault, now: () -> Long = System::currentTimeMillis): List<Tool> =
    listOf(CalendarRead(phone, zone, now), ContactsPick(phone), PhotosPick(phone))

private const val NOT_ON_SCREEN = "Locus isn't on screen, so it can't ask the user now. Ask them to open Locus and try again."

internal class CalendarRead(private val phone: PhoneIntegrations, private val zone: () -> ZoneId, private val now: () -> Long) : Tool {
    override val risk = ToolRisk.READ

    // Calendars are private: each read asks unless the user allows it.
    override val defaultPolicy = ToolPolicy.ASK

    override val spec = ToolSpec(
        DeviceIntegrations.CALENDAR_READ,
        "Read upcoming events from the calendars on the user's phone (read-only; recurring events expanded). " +
            "The first use asks the user for calendar access.",
        ObjectSchema()
            .integer("days", "How many days ahead, from now (default 7, at most $MAX_DAYS).", required = false)
            .string("query", "Only events whose title, place or notes contain this.", required = false)
            .integer("limit", "Most events to return (default 50, at most $MAX_EVENTS).", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Read your calendar for the next ${days(args)} days" + (args.str("query")?.let { " (“$it”)" }.orEmpty())

    private fun days(args: JsonObject) = (args.int("days") ?: 7).coerceIn(1, MAX_DAYS)

    override suspend fun execute(args: JsonObject): ToolResult {
        val days = days(args)
        val limit = (args.int("limit") ?: 50).coerceIn(1, MAX_EVENTS)
        val query = args.str("query")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val from = now()
        val to = from + days * DAY_MS
        val events = when (val r = phone.calendar(from, to, if (query == null) limit else MAX_EVENTS)) {
            is UserPick.Picked -> r.value
            UserPick.Cancelled -> return ToolResult.error("The user didn't allow calendar access. Don't ask again unless they bring it up.")
            UserPick.NotOnScreen -> return ToolResult.error(NOT_ON_SCREEN)
        }
        val shown = events
            .filter { e -> query == null || listOfNotNull(e.title, e.location, e.description).any { query in it.lowercase() } }
            .sortedBy { it.start }
            .take(limit)
        if (shown.isEmpty()) return ToolResult("No events in the next $days days" + (query?.let { " matching “$it”" }.orEmpty()) + ".")
        val z = zone()
        val text = shown.joinToString("\n") { line(it, z) }
        // Invitations carry text other people wrote, so the list is marked like any outside content.
        return ToolResult(
            "${shown.size} event${if (shown.size == 1) "" else "s"} in the next $days days (times in ${z.id}):\n" +
                UntrustedContent.wrap(text, UntrustedContent.note("from the user's calendar; invitations can hold text other people wrote")),
        )
    }

    companion object {
        const val MAX_DAYS = 62
        const val MAX_EVENTS = 200
        private const val DAY_MS = 86_400_000L
        private val DAY = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", Locale.ROOT)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        internal fun line(e: CalendarItem, zone: ZoneId): String {
            // All-day events are stored at UTC midnight; shown as their date, whatever the phone's zone.
            val z = if (e.allDay) ZoneId.of("UTC") else zone
            val start = Instant.ofEpochMilli(e.start).atZone(z)
            val end = Instant.ofEpochMilli(e.end).atZone(z)
            val time = if (e.allDay) "all day" else "${start.format(TIME)}–${end.format(TIME)}" + if (end.toLocalDate() != start.toLocalDate()) " (ends ${end.format(DAY)})" else ""
            return buildString {
                append("- ").append(start.format(DAY)).append(' ').append(time).append(" · ").append(e.title.ifBlank { "(no title)" })
                e.location?.takeIf { it.isNotBlank() }?.let { append(" · at ").append(it) }
                e.calendar?.takeIf { it.isNotBlank() }?.let { append(" · [").append(it).append(']') }
                e.eventId?.let { append(" · id ").append(it) }
                if (e.recurring) append(" (repeats)")
                e.description?.takeIf { it.isNotBlank() }?.let { append("\n  ").append(it.replace('\n', ' ').take(200)) }
            }
        }
    }
}

internal class ContactsPick(private val phone: PhoneIntegrations) : Tool {
    override val risk = ToolRisk.READ
    override val defaultPolicy = ToolPolicy.ASK

    // It waits for the user, like ask_user.
    override val runsAlone = true

    override val spec = ToolSpec(
        DeviceIntegrations.CONTACTS_PICK,
        "Ask the user to pick one contact in the phone's contact picker, and get the name with the phone numbers or email " +
            "addresses they share. Locus can't search or list contacts; only the one the user picks is shared.",
        ObjectSchema()
            .string("field", "What you need: \"phone\", \"email\" or \"any\" (default).", required = false)
            .string("reason", "One short line shown to the user: why you need a contact.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Ask you to pick a contact" + (args.str("reason")?.let { ": $it" }.orEmpty())

    override suspend fun execute(args: JsonObject): ToolResult {
        val field = field(args.str("field"))
        return when (val r = phone.pickContact(field)) {
            is UserPick.Picked -> ToolResult(format(r.value, field))
            UserPick.Cancelled -> ToolResult("The user closed the contact picker without choosing anyone.")
            UserPick.NotOnScreen -> ToolResult.error(NOT_ON_SCREEN)
        }
    }

    companion object {
        internal fun field(value: String?): ContactField = when (value?.trim()?.lowercase()) {
            null, "", "any" -> ContactField.ANY
            "phone", "number", "phones" -> ContactField.PHONE
            "email", "mail", "emails" -> ContactField.EMAIL
            else -> throw ToolException("field must be \"phone\", \"email\" or \"any\".")
        }

        internal fun format(c: PickedContact, field: ContactField): String = buildString {
            append("The user picked: ").append(c.name.ifBlank { "(no name)" })
            if (field != ContactField.EMAIL) append("\nPhone: ").append(c.phones.joinToString().ifEmpty { "none shared" })
            if (field != ContactField.PHONE) append("\nEmail: ").append(c.emails.joinToString().ifEmpty { "none shared" })
        }
    }
}

internal class PhotosPick(private val phone: PhoneIntegrations) : Tool {
    // Copies files into the workspace.
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ASK
    override val runsAlone = true

    override val spec = ToolSpec(
        DeviceIntegrations.PHOTOS_PICK,
        "Ask the user to pick photos or videos in the system Photo Picker; Locus copies the ones they choose into the " +
            "workspace's inbox/ folder and returns their paths. Locus can't browse the gallery; only what the user picks is shared.",
        ObjectSchema()
            .integer("max", "Most items the user may pick (default 1, at most $MAX).", required = false)
            .string("reason", "One short line shown to the user: what the photos are for.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Ask you to pick up to ${max(args)} photo${if (max(args) == 1) "" else "s"}" + (args.str("reason")?.let { ": $it" }.orEmpty())

    private fun max(args: JsonObject) = (args.int("max") ?: 1).coerceIn(1, MAX)

    override suspend fun execute(args: JsonObject): ToolResult = when (val r = phone.pickPhotos(max(args), FOLDER)) {
        is UserPick.Picked -> if (r.value.isEmpty()) {
            ToolResult("The user closed the Photo Picker without choosing anything.")
        } else {
            ToolResult("The user picked ${r.value.size} item${if (r.value.size == 1) "" else "s"}, now in the workspace:\n" + r.value.joinToString("\n") { "- $it" })
        }
        UserPick.Cancelled -> ToolResult("The user closed the Photo Picker without choosing anything.")
        UserPick.NotOnScreen -> ToolResult.error(NOT_ON_SCREEN)
    }

    companion object {
        const val MAX = 20
        const val FOLDER = "inbox"
    }
}

/** No phone to ask (tests, or a build without a screen): every request answers that Locus isn't on screen. */
object NoPhone : PhoneIntegrations {
    override suspend fun calendar(from: Long, to: Long, limit: Int): UserPick<List<CalendarItem>> = UserPick.NotOnScreen
    override suspend fun pickContact(field: ContactField): UserPick<PickedContact> = UserPick.NotOnScreen
    override suspend fun pickPhotos(max: Int, folder: String): UserPick<List<String>> = UserPick.NotOnScreen
}

/** No calendar provider (tests, or a build without one): no access, no calendars. */
object NoCalendar : CalendarData {
    override fun hasWriteAccess() = false
    override fun writableCalendars(): List<WritableCalendar> = emptyList()
    override fun event(id: Long): StoredEvent? = null
    override fun insert(calendarId: Long, event: NewEvent): Long = error("No calendar on this build.")
    override fun update(event: StoredEvent, change: EventChange) = error("No calendar on this build.")
    override fun delete(id: Long) = error("No calendar on this build.")
}

/** Reminders without alarms (tests, previews): nothing is scheduled. */
object NoReminderAlarms : ReminderAlarms {
    override fun exactAllowed() = false
    override fun schedule(reminder: dev.locus.core.model.Reminder) = Unit
    override fun cancel(id: String) = Unit
}

/** Reminders without notifications (tests, previews). */
object NoReminderNotifier : ReminderNotifier {
    override fun canNotify() = true
    override fun show(reminder: dev.locus.core.model.Reminder, late: Boolean) = Unit
    override fun dismiss(id: String) = Unit
}
