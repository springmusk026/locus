package dev.locus.core.designsystem

import android.text.format.DateUtils
import java.util.Locale

/** "Just now", "5 minutes ago", "Yesterday"… */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * A short duration, in the user's locale: "420ms", "3.4s", "12s", "3m 05s"; "—" when unknown
 * (negative). Short enough for a column of figures to line up.
 */
fun durationLabel(ms: Long, locale: Locale = Locale.getDefault()): String = when {
    ms < 0 -> "—"
    ms < 1_000 -> "${ms}ms"
    ms < 10_000 -> String.format(locale, "%.1fs", ms / 1_000.0)
    ms < 60_000 -> "${ms / 1_000}s"
    else -> String.format(locale, "%dm %02ds", ms / 60_000, (ms / 1_000) % 60)
}
