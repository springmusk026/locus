package dev.locus.core.data.device

import dev.locus.core.agent.ApprovalScopes
import dev.locus.core.agent.Taint
import dev.locus.core.agent.ToolDiscovery
import dev.locus.core.agent.ToolException
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.DeviceIntegrations
import dev.locus.core.model.PhoneSettings
import dev.locus.core.model.ToolCapability
import dev.locus.core.model.ToolGroup
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** calendar_create, calendar_update and calendar_delete over a fake calendar provider (docs/design/phone-integrations.md). */
class CalendarWriteToolsTest {
    private val zone = ZoneId.of("Europe/Lisbon")
    private val now = LocalDateTime.of(2026, 11, 6, 9, 0).atZone(zone).toInstant().toEpochMilli()

    private val personal = WritableCalendar(1, "Personal", "ana@example.com", primary = true)
    private val work = WritableCalendar(2, "Work", "ana@work.example")

    /** The provider, in memory. */
    private class FakeCalendar(var access: Boolean = true, val calendars: List<WritableCalendar>) : CalendarData {
        val events = linkedMapOf<Long, StoredEvent>()
        val inserted = mutableListOf<Pair<Long, NewEvent>>()
        val changes = mutableListOf<Pair<Long, EventChange>>()
        val deleted = mutableListOf<Long>()
        private var next = 100L

        override fun hasWriteAccess() = access
        override fun writableCalendars() = calendars
        override fun event(id: Long) = events[id]
        override fun insert(calendarId: Long, event: NewEvent): Long {
            inserted += calendarId to event
            val id = next++
            events[id] = StoredEvent(id, calendarId, event.title, event.start, event.end, event.allDay, event.location, event.notes, event.rrule, event.attendees)
            return id
        }
        override fun update(event: StoredEvent, change: EventChange) {
            changes += event.id to change
            events[event.id] = event.copy(
                title = change.title ?: event.title,
                start = change.times?.start ?: event.start,
                end = change.times?.end ?: event.end,
                attendees = event.attendees + change.addAttendees,
            )
        }
        override fun delete(id: Long) {
            deleted += id
            events.remove(id)
        }
    }

    /** The phone's answer to the permission prompt. */
    private class Phone(var answer: UserPick<Unit> = UserPick.Picked(Unit), val onGrant: () -> Unit = {}) : PhoneIntegrations {
        var asked = 0
        override suspend fun calendar(from: Long, to: Long, limit: Int): UserPick<List<CalendarItem>> = UserPick.Cancelled
        override suspend fun pickContact(field: ContactField): UserPick<PickedContact> = UserPick.Cancelled
        override suspend fun pickPhotos(max: Int, folder: String): UserPick<List<String>> = UserPick.Cancelled
        override suspend fun calendarWriteAccess(): UserPick<Unit> = answer.also { asked++; if (it is UserPick.Picked) onGrant() }
    }

    private class Choice(var settings: PhoneSettings = PhoneSettings()) : CalendarChoice {
        override suspend fun current() = settings
        override suspend fun save(calendar: WritableCalendar) {
            settings = PhoneSettings(calendar.id, calendar.name, calendar.account)
        }
    }

    private class Setup(val data: FakeCalendar, val phone: Phone, val choice: Choice, val tools: Map<String, dev.locus.core.agent.Tool>)

