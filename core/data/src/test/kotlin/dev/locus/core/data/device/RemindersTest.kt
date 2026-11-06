package dev.locus.core.data.device

import dev.locus.core.agent.Taint
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.model.DeviceIntegrations
import dev.locus.core.model.Reminder
import dev.locus.core.model.ToolCapability
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.FakeReminderAlarms
import dev.locus.core.testing.FakeReminderNotifier
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** remind_me's reminders: scheduling, firing, snooze, cancel and surviving a reboot (docs/design/phone-integrations.md). */
class RemindersTest {
    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private val zone = ZoneId.of("Europe/Lisbon")
    private var now = LocalDateTime.of(2026, 11, 6, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private fun graph() = TestGraph(scope).also { g -> g.reminders.clock = { now } }

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) })

    @Test
    fun `a reminder is stored and its alarm set for its time`() = runBlocking<Unit> {
        val g = graph()
        val r = g.reminders.add("Call mum", now + 30 * minute, conversationId = "c1")
        assertEquals(now + 30 * minute, g.reminderAlarms.scheduled[r.id])
        val saved = g.settingsRepository.current().reminders.single()
        assertEquals("Call mum", saved.text)
        assertEquals("c1", saved.conversationId)
        assertTrue(saved.upcoming)
        assertFailsWith<IllegalArgumentException> { g.reminders.add("Too late", now - 10 * minute) }
        assertFailsWith<IllegalArgumentException> { g.reminders.add("Too far", now + 400L * 24 * 60 * minute) }
        assertFailsWith<IllegalArgumentException> { g.reminders.add("   ", now + minute) }
    }

    @Test
    fun `firing shows the notification once, with no model run, and marks it shown`() = runBlocking<Unit> {
        val g = graph()
        val r = g.reminders.add("Call mum", now + 30 * minute)
        // Not due yet: an early broadcast does nothing.
        g.reminders.fire(r.id)
        assertTrue(g.reminderNotifier.shown.isEmpty())
        now += 30 * minute
        g.reminders.fire(r.id)
        assertEquals(false, g.reminderNotifier.showing[r.id])
        val saved = g.settingsRepository.current().reminders.single()
        assertEquals(now, saved.shownAt)
        assertFalse(saved.upcoming)
        // A second broadcast for the same alarm doesn't show it twice.
        g.reminders.fire(r.id)
        assertEquals(1, g.reminderNotifier.shown.size)
    }

    @Test
    fun `snooze sets it again ten minutes on, and done removes it`() = runBlocking<Unit> {
        val g = graph()
        val r = g.reminders.add("Stretch", now + minute)
        now += minute
        g.reminders.fire(r.id)
        g.reminders.snooze(r.id)
        assertEquals(now + Reminder.SNOOZE_MS, g.reminderAlarms.scheduled[r.id])
        assertNull(g.reminderNotifier.showing[r.id])
        val snoozed = g.settingsRepository.current().reminders.single()
        assertTrue(snoozed.upcoming)
        assertEquals(1, snoozed.snoozes)
        now += Reminder.SNOOZE_MS
        g.reminders.fire(r.id)
        assertTrue(r.id in g.reminderNotifier.showing)
        g.reminders.done(r.id)
        assertTrue(g.settingsRepository.current().reminders.isEmpty())
        assertTrue(g.reminderNotifier.showing.isEmpty())
        assertTrue(g.reminderAlarms.scheduled.isEmpty())
    }

    @Test
    fun `cancel removes the reminder and its alarm`() = runBlocking<Unit> {
        val g = graph()
        val keep = g.reminders.add("Keep", now + 60 * minute)
        val drop = g.reminders.add("Drop", now + 30 * minute)
        assertEquals("Drop", g.reminders.cancel(drop.id)?.text)
        assertEquals(setOf(keep.id), g.reminderAlarms.scheduled.keys)
        assertEquals(listOf("Keep"), g.settingsRepository.current().reminders.map { it.text })
        assertNull(g.reminders.cancel("nope"))
    }

    @Test
    fun `after a reboot every reminder to come is set again, and one missed while off fires late`() = runBlocking<Unit> {
        val g = graph()
        val missed = g.reminders.add("Missed", now + 10 * minute)
        val later = g.reminders.add("Later", now + 120 * minute)
        // The phone is off for an hour: Android cleared every alarm.
        now += 60 * minute
        val alarmsAfterBoot = FakeReminderAlarms()
        val notifierAfterBoot = FakeReminderNotifier()
        // A new process: the reminders come back from the settings store alone.
        val afterBoot = Reminders(g.settingsRepository, alarmsAfterBoot, notifierAfterBoot).also { it.clock = { now } }
        afterBoot.rescheduleAll()
        assertEquals(mapOf(missed.id to missed.at, later.id to later.at), alarmsAfterBoot.scheduled)
        // The missed one's time has passed, so its alarm goes off at once, and says it's late.
        afterBoot.fire(missed.id)
        assertEquals(true, notifierAfterBoot.showing[missed.id])
        // Shown reminders nobody answered are dropped after a day.
        now += Reminder.KEEP_SHOWN_MS + minute
        afterBoot.rescheduleAll()
        assertEquals(listOf("Later"), g.settingsRepository.current().reminders.map { it.text })
    }

    @Test
    fun `remind_me takes a local time or minutes from now and records its chat`() = runBlocking<Unit> {
        val g = graph()
        val tools = reminderTools(g.reminders, zone = { zone }, now = { now }).associateBy { it.spec.name }
        val remind = tools.getValue(DeviceIntegrations.REMIND_ME)
        val result = withContext(ToolCallContext("chat-1", "call-1", 0, null)) { remind.execute(args("text" to "Call mum", "at" to "2026-11-06T17:00")) }
        assertFalse(result.isError, result.content)
        assertTrue("Fri 6 Nov 2026, 17:00" in result.content, result.content)
        // Exact alarms aren't allowed here: the result says it may be late.
        assertTrue("10 minutes late" in result.content, result.content)
        val saved = g.settingsRepository.current().reminders.single()
        assertEquals(LocalDateTime.of(2026, 11, 6, 17, 0).atZone(zone).toInstant().toEpochMilli(), saved.at)
        assertEquals("chat-1", saved.conversationId)
        remind.execute(args("text" to "Tea", "in_minutes" to 5))
        assertEquals(now + 5 * minute, g.settingsRepository.current().reminders.first { it.text == "Tea" }.at)
        assertFailsWith<ToolException> { remind.execute(args("text" to "Both", "at" to "2026-11-06T17:00", "in_minutes" to 5)) }
        assertFailsWith<ToolException> { remind.execute(args("text" to "Past", "at" to "2026-11-05T17:00")) }
        g.reminderNotifier.enabled = false
        assertTrue("Notifications for Locus are off" in remind.execute(args("text" to "Quiet", "in_minutes" to 1)).content)

        val list = tools.getValue(DeviceIntegrations.REMINDERS_LIST).execute(JsonObject(emptyMap())).content
        assertTrue("Tea" in list && "Call mum" in list, list)
        val id = saved.id.take(8)
        val cancel = tools.getValue(DeviceIntegrations.REMINDER_CANCEL)
        assertTrue("Call mum" in cancel.execute(args("id" to id)).content)
        assertTrue(g.reminders.current().none { it.text == "Call mum" })
    }

    @Test
    fun `reminders stay on the phone, so taint doesn't hold them back`() = runBlocking<Unit> {
        val g = graph()
        val tools = reminderTools(g.reminders).associateBy { it.spec.name }
        assertEquals(ToolCapability.LOCAL, ToolCapability.of(DeviceIntegrations.REMIND_ME))
        assertEquals(ToolPolicy.ALLOW, tools.getValue(DeviceIntegrations.REMIND_ME).defaultPolicy)
        assertEquals(ToolPolicy.ASK, tools.getValue(DeviceIntegrations.REMINDER_CANCEL).defaultPolicy)
        assertNull(Taint.reason(tools.getValue(DeviceIntegrations.REMIND_ME), args("text" to "x", "in_minutes" to 5), setOf("web_read"), emptySet()))
    }
}
