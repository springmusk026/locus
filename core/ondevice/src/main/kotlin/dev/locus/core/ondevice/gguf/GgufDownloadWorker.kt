package dev.locus.core.ondevice.gguf

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

/**
 * Runs one GGUF download in the foreground with a progress notification (speed and time left), so
 * it goes on when Locus is in the background; the same pattern and channel as the LiteRT-LM
 * downloads (`ModelDownloadWorker`).
 */
@HiltWorker
class GgufDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloads: GgufDownloads,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val download = downloads.download(id) ?: return Result.failure()
        val name = download.offer.name
        setForeground(foregroundInfo(id, name, null))
        var lastUpdate = 0L
        downloads.run(id) { running ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 1_000) {
                lastUpdate = now
                setForeground(foregroundInfo(id, name, running))
            }
        }
        return Result.success()
    }

    private fun foregroundInfo(id: String, name: String, running: GgufDownloadState.Running?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        val total = running?.total
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name")
            .setContentText(running?.let(GgufDownloadText::progress) ?: "Starting…")
            .setProgress(100, if (running != null && total != null && total > 0) (running.bytes * 100 / total).toInt() else 0, total == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val notificationId = NOTIFICATION_BASE + (id.hashCode() and 0xfff)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }

    companion object {
        const val KEY_ID = "gguf-download"
        /** The LiteRT-LM downloads' channel: one "Model downloads" channel in Android's settings. */
        private const val CHANNEL = "model_downloads"
        private const val NOTIFICATION_BASE = 0x6000
    }
}

/** The words for a download's progress, shared by the notification and Settings. */
object GgufDownloadText {
    /** "512 / 1281 MB · 4.2 MB/s · 3 min left" */
    fun progress(r: GgufDownloadState.Running): String = listOfNotNull(
        r.total?.let { "${r.bytes / 1_000_000} / ${it / 1_000_000} MB" } ?: "${r.bytes / 1_000_000} MB",
        r.bytesPerSecond?.let { "%.1f MB/s".format(java.util.Locale.ROOT, it / 1e6) },
        r.etaSeconds?.let { "${duration(it)} left" },
    ).joinToString(" · ")

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3_600 -> "${(seconds + 59) / 60} min"
        else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
    }
}
