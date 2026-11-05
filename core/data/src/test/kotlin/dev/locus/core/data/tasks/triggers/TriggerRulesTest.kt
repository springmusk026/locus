package dev.locus.core.data.tasks.triggers

import dev.locus.core.model.QuietHours
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.TriggerSource
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trigger's filter, rate limit, quiet hours and model (docs/design/event-triggers.md). */
class TriggerRulesTest {
    private val utc = ZoneOffset.UTC
    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, 5, hour, minute, 0, 0, utc).toInstant().toEpochMilli()

    private fun event(source: TriggerSource, text: String, priority: Int? = null, calendar: String? = null, from: String? = null, name: String? = null, app: String? = null) =
        IncomingEvent(source, "k", 0, text, text, text, priority = priority, calendar = calendar, from = from, eventName = name, app = app)

    @Test
    fun `words must all appear, in any case`() {
        val t = TaskTrigger(TriggerSource.NTFY, contains = "backup FAILED")
        assertNull(TriggerRules.mismatch(t, event(TriggerSource.NTFY, "Nightly Backup failed on homelab")))
        assertEquals("it doesn't mention “FAILED”", TriggerRules.mismatch(t, event(TriggerSource.NTFY, "Backup finished")))
        assertNull(TriggerRules.mismatch(TaskTrigger(TriggerSource.NTFY), event(TriggerSource.NTFY, "anything")))
    }

    @Test
    fun `ntfy priority counts its default when a message sets none`() {
        val t = TaskTrigger(TriggerSource.NTFY, minPriority = 4)
        assertNotNull(TriggerRules.mismatch(t, event(TriggerSource.NTFY, "x")))
        assertNotNull(TriggerRules.mismatch(t, event(TriggerSource.NTFY, "x", priority = 3)))
        assertNull(TriggerRules.mismatch(t, event(TriggerSource.NTFY, "x", priority = 5)))
        assertNull(TriggerRules.mismatch(TaskTrigger(TriggerSource.NTFY, minPriority = 3), event(TriggerSource.NTFY, "x")))
    }

    @Test
    fun `calendar, sender and event name filters`() {
        val cal = TaskTrigger(TriggerSource.CALENDAR, calendar = "Work")
        assertNull(TriggerRules.mismatch(cal, event(TriggerSource.CALENDAR, "Standup", calendar = "work")))
        assertEquals("it's in another calendar", TriggerRules.mismatch(cal, event(TriggerSource.CALENDAR, "Standup", calendar = "Family")))

        val mail = TaskTrigger(TriggerSource.MAIL, from = "@github.com")
        assertNull(TriggerRules.mismatch(mail, event(TriggerSource.MAIL, "PR merged", from = "GitHub <noreply@github.com>")))
        assertEquals("it's from someone else", TriggerRules.mismatch(mail, event(TriggerSource.MAIL, "PR merged", from = "eve@phish.example")))

        val app = TaskTrigger(TriggerSource.APP, eventName = "left-home")
        assertNull(TriggerRules.mismatch(app, event(TriggerSource.APP, "x", name = "LEFT-HOME")))
        assertNotNull(TriggerRules.mismatch(app, event(TriggerSource.APP, "x", name = "arrived")))
    }

    @Test
    fun `an app allow-list needs the package Android named`() {
        val open = TaskTrigger(TriggerSource.APP)
        assertTrue(TriggerRules.appAllowed(open, null))
        val listed = TaskTrigger(TriggerSource.APP, allowedApps = listOf("net.dinglisch.android.taskerm"))
        assertTrue(TriggerRules.appAllowed(listed, "net.dinglisch.android.taskerm"))
        assertFalse(TriggerRules.appAllowed(listed, "com.evil"))
        // Android didn't name the caller: an allow-list can't be met.
        assertFalse(TriggerRules.appAllowed(listed, null))
    }

    @Test
    fun `the rate limit, measured from the last run`() {
        val t = TaskTrigger(TriggerSource.NTFY, minGapMinutes = 15)
        val last = at(9)
        assertEquals(TriggerDecision.RATE_LIMITED, TriggerRules.decide(t, true, at(9, 14), utc, last))
        assertEquals(TriggerDecision.FIRED, TriggerRules.decide(t, true, at(9, 15), utc, last))
        assertEquals(TriggerDecision.FIRED, TriggerRules.decide(t, true, at(9, 1), utc, lastFiredAt = null))
        assertEquals("At most one run every 15 minutes; the next can start in 6 min.", TriggerRules.detail(t, TriggerDecision.RATE_LIMITED, at(9, 9), last))
    }

    @Test
    fun `quiet hours, across midnight, and paused tasks`() {
        val t = TaskTrigger(TriggerSource.NTFY, quietHours = QuietHours(22 * 60, 7 * 60))
        assertEquals(TriggerDecision.QUIET_HOURS, TriggerRules.decide(t, true, at(23), utc, null))
        assertEquals(TriggerDecision.QUIET_HOURS, TriggerRules.decide(t, true, at(6, 59), utc, null))
        assertEquals(TriggerDecision.FIRED, TriggerRules.decide(t, true, at(7), utc, null))
        assertEquals(TriggerDecision.DISABLED, TriggerRules.decide(t, false, at(12), utc, null))
        // A test runs whatever the state: paused, quiet, rate-limited.
        assertEquals(TriggerDecision.TEST, TriggerRules.decide(t, false, at(23), utc, at(23), test = true))
        assertFalse(QuietHours(9 * 60, 9 * 60).contains(9 * 60))
        assertTrue(QuietHours(13 * 60, 14 * 60).contains(13 * 60 + 30))
    }

    @Test
    fun `times parse the ways people type them`() {
        assertEquals(7 * 60, QuietHours.parse("7"))
        assertEquals(7 * 60 + 30, QuietHours.parse("07:30"))
        assertEquals(7 * 60 + 30, QuietHours.parse("730"))
        assertEquals(22 * 60, QuietHours.parse("2200"))
        assertNull(QuietHours.parse("25:00"))
        assertNull(QuietHours.parse("seven"))
        assertEquals("22:00–07:00", QuietHours(22 * 60, 7 * 60).label)
    }

    @Test
    fun `events found together run once, cut to size`() {
        val events = (1..3).map { IncomingEvent(TriggerSource.NTFY, "k$it", 0, "t$it", "t$it", "message $it") }
        val details = TriggerRules.combine(events)
        assertTrue(details.startsWith("3 events:"), details)
        assertTrue("[3] message 3" in details)
        assertEquals("t1 (+2 more)", TriggerRules.title(events))
        val big = (1..10).map { IncomingEvent(TriggerSource.NTFY, "k$it", 0, "t", "t", "x".repeat(2_000)) }
        val cut = TriggerRules.combine(big)
        assertTrue(cut.length <= TriggerRules.MAX_DETAILS + 100, "${cut.length}")
        assertTrue("more, left out]" in cut)
    }

    @Test
    fun `a trigger checks what it needs, and survives a round trip`() {
        assertNotNull(TaskTrigger(TriggerSource.NTFY, topicUrl = "ntfy.sh/x").problem)
        assertNull(TaskTrigger(TriggerSource.NTFY, topicUrl = "https://ntfy.sh/backups_4f9a").problem)
        assertNotNull(TaskTrigger(TriggerSource.APP, allowedApps = listOf("not a package")).problem)
        assertNull(TaskTrigger(TriggerSource.APP, allowedApps = listOf("net.dinglisch.android.taskerm")).problem)
        assertNotNull(TaskTrigger(TriggerSource.CALENDAR, minGapMinutes = 0).problem)
        val t = TaskTrigger(TriggerSource.CALENDAR, contains = "standup", calendar = "Work", minutesBefore = 30, quietHours = QuietHours(1, 2))
        assertEquals(t, TaskTrigger.fromJson(t.toJson()))
        assertNull(TaskTrigger.fromJson(null))
        assertNull(TaskTrigger.fromJson("{\"source\":\"SATELLITE\"}"))
        assertEquals(listOf("a.b", "c.d"), TaskTrigger.parseApps("a.b, c.d  a.b"))
        assertEquals("30 min before a calendar entry with “standup” in Work", t.summary)
    }

    @Test
    fun `state survives a round trip and reads garbage as new`() {
        val s = TriggerState(cursor = "abc", handled = listOf("1:2"))
        assertEquals(s, TriggerState.fromJson(s.toJson()))
        assertEquals(TriggerState(), TriggerState.fromJson("not json"))
    }
}
