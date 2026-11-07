package dev.locus.core.ui

import dev.locus.core.data.device.AndroidCalendar
import dev.locus.core.data.device.AndroidReminderAlarms
import dev.locus.core.data.device.AndroidReminderNotifier
import dev.locus.core.data.device.EventChange
import dev.locus.core.data.device.EventTimes
import dev.locus.core.data.device.NewContact
import dev.locus.core.data.device.NewEvent
import dev.locus.core.data.device.PhoneIntents
import dev.locus.core.data.device.ReminderIntents
import dev.locus.core.data.device.ReminderReceiver

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.Reminder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android side of the phone integrations on Robolectric (docs/design/phone-integrations.md). It lives
 * here, where Robolectric already is, rather than in core/data: Robolectric brings BouncyCastle, which breaks
 * core/data's in-process SSH server tests. It covers the
 * contact-insert and Save-as intents, the sensitive clip, the calendar provider's ContentValues through a
 * stand-in provider, and the reminder alarms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the contact intent opens Contacts' insert screen with the details and no permission`() {
        val intent = PhoneIntents.insertContact(NewContact("Ana", listOf("+351 912", "213"), listOf("ana@x.example"), company = "Pipes", note = "Boiler"))
        assertEquals(ContactsContract.Intents.Insert.ACTION, intent.action)
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(ContactsContract.RawContacts.CONTENT_TYPE, intent.type)
        assertEquals("Ana", intent.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("+351 912", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals("213", intent.getStringExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE))
        assertNull(intent.getStringExtra(ContactsContract.Intents.Insert.TERTIARY_PHONE))
        assertEquals("ana@x.example", intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
        assertEquals("Pipes", intent.getStringExtra(ContactsContract.Intents.Insert.COMPANY))
        assertEquals("Boiler", intent.getStringExtra(ContactsContract.Intents.Insert.NOTES))
    }

    @Test
    fun `save as is the system create-document picker with the suggested name and type`() {
        val intent = PhoneIntents.saveAs("Q3 report.pdf", "application/pdf")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("application/pdf", intent.type)
        assertEquals("Q3 report.pdf", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun `a copied clip is marked sensitive`() {
        val clip = PhoneIntents.sensitiveClip("Locus", "1234")
        assertEquals("1234", clip.getItemAt(0).text)
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    /** Records what the calendar code writes, and answers the calendar list and one event. */
    class FakeCalendarProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queries += uri to (selectionArgs?.toList() ?: emptyList())
            return when {
                uri.toString().startsWith(CalendarContract.Calendars.CONTENT_URI.toString()) -> MatrixCursor(projection).apply {
                    addRow(arrayOf<Any?>(3L, "Personal", "ana@example.com", 1))
                    addRow(arrayOf<Any?>(4L, "Shared", "team@example.com", 0))
                }
                uri.toString().startsWith(CalendarContract.Events.CONTENT_URI.toString()) -> MatrixCursor(projection).apply {
                    // A weekly series: DTEND empty, DURATION set.
                    addRow(arrayOf<Any?>(9L, 3L, "Standup", 1_000_000L, null, "P900S", 0, "", "", "FREQ=WEEKLY", 0))
                }
                uri.toString().startsWith(CalendarContract.Attendees.CONTENT_URI.toString()) -> MatrixCursor(projection).apply { addRow(arrayOf<Any?>("Bo@Example.com")) }
                else -> null
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts += uri to ContentValues(values)
            return ContentUris.withAppendedId(uri, 77)
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1.also { deletes += uri }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 1.also { updates += uri to ContentValues(values) }

        companion object {
            val inserts = mutableListOf<Pair<Uri, ContentValues>>()
            val updates = mutableListOf<Pair<Uri, ContentValues>>()
            val deletes = mutableListOf<Uri>()
            val queries = mutableListOf<Pair<Uri, List<String>>>()
            fun reset() { inserts.clear(); updates.clear(); deletes.clear(); queries.clear() }
        }
    }

    private fun calendar(): AndroidCalendar {
        FakeCalendarProvider.reset()
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        return AndroidCalendar({ context.contentResolver }, { true })
    }

    @Test
    fun `a one-off event is inserted with DTEND, its zone, guests and reminder`() {
        val cal = calendar()
        val id = cal.insert(3, NewEvent("Dentist", 1_000_000, 4_600_000, false, "Europe/Lisbon", location = "Rua Augusta", attendees = listOf("bo@example.com"), reminderMinutes = 30))
        assertEquals(77L, id)
        val (eventUri, event) = FakeCalendarProvider.inserts.first()
        assertEquals(CalendarContract.Events.CONTENT_URI, eventUri)
        assertEquals(3L, event.getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals(1_000_000L, event.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(4_600_000L, event.getAsLong(CalendarContract.Events.DTEND))
        assertEquals("Europe/Lisbon", event.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertFalse(event.containsKey(CalendarContract.Events.DURATION))
        assertFalse(event.containsKey(CalendarContract.Events.RRULE))
        val attendee = FakeCalendarProvider.inserts.single { it.first == CalendarContract.Attendees.CONTENT_URI }.second
        assertEquals(77L, attendee.getAsLong(CalendarContract.Attendees.EVENT_ID))
        assertEquals("bo@example.com", attendee.getAsString(CalendarContract.Attendees.ATTENDEE_EMAIL))
        assertEquals(CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, attendee.getAsInteger(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP))
        val reminder = FakeCalendarProvider.inserts.single { it.first == CalendarContract.Reminders.CONTENT_URI }.second
        assertEquals(30, reminder.getAsInteger(CalendarContract.Reminders.MINUTES))
        assertEquals(CalendarContract.Reminders.METHOD_ALERT, reminder.getAsInteger(CalendarContract.Reminders.METHOD))
    }

    @Test
    fun `a repeating event gets RRULE and DURATION instead of DTEND, and all-day ones are UTC`() {
        val cal = calendar()
        cal.insert(3, NewEvent("Holiday", 0, 86_400_000L, true, "UTC", rrule = "FREQ=YEARLY"))
        val event = FakeCalendarProvider.inserts.first().second
        assertEquals("FREQ=YEARLY", event.getAsString(CalendarContract.Events.RRULE))
        assertEquals("P1D", event.getAsString(CalendarContract.Events.DURATION))
        assertFalse(event.containsKey(CalendarContract.Events.DTEND))
        assertEquals("UTC", event.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(1, event.getAsInteger(CalendarContract.Events.ALL_DAY))
    }

    @Test
    fun `reading, updating and deleting go through the event's own row`() {
        val cal = calendar()
        val calendars = cal.writableCalendars()
        assertEquals(listOf("Personal", "Shared"), calendars.map { it.name })
        assertTrue(calendars.first().primary)
        // Contributor access or more is asked for.
        assertEquals(listOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), FakeCalendarProvider.queries.first().second)
        val event = cal.event(9)!!
        assertTrue(event.recurring)
        assertEquals(1_000_000L + 900_000L, event.end)
        assertEquals(listOf("bo@example.com"), event.attendees)
        cal.update(event, EventChange(title = "Sync", times = EventTimes(2_000_000, 2_600_000, false, "Europe/Lisbon")))
        val (uri, values) = FakeCalendarProvider.updates.single()
        assertEquals(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 9), uri)
        assertEquals("Sync", values.getAsString(CalendarContract.Events.TITLE))
        // Still a series: DURATION, never DTEND.
        assertEquals("P600S", values.getAsString(CalendarContract.Events.DURATION))
        assertFalse(values.containsKey(CalendarContract.Events.DTEND))
        cal.delete(9)
        assertEquals(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 9), FakeCalendarProvider.deletes.single())
        assertEquals(5_400_000L, AndroidCalendar.parseDuration("PT1H30M"))
        assertEquals(86_400_000L, AndroidCalendar.parseDuration("P1D"))
        assertNull(AndroidCalendar.parseDuration("soon"))
    }

    @Test
    fun `reminder alarms are set and cancelled, exact only when allowed`() {
        val alarms = AndroidReminderAlarms(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(alarms.exactAllowed())
        alarms.schedule(Reminder("r1", "Call mum", System.currentTimeMillis() + 60_000))
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        // Setting the same reminder again replaces its alarm.
        alarms.schedule(Reminder("r1", "Call mum", System.currentTimeMillis() + 120_000))
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(alarms.exactAllowed())
        alarms.schedule(Reminder("r2", "Tea", System.currentTimeMillis() + 60_000))
        assertEquals(2, shadowOf(manager).scheduledAlarms.size)
        alarms.cancel("r1")
        assertEquals(1, shadowOf(manager).scheduledAlarms.size)
        val fire = ReminderIntents.intent(context, ReminderIntents.ACTION_FIRE, "r2")
        assertEquals("r2", fire.getStringExtra(ReminderIntents.EXTRA_ID))
        assertEquals(ReminderReceiver::class.java.name, fire.component?.className)
    }

    @Test
    fun `a reminder's notification stays up and a removal Locus didn't make can't delete the reminder`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifier = AndroidReminderNotifier(context)
        val stretch = Reminder("r-stretch", "Stretch", System.currentTimeMillis())
        val tea = Reminder("r-tea", "Tea", System.currentTimeMillis())
        notifier.show(stretch, late = false)
        notifier.show(tea, late = false)
        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size, "Both reminders show, side by side")
        val n = shadowOf(manager).getNotification(ReminderIntents.notificationId(stretch.id))
        assertEquals(AndroidReminderNotifier.CHANNEL, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(AndroidReminderNotifier.CHANNEL).importance)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        assertEquals(listOf("Snooze 10 min", "Done"), n.actions.map { it.title.toString() })
        // The regression: the delete intent was Done, so Android removing the notification (a bundle cleared,
        // Clear all, a regroup) deleted the reminder before the user saw it. A removal now leaves it as shown.
        assertNull(n.deleteIntent)
        // Not in a group of Locus's agent notifications, and never one of their ids.
        assertNull(n.group)
        assertTrue(ReminderIntents.notificationId(stretch.id) !in setOf(1, 2, 3))
        // Done on one leaves the other.
        notifier.dismiss(stretch.id)
        assertEquals(listOf("Tea"), shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE) })
    }
}
