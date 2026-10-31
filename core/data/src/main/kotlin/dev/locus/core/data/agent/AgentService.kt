package dev.locus.core.data.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.locus.core.data.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while an agent task runs, and lets the user approve or deny tool calls
 * from the notification while the app is in the background.
 *
 * Uses the `specialUse` foreground service type: the agent work is user-initiated and open-ended,
 * which no predefined type describes, and `dataSync` is capped at 6 hours a day on Android 15+.
 */
@AndroidEntryPoint
class AgentService : LifecycleService() {

    @Inject lateinit var runner: AgentRunner

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            statusNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        lifecycleScope.launch {
            // Redraw only when what the notifications show changes (not on every streamed token: each
            // notify() is a binder call on the main thread, and the system drops bursts anyway), and when
            // the app moves between foreground and background.
            combine(runner.states, ProcessLifecycleOwner.get().lifecycle.currentStateFlow) { states, process ->
                NotificationModel(
                    active = runner.activeCount,
                    status = statusText(states.values.firstOrNull { it.running }, states.values.sumOf { it.helpers }),
                    approval = runner.pendingApprovals.firstOrNull()?.callId,
                    appVisible = process.isAtLeast(Lifecycle.State.STARTED),
                )
            }.distinctUntilChanged().collectLatest { model -> refreshNotifications(model.appVisible) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        refreshNotifications(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // specialUse has no system time limit; this only fires if that changes. Stop cleanly.
        stopSelf()
    }

    /** While the app is on screen the approval card is shown there, so no notification is posted. */
    private fun refreshNotifications(appVisible: Boolean) {
        if (runner.activeCount == 0) {
            NotificationManagerCompat.from(this).cancel(APPROVAL_NOTIFICATION_ID)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        @Suppress("MissingPermission")
        manager.notify(NOTIFICATION_ID, statusNotification())
        val pending = runner.pendingApprovals.firstOrNull()
        @Suppress("MissingPermission")
        if (pending != null && !appVisible) {
            // A helper's request names the helper: "Researcher #2 wants to…".
            val title = pending.origin?.let { "${it.label} wants to use ${pending.toolName}" } ?: "Allow ${pending.toolName}?"
            manager.notify(APPROVAL_NOTIFICATION_ID, approvalNotification(pending.callId, title, pending.summary))
        }
        else manager.cancel(APPROVAL_NOTIFICATION_ID)
    }

    private fun statusNotification(): Notification {
        val text = statusText(runner.states.value.values.firstOrNull { it.running }, runner.states.value.values.sumOf { it.helpers })
        return NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Locus is working")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Separate groups stop launchers (Samsung's One UI in testing) from bundling the status and
            // the approval request, which hides the approval's Allow/Deny buttons behind two expansions.
            .setGroup(STATUS_CHANNEL)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private data class NotificationModel(val active: Int, val status: String, val approval: String?, val appVisible: Boolean)

    private fun statusText(state: RunState?, helpers: Int): String {
        val base = when {
            runner.pendingApprovals.isNotEmpty() -> "Waiting for your approval"
            state?.activeTool != null -> "Using ${state.activeTool}"
            else -> "Working"
        }
        return when (helpers) {
            0 -> if (base == "Working") "Working…" else base
            1 -> "$base · 1 helper"
            else -> "$base · $helpers helpers"
        }
    }

    private fun approvalNotification(callId: String, title: String, summary: String): Notification {
        fun action(approve: Boolean, label: String) = NotificationCompat.Action.Builder(
            0,
            label,
            PendingIntent.getBroadcast(
                this,
                (callId + approve).hashCode(),
                Intent(this, ApprovalReceiver::class.java).putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_APPROVE, approve),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
            // Approving from a locked phone must not be possible.
            .setAuthenticationRequired(approve)
            .build()

        return NotificationCompat.Builder(this, APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setGroup(APPROVAL_CHANNEL)
            .setContentIntent(openAppIntent())
            .addAction(action(false, "Deny"))
            .addAction(action(true, "Allow"))
            .build()
    }

    private fun openAppIntent(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val STATUS_CHANNEL = "agent_status"
        private const val APPROVAL_CHANNEL = "agent_approvals"
        private const val NOTIFICATION_ID = 1
        private const val APPROVAL_NOTIFICATION_ID = 2
        internal const val EXTRA_CALL_ID = "call_id"
        internal const val EXTRA_APPROVE = "approve"

        fun intent(context: Context) = Intent(context, AgentService::class.java)

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(
                NotificationChannel(APPROVAL_CHANNEL, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Asks before the agent changes files, runs commands or contacts other services."
                },
            )
        }
    }
}

@AndroidEntryPoint
class ApprovalReceiver : BroadcastReceiver() {
    @Inject lateinit var runner: AgentRunner

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(AgentService.EXTRA_CALL_ID) ?: return
        runner.answer(callId, intent.getBooleanExtra(AgentService.EXTRA_APPROVE, false))
    }
}
