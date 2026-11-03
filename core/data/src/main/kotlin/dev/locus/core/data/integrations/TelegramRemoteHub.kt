package dev.locus.core.data.integrations

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.Telegram
import dev.locus.core.agent.integrations.TelegramRemote
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.IntegrityKey
import dev.locus.core.model.ModeNote
import dev.locus.core.model.runCatchingCancellable
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the Telegram remote channel (docs/design/telegram-remote.md) inside the app:
 * - approvals of tasks running while Locus is in the background go to the bot chat (with buttons,
 *   unless they need a tap in Locus), and are withdrawn when they end there;
 * - remote mode: [TelegramRemoteService] keeps the process in the foreground with a notification, and
 *   switches off at its time limit;
 * - getUpdates long polling runs only while buttons wait for a press or remote mode is on. Either way a
 *   foreground service is already up (AgentService for a pending approval), so no extra wakeups.
 *
 * The bot token is read from the vault for each call and never logged or shown to the model.
 */
@Singleton
class TelegramRemoteHub @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val http: HttpClient,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val runner: dagger.Lazy<AgentRunner>,
    private val conversations: ConversationRepository,
    private val integrityKey: IntegrityKey,
) {
    private val started = AtomicBoolean(false)
    private val chatLock = Mutex()

    /** Bumped when the offered approvals change, so polling starts or stops. */
    private val offeredTick = MutableStateFlow(0)

    private val host = object : TelegramRemote.Host {
        override suspend fun remote(): Pair<Boolean, Long> = settings.current().telegramRemote.let { it.inboundOn(now()) to it.since }

        override fun answer(callId: String, approve: Boolean): Boolean {
            val agents = runner.get()
            if (agents.pendingApprovals.none { it.callId == callId }) return false
            agents.answer(callId, approve)
            return true
        }

        override suspend fun task(text: String): String = startTask(text)

        override suspend fun turnOff() {
            settings.updateTelegramRemote { it.copy(inbound = false) }
        }

        override fun now(): Long = System.currentTimeMillis()
    }

    private val channel = TelegramRemote.Channel(
        http, host,
        inbox = TelegramInboxFile(java.io.File(context.noBackupFilesDir, "telegram/inbox.json"), integrityKey),
    )

    private suspend fun target(): TelegramRemote.Target? =
        TelegramRemote.Target.of(secrets.get(SecretStore.connectorSecret(Integrations.telegram.secret))?.let(Telegram::parse))

    /** Once per process, from the Application. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // A new process: any remote-mode service from before is gone with the old one, so it's off
            // until the user turns it on again.
            settings.updateTelegramRemote { if (it.inbound) it.copy(inbound = false) else it }
            scope.launch { offerApprovals() }
            scope.launch { pollWhileNeeded() }
            scope.launch { keepRemoteMode() }
        }
    }

    /**
     * While approvals by button are on: offers each new approval to the chat when Locus is in the
     * background, and withdraws the ones that ended. Switched off: withdraws them all.
     */
    private suspend fun offerApprovals() {
        settings.settings.map { it.telegramRemote.approvals }.distinctUntilChanged().collectLatest { on ->
            if (!on) {
                target()?.let { t -> channel.offered().forEach { runCatchingCancellable { channel.withdraw(t, it, "Approvals from Telegram were switched off. Answer in Locus.") } } }
                offeredTick.value++
                return@collectLatest
            }
            val agents = runner.get()
            combine(
                agents.states.map { agents.pendingApprovals }.distinctUntilChanged { a, b -> a.map { it.callId } == b.map { it.callId } },
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged(),
            ) { pending, visible -> pending to visible }
                .collect { (pending, visible) ->
                    val target = target()
                    if (target != null) {
                        val waiting = pending.map { it.callId }.toSet()
                        channel.offered().filter { it !in waiting }.forEach { callId ->
                            runCatchingCancellable { channel.withdraw(target, callId, "No longer waiting: answered in Locus, timed out, or the task ended.") }
                        }
                        if (!visible) {
                            pending.filter { it.callId !in channel.offered() }.forEach { request ->
                                try {
                                    channel.offer(target, request)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // The in-app card and notification still work; nothing to retry here.
                                }
                            }
                        }
                    }
                    offeredTick.value++
                }
        }
    }

    /** getUpdates in a loop, only while buttons wait for a press or remote mode is on; backs off on errors. */
    private suspend fun pollWhileNeeded() {
        combine(settings.settings.map { it.telegramRemote }.distinctUntilChanged(), offeredTick) { remote, _ -> remote }
            .collectLatest { remote ->
                var backoff = MIN_BACKOFF_MS
                while (remote.inboundOn(System.currentTimeMillis()) || channel.needsPolling()) {
                    val target = target() ?: return@collectLatest
                    try {
                        channel.poll(target)
                        backoff = MIN_BACKOFF_MS
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Telegram.Failure) {
                        // A refused token won't get better by asking again.
                        if (e.status == 401 || e.status == 404) return@collectLatest
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    } catch (_: Exception) {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                    if (!settings.current().telegramRemote.inboundOn(System.currentTimeMillis()) && !channel.needsPolling()) return@collectLatest
                }
            }
    }

    /** While remote mode is on: its foreground service runs, and at its time limit it switches off. */
    private suspend fun keepRemoteMode() {
        // Only switching on (or a new time limit) restarts this, not other changes such as the chat being made.
        settings.settings.map { it.telegramRemote }.distinctUntilChanged { a, b -> a.inbound == b.inbound && a.until == b.until }.collectLatest { remote ->
            if (!remote.inbound) return@collectLatest
            val now = System.currentTimeMillis()
            if (remote.inboundOn(now) && !TelegramRemoteService.running && !TelegramRemoteService.start(context)) {
                // Android refused the service (Locus is in the background): no remote mode without it.
                settings.updateTelegramRemote { it.copy(inbound = false) }
                return@collectLatest
            }
            delay((remote.until - now).coerceAtLeast(0))
            settings.updateTelegramRemote { if (it.inbound && it.until <= System.currentTimeMillis()) it.copy(inbound = false) else it }
            target()?.let { t ->
                runCatchingCancellable { Telegram.send(http, t.target, null, "Remote mode switched itself off at its time limit. Turn it on again in Locus when you need it.") }
            }
        }
    }

    /** A message in remote mode: a task (or a note for the running one) in the Telegram chat. */
    private suspend fun startTask(text: String): String {
        val chatId = telegramChat()
        val locked = settings.current().telegramRemote.lockedChat(chatId)
        return when (val sent = runner.get().sendRemote(chatId, text, ModeNote.telegram())) {
            is AgentRunner.RemoteSend.Started -> {
                follow(sent.run)
                "Working on it in Locus (the Telegram chat" + (if (locked) ", read-only" else "") + "). The answer comes here."
            }
            AgentRunner.RemoteSend.Queued -> "Added to the task that's running; Locus reads it at its next step."
            AgentRunner.RemoteSend.Failed -> "Locus couldn't start a task. Open Locus to check."
        }
    }

    /**
     * Sends the answer of run [runId] back to the bot chat when it ends: that run's own answer, or how it
     * ended. The Telegram chat is reused for every remote task, so an earlier task's answer must never
     * stand in for this one's.
     */
    private fun follow(runId: String) {
        scope.launch {
            val run = runner.get().awaitRun(runId) ?: return@launch
            val target = target() ?: return@launch
            runCatchingCancellable { Telegram.send(http, target.target, "Locus", remoteReply(run)) }
        }
    }

    /** The chat remote messages go to, made on the first one. */
    private suspend fun telegramChat(): String = chatLock.withLock {
        settings.current().telegramRemote.chatId?.let { id -> if (conversations.get(id) != null) return@withLock id }
        val chat = conversations.create(settings.current().defaultProviderId)
        conversations.rename(chat.id, "Telegram")
        settings.updateTelegramRemote { it.copy(chatId = chat.id) }
        chat.id
    }

    companion object {
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 120_000L

        /** What the bot sends when a remote task ends: its answer, or how it ended without one. */
        internal fun remoteReply(run: dev.locus.core.data.agent.BackgroundRun): String =
            run.endLine?.let { "The task stopped. $it" } ?: run.answer ?: "Done, with nothing to report."
    }
}

