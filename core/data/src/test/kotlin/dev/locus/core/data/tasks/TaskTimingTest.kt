package dev.locus.core.data.tasks

import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A task at a time of day keeps its wall-clock time across daylight saving, and a nearer check isn't dropped. */
class TaskTimingTest {
    private val newYork = ZoneId.of("America/New_York")
    private val lisbon = ZoneId.of("Europe/Lisbon")
    private val seven = 7 * 60

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant()

    private fun local(i: Instant, zone: ZoneId) = i.atZone(zone).toLocalDateTime().toString()

    @Test
    fun `the first run is the next time of day, today's if it's still ahead`() {
        assertEquals(at(newYork, 2026, 3, 5, 7), TaskTiming.first(seven, at(newYork, 2026, 3, 5, 6, 59), newYork))
        assertEquals(at(newYork, 2026, 3, 6, 7), TaskTiming.first(seven, at(newYork, 2026, 3, 5, 7), newYork), "exactly now is already past")
        assertEquals(at(newYork, 2026, 3, 6, 7), TaskTiming.first(seven, at(newYork, 2026, 3, 5, 22), newYork))
    }

    @Test
    fun `when the clocks go forward, 7 o'clock stays 7 o'clock, 23 hours later`() {
        val saturday = at(newYork, 2026, 3, 7, 7)
        val sunday = TaskTiming.next(seven, 1, saturday, saturday.plusSeconds(5), newYork)
        assertEquals("2026-03-08T07:00", local(sunday, newYork))
        assertEquals(Duration.ofHours(23), Duration.between(saturday, sunday))
        // A fixed 1,440-minute period would have run it at 8:00.
        assertEquals("2026-03-08T08:00", local(saturday.plus(Duration.ofMinutes(1_440)), newYork))
    }

    @Test
    fun `when the clocks go back, 7 o'clock stays 7 o'clock, 25 hours later`() {
        val saturday = at(newYork, 2026, 10, 31, 7)
        val sunday = TaskTiming.next(seven, 1, saturday, saturday.plusSeconds(5), newYork)
        assertEquals("2026-11-01T07:00", local(sunday, newYork))
        assertEquals(Duration.ofHours(25), Duration.between(saturday, sunday))
        // A fixed period would have run it at 6:00.
        assertEquals("2026-11-01T06:00", local(saturday.plus(Duration.ofMinutes(1_440)), newYork))
    }

    @Test
    fun `the same in Europe, where the change comes in the early hours of the last Sunday`() {
        val spring = at(lisbon, 2026, 3, 28, 9)
        assertEquals("2026-03-29T09:00", local(TaskTiming.next(9 * 60, 1, spring, spring.plusSeconds(1), lisbon), lisbon))
        val autumn = at(lisbon, 2026, 10, 24, 9)
        assertEquals("2026-10-25T09:00", local(TaskTiming.next(9 * 60, 1, autumn, autumn.plusSeconds(1), lisbon), lisbon))
    }

    @Test
    fun `a time that doesn't exist on the day the clocks go forward runs after the jump, and the next day is back to normal`() {
        val half = 2 * 60 + 30
        val saturday = at(newYork, 2026, 3, 7, 2, 30)
        val sunday = TaskTiming.next(half, 1, saturday, saturday.plusSeconds(1), newYork)
        assertEquals("2026-03-08T03:30", local(sunday, newYork))
        val monday = TaskTiming.next(half, 1, sunday, sunday.plusSeconds(1), newYork)
        assertEquals("2026-03-09T02:30", local(monday, newYork))
    }

    @Test
    fun `a time that happens twice runs once, the first time`() {
        val oneThirty = 60 + 30
        val saturday = at(newYork, 2026, 10, 31, 1, 30)
        val sunday = TaskTiming.next(oneThirty, 1, saturday, saturday.plusSeconds(1), newYork)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), sunday)
        val monday = TaskTiming.next(oneThirty, 1, sunday, sunday.plusSeconds(1), newYork)
        assertEquals("2026-11-02T01:30", local(monday, newYork))
    }

    @Test
    fun `a run Android held for a day doesn't make the next one late too`() {
        val due = at(newYork, 2026, 3, 4, 7)
        val ranAt = at(newYork, 2026, 3, 5, 12)
        assertEquals(at(newYork, 2026, 3, 6, 7), TaskTiming.next(seven, 1, due, ranAt, newYork))
        // Held past the next one: it is skipped, not run at once.
        assertEquals(at(newYork, 2026, 3, 8, 7), TaskTiming.next(seven, 1, due, at(newYork, 2026, 3, 7, 9), newYork))
    }

    @Test
    fun `a weekly task stays on its day of the week through the change`() {
        val monday = at(newYork, 2026, 3, 2, 7)
        val next = TaskTiming.next(seven, 7, monday, monday.plusSeconds(1), newYork)
        assertEquals("2026-03-09T07:00", local(next, newYork))
        assertEquals(Duration.ofHours(7 * 24 - 1), Duration.between(monday, next))
    }

    @Test
    fun `setting a schedule up again keeps the planned run while it still holds, else works out the next one`() {
        val now = at(newYork, 2026, 3, 5, 10)
        val weekly = at(newYork, 2026, 3, 9, 7)
        assertEquals(weekly, TaskTiming.resume(seven, weekly, now, newYork))
        assertEquals(at(newYork, 2026, 3, 6, 7), TaskTiming.resume(seven, null, now, newYork))
        assertEquals(at(newYork, 2026, 3, 6, 7), TaskTiming.resume(seven, at(newYork, 2026, 3, 5, 7), now, newYork), "already past")
        // The phone moved to another zone: 7:00 in New York is 12:00 in Lisbon, so it is worked out again.
        assertEquals(at(lisbon, 2026, 3, 6, 7), TaskTiming.resume(seven, weekly, now, lisbon))
    }

    @Test
    fun `a check due sooner is kept when a later one is asked for`() {
        val now = 1_000L
        assertTrue(TaskTiming.replacesCheck(pendingDue = null, newDue = now + 60 * 60_000, now = now))
        assertFalse(TaskTiming.replacesCheck(pendingDue = now + 5 * 60_000, newDue = now + 60 * 60_000, now = now), "5 minutes beats 60")
        assertTrue(TaskTiming.replacesCheck(pendingDue = now + 60 * 60_000, newDue = now + 5 * 60_000, now = now), "a nearer one replaces a later one")
        assertTrue(TaskTiming.replacesCheck(pendingDue = now - 1, newDue = now + 60 * 60_000, now = now), "one that has run is no longer waiting")
    }
}
