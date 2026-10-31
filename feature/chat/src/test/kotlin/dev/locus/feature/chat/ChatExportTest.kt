package dev.locus.feature.chat

import dev.locus.core.model.Message
import dev.locus.core.model.MessageTree
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TreeNode
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatExportTest {
    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "c", role, parts.toList(), 0)
    private val utc = ZoneOffset.UTC

    private fun export(messages: List<Message>) = ChatExport.markdown("Fibonacci", 1_793_448_000_000, transcriptItems(messages), utc)

    @Test
    fun `turns, code fences, tool steps and attachments`() {
        val md = export(
            listOf(
                msg("u1", Role.USER, Part.Text("Write fib"), Part.Attachment("notes.txt", "text/plain", "/x/notes.txt", "uploads/notes.txt", 3)),
                msg("a1", Role.ASSISTANT, Part.Reasoning("secret thinking"), Part.ToolCall("c1", "run_command", """{"command":"python3 fib.py"}""")),
                msg("t1", Role.TOOL, Part.ToolResult("c1", "run_command", "0 1 1 2 3")),
                msg("a2", Role.ASSISTANT, Part.Text("Here it is:\n\n```python\nprint(1)\n```")),
            ),
        )
        assertTrue(md.startsWith("# Fibonacci\n\n_31 October 2026"))
        assertTrue("**You:**\n\nWrite fib\n\nAttached: `notes.txt`" in md)
        assertTrue("**Locus:**\n\n- Ran `python3 fib.py`\n\nHere it is:\n\n```python\nprint(1)\n```" in md)
        assertFalse("secret thinking" in md, "reasoning is excluded")
        assertFalse("0 1 1 2 3" in md, "tool output is excluded")
    }

    @Test
    fun `only the active branch is exported`() {
        val rows = listOf(
            msg("u1", Role.USER, Part.Text("Question")) to null,
            msg("old", Role.ASSISTANT, Part.Text("Old answer")) to "u1",
            msg("new", Role.ASSISTANT, Part.Text("New answer")) to "u1",
        )
        val tree = MessageTree(rows.map { (m, parent) -> TreeNode(m.id, parent) })
        val path = tree.path("new").map { id -> rows.first { it.first.id == id }.first }
        val md = export(path)
        assertTrue("New answer" in md)
        assertFalse("Old answer" in md)
    }

    @Test
    fun `secrets in tool labels and text are redacted`() {
        val md = export(
            listOf(
                msg("u1", Role.USER, Part.Text("my key is sk-ant-abcdefghijklmnopqrstuvwx")),
                msg(
                    "a1", Role.ASSISTANT,
                    Part.ToolCall("c1", "run_command", """{"command":"curl -H 'Authorization: Bearer abcdefghijklmnop1234' api.x"}"""),
                    Part.ToolCall("c2", "browser_type", """{"ref":"e3","text":"hunter2"}"""),
                    Part.ToolCall("c3", "run_command", """{"command":"export TOKEN=s3cr3tvalue"}"""),
                ),
            ),
        )
        assertFalse("abcdefghijklmnopqrstuvwx" in md)
        assertFalse("abcdefghijklmnop1234" in md)
        assertFalse("hunter2" in md)
        assertFalse("s3cr3tvalue" in md)
        assertTrue("Type into the page (not run)" in md)
    }

    @Test
    fun `file names are safe and dated`() {
        assertEquals("a b c 2026-10-31.md", ChatExport.fileName("a/b: c", 1_793_448_000_000, "md", utc))
        assertEquals("Chat.pdf", ChatExport.fileName("  ", 0, "pdf", utc))
    }
}
