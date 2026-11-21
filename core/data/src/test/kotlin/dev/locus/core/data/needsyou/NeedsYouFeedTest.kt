package dev.locus.core.data.needsyou

import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.AppLockSettings
import dev.locus.core.model.AppLockStatus
import dev.locus.core.model.LockSession
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.ToolRisk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Needs you's feed (docs/design/needs-you.md): what's waiting in chats, proposals and unread results, each newest
 * first; a locked chat's items keep their place but are redacted until the session is unlocked; settled proposals
 * leave; the count is what still needs a decision or a read. And the one notification's text never describes a
 * locked chat's proposal.
 */
class NeedsYouFeedTest {
    private val now = 10_000_000L

    private fun approval(chat: String, call: String) = ApprovalRequest(chat, call, "email_send", ToolRisk.NETWORK, "Send email", "{}")

    private fun proposal(id: String, chat: String, createdAt: Long, status: ProposalStatus = ProposalStatus.PENDING, expiresAt: Long = now + 1_000_000) = Proposal(
        id = id, conversationId = chat, taskId = "t", source = "Inbox", callId = "k-$id", toolName = "email_send", argumentsJson = "{}",
        summary = "Send email to $id", reason = null, flags = setOf(ApprovalFlag.MUST_CONFIRM), taintSources = emptySet(), preview = null,
        createdAt = createdAt, expiresAt = expiresAt, status = status,
    )

    private fun task(id: String, chat: String, ranAt: Long?, seen: Long? = null, outcome: TaskRunOutcome = TaskRunOutcome.DONE) =
        ScheduledTask(id, "Task $id", "p", 60, true, ranAt, "Summary $id", outcome, lastConversationId = chat.takeIf { ranAt != null }, lastSeenRunAt = seen)

    private val titles = mapOf("a" to "Book a table", "b" to "Fix tests", "locked" to "Secret plans", "r1" to "Scheduled: Inbox", "r2" to "Scheduled: News", "rl" to "Scheduled: Diary")

    private val open = AppLockStatus()

    /** "locked" is a locked chat this session hasn't unlocked. */
    private val locked = AppLockStatus(settings = AppLockSettings(enabled = true, lockedChats = setOf("locked", "rl")), available = true, session = LockSession())

    private fun feed(
        states: Map<String, RunState> = emptyMap(),
        proposals: List<Proposal> = emptyList(),
        tasks: List<ScheduledTask> = emptyList(),
        lock: AppLockStatus = open,
        seen: Map<String, Long> = emptyMap(),
    ) = needsYouFeed(states, proposals, tasks, titles, emptyMap(), lock, now) { key -> seen[key] ?: 0L }

    @Test
    fun `waiting items from every chat, newest first, with their chats' titles`() {
        val states = mapOf(
            "a" to RunState(approvals = listOf(approval("a", "c1"))),
            "b" to RunState(
                questions = listOf(UserQuestion("b", "q1", "Which branch?")),
                budgetPause = BudgetPause(BudgetLimit.TOKENS, "Paused"),
            ),
        )
        val f = feed(states, seen = mapOf("approval:c1" to 5, "question:q1" to 9, "budget:b" to 7))
        assertEquals(listOf("question:q1", "budget:b", "approval:c1"), f.waiting.map { it.key })
        assertEquals("Book a table", f.waiting.last().chatTitle)
        assertEquals(3, f.count)
    }

    @Test
    fun `a locked chat's items stay in place but are redacted until the session is unlocked`() {
        val states = mapOf("locked" to RunState(approvals = listOf(approval("locked", "c9"))))
        val f = feed(states, listOf(proposal("p", "locked", 5)), listOf(task("x", "rl", ranAt = 9)), lock = locked)
        assertTrue(f.waiting.single().redacted)
        assertTrue(f.proposals.single().redacted)
        assertTrue(f.results.single().redacted)

        val unlocked = feed(states, listOf(proposal("p", "locked", 5)), listOf(task("x", "rl", ranAt = 9)), lock = locked.copy(session = LockSession(unlocked = true)))
        assertFalse(unlocked.waiting.single().redacted)
        assertFalse(unlocked.proposals.single().redacted)
        assertFalse(unlocked.results.single().redacted)
    }

