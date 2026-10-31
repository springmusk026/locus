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
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.locus.core.agent.tools.QuestionAnswer
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while an agent task runs, and lets the user approve or deny tool calls,
 * and answer the agent's questions (`ask_user`), from notifications while the app is in the background.
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
                    question = runner.pendingQuestions.firstOrNull()?.callId,
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

    /** While the app is on screen the approval and question cards are shown there, so no notification is posted. */
    private fun refreshNotifications(appVisible: Boolean) {
        if (runner.activeCount == 0) {
            NotificationManagerCompat.from(this).cancel(APPROVAL_NOTIFICATION_ID)
            NotificationManagerCompat.from(this).cancel(QUESTION_NOTIFICATION_ID)
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
        val question = runner.pendingQuestions.firstOrNull()
        @Suppress("MissingPermission")
        if (question != null && !appVisible) manager.notify(QUESTION_NOTIFICATION_ID, questionNotification(question))
        else manager.cancel(QUESTION_NOTIFICATION_ID)
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

    private data class NotificationModel(val active: Int, val status: String, val approval: String?, val question: String?, val appVisible: Boolean)

    private fun statusText(state: RunState?, helpers: Int): String {
        val base = when {
            runner.pendingApprovals.isNotEmpty() -> "Waiting for your approval"
            runner.pendingQuestions.isNotEmpty() -> "Waiting for your answer"
            runner.states.value.values.any { it.budgetPause != null } -> "Paused at the task budget"
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

    /**
     * The agent's question with its options as buttons and a direct reply. Android shows at most three
     * actions: a single choice of up to three options with no free text gets all three as buttons;
     * otherwise up to two options (single choice only) and Reply, with every option listed in the text.
     *
     * Unlike Allow, answering doesn't need the phone unlocked: it isn't a permission, only information
     * the agent asked for, and the agent still asks before any action that needs approval.
     */
    private fun questionNotification(question: UserQuestion): Notification {
        val allButtons = !question.multi && !question.allowOther && question.options.size in 1..3
        val buttons = when {
            allButtons -> question.options.size
            question.multi -> 0
            else -> minOf(2, question.options.size)
        }
        val body = buildString {
            append(question.question)
            if (question.options.isNotEmpty() && !allButtons) {
                question.options.forEachIndexed { i, option -> append("\n${i + 1}. $option") }
                if (question.multi) append("\nReply with your choices.")
            }
        }
        val builder = NotificationCompat.Builder(this, QUESTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(question.origin?.let { "${it.label} asks" } ?: "Locus asks")
            .setContentText(question.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setGroup(QUESTION_CHANNEL)
            .setContentIntent(openAppIntent())
        question.options.take(buttons).forEachIndexed { i, option ->
            val intent = Intent(this, QuestionReceiver::class.java).putExtra(EXTRA_CALL_ID, question.callId).putExtra(EXTRA_OPTION, i)
            val pending = PendingIntent.getBroadcast(this, (question.callId + "#" + i).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(NotificationCompat.Action.Builder(0, option.take(40), pending).setAuthenticationRequired(false).build())
        }
        if (!allButtons) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Your answer").build()
            val intent = Intent(this, QuestionReceiver::class.java).putExtra(EXTRA_CALL_ID, question.callId)
            // Mutable so the system can add the typed text; the intent is explicit (our own receiver).
            val pending = PendingIntent.getBroadcast(this, (question.callId + "#reply").hashCode(), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply", pending)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .setAuthenticationRequired(false)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val STATUS_CHANNEL = "agent_status"
        private const val APPROVAL_CHANNEL = "agent_approvals"
        private const val NOTIFICATION_ID = 1
        private const val APPROVAL_NOTIFICATION_ID = 2
        private const val QUESTION_CHANNEL = "agent_questions"
        private const val QUESTION_NOTIFICATION_ID = 3
        internal const val EXTRA_CALL_ID = "call_id"
        internal const val EXTRA_APPROVE = "approve"
        internal const val EXTRA_OPTION = "option"
        internal const val KEY_REPLY = "reply"

        fun intent(context: Context) = Intent(context, AgentService::class.java)

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(
                NotificationChannel(APPROVAL_CHANNEL, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Asks before the agent changes files, runs commands or contacts other services."
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(QUESTION_CHANNEL, "Questions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Questions the agent asks you while it works, answerable from the notification."
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

/** An answer from the question notification: an option button, or text typed into Reply. */
@AndroidEntryPoint
class QuestionReceiver : BroadcastReceiver() {
    @Inject lateinit var runner: AgentRunner

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(AgentService.EXTRA_CALL_ID) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentService.KEY_REPLY)?.toString()
        if (reply != null) {
            runner.answerQuestion(callId, QuestionAnswer(text = reply))
        } else {
            val option = intent.getIntExtra(AgentService.EXTRA_OPTION, -1)
            if (option >= 0) runner.answerQuestionOption(callId, option)
        }
    }
}
