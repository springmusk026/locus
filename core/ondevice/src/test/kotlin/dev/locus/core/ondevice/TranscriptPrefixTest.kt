package dev.locus.core.ondevice

import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptPrefixTest {
    private var n = 0
    private fun msg(role: Role, vararg parts: Part) = Message("m${n++}", "c", role, parts.toList(), n.toLong())

    private val user = msg(Role.USER, Part.Text("write hello.txt"))
    private val call = Part.ToolCall("call_1", "write_file", """{"path":"hello.txt"}""")

    @Test
    fun `a tool result after our own turn continues the conversation`() {
        val consumed = listOf(TurnKey.of(user), TurnKey.assistant("On it.", listOf(call)))
        val assistant = msg(Role.ASSISTANT, Part.Text("On it."), call) // the agent's copy has a new id and time
        val result = msg(Role.TOOL, Part.ToolResult("call_1", "write_file", "ok"))
        assertTrue(TranscriptPrefix.continues(consumed, listOf(user, assistant, result).map(TurnKey::of)))
    }

    @Test
    fun `empty text is not a part, matching how the agent builds the message`() {
        val consumed = listOf(TurnKey.of(user), TurnKey.assistant("", listOf(call)))
        val assistant = msg(Role.ASSISTANT, call)
        val result = msg(Role.TOOL, Part.ToolResult("call_1", "write_file", "ok"))
        assertTrue(TranscriptPrefix.continues(consumed, listOf(user, assistant, result).map(TurnKey::of)))
    }

    @Test
    fun `the approval decision on a tool result is ignored`() {
        val result = Part.ToolResult("call_1", "write_file", "ok")
        val decided = result.copy(decision = dev.locus.core.model.ApprovalDecision.entries.first())
        assertEquals(TurnKey.of(msg(Role.TOOL, result)), TurnKey.of(msg(Role.TOOL, decided)))
    }

    @Test
    fun `a changed earlier message forces a rebuild`() {
        val consumed = listOf(TurnKey.of(user), TurnKey.assistant("Hi", emptyList()))
        val edited = msg(Role.USER, Part.Text("write bye.txt"))
        val next = msg(Role.USER, Part.Text("thanks"))
        assertFalse(TranscriptPrefix.continues(consumed, listOf(edited, msg(Role.ASSISTANT, Part.Text("Hi")), next).map(TurnKey::of)))
    }

    @Test
    fun `a different reply than the one generated forces a rebuild`() {
        val consumed = listOf(TurnKey.of(user), TurnKey.assistant("Hi", emptyList()))
        val next = msg(Role.USER, Part.Text("thanks"))
        assertFalse(TranscriptPrefix.continues(consumed, listOf(user, msg(Role.ASSISTANT, Part.Text("Hello")), next).map(TurnKey::of)))
    }

    @Test
    fun `retrying the same request or skipping ahead forces a rebuild`() {
        val consumed = listOf(TurnKey.of(user), TurnKey.assistant("Hi", emptyList()))
        // Same request again (a retry after an error): nothing new to send.
        assertFalse(TranscriptPrefix.continues(consumed, consumed))
        assertFalse(TranscriptPrefix.continues(consumed, listOf(TurnKey.of(user))))
        // Two new messages.
        val a = msg(Role.USER, Part.Text("a"))
        val b = msg(Role.USER, Part.Text("b"))
        assertFalse(TranscriptPrefix.continues(consumed, listOf(user, msg(Role.ASSISTANT, Part.Text("Hi")), a, b).map(TurnKey::of)))
    }

    @Test
    fun `the new message must not be an assistant message`() {
        val consumed = listOf(TurnKey.of(user), TurnKey.assistant("Hi", emptyList()))
        val request = listOf(user, msg(Role.ASSISTANT, Part.Text("Hi")), msg(Role.ASSISTANT, Part.Text("more"))).map(TurnKey::of)
        assertFalse(TranscriptPrefix.continues(consumed, request))
    }

    @Test
    fun `a new user message after a finished turn continues`() {
        val consumed = listOf(TurnKey.of(user), TurnKey.assistant("Done.", emptyList()))
        val request = listOf(user, msg(Role.ASSISTANT, Part.Text("Done.")), msg(Role.USER, Part.Text("thanks"))).map(TurnKey::of)
        assertTrue(TranscriptPrefix.continues(consumed, request))
    }
}
