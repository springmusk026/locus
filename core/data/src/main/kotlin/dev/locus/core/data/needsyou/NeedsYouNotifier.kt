package dev.locus.core.data.needsyou

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.R
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.lock.LockedNotifications.privateWhenLocked
import dev.locus.core.model.AppLockSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the one Needs you notification says: how many proposals wait, and up to [MAX_LINES] of them by task and
 * call. A proposal from a locked chat is counted but never described, lock on or off (docs/design/app-lock.md).
 * Pure, so it is tested on the JVM.
 */
data class NeedsYouNotice(val count: Int, val lines: List<String>, val more: Int) {
    companion object {
        const val MAX_LINES = 3

        /** Null when nothing waits: the notification goes. */
        fun of(waiting: List<Proposal>, lock: AppLockSettings): NeedsYouNotice? {
            if (waiting.isEmpty()) return null
            val described = waiting.filter { it.conversationId !in lock.lockedChats }.take(MAX_LINES)
            val lines = described.map { p -> "${p.source}: ${p.summary.lineSequence().first().take(120)}" }
            return NeedsYouNotice(waiting.size, lines, waiting.size - lines.size)
        }
    }
}

/**
 * The single "N things need you" notification (docs/design/needs-you.md): one for every proposal waiting, never one
 * per item. It alerts when a run has just proposed something, and otherwise only keeps an existing one up to date (or
 * removes it once nothing waits), so declining one doesn't bring back a notification the user swiped away. It has
 * no actions: a tap opens Locus, whose own lock applies, and approving happens on the card in Locus. With Lock Locus
 * on it is private, with a public version that says nothing.
 */
@Singleton
class NeedsYouNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val proposals: ProposalRepository,
    private val appLock: AppLock,
) {
    /** Brings the notification up to date; with [newSince], alerts if something was proposed at or after it. */
    suspend fun refresh(newSince: Long? = null) {
        val manager = NotificationManagerCompat.from(context)
        val now = proposals.now()
        val waiting = proposals.open.first().filter { it.approvable(now) }
        val lock = appLock.loaded().settings
        val notice = NeedsYouNotice.of(waiting, lock)
        if (notice == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val fresh = newSince != null && waiting.any { it.createdAt >= newSince }
        if (!fresh && !showing()) return
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.data_needs_you_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, REQUEST_CODE, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val title = context.resources.getQuantityString(R.plurals.data_needs_you_title, notice.count, notice.count)
        val text = text(notice)
        @Suppress("MissingPermission")
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setNumber(notice.count)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!fresh)
                .privateWhenLocked(context, lock, CHANNEL)
                .build(),
        )
    }

    private fun text(notice: NeedsYouNotice): String {
        if (notice.lines.isEmpty()) return context.getString(R.string.data_needs_you_open)
        val more = if (notice.more > 0) listOf(context.resources.getQuantityString(R.plurals.data_needs_you_more, notice.more, notice.more)) else emptyList()
        return (notice.lines + more).joinToString("\n")
    }

    private fun showing(): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
    }.getOrDefault(false)

    private companion object {
        const val CHANNEL = "needs_you"
        val NOTIFICATION_ID = "needs-you".hashCode()
        val REQUEST_CODE = "needs-you-open".hashCode()
    }
}
