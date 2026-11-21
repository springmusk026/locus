package dev.locus.core.data.lock

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.locus.core.data.R
import dev.locus.core.model.AppLockSettings

/**
 * How Locus's notifications change with the lock on (docs/design/app-lock.md, "Notifications").
 * Pure apart from the builder calls, so the rules are tested on the JVM.
 *
 * - With Lock Locus on, a notification is VISIBILITY_PRIVATE with a public version that says only
 *   "Locus" and "Unlock to see details": a secure lock screen shows that instead of the content
 *   (developer.android.com/reference/android/app/Notification#VISIBILITY_PRIVATE).
 * - With Lock Locus on, every action needs the phone unlocked: `setAuthenticationRequired(true)` on
 *   Android 12+ (API 31), where the system shows the keyguard first. Below 31 that call does nothing,
 *   so the actions are left off and a tap opens Locus, which shows its own lock.
 * - A locked chat's text never goes into a notification, lock on or off: [lockedChatText] replaces it,
 *   and its approval or question offers no actions, only a tap that opens Locus.
 */
object LockedNotifications {
    /** Actions may be offered: always with the lock off; with it on, only where Android can ask for the unlock first. */
    fun actionsAllowed(lock: AppLockSettings, sdk: Int): Boolean = !lock.enabled || sdk >= 31

    /** Whether an action needs the phone unlocked: [default] (Allow, Continue), or every action with the lock on. */
    fun authenticationRequired(lock: AppLockSettings, default: Boolean): Boolean = default || lock.enabled

    /** VISIBILITY_PRIVATE with a public version, when the lock is on; unchanged otherwise. */
    fun NotificationCompat.Builder.privateWhenLocked(context: Context, lock: AppLockSettings, channel: String): NotificationCompat.Builder {
        if (!lock.enabled) return this
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(context.getString(R.string.data_lock_public_title))
            .setContentText(context.getString(R.string.data_lock_public_text))
            .build()
        return setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
    }

    /** The line that stands in for a locked chat's approval, question or result. */
    fun lockedChatText(context: Context): String = context.getString(R.string.data_lock_chat_waiting)
}
