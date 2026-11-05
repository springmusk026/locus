package dev.locus.core.testing

import dev.locus.core.data.tasks.triggers.CalendarInstance
import dev.locus.core.data.tasks.triggers.CalendarReader

/** The phone's calendar in memory: whether Locus may read it, its entries and its calendars' names. */
class FakeCalendarReader(
    var allowed: Boolean = false,
    var entries: List<CalendarInstance> = emptyList(),
    var names: List<String> = emptyList(),
) : CalendarReader {
    override fun hasPermission() = allowed

    override fun instances(from: Long, to: Long): List<CalendarInstance> =
        if (allowed) entries.filter { it.end >= from && it.begin <= to } else throw SecurityException("READ_CALENDAR")

    override fun calendars(): List<String> = if (allowed) names else emptyList()
}