    @Test
    fun `proposals newest first, expired ones shown as expired, settled ones gone, only waiting ones counted`() {
        val f = feed(
            proposals = listOf(
                proposal("old", "r1", createdAt = 1, expiresAt = now),
                proposal("new", "r1", createdAt = 3),
                proposal("mid", "r2", createdAt = 2),
                proposal("done", "r1", createdAt = 4, status = ProposalStatus.DONE),
                proposal("no", "r1", createdAt = 5, status = ProposalStatus.DECLINED),
                proposal("run", "r1", createdAt = 6, status = ProposalStatus.RUNNING),
                proposal("lost", "r1", createdAt = 7, status = ProposalStatus.INTERRUPTED),
                proposal("bak", "r1", createdAt = 8, status = ProposalStatus.STALE),
                proposal("bad", "r1", createdAt = 9, status = ProposalStatus.FAILED),
            ),
        )
        assertEquals(listOf("bad", "bak", "lost", "run", "new", "mid", "old"), f.proposals.map { it.proposal.id })
        assertEquals(
            listOf(ProposalState.FAILED, ProposalState.STALE, ProposalState.INTERRUPTED, ProposalState.RUNNING, ProposalState.WAITING, ProposalState.WAITING, ProposalState.EXPIRED),
            f.proposals.map { it.state },
        )
        assertEquals(2, f.count)
        assertFalse(f.proposals.single { it.proposal.id == "old" }.proposal.approvable(now))
    }

    @Test
    fun `results are the unread latest runs, newest first`() {
        val f = feed(
            tasks = listOf(
                task("seen", "r1", ranAt = 50, seen = 50),
                task("older", "r1", ranAt = 20, seen = 10),
                task("newer", "r2", ranAt = 40, outcome = TaskRunOutcome.FAILED),
                task("never", "r1", ranAt = null),
                task("gone", "deleted-chat", ranAt = 60),
            ),
        )
        assertEquals(listOf("newer", "older"), f.results.map { it.taskId })
        assertEquals(TaskRunOutcome.FAILED, f.results.first().outcome)
        assertEquals("r2", f.results.first().chatId)
        assertEquals(2, f.count)
    }

    @Test
    fun `nothing waiting is an empty feed`() {
        val f = feed(states = mapOf("a" to RunState(running = true)))
        assertTrue(f.isEmpty)
        assertEquals(0, f.count)
    }

    // ---- The one notification

    @Test
    fun `the notification counts every proposal but never describes a locked chat's`() {
        val lock = AppLockSettings(enabled = false, lockedChats = setOf("locked"))
        val notice = NeedsYouNotice.of(listOf(proposal("p1", "locked", 3), proposal("p2", "r1", 2)), lock)!!
        assertEquals(2, notice.count)
        assertEquals(listOf("Inbox: Send email to p2"), notice.lines)
        assertEquals(1, notice.more)
        assertTrue(notice.lines.none { "p1" in it })

        val onlyLocked = NeedsYouNotice.of(listOf(proposal("p1", "locked", 3)), lock)!!
        assertTrue(onlyLocked.lines.isEmpty())
        assertEquals(1, onlyLocked.count)
    }

    @Test
    fun `the notification lists at most three and counts the rest`() {
        val notice = NeedsYouNotice.of((1..5).map { proposal("p$it", "r1", it.toLong()) }, AppLockSettings())!!
        assertEquals(NeedsYouNotice.MAX_LINES, notice.lines.size)
        assertEquals(2, notice.more)
        assertNull(NeedsYouNotice.of(emptyList(), AppLockSettings()))
    }
}
