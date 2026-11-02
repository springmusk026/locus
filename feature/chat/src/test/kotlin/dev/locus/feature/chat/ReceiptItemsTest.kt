package dev.locus.feature.chat

import dev.locus.core.agent.TaskReceipts
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ReceiptApprovals
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TokenUsage
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Where task receipts sit in the transcript, what they link to, and how a chat export shows them. */
class ReceiptItemsTest {
    private fun msg(id: String, role: Role, at: Long, vararg parts: Part, usage: TokenUsage? = null) =
        Message(id, "c", role, parts.toList(), at, usage = usage, reply = if (role == Role.ASSISTANT) ReplyInfo("p", "claude-x") else null)

    private val messages = listOf(
        msg("u1", Role.USER, 10, Part.Text("Fix the tests")),
        msg("a1", Role.ASSISTANT, 20, Part.ToolCall("c1", "run_command", """{"command":"./gradlew test"}"""), Part.ToolCall("c2", "write_file", """{"path":"a.kt"}"""), usage = TokenUsage(1_000, 50)),
        msg("t1", Role.TOOL, 30, Part.ToolResult("c1", "run_command", "Exit code 0 (2.0 s)\nok"), Part.ToolResult("c2", "write_file", "Wrote a.kt")),
        msg("a2", Role.ASSISTANT, 40, Part.Text("Fixed."), usage = TokenUsage(1_200, 20)),
        // A second task that failed before the model answered.
        msg("u2", Role.USER, 100, Part.Text("Now the docs")),
    )
    private val change = TrackedChange(1, "a1", "a.kt", TrackedChange.Kind.MODIFIED, 3, 1, null, binary = false, isDirectory = false, undone = false, canUndo = true)
    private val records = listOf(
        TaskRecord("t1", "c", 5, 50, TaskOutcome.DONE),
        TaskRecord("t2", "c", 95, 110, TaskOutcome.FAILED, "Quota exceeded"),
    )
    private val audit = listOf(
        AuditEntry("x", "c", "c2", "write_file", "{}", ApprovalDecision.USER_APPROVED, "", false, 25, 26),
    )
    private val receipts = TaskReceipts.build(records, messages, audit, mapOf("a1" to listOf("a.kt")))
    private val items = transcriptItems(messages, changes = mapOf("a1" to listOf(change)), receipts = receipts)

    @Test
    fun `a receipt follows everything its turn shows, and a failed task's follows the user's message`() {
        val keys = items.map { it.key }
        assertEquals(listOf("u1", "a1:c1", "a1:c2", "a2", "a1:changes", "receipt:t1", "u2", "receipt:t2"), keys)
        val failed = items.last()
        assertIs<ChatItem.Receipt>(failed)
        assertEquals("Failed · 0s", TaskReceipts.summary(failed.receipt))
    }

    @Test
    fun `it links to the changes card, the verification card and the task's Activity entries`() {
        val done = items.filterIsInstance<ChatItem.Receipt>().first()
        assertEquals("a1:changes", done.changesKey)
        assertEquals("a1:c1", done.checkKey)
        assertTrue(items.any { it.key == done.changesKey && it is ChatItem.Changes })
        assertTrue(items.any { it.key == done.checkKey && it is ChatItem.Verification })
        val links = approvalLinks(done.receipt)
        assertEquals(listOf("1 asked", "1 you approved"), links.map { it.text })
        val approved = links[1].filter
        assertEquals(ReceiptApprovals.APPROVED, approved.decisions)
        assertEquals(5L to 50L, approved.since to approved.until)
        assertEquals("c", approved.conversationId)
    }

    @Test
    fun `the receipt doesn't change what a turn ends with`() {
        // The reply still carries the turn's branch controls.
        val reply = items.filterIsInstance<ChatItem.Assistant>().single()
        assertTrue(reply.turn != null)
        assertFalse(items.any { it is ChatItem.TurnEnd && it.key.startsWith("a1") })
    }

    @Test
    fun `a chat export has a short receipt block after the task`() {
        val md = ChatExport.markdown("Tests", 0, items, ZoneOffset.UTC)
        assertTrue(
            "- Changed `a.kt` (+3 −1)\n\n_Task receipt: Done · 0s · 1 file · tests passed · 1 approval · cost unknown_\n- Steps: 2\n" in md,
            md,
        )
        assertTrue("- Approvals: 1 asked · 1 you approved\n" in md, md)
        assertTrue("**You:**\n\nNow the docs\n\n_Task receipt: Failed · 0s_\n- Outcome: Failed: Quota exceeded" in md, md)
    }
}
