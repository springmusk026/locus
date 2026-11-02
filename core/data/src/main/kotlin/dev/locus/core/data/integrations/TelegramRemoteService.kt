package dev.locus.core.data.integrations

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.locus.core.data.R
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.TelegramRemoteSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/**
 * Telegram remote mode's foreground service (docs/design/telegram-remote.md): while it runs, Locus
 * reads the bot's messages with long polling ([TelegramRemoteHub]), and its notification says so, with
 * the time it switches off and a Turn off button. It stops itself as soon as remote mode is off.
 *
 * `specialUse`, like AgentService: no predefined type covers waiting for the user's own remote
 * messages, and `dataSync` is capped at 6 hours a day on Android 15+.
 */
@AndroidEntryPoint
class TelegramRemoteService : LifecycleService() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Telegram remote mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while messages to your Telegram bot can start tasks."
            },
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        lifecycleScope.launch {
            settings.settings.map { it.telegramRemote }.distinctUntilChanged().collect { remote ->
                if (!remote.inboundOn(System.currentTimeMillis())) {
                    ServiceCompat.stopForeground(this@TelegramRemoteService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (NotificationManagerCompat.from(this@TelegramRemoteService).areNotificationsEnabled()) {
                    @Suppress("MissingPermission")
                    NotificationManagerCompat.from(this@TelegramRemoteService).notify(NOTIFICATION_ID, notification(remote))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_OFF) lifecycleScope.launch { settings.updateTelegramRemote { it.copy(inbound = false) } }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // specialUse has no system time limit; this only fires if that changes.
        lifecycleScope.launch { settings.updateTelegramRemote { it.copy(inbound = false) } }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun notification(remote: TelegramRemoteSettings?): Notification {
        val until = remote?.until?.takeIf { it > 0 }?.let { DateFormat.getTimeFormat(this).format(Date(it)) }
        val off = PendingIntent.getService(
            this, 0, Intent(this, TelegramRemoteService::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Telegram remote mode is on")
            .setContentText(
                (until?.let { "Until $it. " } ?: "") + "Messages to your bot start tasks" +
                    if (remote?.widened == true) "." else " (read-only).",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", off)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "telegram_remote"
        private const val NOTIFICATION_ID = 40
        private const val ACTION_OFF = "dev.locus.telegram.REMOTE_OFF"

        /** Whether the service is up in this process. */
        @Volatile
        var running: Boolean = false
            private set

        /** Starts it; false when Android refused (a foreground service can't start from the background). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TelegramRemoteService::class.java))
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
