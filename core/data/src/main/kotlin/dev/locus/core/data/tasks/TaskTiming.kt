package dev.locus.core.data.tasks

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a task that runs "every day at 07:00" runs next, by the wall clock of the user's zone. A fixed
 * 1,440-minute period drifts an hour when the clocks change; the next run is instead worked out from
 * the local date and time, and enqueued once the last one has run (see [WorkManagerTaskWork]).
 *
 * A time that doesn't exist on a day (02:30 when the clocks jump from 02:00 to 03:00) runs at the same
 * distance after the jump (03:30); one that happens twice (01:30 when they go back) runs the first time.
 * The day after, it is at its own time again.
 */
internal object TaskTiming {
    const val DAY_MINUTES = 1_440L

    /** The first run: the next [minuteOfDay] (minutes after local midnight) after [now]. */
    fun first(minuteOfDay: Int, now: Instant, zone: ZoneId): Instant {
        var day = now.atZone(zone).toLocalDate()
        while (true) {
            val at = at(day, minuteOfDay, zone)
            if (at.toInstant().isAfter(now)) return at.toInstant()
            day = day.plusDays(1)
        }
    }

    /**
     * The run after the one that was due at [due]: [everyDays] local days later at [minuteOfDay], and
     * never at or before [now], so a run that Android held for hours doesn't make the next one late too.
     */
    fun next(minuteOfDay: Int, everyDays: Int, due: Instant, now: Instant, zone: ZoneId): Instant {
        var day = due.atZone(zone).toLocalDate().plusDays(everyDays.toLong())
        while (true) {
            val at = at(day, minuteOfDay, zone)
            if (at.toInstant().isAfter(now)) return at.toInstant()
            day = day.plusDays(everyDays.toLong())
        }
    }

    /**
     * The run to enqueue when a schedule is set up again (a changed condition, a restart): the one that
     * was already planned, [planned], if it is still ahead and still at [minuteOfDay] here (so a weekly task
     * keeps its day), else the next [minuteOfDay].
     */
    fun resume(minuteOfDay: Int, planned: Instant?, now: Instant, zone: ZoneId): Instant =
        planned?.takeIf { it.isAfter(now) && it.atZone(zone).let { z -> z.hour * 60 + z.minute == minuteOfDay } }
            ?: first(minuteOfDay, now, zone)

    private fun at(day: java.time.LocalDate, minuteOfDay: Int, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.of(day, LocalTime.of(minuteOfDay / 60, minuteOfDay % 60), zone)

    /**
     * Whether an extra trigger check due at [newDue] should replace the one already waiting for [pendingDue]
     * (null: none; at or before [now]: it has run or is running). A later one never cancels a nearer one: the
     * nearer check works out when to look again.
     */
    fun replacesCheck(pendingDue: Long?, newDue: Long, now: Long): Boolean =
        pendingDue == null || pendingDue <= now || newDue <= pendingDue
}
