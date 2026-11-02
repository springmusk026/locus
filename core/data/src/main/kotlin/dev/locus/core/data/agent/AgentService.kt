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
            statusNotification(appVisible()),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        lifecycleScope.launch {
            // Redraw only when what the notifications show changes (not on every streamed token: each
            // notify() is a binder call on the main thread, and the system drops bursts anyway), and when
            // the app moves between foreground and background.
            combine(runner.states, ProcessLifecycleOwner.get().lifecycle.currentStateFlow) { states, process ->
                NotificationModel(
                    active = runner.activeCount,
                    status = agentStatus(states, runner.pendingQuestions),
                    approval = runner.pendingApprovals.firstOrNull()?.callId,
                    question = runner.pendingQuestions.firstOrNull()?.callId,
                    appVisible = process.isAtLeast(Lifecycle.State.STARTED),
                )
            }.distinctUntilChanged().collectLatest { model -> refreshNotifications(model.appVisible) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        refreshNotifications(appVisible())
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // specialUse has no system time limit; this only fires if that changes. Stop cleanly.
        stopSelf()
    }

    private fun appVisible() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

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
        manager.notify(NOTIFICATION_ID, statusNotification(appVisible))
        val pending = runner.pendingApprovals.firstOrNull()
        @Suppress("MissingPermission")
        if (pending != null && !appVisible) {
            // A helper's request names the helper: "Researcher #2 wants to…".
            manager.notify(APPROVAL_NOTIFICATION_ID, approvalNotification(pending.conversationId, pending.callId, approvalTitle(pending), pending.summary))
        }
        else manager.cancel(APPROVAL_NOTIFICATION_ID)
        val question = runner.pendingQuestions.firstOrNull()
        @Suppress("MissingPermission")
        if (question != null && !appVisible) manager.notify(QUESTION_NOTIFICATION_ID, questionNotification(question))
        else manager.cancel(QUESTION_NOTIFICATION_ID)
    }

    /**
     * The foreground notification. On Android 16+, while a run the user started is working, it asks to be
     * a Live Update: promoted to the top of the shade and the lock screen, with a short chip in the status
     * bar ("Working", "Step 4", "Asks"). It then also carries the waiting approval's Deny / Allow, or the
     * waiting question's answers, so the user can act without opening the app. Below Android 16, for a
     * scheduled task, or when the user turned Live Updates off for Locus, it stays the plain notification.
     */
    private fun statusNotification(appVisible: Boolean): Notification {
        val status = agentStatus(runner.states.value, runner.pendingQuestions)
        val builder = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(status.title)
            .setContentText(status.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Separate groups stop launchers (Samsung's One UI in testing) from bundling the status and
            // the approval request, which hides the approval's Allow/Deny buttons behind two expansions.
            // Not a group summary, which a Live Update must not be.
            .setGroup(STATUS_CHANNEL)
            .setContentIntent(openAppIntent(status.chatId))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val promoted = canPromote(status)
        if (promoted) {
            // Eligible: ongoing, a title, standard or BigTextStyle, no custom views, not colorized,
            // not a group summary, and a channel above IMPORTANCE_MIN.
            builder.setRequestPromotedOngoing(true).setShortCriticalText(status.chip)
            status.detail?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(status.text + "\n" + it)) }
        }
        // While the app is on screen its own cards take the answer. Approvals and questions have their own
        // notifications when this one isn't a Live Update; a budget pause has only this one.
        if (!appVisible) {
            val approval = status.approval
            val question = status.question
            val paused = status.pausedChat
            when {
                approval != null -> if (promoted) approvalActions(approval.callId).forEach { builder.addAction(it) }
                question != null -> if (promoted) questionActions(question).forEach { builder.addAction(it) }
                paused != null -> budgetActions(paused).forEach { builder.addAction(it) }
            }
        }
        return builder.build()
    }

    /** Android 16+, a user-started run, and the user hasn't turned Live Updates off for Locus. */
    private fun canPromote(status: AgentStatus): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            status.promotable &&
            NotificationManagerCompat.from(this).canPostPromotedNotifications()

    private data class NotificationModel(val active: Int, val status: AgentStatus, val approval: String?, val question: String?, val appVisible: Boolean)

    /** Deny, then Allow; Allow needs the phone unlocked. */
    private fun approvalActions(callId: String): List<NotificationCompat.Action> {
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
        return listOf(action(false, "Deny"), action(true, "Allow"))
    }

    /** Stop, then Continue for a task paused at its budget; Continue spends more, so it needs the phone unlocked. */
    private fun budgetActions(conversationId: String): List<NotificationCompat.Action> {
        fun action(continueTask: Boolean, label: String) = NotificationCompat.Action.Builder(
            0,
            label,
            PendingIntent.getBroadcast(
                this,
                (conversationId + "#budget" + continueTask).hashCode(),
                Intent(this, BudgetReceiver::class.java).putExtra(EXTRA_CHAT_ID, conversationId).putExtra(EXTRA_CONTINUE, continueTask),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
            .setAuthenticationRequired(continueTask)
            .build()
        return listOf(action(false, "Stop"), action(true, "Continue"))
    }

    private fun approvalNotification(conversationId: String, callId: String, title: String, summary: String): Notification {
        val builder = NotificationCompat.Builder(this, APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setGroup(APPROVAL_CHANNEL)
            .setContentIntent(openAppIntent(conversationId))
        approvalActions(callId).forEach { builder.addAction(it) }
        return builder.build()
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
        val builder = NotificationCompat.Builder(this, QUESTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(question.origin?.let { "${it.label} asks" } ?: "Locus asks")
            .setContentText(question.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(questionBody(question)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setGroup(QUESTION_CHANNEL)
            .setContentIntent(openAppIntent(question.chatId))
        questionActions(question).forEach { builder.addAction(it) }
        return builder.build()
    }

    /** The question's option buttons (see [questionButtons]) and, unless every option is a button, Reply. */
    private fun questionActions(question: UserQuestion): List<NotificationCompat.Action> {
        val actions = mutableListOf<NotificationCompat.Action>()
        question.options.take(questionButtons(question)).forEachIndexed { i, option ->
            val intent = Intent(this, QuestionReceiver::class.java).putExtra(EXTRA_CALL_ID, question.callId).putExtra(EXTRA_OPTION, i)
            val pending = PendingIntent.getBroadcast(this, (question.callId + "#" + i).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            actions += NotificationCompat.Action.Builder(0, option.take(40), pending).setAuthenticationRequired(false).build()
        }
        if (!allOptionsAsButtons(question)) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Your answer").build()
            val intent = Intent(this, QuestionReceiver::class.java).putExtra(EXTRA_CALL_ID, question.callId)
            // Mutable so the system can add the typed text; the intent is explicit (our own receiver).
            val pending = PendingIntent.getBroadcast(this, (question.callId + "#reply").hashCode(), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            actions += NotificationCompat.Action.Builder(0, "Reply", pending)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAuthenticationRequired(false)
                .build()
        }
        return actions
    }

    /** Opens Locus on [conversationId]'s chat; one request code per chat, so each notification keeps its own chat. */
    private fun openAppIntent(conversationId: String?): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        if (conversationId != null) OpenChatLink.attach(this, it, conversationId)
        PendingIntent.getActivity(this, conversationId?.hashCode() ?: 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        internal const val EXTRA_CHAT_ID = "chat_id"
        internal const val EXTRA_CONTINUE = "continue"

        fun intent(context: Context) = Intent(context, AgentService::class.java)

        private const val CONTINUE_CHANNEL = "agent_continue"

        /**
         * "Helper finished: tap to continue", when Android refused to start this service for a turn Locus
         * began on its own while in the background (AgentRunner.start). Tapping opens the chat, which
         * offers Continue; a notification tap also lets Locus start a foreground service again.
         */
        fun postTapToContinue(context: Context, conversationId: String, title: String, text: String) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CONTINUE_CHANNEL, "Waiting to continue", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When Android won't let a task continue in the background, tap to pick it up."
                },
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                OpenChatLink.attach(context, it, conversationId)
                PendingIntent.getActivity(context, (conversationId + "#continue").hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            @Suppress("MissingPermission")
            manager.notify(
                (conversationId + "#continue").hashCode(),
                NotificationCompat.Builder(context, CONTINUE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_agent)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }

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

/** Continue or Stop for a task paused at its budget, from the status notification. */
@AndroidEntryPoint
class BudgetReceiver : BroadcastReceiver() {
    @Inject lateinit var runner: AgentRunner

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(AgentService.EXTRA_CHAT_ID) ?: return
        runner.answerBudget(conversationId, intent.getBooleanExtra(AgentService.EXTRA_CONTINUE, false))
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
