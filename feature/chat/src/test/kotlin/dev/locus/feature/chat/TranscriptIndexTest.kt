package dev.locus.feature.chat

import dev.locus.core.model.ReceiptApprovals
import dev.locus.core.model.ReceiptTokens
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskReceipt
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the chat screen reads from the whole transcript, found in one pass instead of one per row (#321). */
class TranscriptIndexTest {
    private fun receipt(task: String) = ChatItem.Receipt(
        "receipt-$task",
        TaskReceipt(
            taskId = task, conversationId = "c", outcome = TaskOutcome.DONE, detail = null, startedAt = 0, endedAt = 1, steps = 1,
            models = emptyList(), tokens = ReceiptTokens(), cost = null, files = 0, changeTurns = emptyList(), checks = emptyList(),
            approvals = ReceiptApprovals(), outsideContent = emptyList(), taintedFromStart = false, helpers = emptyList(), afterMessageId = "m",
        ),
    )

    private fun reply(key: String) = ChatItem.Assistant(key, "done")

    @Test
    fun eachReplyBelongsToTheFirstTaskReceiptAfterIt() {
        val items = listOf(
            ChatItem.User("u1", "first"),
            reply("a1"),
            ChatItem.ToolCall("call-1", "run_command", "{}", null),
            reply("a2"),
            receipt("t1"),
            ChatItem.User("u2", "second"),
            reply("a3"),
            receipt("t2"),
            ChatItem.User("u3", "third, still running"),
            reply("a4"),
        )
        val index = transcriptIndex(items)
        // The same answers as the per-row scan it replaces: the first receipt at or after the reply.
        val scan = items.filterIsInstance<ChatItem.Assistant>().mapNotNull { a ->
            items.dropWhile { it.key != a.key }.firstNotNullOfOrNull { (it as? ChatItem.Receipt)?.receipt?.taskId }?.let { a.key to it }
        }.toMap()
        assertEquals(mapOf("a1" to "t1", "a2" to "t1", "a3" to "t2"), index.taskByReply)
        assertEquals(scan, index.taskByReply)
        assertEquals("u3", index.lastUserKey)
        assertTrue(index.usedTerminal)
        assertFalse(index.usedBrowser)
    }

    @Test
    fun panelTogglesFromTheIndexMatchTheTranscript() {
        val items = listOf(ChatItem.ToolCall("call-1", "browser_open", "{}", null))
        val index = transcriptIndex(items)
        listOf(true, false).forEach { page ->
            assertEquals(panelToggles(items, null, page, null), panelToggles(index, null, page, null))
            assertEquals(panelToggles(items, "run_command", page, null), panelToggles(index, "run_command", page, null))
        }
    }
}
