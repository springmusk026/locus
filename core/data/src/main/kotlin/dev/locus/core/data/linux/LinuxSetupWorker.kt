package dev.locus.core.data.linux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.locus.core.linux.LinuxStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Runs a Linux install or reinstall ([LinuxSetup.execute]) as an expedited WorkManager job in the
 * foreground, with a progress notification, so it carries on when the user leaves the screen or the
 * app (docs/design/linux-setup.md). Cancelled through [Launcher.cancel], which [LinuxSetup.cancel] calls.
 *
 * It never retries on its own: an install that fails says why on the Linux card, and the user tries
 * again from there.
 */
@HiltWorker
class LinuxSetupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val setup: LinuxSetup,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Setting up Linux", null)

    override suspend fun doWork(): Result {
        val packages = inputData.getStringArray(KEY_PACKAGES).orEmpty().mapNotNull { name -> LinuxPackage.entries.firstOrNull { it.name == name } }
        val request = LinuxSetupRequest(packages, reinstall = inputData.getBoolean(KEY_REINSTALL, false))
        // Android may refuse a foreground start (the app went to the background first); the install
        // still runs, as ordinary expedited work.
        runCatching { setForeground(foregroundInfo("Setting up Linux", null)) }
        coroutineScope {
            val progress = launch {
                combine(setup.status, setup.state) { status, state ->
                    val installing = status as? LinuxStatus.Installing
                    (installing?.step ?: state.phase ?: "Setting up Linux") to (installing?.fraction ?: state.progress)
                }
                    // At most one update per percent, so the notification isn't rebuilt per chunk.
                    .distinctUntilChanged { a, b -> a.first == b.first && percent(a.second) == percent(b.second) }
                    .collect { (step, fraction) -> runCatching { setForeground(foregroundInfo(step, fraction)) } }
            }
            try {
                setup.execute(request)
            } finally {
                progress.cancel()
            }
        }
        return Result.success()
    }

    private fun percent(fraction: Float?) = fraction?.let { (it * 100).toInt() }

    private fun foregroundInfo(step: String, fraction: Float?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Linux setup", NotificationManager.IMPORTANCE_LOW))
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Installing Linux")
            .setContentText(step)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    /** Starts and cancels the one Linux setup job. */
    class Launcher(private val context: Context) : LinuxSetup.Background {
        override fun start(request: LinuxSetupRequest) {
            val work = OneTimeWorkRequestBuilder<LinuxSetupWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PACKAGES to request.packages.map { it.name }.toTypedArray(),
                        KEY_REINSTALL to request.reinstall,
                    ),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, work)
        }

        override fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    companion object {
        const val WORK_NAME = "linux-setup"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_REINSTALL = "reinstall"
        private const val CHANNEL = "linux_setup"
        private const val NOTIFICATION_ID = 8
    }
}
