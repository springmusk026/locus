package dev.locus.core.designsystem

import android.text.format.DateUtils
import java.util.Locale

/** "Just now", "5 minutes ago", "Yesterday"… */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * A compact "when" for a small card's meta line, where "2 hours ago" doesn't fit beside a count:
 * "Just now", "5m ago", "2h ago", "3d ago", then the date ("12 Mar", in the user's locale). Give
 * TalkBack [relativeTime] instead, which it reads as words.
 */
fun shortRelativeTime(millis: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
    val ago = (now - millis).coerceAtLeast(0)
    return when {
        ago < DateUtils.MINUTE_IN_MILLIS -> "Just now"
        ago < DateUtils.HOUR_IN_MILLIS -> "${ago / DateUtils.MINUTE_IN_MILLIS}m ago"
        ago < DateUtils.DAY_IN_MILLIS -> "${ago / DateUtils.HOUR_IN_MILLIS}h ago"
        ago < 7 * DateUtils.DAY_IN_MILLIS -> "${ago / DateUtils.DAY_IN_MILLIS}d ago"
        else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale).format(java.util.Date(millis))
    }
}

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
