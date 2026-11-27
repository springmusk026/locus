package dev.locus.core.data.device

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.locus.core.data.R
import dev.locus.core.data.agent.NotificationIntents
import dev.locus.core.data.agent.OpenChatLink
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.lock.LockedNotifications.privateWhenLocked
import dev.locus.core.linux.EngineText
import dev.locus.core.model.AppLockSettings
import dev.locus.core.model.Reminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminder alarms through AlarmManager (docs/design/phone-integrations.md, "Timing"). Exact
 * (`setExactAndAllowWhileIdle`) only when the user granted "Alarms & reminders" (SCHEDULE_EXACT_ALARM, denied by
 * default on Android 14+ for new installs); otherwise a 10-minute window (`setWindow`), the fallback Android's
 * own sample uses, and shorter windows "are typically clipped to 600000" anyway
 * (developer.android.com/develop/background-work/services/alarms). Locus doesn't declare USE_EXACT_ALARM,
 * which Play reserves for alarm-clock and calendar apps.
 */
@Singleton
class AndroidReminderAlarms @Inject constructor(@param:ApplicationContext private val context: Context) : ReminderAlarms {
    private val manager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    override fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < 31 || manager?.canScheduleExactAlarms() == true

    override fun schedule(reminder: Reminder) {
        val am = manager ?: return
        val fire = ReminderIntents.broadcast(context, ReminderIntents.ACTION_FIRE, reminder.id)
        try {
            if (exactAllowed()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, fire)
                return
            }
        } catch (_: SecurityException) {
            // Revoked between the check and the call: fall back to the window.
        }
        am.setWindow(AlarmManager.RTC_WAKEUP, reminder.at, WINDOW_MS, fire)
    }

    override fun cancel(id: String) {
        manager?.cancel(ReminderIntents.broadcast(context, ReminderIntents.ACTION_FIRE, id))
    }

    companion object {
        const val WINDOW_MS: Long = 10 * 60 * 1000L
    }
}

/**
 * The reminder notification: the text, when it was due, Snooze 10 min and Done.
 *
 * It has no delete intent. Android sends an app's delete intent for every removal it didn't ask for, not only
 * a deliberate swipe: clearing a bundle of Locus notifications, "Clear all", a notification listener, a
 * system regroup. Android 16+ bundles notifications an app posts without a summarised group into an
 * "Aggregate" group, and Locus's agent notifications are such, so a reminder lands in that bundle with them.
 * When the delete intent meant Done, any of those removals deleted the reminder for good, often before the
 * user saw it: on a Galaxy S25 (Android 17) two 1-minute reminders were posted into a "g:Aggregate" bundle,
 * left the shade unseen, and were gone from the list, which only Done or Cancel does. Now a removed
 * notification leaves the reminder as shown: it stays under Scheduled tasks for a day, where Clear removes it.
 */
@Singleton
class AndroidReminderNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /** Null only in tests that don't need it: nothing is locked then. */
    private val appLock: AppLock? = null,
) : ReminderNotifier {
    override fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun show(reminder: Reminder, late: Boolean) {
        if (!canNotify()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.data_notif_channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.data_notif_channel_reminders_description)
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            NotificationIntents.forActivity(launch, "reminder", reminder.id)
            reminder.conversationId?.let { OpenChatLink.attach(context, launch, it) }
            PendingIntent.getActivity(context, ("reminder-open:" + reminder.id).hashCode(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        // A reminder set in a locked chat doesn't say what it is (docs/design/app-lock.md). Until the lock's
        // settings are read (a fresh process), it errs on the side of saying less.
        val status = if (appLock == null) dev.locus.core.model.AppLockStatus() else appLock.status.value
        val lock = status?.settings ?: AppLockSettings(enabled = true)
        val hidden = if (status == null) reminder.conversationId != null else status.redactOutside(reminder.conversationId)
        val text = if (hidden) context.getString(R.string.data_lock_reminder_text) else reminder.text
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(
            ReminderIntents.notificationId(reminder.id),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(text)
                .setContentText(reminderLine(late, reminderTime(reminder.at, ZoneId.systemDefault())).asString(context.resources))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .privateWhenLocked(context, lock, CHANNEL)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setWhen(reminder.at)
                .setShowWhen(true)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.data_reminder_snooze), ReminderIntents.broadcast(context, ReminderIntents.ACTION_SNOOZE, reminder.id))
                .addAction(0, context.getString(R.string.data_reminder_done), ReminderIntents.broadcast(context, ReminderIntents.ACTION_DONE, reminder.id))
                .build(),
        )
    }

    override fun dismiss(id: String) = NotificationManagerCompat.from(context).cancel(ReminderIntents.notificationId(id))

    /** When the reminder was due, in the app's language and the phone's 12/24-hour setting. */
    private fun reminderTime(at: Long, zone: ZoneId): String {
        val locale = context.resources.configuration.locales[0]
        val skeleton = reminderSkeleton(at, System.currentTimeMillis(), zone, android.text.format.DateFormat.is24HourFormat(context))
        val format = android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale)
        format.timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
        return format.format(java.util.Date(at))
    }

    companion object {
        const val CHANNEL = "reminders"
    }
}

