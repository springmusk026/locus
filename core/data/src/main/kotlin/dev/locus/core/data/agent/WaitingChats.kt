package dev.locus.core.data.agent

import dev.locus.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** What a chat is waiting for the user to do, most pressing first. */
enum class WaitingKind { APPROVAL, QUESTION, BUDGET }

/** A chat waiting for the user: what for, and since when (the first time Locus saw it waiting). */
data class WaitingChat(val chatId: String, val kind: WaitingKind, val since: Long)

/**
 * What each chat is waiting for, from its [RunState]: an approval (its own or a helper's), else an
 * `ask_user` question, else a budget pause. Scheduled runs never wait for anyone (they're read-only),
 * and a chat with none of these isn't listed. Pure.
 */
fun waitingKinds(states: Map<String, RunState>): Map<String, WaitingKind> = buildMap {
    for ((id, s) in states) {
        if (s.scheduled) continue
        val kind = when {
            s.approvals.isNotEmpty() -> WaitingKind.APPROVAL
            s.questions.isNotEmpty() -> WaitingKind.QUESTION
            s.budgetPause != null -> WaitingKind.BUDGET
            else -> null
        }
        if (kind != null) put(id, kind)
    }
}

/**
 * The waiting list after [now] is seen at [at]: a chat that was already waiting keeps its time (even
 * when what it waits for changes, say from a question to an approval), a new one gets [at], and one no
 * longer waiting leaves. Oldest first, ties by chat id, so the order never flickers. Pure.
 */
fun trackWaiting(previous: List<WaitingChat>, now: Map<String, WaitingKind>, at: Long): List<WaitingChat> {
    val since = previous.associate { it.chatId to it.since }
    return now.map { (id, kind) -> WaitingChat(id, kind, since[id] ?: at) }
        .sortedWith(compareBy<WaitingChat> { it.since }.thenBy { it.chatId })
}

/**
 * Every chat waiting for the user, oldest first: the source of the "questions waiting" banner on home
 * and in a chat (docs/design/approvals-waiting.md). Built on [AgentRunner.states], the flow the status
 * notification and the widget use; nothing is polled. Kept for the app's life, so "oldest" means the
 * chat that has waited longest even while no screen showed the banner.
 */
@Singleton
class WaitingChats internal constructor(states: kotlinx.coroutines.flow.Flow<Map<String, RunState>>, scope: CoroutineScope, clock: () -> Long) {
    @Inject constructor(runner: AgentRunner, @ApplicationScope scope: CoroutineScope) : this(runner.states, scope, System::currentTimeMillis)

    val chats: StateFlow<List<WaitingChat>> = states
        .map(::waitingKinds)
        .distinctUntilChanged()
        .runningFold(emptyList<WaitingChat>()) { previous, now -> trackWaiting(previous, now, clock()) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    companion object {
        /** For tests: [states] as the runner's, on [scope], with [clock]. */
        fun forTest(states: kotlinx.coroutines.flow.Flow<Map<String, RunState>>, scope: CoroutineScope, clock: () -> Long): WaitingChats =
            WaitingChats(states, scope, clock)
    }
}
