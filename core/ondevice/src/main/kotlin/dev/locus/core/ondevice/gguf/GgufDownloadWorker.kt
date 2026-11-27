package dev.locus.core.ondevice.gguf

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.locus.core.ondevice.R
import java.util.Locale

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
        val res = applicationContext.resources
        manager.createNotificationChannel(NotificationChannel(CHANNEL, res.getString(R.string.ondevice_download_channel), NotificationManager.IMPORTANCE_LOW))
        val total = running?.total
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(res.getString(R.string.ondevice_download_title, name))
            .setContentText(running?.let { GgufDownloadText.progress(res, it) } ?: res.getString(R.string.ondevice_download_starting))
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

/**
 * The words for a download's progress, shared by the notification and Settings. The notification says them in
 * the app's language ([progress] with resources); Settings still uses the English forms.
 */
object GgufDownloadText {
    /** A no-break space: a figure and its unit ("1.9 GB", "4 min") never break across lines. */
    const val NBSP = "\u00A0"

    /** How the figures are written: the number format's [locale], and the time-left and duration phrases. */
    class Words(
        val locale: Locale,
        val left: (String) -> String,
        val seconds: (Long) -> String,
        val minutes: (Long) -> String,
        val hoursMinutes: (Long, Long) -> String,
    ) {
        companion object {
            /** "3 min left", "4.2 MB/s": the English the notification wrote before it followed the app's language. */
            val ENGLISH = Words(
                Locale.ROOT,
                left = { "$it${NBSP}left" },
                seconds = { "$it${NBSP}s" },
                minutes = { "$it${NBSP}min" },
                hoursMinutes = { h, m -> "$h${NBSP}h $m${NBSP}min" },
            )

            /** The words in [resources]' language ("quedan 3 min", "4,2 MB/s"). */
            fun of(resources: Resources) = Words(
                resources.configuration.locales[0],
                left = { resources.getString(R.string.ondevice_download_left, it) },
                seconds = { resources.getString(R.string.ondevice_duration_seconds, it) },
                minutes = { resources.getString(R.string.ondevice_duration_minutes, it) },
                hoursMinutes = { h, m -> resources.getString(R.string.ondevice_duration_hours, h, m) },
            )
        }
    }

    /** "0.8 / 1.9 GB · 4.2 MB/s · 3 min left": one line, for the notification. */
    fun progress(r: GgufDownloadState.Running): String = progress(r, Words.ENGLISH)

    /** [progress] in [resources]' language, for the notification. */
    fun progress(resources: Resources, r: GgufDownloadState.Running): String = progress(r, Words.of(resources))

    private fun progress(r: GgufDownloadState.Running, words: Words): String =
        listOfNotNull(amount(r.bytes, r.total, words.locale), rate(r.bytesPerSecond, r.etaSeconds, words)).joinToString(" · ")

    /** "0.8 / 1.9 GB" in one unit for both figures (GB from 100 MB up, else MB); "0.8 GB" without a total. */
    fun amount(bytes: Long, total: Long?): String = amount(bytes, total, Locale.ROOT)

    private fun amount(bytes: Long, total: Long?, locale: Locale): String {
        val gb = (total ?: bytes) >= 100_000_000
        fun f(b: Long) = if (gb) "%.1f".format(locale, b / 1e9) else "${b / 1_000_000}"
        val unit = if (gb) "GB" else "MB"
        return if (total != null) "${f(bytes)}$NBSP/$NBSP${f(total)}$NBSP$unit" else "${f(bytes)}$NBSP$unit"
    }

    /** "4.2 MB/s · 3 min left", or null before there's a speed. */
    fun rate(bytesPerSecond: Long?, etaSeconds: Long?): String? = rate(bytesPerSecond, etaSeconds, Words.ENGLISH)

    private fun rate(bytesPerSecond: Long?, etaSeconds: Long?, words: Words): String? = listOfNotNull(
        bytesPerSecond?.let { "%.1f".format(words.locale, it / 1e6) + "${NBSP}MB/s" },
        etaSeconds?.let { words.left(duration(it, words)) },
    ).joinToString(" · ").ifEmpty { null }

    fun duration(seconds: Long): String = duration(seconds, Words.ENGLISH)

    private fun duration(seconds: Long, words: Words): String = when {
        seconds < 60 -> words.seconds(seconds)
        seconds < 3_600 -> words.minutes((seconds + 59) / 60)
        else -> words.hoursMinutes(seconds / 3_600, (seconds % 3_600) / 60)
    }
}
