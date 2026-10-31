package dev.locus.core.data.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.locus.core.agent.integrations.ReachMe
import dev.locus.core.data.R
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import java.text.DateFormat
import java.util.Date

/**
 * Runs a scheduled task: a fresh conversation, read-only tools only (it can look things up and
 * read, never change or send anything), then a notification with the answer.
 */
@HiltWorker
class TaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tasks: TaskRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val runner: AgentRunner,
    private val secrets: dev.locus.core.agent.home.SecretResolver,
    private val http: io.ktor.client.HttpClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val task = tasks.get(inputData.getString(KEY_TASK_ID) ?: return Result.failure()) ?: return Result.failure()
        if (!task.enabled && TAG_RUN_NOW !in tags) return Result.success()
        val conversation = conversations.create(settings.current().defaultProviderId)
        conversations.rename(conversation.id, "Scheduled: ${task.title} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}")
        val answer = runner.runInBackground(
            conversation.id,
            "${task.prompt}\n\n(This is a scheduled task running in the background. You can open and read web pages, files and connectors, but not click, type, write or send anything. Reply with a short plain-text summary the user will see in a notification.)",
        )
        tasks.recordRun(task.id, conversation.id, answer)
        notify(task.title, plain(answer ?: "Finished with no answer."))
        // Also to the user's reach-me channels (ntfy, Telegram) when any is set up, so the answer reaches their other devices.
        // ReachMe catches each channel's failure; this guards the rest.
        runCatching { ReachMe.send(http, secrets, task.title, answer ?: "Finished with no answer.") }
        return Result.success()
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

    /** Notifications show plain text, so drop Markdown emphasis, code and link syntax. */
    private fun plain(markdown: String) = markdown
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val TAG_RUN_NOW = "run-now"
        private const val CHANNEL = "task_results"
    }
}
