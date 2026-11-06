package dev.locus.core.data.device

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.PhoneSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The calendar provider for the calendar write tools (docs/design/phone-integrations.md). Follows the
 * CalendarContract rules (developer.android.com/reference/android/provider/CalendarContract.Events): an
 * insert carries CALENDAR_ID, DTSTART and EVENT_TIMEZONE, with DTEND for a one-off or DURATION plus RRULE
 * for a repeating event; an all-day event is stored in UTC at midnight boundaries; an app's delete marks
 * the event deleted and the sync adapter removes it on the server. The resolver and permission check are
 * injectable for tests (PhoneAndroidTest).
 */
@Singleton
class AndroidCalendar(private val resolver: () -> ContentResolver, private val granted: () -> Boolean) : CalendarData {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { context.contentResolver },
        {
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        },
    )

    override fun hasWriteAccess(): Boolean = granted()

    override fun writableCalendars(): List<WritableCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        // Contributor access or more ("Full access to modify the calendar"), shown and synced to the phone.
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.SYNC_EVENTS} = 1"
        val out = mutableListOf<WritableCalendar>()
        resolver().query(CalendarContract.Calendars.CONTENT_URI, projection, selection, arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                out += WritableCalendar(c.getLong(0), c.getString(1).orEmpty().ifBlank { "Calendar" }, c.getString(2).orEmpty(), primary = !c.isNull(3) && c.getInt(3) != 0)
            }
        }
        return out.sortedWith(compareByDescending<WritableCalendar> { it.primary }.thenBy { it.name.lowercase() })
    }

    override fun event(id: Long): StoredEvent? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DELETED,
        )
        val event = resolver().query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            if (!c.isNull(10) && c.getInt(10) != 0) return@use null
            val start = c.getLong(3)
            val end = if (!c.isNull(4)) c.getLong(4) else start + (parseDuration(c.getString(5)) ?: 3_600_000L)
            StoredEvent(
                id = c.getLong(0),
                calendarId = c.getLong(1),
                title = c.getString(2).orEmpty(),
                start = start,
                end = end,
                allDay = c.getInt(6) != 0,
                location = c.getString(7).orEmpty(),
                description = c.getString(8).orEmpty(),
                rrule = c.getString(9).orEmpty(),
            )
        } ?: return null
        return event.copy(attendees = attendees(id))
    }

    private fun attendees(eventId: Long): List<String> {
        val out = mutableListOf<String>()
        resolver().query(
            CalendarContract.Attendees.CONTENT_URI,
            arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { out += it } }
        return out.distinct()
    }

    override fun insert(calendarId: Long, event: NewEvent): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.start)
            put(CalendarContract.Events.EVENT_TIMEZONE, event.zone)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            if (event.location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, event.location)
            if (event.notes.isNotEmpty()) put(CalendarContract.Events.DESCRIPTION, event.notes)
            if (event.rrule.isNotEmpty()) {
                put(CalendarContract.Events.RRULE, event.rrule)
                put(CalendarContract.Events.DURATION, rfcDuration(event.start, event.end, event.allDay))
            } else {
                put(CalendarContract.Events.DTEND, event.end)
            }
            if (event.attendees.isNotEmpty()) put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
            if (event.reminderMinutes != null) put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = resolver().insert(CalendarContract.Events.CONTENT_URI, values) ?: error("The calendar didn't accept the event.")
        val id = ContentUris.parseId(uri)
        addAttendees(id, event.attendees)
        event.reminderMinutes?.let { minutes ->
            resolver().insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        }
        return id
    }

    override fun update(event: StoredEvent, change: EventChange) {
        val values = ContentValues().apply {
            change.title?.let { put(CalendarContract.Events.TITLE, it) }
            change.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            change.notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            change.rrule?.let { put(CalendarContract.Events.RRULE, it) }
            change.times?.let { t ->
                put(CalendarContract.Events.DTSTART, t.start)
                put(CalendarContract.Events.EVENT_TIMEZONE, t.zone)
                put(CalendarContract.Events.ALL_DAY, if (t.allDay) 1 else 0)
                // "Updating an event must respect the same rules as inserting": a series keeps DURATION, a one-off DTEND.
                if (event.recurring || change.rrule != null) {
                    put(CalendarContract.Events.DURATION, rfcDuration(t.start, t.end, t.allDay))
                } else {
                    put(CalendarContract.Events.DTEND, t.end)
                }
            }
            if (change.addAttendees.isNotEmpty()) put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1)
        }
        if (values.size() > 0) resolver().update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id), values, null, null)
        addAttendees(event.id, change.addAttendees)
    }

    private fun addAttendees(eventId: Long, emails: List<String>) {
        emails.forEach { email ->
            resolver().insert(
                CalendarContract.Attendees.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, eventId)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                },
            )
        }
    }

    override fun delete(id: Long) {
        // Not as a sync adapter: the provider sets DELETED and the sync adapter removes it on the server.
        resolver().delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
    }

    companion object {
        private val DURATION = Regex("""^[+-]?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

        /** An RFC 2445 DURATION ("P3600S", "PT1H30M", "P1D") in milliseconds; null when it can't be read. */
        fun parseDuration(text: String?): Long? {
            val m = DURATION.matchEntire(text?.trim()?.uppercase() ?: return null) ?: return null
            val (w, d, h, min, s) = m.destructured
            fun n(x: String) = x.toLongOrNull() ?: 0L
            val ms = (((n(w) * 7 + n(d)) * 24 + n(h)) * 60 + n(min)) * 60_000L + n(s) * 1000L
            return ms.takeIf { it > 0 }
        }
    }
}

/** Settings → Phone as the calendar tools read and save it. */
class SettingsCalendarChoice @Inject constructor(private val settings: SettingsRepository) : CalendarChoice {
    override suspend fun current(): PhoneSettings = settings.current().phone

    override suspend fun save(calendar: WritableCalendar) {
        settings.setPhone { it.copy(calendarId = calendar.id, calendarName = calendar.name, calendarAccount = calendar.account) }
    }
}
