package dev.locus.feature.activity

import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RunsTest {
    private val min = 60_000L
    private var n = 0

    private fun call(chat: String, at: Long, decision: ApprovalDecision = ApprovalDecision.AUTO_ALLOWED, error: Boolean = false) =
        AuditEntry("e${n++}", chat, "c$n", "read_file", "{}", decision, "", error, at, at + 1_000)

    private fun runs(vararg oldestFirst: AuditEntry) = clusterRuns(oldestFirst.reversed(), { RunChat(it, exists = true) })

    @Test fun `close calls in one chat make one run, oldest first`() {
        val r = runs(call("a", 0), call("a", 5 * min), call("a", 14 * min))
        assertEquals(1, r.size)
        assertEquals(listOf("e0", "e1", "e2"), r[0].entries.map { it.id })
    }

    @Test fun `a quiet gap over ten minutes starts a new run, newest run first`() {
        val r = runs(call("a", 0), call("a", 30 * min), call("a", 31 * min))
        assertEquals(listOf(2, 1), r.map { it.entries.size })
    }

    @Test fun `interleaved chats are clustered separately`() {
        val r = runs(call("a", 0), call("b", 1 * min), call("a", 2 * min), call("b", 3 * min))
        assertEquals(listOf("b", "a"), r.map { it.conversationId })
        assertEquals(listOf(2, 2), r.map { it.entries.size })
    }

    @Test fun `declines are not failures, and asked counts the user's decisions`() {
        val r = runs(
            call("a", 0, ApprovalDecision.USER_DENIED, error = true),
            call("a", min, ApprovalDecision.USER_APPROVED, error = true),
            call("a", 2 * min, ApprovalDecision.POLICY_DENIED, error = true),
        ).single()
        assertEquals(1, r.failed)
        assertEquals(2, r.asked)
    }

    @Test fun `the run key stays put as newer calls arrive`() {
        val before = runs(call("a", 0)).single().key
        n = 0
        val after = runs(call("a", 0), call("a", min)).single().key
        assertEquals(before, after)
    }
}