    private fun setup(access: Boolean = true, picked: WritableCalendar? = null, answer: UserPick<Unit> = UserPick.Picked(Unit)): Setup {
        val data = FakeCalendar(access, listOf(personal, work))
        val phone = Phone(answer) { data.access = true }
        val choice = Choice(picked?.let { PhoneSettings(it.id, it.name, it.account) } ?: PhoneSettings())
        return Setup(data, phone, choice, calendarWriteTools(phone, data, choice, zone = { zone }, now = { now }).associateBy { it.spec.name })
    }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        },
    )

    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val create get() = DeviceIntegrations.CALENDAR_CREATE

    @Test
    fun `create writes the event to the default calendar in the phone's zone and saves that choice`() = runTest {
        val s = setup()
        val tool = s.tools.getValue(create)
        val call = args("title" to "Dentist", "start" to "2026-11-10T15:30", "location" to "Rua Augusta", "reminder_minutes" to 30)
        assertNull(tool.precheck(call))
        val result = tool.execute(call)
        assertFalse(result.isError, result.content)
        val (calendarId, event) = s.data.inserted.single()
        assertEquals(personal.id, calendarId)
        assertEquals(at(10, 15, 30), event.start)
        assertEquals(at(10, 16, 30), event.end) // an hour by default
        assertEquals("Europe/Lisbon", event.zone)
        assertEquals(30, event.reminderMinutes)
        assertEquals(1L, s.choice.settings.calendarId) // the default is remembered in Settings → Phone
        assertTrue("Personal" in result.content && "event id 100" in result.content, result.content)
        // No guests: nothing forces a confirmation, and "this calendar" can be remembered.
        assertNull(tool.mustConfirm(call))
        val scope = assertNotNull(tool.rememberScope(call))
        assertEquals(ApprovalScopeKind.CALENDAR, scope.kind)
        assertEquals("1:Personal", scope.value)
    }

    @Test
    fun `all-day events are UTC midnights with an exclusive end, and offsets are honoured`() = runTest {
        val s = setup(picked = work)
        val tool = s.tools.getValue(create)
        tool.execute(args("title" to "Conference", "start" to "2026-11-12", "end" to "2026-11-13", "all_day" to true))
        val allDay = s.data.inserted.last().second
        assertEquals(work.id, s.data.inserted.last().first)
        assertEquals(LocalDate.of(2026, 11, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay.start)
        assertEquals(LocalDate.of(2026, 11, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay.end)
        assertEquals("UTC", allDay.zone)
        tool.execute(args("title" to "Call NY", "start" to "2026-11-10T15:00-05:00", "duration_minutes" to 45))
        val call = s.data.inserted.last().second
        assertEquals(java.time.OffsetDateTime.parse("2026-11-10T15:00-05:00").toInstant().toEpochMilli(), call.start)
        assertEquals(call.start + 45 * 60_000L, call.end)
    }

    @Test
    fun `bad times and rules are refused before anyone is asked`() = runTest {
        val tool = setup().tools.getValue(create)
        assertTrue(tool.precheck(args("title" to "X", "start" to "next tuesday"))!!.contains("ISO"))
        assertTrue(tool.precheck(args("title" to "X", "start" to "2026-11-10T15:00", "end" to "2026-11-10T14:00"))!!.contains("after the start"))
        assertTrue(tool.precheck(args("title" to "X", "start" to "2019-01-01T10:00"))!!.contains("a year ago"))
        assertTrue(tool.precheck(args("title" to "X", "start" to "2026-11-10T15:00", "recurrence" to "FREQ=HOURLY"))!!.contains("FREQ"))
        assertTrue(tool.precheck(args("title" to "X", "start" to "2026-11-10T15:00", "recurrence" to "FREQ=WEEKLY;BYSETPOS=1"))!!.contains("isn't supported"))
        assertTrue(tool.precheck(args("title" to "X", "start" to "2026-11-10T15:00", "guests" to "not-an-email"))!!.contains("email"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10", Recurrence.normalize("RRULE:freq=weekly;byday=MO,WE;count=10"))
        assertEquals("every week on MO,WE, 10 times", describeRule("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10"))
        assertFailsWith<ToolException> { Recurrence.normalize("FREQ=DAILY;COUNT=3;UNTIL=20261231") }
    }

    @Test
    fun `a repeating event is stored with its rule`() = runTest {
        val s = setup()
        s.tools.getValue(create).execute(args("title" to "Standup", "start" to "2026-11-09T09:30", "duration_minutes" to 15, "recurrence" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
        val e = s.data.inserted.single().second
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", e.rrule)
        assertEquals("P900S", rfcDuration(e.start, e.end, e.allDay))
        assertEquals("P2D", rfcDuration(0, 2 * 86_400_000L, true))
    }

    @Test
    fun `guests force a confirmation that names them, and are never remembered`() = runTest {
        val s = setup(picked = personal)
        val tool = s.tools.getValue(create)
        val call = args("title" to "Lunch", "start" to "2026-11-10T13:00", "guests" to "bo@example.com, Cy@Example.com")
        val reason = assertNotNull(tool.mustConfirm(call))
        assertTrue("bo@example.com" in reason && "cy@example.com" in reason && "invitation" in reason, reason)
        assertNull(tool.rememberScope(call))
        val preview = assertNotNull(tool.preview(call))
        val invites = preview.rows.single { it.label == "Invites" }
        assertTrue(invites.outward)
        assertEquals("bo@example.com, cy@example.com", invites.value)
        assertEquals("Lunch", preview.rows.single { it.label == "Title" }.value)
        assertTrue(preview.rows.single { it.label == "Calendar" }.value.startsWith("Personal"))
        tool.execute(call)
        assertEquals(listOf("bo@example.com", "cy@example.com"), s.data.inserted.single().second.attendees)
        // Adding guests to an existing event confirms the same way.
        val update = s.tools.getValue(DeviceIntegrations.CALENDAR_UPDATE)
        assertNotNull(update.mustConfirm(args("event_id" to 100, "add_guests" to "dee@example.com")))
    }

    @Test
    fun `a tainted run asks before any calendar write`() = runTest {
        val s = setup(picked = personal)
        val sources = setOf("web_read")
        DeviceIntegrations.CALENDAR_WRITES.forEach { name ->
            assertEquals(ToolCapability.OUTWARD, ToolCapability.of(name))
            val reason = Taint.reason(s.tools.getValue(name), args("title" to "X", "start" to "2026-11-10T13:00", "event_id" to 1), sources, emptySet())
            assertTrue(reason?.startsWith("Tainted run") == true, "$name: $reason")
        }
        // Untainted, nothing extra.
        assertNull(Taint.reason(s.tools.getValue(create), args("title" to "X", "start" to "2026-11-10T13:00"), emptySet(), emptySet()))
    }

    @Test
    fun `permission denied and off screen are said plainly, and nothing is written`() = runTest {
        val denied = setup(access = false, answer = UserPick.Cancelled)
        val call = args("title" to "Dentist", "start" to "2026-11-10T15:30")
        val reason = assertNotNull(denied.tools.getValue(create).precheck(call))
        assertTrue("didn't allow" in reason && "Settings → Phone" in reason, reason)
        assertEquals(1, denied.phone.asked)
        assertTrue(denied.tools.getValue(create).execute(call).isError)
        assertTrue(denied.data.inserted.isEmpty())

        val away = setup(access = false, answer = UserPick.NotOnScreen)
        assertTrue(away.tools.getValue(create).precheck(call)!!.contains("isn't on screen"))

        // Granted at the prompt: it goes ahead.
        val granted = setup(access = false)
        assertNull(granted.tools.getValue(create).precheck(call))
        assertFalse(granted.tools.getValue(create).execute(call).isError)
    }

    @Test
    fun `update changes only the given fields of an event in the allowed calendar`() = runTest {
        val s = setup(picked = personal)
        s.data.events[7] = StoredEvent(7, personal.id, "Dentist", at(10, 15), at(10, 16), false, "Rua Augusta")
        val tool = s.tools.getValue(DeviceIntegrations.CALENDAR_UPDATE)
        val call = args("event_id" to 7, "start" to "2026-11-11T09:00")
        assertNull(tool.precheck(call))
        val preview = assertNotNull(tool.preview(call))
        assertTrue(preview.rows.any { it.label == "Event" && "Dentist" in it.value })
        assertTrue(preview.rows.any { it.label == "New time" && "09:00–10:00" in it.value }, preview.toString())
        tool.execute(call)
        val (id, change) = s.data.changes.single()
        assertEquals(7L, id)
        assertEquals(at(11, 9), change.times!!.start)
        assertEquals(at(11, 10), change.times!!.end) // it keeps its length
        assertNull(change.title)
        assertNull(change.location)
        assertEquals("1:Personal", tool.rememberScope(call)?.value)
        // Nothing to change.
        assertTrue(tool.precheck(args("event_id" to 7))!!.contains("Nothing to change"))
    }

    @Test
    fun `events outside the allowed calendar are read-only`() = runTest {
        val s = setup(picked = personal)
        s.data.events[8] = StoredEvent(8, work.id, "Board meeting", at(10, 10), at(10, 11), false)
        val reason = assertNotNull(s.tools.getValue(DeviceIntegrations.CALENDAR_UPDATE).precheck(args("event_id" to 8, "title" to "Moved")))
        assertTrue("isn't in “Personal”" in reason, reason)
        assertNotNull(s.tools.getValue(DeviceIntegrations.CALENDAR_DELETE).precheck(args("event_id" to 8)))
        assertTrue(s.tools.getValue(DeviceIntegrations.CALENDAR_DELETE).precheck(args("event_id" to 999))!!.contains("no event with id 999"))
    }

    @Test
    fun `a repeating event changes or goes only as a whole series, never one ambiguous occurrence`() = runTest {
        val s = setup(picked = personal)
        s.data.events[9] = StoredEvent(9, personal.id, "Standup", at(9, 9, 30), at(9, 9, 45), false, rrule = "FREQ=WEEKLY;BYDAY=MO")
        val update = s.tools.getValue(DeviceIntegrations.CALENDAR_UPDATE)
        val refused = assertNotNull(update.precheck(args("event_id" to 9, "title" to "Sync")))
        assertTrue("repeats (every week on MO)" in refused && "series: true" in refused && "calendar app" in refused, refused)
        assertFailsWith<ToolException> { update.execute(args("event_id" to 9, "title" to "Sync")) }
        assertTrue(s.data.changes.isEmpty())
        assertNull(update.precheck(args("event_id" to 9, "title" to "Sync", "series" to true)))
        update.execute(args("event_id" to 9, "title" to "Sync", "series" to true))
        assertEquals("Sync", s.data.changes.single().second.title)

        val delete = s.tools.getValue(DeviceIntegrations.CALENDAR_DELETE)
        assertNotNull(delete.precheck(args("event_id" to 9)))
        assertTrue(delete.preview(args("event_id" to 9, "series" to true))!!.rows.any { it.label == "Repeats" && "every occurrence" in it.value })
        delete.execute(args("event_id" to 9, "series" to true))
        assertEquals(listOf(9L), s.data.deleted)
    }

    @Test
    fun `delete always confirms, shows the event, and is never remembered`() = runTest {
        val s = setup(picked = personal)
        s.data.events[5] = StoredEvent(5, personal.id, "Dentist", at(10, 15), at(10, 16), false, "Rua Augusta", attendees = listOf("dr@clinic.example"))
        val tool = s.tools.getValue(DeviceIntegrations.CALENDAR_DELETE)
        val call = args("event_id" to 5)
        assertNotNull(tool.mustConfirm(call))
        assertNull(tool.rememberScope(call))
        assertNull(ApprovalScopes.derive(DeviceIntegrations.CALENDAR_DELETE, call))
        val rows = assertNotNull(tool.preview(call)).rows.associateBy { it.label }
        assertEquals("Dentist", rows.getValue("Deletes").value)
        assertTrue("15:00–16:00" in rows.getValue("When").value)
        assertEquals("Rua Augusta", rows.getValue("Where").value)
        assertTrue(rows.getValue("Guests").outward)
        val result = tool.execute(call)
        assertTrue("Deleted “Dentist”" in result.content, result.content)
        assertEquals(listOf(5L), s.data.deleted)
    }

    @Test
    fun `a picked calendar that has gone is reported, not silently replaced`() = runTest {
        val s = setup(picked = WritableCalendar(42, "Old", "gone@example.com"))
        val reason = assertNotNull(s.tools.getValue(create).precheck(args("title" to "X", "start" to "2026-11-10T13:00")))
        assertTrue("“Old”" in reason && "Settings → Phone" in reason, reason)
    }

    @Test
    fun `they ask by default, sit with the phone apps and are found on demand`() {
        val s = setup()
        s.tools.values.forEach { t ->
            assertEquals(ToolPolicy.ASK, t.defaultPolicy, t.spec.name)
            assertEquals(ToolGroup.PHONE, ToolGroup.of(t.spec.name))
            assertTrue(t.spec.name in ToolDiscovery.DISCOVERY_ONLY)
            assertFalse(t.spec.name in ToolDiscovery.CORE_V2)
        }
    }
}
