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
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.lock.LockedNotifications
import dev.locus.core.data.lock.LockedNotifications.privateWhenLocked
import dev.locus.core.linux.EngineText
import dev.locus.core.model.AppLockSettings
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

    @Inject lateinit var appLock: AppLock

    /** The tool and budget phrases the chat uses, in the app's language. */
    @Inject lateinit var phrases: NotificationPhrases

    /** The lock's settings as last read; the defaults (off, nothing locked) until they load. */
    private val lock: AppLockSettings get() = appLock.status.value?.settings ?: AppLockSettings()

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
            combine(runner.states, ProcessLifecycleOwner.get().lifecycle.currentStateFlow, appLock.status) { states, process, lockStatus ->
                val lock = lockStatus?.settings ?: AppLockSettings()
                NotificationModel(
                    active = runner.activeCount,
                    status = agentStatus(states, runner.pendingQuestions, lock.lockedChats),
                    approval = runner.pendingApprovals.firstOrNull()?.callId,
                    question = runner.pendingQuestions.firstOrNull()?.callId,
                    appVisible = process.isAtLeast(Lifecycle.State.STARTED),
                    lock = lock,
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
            // A helper's request names the helper: "Researcher #2 wants to…". A locked chat's says nothing of it.
            val locked = pending.conversationId in lock.lockedChats
            manager.notify(
                APPROVAL_NOTIFICATION_ID,
                if (locked) lockedChatNotification(APPROVAL_CHANNEL, pending.conversationId) else approvalNotification(pending.conversationId, pending.callId, words(approvalTitle(pending, ::label)), pending.summary),
            )
        }
        else manager.cancel(APPROVAL_NOTIFICATION_ID)
        val question = runner.pendingQuestions.firstOrNull()
        @Suppress("MissingPermission")
        if (question != null && !appVisible) {
            manager.notify(
                QUESTION_NOTIFICATION_ID,
                if (question.chatId in lock.lockedChats) lockedChatNotification(QUESTION_CHANNEL, question.chatId) else questionNotification(question),
            )
        }
        else manager.cancel(QUESTION_NOTIFICATION_ID)
    }

    /**
     * The foreground notification. On Android 16+, while a run the user started is working, it asks to be
     * a Live Update: promoted to the top of the shade and the lock screen, with a short chip in the status
     * bar ("Working", "Step 4", "Asks"). It then also carries the waiting approval's Deny / Allow, or the
     * waiting question's answers, so the user can act without opening the app. Below Android 16, for a
     * scheduled task, or when the user turned Live Updates off for Locus, it stays the plain notification.
     */
    /**
     * On Android 17+, a Live Update's status line ("Waiting for your approval") is styled CAUTION while it
     * waits for the user ([Notification.createSemanticStyleAnnotation]; the system picks the colour, and
     * only for eligible, promoted notifications). Stripped of its style, [text] says the same.
     */
    private fun waitingStyled(status: AgentStatus, line: String, text: String): CharSequence {
        if (Build.VERSION.SDK_INT < 37 || !status.needsUser) return text
        return android.text.SpannableString(text).apply {
            setSpan(
                Notification.createSemanticStyleAnnotation(Notification.SEMANTIC_STYLE_CAUTION),
                0, line.length.coerceAtMost(length), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun statusNotification(appVisible: Boolean): Notification {
        val status = agentStatus(runner.states.value, runner.pendingQuestions, lock.lockedChats)
        // Worded when posted, in the app's language: Android applies the per-app language to this service's
        // resources too (LocaleManager.setApplicationLocales is a configuration change for the whole app).
        val line = words(status.text { phrases.toolActivity(resources, it) })
        val builder = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(words(status.title))
            .setContentText(waitingStyled(status, line, line))
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
            builder.setRequestPromotedOngoing(true).setShortCriticalText(status.chip(::words))
            val detail = status.detail(::label) { phrases.budgetReached(resources, it) }?.let(::words)
                ?: LockedNotifications.lockedChatText(this).takeIf { status.lockedChat }
            detail?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(waitingStyled(status, line, line + "\n" + it))) }
        }
        builder.privateWhenLocked(this, lock, STATUS_CHANNEL)
        // While the app is on screen its own cards take the answer. Approvals and questions have their own
        // notifications when this one isn't a Live Update; a budget pause has only this one.
        // A locked chat's request is answered in the chat, and with the lock on below Android 12 nothing is
        // answered from a notification (LockedNotifications).
        if (!appVisible && !status.lockedChat && LockedNotifications.actionsAllowed(lock, Build.VERSION.SDK_INT)) {
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

    private data class NotificationModel(
        val active: Int,
        val status: AgentStatus,
        val approval: String?,
        val question: String?,
        val appVisible: Boolean,
        val lock: AppLockSettings,
    )

    /** Deny, then Allow; Allow needs the phone unlocked, so it is offered only on Android 12+. */
    private fun approvalActions(callId: String): List<NotificationCompat.Action> {
        fun action(approve: Boolean, label: String) = NotificationCompat.Action.Builder(
            0,
            label,
            PendingIntent.getBroadcast(
                this,
                (callId + approve).hashCode(),
                Intent(this, ApprovalReceiver::class.java).setData(NotificationIntents.data("approval", callId, approve.toString())).putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_APPROVE, approve),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
            // Approving from a locked phone must not be possible; with Lock Locus on, denying isn't either.
            .setAuthenticationRequired(LockedNotifications.authenticationRequired(lock, approve))
            .build()
        // Below Android 12 Allow couldn't ask for the unlock, so it isn't offered: the tap opens Locus.
        return listOf(false to getString(R.string.data_notif_action_deny), true to getString(R.string.data_notif_action_allow))
            .filter { (approve, _) -> LockedNotifications.actionOffered(lock, Build.VERSION.SDK_INT, approve) }
            .map { (approve, label) -> action(approve, label) }
    }

    /** Stop, then Continue for a task paused at its budget; Continue spends more, so it needs the phone unlocked (Android 12+ only). */
    private fun budgetActions(conversationId: String): List<NotificationCompat.Action> {
        fun action(continueTask: Boolean, label: String) = NotificationCompat.Action.Builder(
            0,
            label,
            PendingIntent.getBroadcast(
                this,
                (conversationId + "#budget" + continueTask).hashCode(),
                Intent(this, BudgetReceiver::class.java).setData(NotificationIntents.data("budget", conversationId, continueTask.toString())).putExtra(EXTRA_CHAT_ID, conversationId).putExtra(EXTRA_CONTINUE, continueTask),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
            .setAuthenticationRequired(LockedNotifications.authenticationRequired(lock, continueTask))
            .build()
        // Below Android 12 Continue couldn't ask for the unlock, so it isn't offered: the tap opens Locus.
        return listOf(false to getString(R.string.data_notif_action_stop), true to getString(R.string.data_notif_action_continue))
            .filter { (continueTask, _) -> LockedNotifications.actionOffered(lock, Build.VERSION.SDK_INT, continueTask) }
            .map { (continueTask, label) -> action(continueTask, label) }
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
            .privateWhenLocked(this, lock, APPROVAL_CHANNEL)
        if (LockedNotifications.actionsAllowed(lock, Build.VERSION.SDK_INT)) approvalActions(callId).forEach { builder.addAction(it) }
        return builder.build()
    }

    /**
     * A locked chat's approval or question (docs/design/app-lock.md): only that it waits, with no actions.
     * The tap opens the chat, which asks for authentication first.
     */
    private fun lockedChatNotification(channel: String, conversationId: String): Notification =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(getString(R.string.data_lock_public_title))
            .setContentText(LockedNotifications.lockedChatText(this))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setGroup(channel)
            .setContentIntent(openAppIntent(conversationId))
            .privateWhenLocked(this, lock, channel)
            .build()

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
            .setContentTitle(words(questionTitle(question)))
            .setContentText(question.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words(questionBody(question))))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setGroup(QUESTION_CHANNEL)
            .setContentIntent(openAppIntent(question.chatId))
            .privateWhenLocked(this, lock, QUESTION_CHANNEL)
        if (LockedNotifications.actionsAllowed(lock, Build.VERSION.SDK_INT)) questionActions(question).forEach { builder.addAction(it) }
        return builder.build()
    }

    /** The question's option buttons (see [questionButtons]) and, unless every option is a button, Reply. */
    private fun questionActions(question: UserQuestion): List<NotificationCompat.Action> {
        val actions = mutableListOf<NotificationCompat.Action>()
        question.options.take(questionButtons(question)).forEachIndexed { i, option ->
            val intent = Intent(this, QuestionReceiver::class.java).setData(NotificationIntents.data("question", question.callId, i.toString())).putExtra(EXTRA_CALL_ID, question.callId).putExtra(EXTRA_OPTION, i)
            val pending = PendingIntent.getBroadcast(this, (question.callId + "#" + i).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            actions += NotificationCompat.Action.Builder(0, option.take(40), pending).setAuthenticationRequired(LockedNotifications.authenticationRequired(lock, false)).build()
        }
        if (!allOptionsAsButtons(question)) {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(getString(R.string.data_notif_reply_hint)).build()
            val intent = Intent(this, QuestionReceiver::class.java).setData(NotificationIntents.data("reply", question.callId)).putExtra(EXTRA_CALL_ID, question.callId)
            // Mutable so the system can add the typed text; the intent is explicit (our own receiver).
            // A holder's fill-in can't change EXTRA_CALL_ID: Intent.fillIn copies an extra only where this
            // intent has no value under that name (developer.android.com/reference/android/content/Intent#fillIn).
            val pending = PendingIntent.getBroadcast(this, (question.callId + "#reply").hashCode(), intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            actions += NotificationCompat.Action.Builder(0, getString(R.string.data_notif_action_reply), pending)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAuthenticationRequired(LockedNotifications.authenticationRequired(lock, false))
                .build()
        }
        return actions
    }

    private fun words(text: EngineText): String = text.asString(resources)

    /** What [request] would do, in the chat's words ("Run `ls`"). */
    private fun label(request: dev.locus.core.agent.ApprovalRequest): String = phrases.toolLabel(resources, request.toolName, request.argumentsJson)

    /** Opens Locus on [conversationId]'s chat; one request code per chat, so each notification keeps its own chat. */
    private fun openAppIntent(conversationId: String?): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        // Without a chat every notification opens the same thing, so they share request code 0 and one intent.
        if (conversationId != null) OpenChatLink.attach(this, NotificationIntents.forActivity(it, "open", conversationId), conversationId)
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
        fun postTapToContinue(context: Context, conversationId: String, title: EngineText, text: EngineText) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CONTINUE_CHANNEL, context.getString(R.string.data_notif_channel_continue), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.data_notif_channel_continue_description)
                },
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                OpenChatLink.attach(context, NotificationIntents.forActivity(it, "continue", conversationId), conversationId)
                PendingIntent.getActivity(context, (conversationId + "#continue").hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            @Suppress("MissingPermission")
            manager.notify(
                (conversationId + "#continue").hashCode(),
                NotificationCompat.Builder(context, CONTINUE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_agent)
                    .setContentTitle(title.asString(context.resources))
                    .setContentText(text.asString(context.resources))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        private const val FINISHED_CHANNEL = "agent_finished"

        /**
         * "Research finished", when a long run (the research workflow, docs/design/deep-research.md) ends
         * while Locus isn't on screen. Tapping opens the chat with the report. One per chat; a newer one replaces it.
         */
        fun postRunFinished(context: Context, conversationId: String, title: EngineText, text: EngineText) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(FINISHED_CHANNEL, context.getString(R.string.data_notif_channel_finished), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.data_notif_channel_finished_description)
                },
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                OpenChatLink.attach(context, NotificationIntents.forActivity(it, "finished", conversationId), conversationId)
                PendingIntent.getActivity(context, (conversationId + "#finished").hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            @Suppress("MissingPermission")
            manager.notify(
                (conversationId + "#finished").hashCode(),
                NotificationCompat.Builder(context, FINISHED_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_agent)
                    .setContentTitle(title.asString(context.resources))
                    .setContentText(text.asString(context.resources))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text.asString(context.resources)))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Created again on every start, so a channel's name follows the app's language (Android renames
            // an existing channel; its id, importance and the user's settings for it stay).
            manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, context.getString(R.string.data_notif_channel_status), NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(
                NotificationChannel(APPROVAL_CHANNEL, context.getString(R.string.data_notif_channel_approvals), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.data_notif_channel_approvals_description)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(QUESTION_CHANNEL, context.getString(R.string.data_notif_channel_questions), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.data_notif_channel_questions_description)
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
