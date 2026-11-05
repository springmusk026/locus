package dev.locus.core.data.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.locus.core.data.R
import dev.locus.core.data.agent.OpenChatLink

/**
 * Runs a scheduled task: a fresh conversation, read-only tools only (it can look things up and
 * read, never change or send anything), then a notification with the answer. The run itself is
 * [ScheduledRun]; this is its Android side (stop reasons, notifications).
 *
 * A regular worker, neither expedited nor foreground: periodic work can't be expedited, and a
 * worker in the background can't start a foreground service on Android 12+. See
 * docs/design/scheduled-runs.md.
 */
@HiltWorker
class TaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduled: ScheduledRun,
    private val conditions: dev.locus.core.llm.DeviceConditions,
    private val settings: dev.locus.core.data.repository.SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        // Heat isn't a WorkManager constraint: a scheduled run that finds the phone hot (SEVERE and up)
        // tries again later with WorkManager's backoff, before it sends anything. "Run now" isn't held.
        val runNow = TAG_RUN_NOW in tags
        if (!runNow && conditions.state.value.hot && settings.current().power.deferWhenHot) return Result.retry()
        val result = scheduled.run(
            taskId = taskId,
            workId = id.toString(),
            runNow = runNow,
            stopReason = { if (Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_UNKNOWN },
            notify = { title, text, conversationId -> notify(taskId, title, text, conversationId) },
            fromApp = inputData.getString(KEY_FROM_APP),
            eventId = inputData.getString(KEY_EVENT_ID),
        )
        // The result name tells Tasks' "Run now" whether it ran or was skipped (another run was going).
        val output = workDataOf(KEY_RESULT to result.name)
        return if (result == ScheduledRun.Result.FAILED) Result.failure(output) else Result.success(output)
    }

    private fun notify(taskId: String, title: String, text: String, conversationId: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Scheduled task results", NotificationManager.IMPORTANCE_DEFAULT))
        val link = TaskResultLink(taskId, conversationId)
        // The launcher intent of whichever build this is (dev.locus or dev.locus.debug), carrying the chat
        // as OpenChatLink extras that MainActivity checks against its token and opens.
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            OpenChatLink.attach(applicationContext, it, link.chatId)
            PendingIntent.getActivity(applicationContext, link.requestCode, it, link.flags)
        }
        @Suppress("MissingPermission")
        manager.notify(
            link.notificationId,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"

        /** Another app that asked for this run through the automation intents (docs/design/android-bridge.md). */
        const val KEY_FROM_APP = "from_app"
        const val TAG_RUN_NOW = "run-now"

        /** The trigger event that started this run (docs/design/event-triggers.md). */
        const val KEY_EVENT_ID = "event_id"

        /** On every run an event started. */
        const val TAG_EVENT = "trigger-event"

        /** The worker's output: the [ScheduledRun.Result] name. */
        const val KEY_RESULT = "result"
        private const val CHANNEL = "task_results"
    }
}

/**
 * How a task's result notification opens the chat its run happened in. One notification per task
 * (a newer run replaces the older one), and one PendingIntent per task: the request code comes from
 * the task id, so two tasks' notifications never share an intent and each opens its own chat.
 * [FLAG_UPDATE_CURRENT][PendingIntent.FLAG_UPDATE_CURRENT] puts the newest run's chat on that intent;
 * it is immutable, as Android 12+ requires.
 */
internal data class TaskResultLink(val taskId: String, val chatId: String) {
    val requestCode: Int get() = "task-result:$taskId".hashCode()
    val notificationId: Int get() = "task-notification:$taskId".hashCode()
    val flags: Int get() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
