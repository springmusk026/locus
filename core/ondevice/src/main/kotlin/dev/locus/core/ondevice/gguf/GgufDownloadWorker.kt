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
        try {
            setForeground(foregroundInfo(id, name, null))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Android can refuse a foreground service from the background; the row must say so.
            downloads.failed(id, e)
            return Result.failure()
        }
        var lastUpdate = 0L
        val ok = try {
            downloads.run(id) { running ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 1_000) {
                    lastUpdate = now
                    setForeground(foregroundInfo(id, name, running))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // run() records its own failures; this is the boundary for anything that still escapes.
            downloads.failed(id, e)
            false
        }
        return if (ok) Result.success() else Result.failure()
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
    /** A no-break space: a figure and its unit ("1.9 GB", "4 min") never break across lines. */
    const val NBSP = "\u00A0"

    /** "0.8 / 1.9 GB · 4.2 MB/s · 3 min left": one line, for the notification. */
    fun progress(r: GgufDownloadState.Running): String = listOfNotNull(amount(r.bytes, r.total), rate(r.bytesPerSecond, r.etaSeconds)).joinToString(" · ")

    /** "0.8 / 1.9 GB" in one unit for both figures (GB from 100 MB up, else MB); "0.8 GB" without a total. */
    fun amount(bytes: Long, total: Long?): String {
        val gb = (total ?: bytes) >= 100_000_000
        fun f(b: Long) = if (gb) "%.1f".format(java.util.Locale.ROOT, b / 1e9) else "${b / 1_000_000}"
        val unit = if (gb) "GB" else "MB"
        return if (total != null) "${f(bytes)}$NBSP/$NBSP${f(total)}$NBSP$unit" else "${f(bytes)}$NBSP$unit"
    }

    /** "4.2 MB/s · 3 min left", or null before there's a speed. */
    fun rate(bytesPerSecond: Long?, etaSeconds: Long?): String? = listOfNotNull(
        bytesPerSecond?.let { "%.1f".format(java.util.Locale.ROOT, it / 1e6) + "${NBSP}MB/s" },
        etaSeconds?.let { "${duration(it)}${NBSP}left" },
    ).joinToString(" · ").ifEmpty { null }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "$seconds${NBSP}s"
        seconds < 3_600 -> "${(seconds + 59) / 60}${NBSP}min"
        else -> "${seconds / 3_600}${NBSP}h ${(seconds % 3_600) / 60}${NBSP}min"
    }
}
