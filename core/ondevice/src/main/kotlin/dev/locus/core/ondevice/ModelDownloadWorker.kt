package dev.locus.core.ondevice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Downloads an on-device model in the foreground (with a progress notification) so it survives leaving the app. */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val models: OnDeviceModels,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_MODEL) ?: return Result.failure()
        val model = models.model(id) ?: return Result.failure()
        setForeground(foregroundInfo(model.name, 0, 0))
        models.runDownload(id) { bytes, total -> setForeground(foregroundInfo(model.name, bytes, total)) }
        return Result.success()
    }

    private fun foregroundInfo(name: String, bytes: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name")
            .setContentText(if (total > 0) "${bytes / 1_000_000} of ${total / 1_000_000} MB" else "Starting…")
            .setProgress(100, if (total > 0) (bytes * 100 / total).toInt() else 0, total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_MODEL = "model"
        private const val CHANNEL = "model_downloads"
        private const val NOTIFICATION_ID = 7
    }
}
