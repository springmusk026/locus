package dev.locus.core.data.needsyou

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.model.TaskReceipt
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.AppLockStatus
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome

/**
 * Everything that needs the user, in one place on home (docs/design/needs-you.md): what is waiting in a chat now,
 * what scheduled and triggered runs proposed, and their results since the user last looked. Each list is newest
 * first. A locked chat's item keeps its place but says nothing about itself ([redacted]), as in the chat list.
 */
data class NeedsYouFeed(
    val waiting: List<Waiting> = emptyList(),
    val proposals: List<ProposalItem> = emptyList(),
    val results: List<ResultItem> = emptyList(),
) {
    /** What still needs a decision or a read: waiting items, proposals that can be approved, unread results. */
    val count: Int get() = waiting.size + proposals.count { it.state == ProposalState.WAITING } + results.size

    val isEmpty: Boolean get() = waiting.isEmpty() && proposals.isEmpty() && results.isEmpty()
}

/** Something a chat is waiting on right now: an approval, a question or a budget pause. */
sealed interface Waiting {
    val chatId: String
    val chatTitle: String

    /** A locked chat this session hasn't unlocked: shown as "Locked chat", with nothing of what it asks. */
    val redacted: Boolean

    /** When Needs you first saw it, for the order. */
    val since: Long

    /** Its key in a list. */
    val key: String

    data class Approval(val request: ApprovalRequest, override val chatTitle: String, override val redacted: Boolean, override val since: Long) : Waiting {
        override val chatId: String get() = request.conversationId
        override val key: String get() = "approval:${request.callId}"
    }

    data class Question(val question: UserQuestion, override val chatTitle: String, override val redacted: Boolean, override val since: Long) : Waiting {
        override val chatId: String get() = question.chatId
        override val key: String get() = "question:${question.callId}"
    }

    data class Budget(override val chatId: String, val pause: BudgetPause, override val chatTitle: String, override val redacted: Boolean, override val since: Long) : Waiting {
        override val key: String get() = "budget:$chatId"
    }
}

/** How a proposal shows in Needs you. */
enum class ProposalState {
    /** Approve or Decline. */
    WAITING,

    /** Approved; running now. */
    RUNNING,

    /** Too old: shown so the user knows it never ran, with Dismiss. */
    EXPIRED,

    /** Approved, but it failed or Locus's checks stopped it; with Dismiss. */
    FAILED,

    /** Locus closed while it ran; it may or may not have happened. With Dismiss. */
    INTERRUPTED,

    /** It came back with a restored backup and can't be run. With Dismiss. */
    STALE,
}

data class ProposalItem(val proposal: Proposal, val state: ProposalState, val chatTitle: String, val redacted: Boolean) {
    val key: String get() = "proposal:${proposal.id}"
}

/** A scheduled or triggered run's result the user hasn't opened yet: its task, how it ended, and its receipt. */
data class ResultItem(
    val taskId: String,
    val title: String,
    val outcome: TaskRunOutcome?,
    val summary: String?,
    val receipt: TaskReceipt?,
    val at: Long,
    val chatId: String,
    val eventTriggered: Boolean,
    val redacted: Boolean,
) {
    val key: String get() = "result:$taskId"
}

/**
 * Builds the feed. Pure, so ordering and redaction are tested on the JVM. [states] are the runner's per-chat states
 * (approvals, questions, budget pauses); [titles] the chats' titles by id; [receipts] the latest receipt of each
 * result's chat; [firstSeen] when an item (by key) was first seen, for the waiting list's order.
 */
fun needsYouFeed(
    states: Map<String, RunState>,
    proposals: List<Proposal>,
    tasks: List<ScheduledTask>,
    titles: Map<String, String>,
    receipts: Map<String, TaskReceipt?>,
    lock: AppLockStatus,
    now: Long,
    firstSeen: (String) -> Long,
): NeedsYouFeed {
    fun title(id: String) = titles[id].orEmpty()
    val waiting = buildList {
        states.forEach { (chatId, state) ->
            val redact = lock.redactInApp(chatId)
            state.approvals.forEach { r ->
                val key = "approval:${r.callId}"
                add(Waiting.Approval(r, title(chatId), redact, firstSeen(key)))
            }
            state.questions.forEach { q ->
                add(Waiting.Question(q, title(q.chatId), lock.redactInApp(q.chatId), firstSeen("question:${q.callId}")))
            }
            state.budgetPause?.let { pause -> add(Waiting.Budget(chatId, pause, title(chatId), redact, firstSeen("budget:$chatId"))) }
        }
    }.distinctBy { it.key }.sortedWith(compareByDescending<Waiting> { it.since }.thenBy { it.key })
    val shown = proposals.mapNotNull { p ->
        val state = when (p.status) {
            ProposalStatus.PENDING -> if (p.expired(now)) ProposalState.EXPIRED else ProposalState.WAITING
            ProposalStatus.RUNNING -> ProposalState.RUNNING
            ProposalStatus.FAILED -> ProposalState.FAILED
            ProposalStatus.INTERRUPTED -> ProposalState.INTERRUPTED
            ProposalStatus.STALE -> ProposalState.STALE
            // Done or declined: settled, their outcome is in the chat.
            ProposalStatus.DONE, ProposalStatus.DECLINED -> null
        } ?: return@mapNotNull null
        ProposalItem(p, state, title(p.conversationId), lock.redactInApp(p.conversationId))
    }.sortedWith(compareByDescending<ProposalItem> { it.proposal.createdAt }.thenBy { it.proposal.id })
    val results = tasks.filter { it.resultUnread && it.lastConversationId in titles }
        .sortedWith(compareByDescending<ScheduledTask> { it.lastRunAt }.thenBy { it.id })
        .map { t ->
            val chat = t.lastConversationId!!
            ResultItem(
                taskId = t.id,
                title = t.title,
                outcome = t.lastOutcome,
                summary = t.lastSummary,
                receipt = receipts[chat],
                at = t.lastRunAt!!,
                chatId = chat,
                eventTriggered = t.eventTriggered,
                redacted = lock.redactInApp(chat),
            )
        }
    return NeedsYouFeed(waiting, shown, results)
}
