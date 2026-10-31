package dev.locus.core.designsystem

import android.text.format.DateUtils

/** "Just now", "5 minutes ago", "Yesterday"… */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String =
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
