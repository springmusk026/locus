package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * Settings → Phone (docs/design/phone-integrations.md): which calendar the agent may change. Locus
 * writes to this one calendar only; events in any other calendar are read-only to it. Null until the
 * user picks one or the first calendar write saves the phone's primary writable calendar as the default.
 */
@Serializable
data class PhoneSettings(
    /** `CalendarContract.Calendars._ID` of the calendar Locus may add to and change. */
    val calendarId: Long? = null,
    /** Its display name when it was picked, for the approval card and Settings without a provider query. */
    val calendarName: String = "",
    /** The account it syncs with ("ana@example.com", "Local"), shown under the name. */
    val calendarAccount: String = "",
) {
    /** The value a remembered "this calendar" approval is stored under ([ApprovalScopeKind.CALENDAR]). */
    val calendarScope: String? get() = calendarId?.let { "$it:$calendarName" }
}

/**
 * A reminder set with `remind_me`: a Locus notification at [at], with no model run when it fires
 * (docs/design/phone-integrations.md). Kept in the settings store, which survives restarts; the alarms
 * are set again after a reboot, an update, or a change to the exact-alarm permission.
 */
@Serializable
data class Reminder(
    val id: String,
    val text: String,
    /** When it is due next (epoch ms): the time asked for, or the end of a snooze. */
    val at: Long,
    val createdAt: Long = 0,
    /** The chat it was set in, which the notification opens; null when set outside a chat. */
    val conversationId: String? = null,
    /** When its notification was shown (epoch ms); null while it is still to come. */
    val shownAt: Long? = null,
    /** How many times it has been snoozed. */
    val snoozes: Int = 0,
) {
    val upcoming: Boolean get() = shownAt == null

    companion object {
        /** At most this many reminders are kept; the oldest shown ones go first. */
        const val MAX = 100

        /** What Snooze adds. */
        const val SNOOZE_MS: Long = 10 * 60 * 1000L

        /** A shown reminder nobody answered leaves the list after this long. */
        const val KEEP_SHOWN_MS: Long = 24 * 60 * 60 * 1000L
    }
}
