package dev.locus.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleIntervalsTest {
    @Test
    fun `listed intervals have their names and places`() {
        assertEquals(listOf(60L, 360L, 1_440L, 10_080L), ScheduleIntervals.all.map { it.minutes })
        assertEquals("Daily", ScheduleIntervals.label(1_440))
        assertEquals("Weekly", ScheduleIntervals.label(10_080))
        assertEquals(2, ScheduleIntervals.indexOf(1_440))
        assertEquals(ScheduleIntervals.DAILY, ScheduleIntervals.DEFAULT)
    }

    @Test
    fun `other periods are said in words`() {
        assertEquals(-1, ScheduleIntervals.indexOf(45))
        assertEquals("Every 45 min", ScheduleIntervals.label(45))
        assertEquals("Every 3 hours", ScheduleIntervals.label(180))
        assertEquals("Every 2 days", ScheduleIntervals.label(2_880))
        assertEquals("Every 90 min", ScheduleIntervals.label(90))
    }

    @Test
    fun `every interval is above WorkManager's minimum`() {
        assertTrue(ScheduleIntervals.all.all { it.minutes >= 15 })
    }
}