/**
 * The notification's second line: "Reminder from Locus · 17:00", or "Late: it was due at 17:00 (the phone was
 * off or asleep)" after the phone was off; [time] is when it was due ([AndroidReminderNotifier.reminderTime]).
 */
internal fun reminderLine(late: Boolean, time: String): EngineText =
    EngineText.Res(if (late) R.string.data_notif_reminder_late else R.string.data_notif_reminder_line, time)

/**
 * The date-time skeleton for a reminder due at [at]: the time alone when it is today, else the weekday, day,
 * month and time ("Tue 3 Nov, 17:00" in English); 24-hour or not as [is24Hour] says.
 */
internal fun reminderSkeleton(at: Long, now: Long, zone: ZoneId, is24Hour: Boolean): String {
    val today = Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (if (today) "" else "EEEdMMM") + (if (is24Hour) "Hm" else "hm")
}

/** The broadcasts a reminder's alarm and notification buttons send ([ReminderReceiver]). */
object ReminderIntents {
    const val ACTION_FIRE = "dev.locus.reminder.FIRE"
    const val ACTION_SNOOZE = "dev.locus.reminder.SNOOZE"
    const val ACTION_DONE = "dev.locus.reminder.DONE"
    const val EXTRA_ID = "reminder_id"

    fun notificationId(id: String): Int = "reminder:$id".hashCode()

    /**
     * An explicit, immutable broadcast to [ReminderReceiver]. The data URI makes each reminder's and each
     * action's PendingIntent distinct, so cancelling one alarm never cancels another.
     */
    fun intent(context: Context, action: String, id: String): Intent = Intent(context, ReminderReceiver::class.java)
        .setAction(action)
        .setData(dataUri(action, id).toUri())
        .putExtra(EXTRA_ID, id)

    /**
     * What tells two reminders' intents apart. The request code is a hash, so two ids can share one; Android
     * treats intents with the same action, data and component as the same PendingIntent whatever the request
     * code and extras, so the id is in the data.
     */
    internal fun dataUri(action: String, id: String): String =
        "locus-reminder://${URLEncoder.encode(id, "UTF-8")}/${action.substringAfterLast('.').lowercase()}"

    internal fun requestCode(action: String, id: String): Int = ("$action:$id").hashCode()

    fun broadcast(context: Context, action: String, id: String): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode(action, id), intent(context, action, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/**
 * The reminder alarm, Snooze and Done, and the moments Android asks apps to set alarms again: after a
 * reboot ("all alarms are canceled when a device shuts down"), after an update, and when exact alarms are
 * granted (ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED). Also when the time zone or the clock
 * changes, so a reminder set for a wall-clock time moves with the phone. Not exported: the system's broadcasts
 * reach it anyway, and no other app can send it one. Never starts an activity (Android 12's trampoline rule).
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: Reminders

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val id = intent.getStringExtra(ReminderIntents.EXTRA_ID)
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    ReminderIntents.ACTION_FIRE -> id?.let { reminders.fire(it) }
                    ReminderIntents.ACTION_SNOOZE -> id?.let { reminders.snooze(it) }
                    ReminderIntents.ACTION_DONE -> id?.let { reminders.done(it) }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
                    -> reminders.rescheduleAll()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ReminderModule {
    @Binds abstract fun alarms(impl: AndroidReminderAlarms): ReminderAlarms

    @Binds abstract fun notifier(impl: AndroidReminderNotifier): ReminderNotifier

    @Binds abstract fun calendar(impl: AndroidCalendar): CalendarData

    @Binds abstract fun calendarChoice(impl: SettingsCalendarChoice): CalendarChoice
}
