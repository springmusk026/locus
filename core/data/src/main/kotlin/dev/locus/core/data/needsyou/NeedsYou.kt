package dev.locus.core.data.needsyou

import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.ProposalApproval
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.TaskReceiptRepository
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.model.TaskReceipt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Needs you (docs/design/needs-you.md): the feed home shows, and what its buttons do. Approvals, questions and
 * budget pauses come from the runner (they live in their chats); proposals and unread results from the database.
 * Nothing shows until the lock's settings are read, so a locked chat is never shown before it can be hidden.
 */
@Singleton
class NeedsYou internal constructor(
    private val runner: AgentRunner,
    private val proposals: ProposalRepository,
    private val tasks: TaskRepository,
    conversations: ConversationRepository,
    private val receipts: TaskReceiptRepository,
    appLock: AppLock,
    private val notifier: NeedsYouNotifier?,
    private val clock: () -> Long,
    /** How often expiry is looked at again while the feed is watched; null: only when something else changes (tests). */
    tickMs: Long?,
) {
    @Inject constructor(
        runner: AgentRunner,
        proposals: ProposalRepository,
        tasks: TaskRepository,
        conversations: ConversationRepository,
        receipts: TaskReceiptRepository,
        appLock: AppLock,
        notifier: NeedsYouNotifier,
    ) : this(runner, proposals, tasks, conversations, receipts, appLock, notifier, System::currentTimeMillis, 60_000L)

    /** When each waiting item was first seen (they carry no time of their own), for newest first. */
    private val seen = ConcurrentHashMap<String, Long>()

    private val ticks: Flow<Long> = if (tickMs == null) {
        kotlinx.coroutines.flow.flowOf(0L)
    } else {
        flow {
            while (true) {
                emit(clock())
                delay(tickMs)
            }
        }
    }

    private data class Inputs(
        val states: Map<String, dev.locus.core.data.agent.RunState>,
        val proposals: List<Proposal>,
        val tasks: List<dev.locus.core.model.ScheduledTask>,
        val titles: Map<String, String>,
        val lock: dev.locus.core.model.AppLockStatus,
        val now: Long,
    )

    /** Only the parts of the runner's states the feed reads, so streamed text doesn't rebuild it. */
    private val waitingStates = runner.states.map { all ->
        all.filterValues { it.approvals.isNotEmpty() || it.questions.isNotEmpty() || it.budgetPause != null }
            .mapValues { (_, s) -> dev.locus.core.data.agent.RunState(approvals = s.approvals, questions = s.questions, budgetPause = s.budgetPause) }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val feed: Flow<NeedsYouFeed> = combine(
        combine(waitingStates, proposals.open, tasks.tasks, ::Triple),
        conversations.all.map { list -> list.associate { it.id to it.title } }.distinctUntilChanged(),
        appLock.status.filterNotNull(),
        ticks,
    ) { (states, open, taskList), titles, lock, _ -> Inputs(states, open, taskList, titles, lock, clock()) }
        .mapLatest { i ->
            val unread = i.tasks.filter { it.resultUnread }.mapNotNull { it.lastConversationId }
            val latest: Map<String, TaskReceipt?> = unread.associateWith { id -> runCatching { receipts.latest(id) }.getOrNull() }
            // Keys no longer waiting are forgotten, so an item that comes back counts as new.
            val feed = needsYouFeed(i.states, i.proposals, i.tasks, i.titles, latest, i.lock, i.now) { key -> seen.getOrPut(key) { clock() } }
            val live = feed.waiting.mapTo(HashSet()) { it.key }
            seen.keys.retainAll(live)
            feed
        }
        .distinctUntilChanged()

    /** Approve on a proposal's card: runs it once (see [AgentRunner.approveProposal]). */
    suspend fun approve(id: String): ProposalApproval = runner.approveProposal(id).also { refresh() }

    /** Decline on a proposal's card; false when it was no longer waiting. */
    suspend fun decline(id: String): Boolean = runner.declineProposal(id).also { refresh() }

    /** Clears an expired, failed, interrupted or restored proposal from the list. */
    suspend fun dismiss(id: String) {
        proposals.dismiss(id)
        refresh()
    }

    /** A live approval answered in place: the same as answering it in its chat. */
    fun answer(callId: String, answer: ApprovalAnswer) = runner.answer(callId, answer)

    /** A result opened or dismissed. */
    suspend fun markRead(taskId: String) = tasks.markSeen(taskId)

    /** "Mark all read". */
    suspend fun markAllRead() = tasks.markAllSeen()

    private suspend fun refresh() {
        runCatching { notifier?.refresh() }
    }

    companion object {
        /** For tests: no notifications, a fake clock, and no ticking (virtual time would spin through it). */
        fun forTest(
            runner: AgentRunner,
            proposals: ProposalRepository,
            tasks: TaskRepository,
            conversations: ConversationRepository,
            receipts: TaskReceiptRepository,
            appLock: AppLock,
            clock: () -> Long,
            tickMs: Long? = null,
        ) = NeedsYou(runner, proposals, tasks, conversations, receipts, appLock, null, clock, tickMs)
    }
}
