package dev.locus.core.model

/** How often a scheduled task or workflow runs: [minutes] apart, shown as [label] (or [short] in a segmented control). */
data class ScheduleInterval(val minutes: Long, val label: String, val short: String)

/**
 * The one list of intervals a schedule can be set to, shared by Tasks and Library → Workflows so both
 * offer the same choices. WorkManager's minimum period is 15 minutes; every choice here is above it.
 */
object ScheduleIntervals {
    val HOURLY = ScheduleInterval(60, "Hourly", "Hourly")
    val SIX_HOURS = ScheduleInterval(360, "Every 6 hours", "6 hours")
    val DAILY = ScheduleInterval(1_440, "Daily", "Daily")
    val WEEKLY = ScheduleInterval(10_080, "Weekly", "Weekly")

    val all: List<ScheduleInterval> = listOf(HOURLY, SIX_HOURS, DAILY, WEEKLY)

    /** What a new schedule starts at. */
    val DEFAULT: ScheduleInterval = DAILY

    /** [minutes]'s place in [all], or -1 for a period set some other way (an automation intent, an older version). */
    fun indexOf(minutes: Long): Int = all.indexOfFirst { it.minutes == minutes }

    /** "Daily" for a listed interval, otherwise the period in words: "Every 2 days", "Every 3 hours", "Every 45 min". */
    fun label(minutes: Long): String = all.firstOrNull { it.minutes == minutes }?.label ?: when {
        minutes >= 1_440 && minutes % 1_440 == 0L -> plural(minutes / 1_440, "day")
        minutes >= 60 && minutes % 60 == 0L -> plural(minutes / 60, "hour")
        else -> "Every $minutes min"
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "Every $unit" else "Every $n ${unit}s"
}
