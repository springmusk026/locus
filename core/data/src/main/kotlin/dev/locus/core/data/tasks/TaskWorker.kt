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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.locus.core.data.R

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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val result = scheduled.run(
            taskId = taskId,
            workId = id.toString(),
            runNow = TAG_RUN_NOW in tags,
            stopReason = { if (Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_UNKNOWN },
            notify = ::notify,
        )
        return if (result == ScheduledRun.Result.FAILED) Result.failure() else Result.success()
    }

    private fun notify(title: String, text: String) {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Scheduled task results", NotificationManager.IMPORTANCE_DEFAULT))
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        @Suppress("MissingPermission")
        manager.notify(
            title.hashCode(),
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
        const val TAG_RUN_NOW = "run-now"
        private const val CHANNEL = "task_results"
    }
}
