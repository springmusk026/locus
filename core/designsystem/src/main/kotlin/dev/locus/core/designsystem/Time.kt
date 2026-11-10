package dev.locus.core.designsystem

import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/**
 * "Just now", "5 minutes ago", "Yesterday"…, in the app's language: "Just now" from [resources], the
 * rest from Android's own [DateUtils], which follows the app's locale.
 */
fun relativeTime(resources: Resources, millis: Long, now: Long = System.currentTimeMillis()): String =
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) resources.getString(R.string.designsystem_time_just_now)
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()

/** [relativeTime] in the composition's resources, so it follows a language change. */
@Composable
@ReadOnlyComposable
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String = relativeTime(LocalResources.current, millis, now)

/**
 * A compact "when" for a small card's meta line, where "2 hours ago" doesn't fit beside a count:
 * "Just now", "5m ago", "2h ago", "3d ago", then the date ("12 Mar", in the user's locale). Give
 * TalkBack [relativeTime] instead, which it reads as words.
 */
fun shortRelativeTime(resources: Resources, millis: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
    val ago = (now - millis).coerceAtLeast(0)
    return when {
        ago < DateUtils.MINUTE_IN_MILLIS -> resources.getString(R.string.designsystem_time_just_now)
        ago < DateUtils.HOUR_IN_MILLIS -> resources.getString(R.string.designsystem_time_minutes_ago_short, (ago / DateUtils.MINUTE_IN_MILLIS).toInt())
        ago < DateUtils.DAY_IN_MILLIS -> resources.getString(R.string.designsystem_time_hours_ago_short, (ago / DateUtils.HOUR_IN_MILLIS).toInt())
        ago < 7 * DateUtils.DAY_IN_MILLIS -> resources.getString(R.string.designsystem_time_days_ago_short, (ago / DateUtils.DAY_IN_MILLIS).toInt())
        else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale).format(java.util.Date(millis))
    }
}

/** [shortRelativeTime] in the composition's resources. */
@Composable
@ReadOnlyComposable
fun shortRelativeTime(millis: Long, now: Long = System.currentTimeMillis()): String = shortRelativeTime(LocalResources.current, millis, now)

/**
 * A short duration, in the user's locale: "420ms", "3.4s", "12s", "3m 05s"; "—" when unknown
 * (negative). Short enough for a column of figures to line up.
 */
fun durationLabel(resources: Resources, ms: Long, locale: Locale = Locale.getDefault()): String = when {
    ms < 0 -> "—"
    ms < 1_000 -> resources.getString(R.string.designsystem_duration_ms, ms.toInt())
    ms < 10_000 -> resources.getString(R.string.designsystem_duration_seconds, String.format(locale, "%.1f", ms / 1_000.0))
    ms < 60_000 -> resources.getString(R.string.designsystem_duration_seconds, (ms / 1_000).toString())
    else -> resources.getString(R.string.designsystem_duration_minutes_seconds, (ms / 60_000).toInt(), ((ms / 1_000) % 60).toInt())
}

/** [durationLabel] in the composition's resources. */
@Composable
@ReadOnlyComposable
fun durationLabel(ms: Long): String = durationLabel(LocalResources.current, ms)
