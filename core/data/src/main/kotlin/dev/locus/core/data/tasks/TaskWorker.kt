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
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.locus.core.data.R
import dev.locus.core.data.agent.OpenChatLink
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.lock.LockedNotifications
import dev.locus.core.data.lock.LockedNotifications.privateWhenLocked
import dev.locus.core.model.AppLockSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

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
    private val appLock: AppLock,
    /** The one "N things need you" notification, for what this run proposed (docs/design/needs-you.md). */
    private val needsYou: dev.locus.core.data.needsyou.NeedsYouNotifier,
    private val proposals: dev.locus.core.data.needsyou.ProposalRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = try {
            runTask()
        } catch (e: CancellationException) {
            // Stopped (WorkManager runs it again) or cancelled (the task was turned off, or its schedule replaced).
            throw e
        } catch (e: Throwable) {
            enqueueNextDaily()
            throw e
        }
        // Held for later (a hot phone) is the same work again; anything else was this day's run.
        if (result != Result.retry()) enqueueNextDaily()
        return result
    }

    /**
     * A task that runs at a time of day is one one-time run at a time (so the clocks changing can't shift it):
     * this one ends, and the next is enqueued for the same local time on the next day it is due.
     */
    private suspend fun enqueueNextDaily() {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return
        val minute = inputData.getInt(KEY_AT_MINUTE, -1).takeIf { it >= 0 } ?: return
        // A task turned off or deleted while it ran isn't scheduled again; one edited meanwhile is, as it is now.
        coroutineContext.ensureActive()
        val task = scheduled.enabledTask(taskId)?.takeIf { it.intervalMinutes % TaskTiming.DAY_MINUTES == 0L } ?: return
        if (isStopped) return
        val days = (task.intervalMinutes / TaskTiming.DAY_MINUTES).toInt()
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val due = TaskTiming.next(minute, days, Instant.ofEpochMilli(inputData.getLong(KEY_DUE, now.toEpochMilli())), now, ZoneId.systemDefault())
        TaskTimes(applicationContext).setDaily(taskId, minute, due.toEpochMilli())
        // REPLACE under this run's own name: it has finished its work, and nothing else is waiting under the name.
        enqueueDailyRun(WorkManager.getInstance(applicationContext), taskId, minute, due, now, task.conditions, ExistingWorkPolicy.REPLACE)
    }

    private suspend fun runTask(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        // Heat isn't a WorkManager constraint: a scheduled run that finds the phone hot (SEVERE and up)
        // tries again later with WorkManager's backoff, before it sends anything. "Run now" isn't held.
        val runNow = TAG_RUN_NOW in tags
        if (!runNow && conditions.state.value.hot && settings.current().power.deferWhenHot) return Result.retry()
        // Read once, before the run: the result's notification hides a locked chat's text (docs/design/app-lock.md).
        val lock = appLock.loaded().settings
        val startedAt = System.currentTimeMillis()
        // The run's notifications wait until it ends: a run that proposed something is announced by Needs you's one
        // summary instead (its result is listed there too), so it doesn't post two (docs/design/needs-you.md).
        val held = mutableListOf<Triple<String, String, String>>()
        val postHeld = { held.forEach { (title, text, conversationId) -> notify(taskId, title, text, conversationId, lock) }; held.clear() }
        val result = try {
            scheduled.run(
                taskId = taskId,
                workId = id.toString(),
                runNow = runNow,
                stopReason = { if (Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_UNKNOWN },
                notify = { title, text, conversationId -> held += Triple(title, text, conversationId) },
                fromApp = inputData.getString(KEY_FROM_APP),
                eventId = inputData.getString(KEY_EVENT_ID),
            )
        } catch (e: Throwable) {
            // Stopped or failed part-way: whatever it had to say goes out now, as before.
            postHeld()
            throw e
        }
        val proposed = runCatching { proposals.proposedSince(startedAt) }.getOrDefault(false)
        if (proposed) held.clear() else postHeld()
        // What it proposed waits in Needs you: one summary notification for all of it, never one per proposal.
        runCatching { needsYou.refresh(newSince = startedAt) }
        // The result name tells Tasks' "Run now" whether it ran or was skipped (another run was going).
        val output = workDataOf(KEY_RESULT to result.name)
        return if (result == ScheduledRun.Result.FAILED) Result.failure(output) else Result.success(output)
    }

    private fun notify(taskId: String, title: String, text: String, conversationId: String, lock: AppLockSettings) {
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
        // A locked run's answer stays in its chat; the task's own title isn't the chat's content.
        val shown = if (conversationId in lock.lockedChats) LockedNotifications.lockedChatText(applicationContext) else text
        @Suppress("MissingPermission")
        manager.notify(
            link.notificationId,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(title)
                .setContentText(shown.lineSequence().firstOrNull { it.isNotBlank() }?.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(shown.take(1500)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .privateWhenLocked(applicationContext, lock, CHANNEL)
                .build(),
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"

        /** Set on the one-time run of a task at a time of day: minutes after local midnight, and the instant it was due. */
        const val KEY_AT_MINUTE = "at_minute"
        const val KEY_DUE = "due"

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
