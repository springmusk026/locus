package dev.locus.core.data.agent

import dev.locus.core.llm.PromptCacheKey
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The side question (`/btw`) request is built on a read-only copy of the chat: the chat's own prefix
 * (system prompt, tools, history) so the prompt cache is reused, then one marked question.
 */
class SideQuestionRequestTest {
    private val chat = Conversation(id = "c1", title = "t", providerId = "p", systemPrompt = "FROZEN SYSTEM PROMPT", createdAt = 0, updatedAt = 0)
    private val tools = listOf(ToolSpec("read_file", "Reads a file.", buildJsonObject { }))
    private var clock = 1L
    private fun msg(id: String, role: Role, vararg parts: Part) = Message(id, "c1", role, parts.toList(), clock++)

    private val user = msg("u1", Role.USER, Part.Text("tidy my notes"))
    private val call = msg("a1", Role.ASSISTANT, Part.ToolCall("k1", "read_file", "{}"))
    private val result = msg("t1", Role.TOOL, Part.ToolResult("k1", "read_file", "buy milk"))

    @Test
    fun `the chat's prefix goes unchanged, then the question as one marked user message`() {
        val history = listOf(user, call, result)
        val r = sideQuestionRequest(chat, history, tools, "  which file was that? ", id = "q", now = 9)
        assertEquals("FROZEN SYSTEM PROMPT", r.system)
        assertEquals(tools, r.tools)
        assertEquals(PromptCacheKey.forChat("c1"), r.cacheKey)
        assertEquals(history, r.messages.dropLast(1))
        val ask = r.messages.last()
        assertEquals(Role.USER, ask.role)
        assertTrue(ask.text.startsWith(SIDE_QUESTION_MARK))
        assertTrue(ask.text.endsWith("\n\nwhich file was that?"))
        assertTrue("Don't call tools" in ask.text)
    }

    @Test
    fun `a call still running gets a placeholder result in the copy only`() {
        val history = listOf(user, call)
        val r = sideQuestionRequest(chat, history, tools, "what are you doing?", id = "q", now = 9)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), r.messages.map { it.role })
        val placeholder = r.messages[2].toolResults.single()
        assertEquals("k1", placeholder.callId)
        assertEquals(STILL_RUNNING, placeholder.content)
        assertFalse(placeholder.isError)
        // The chat's own list is untouched.
        assertEquals(listOf(user, call), history)
    }

    @Test
    fun `a step with some results joins the missing ones to its tool message`() {
        val two = msg("a2", Role.ASSISTANT, Part.ToolCall("k1", "read_file", "{}"), Part.ToolCall("k2", "read_file", "{}"))
        val half = msg("t2", Role.TOOL, Part.ToolResult("k1", "read_file", "one"))
        val copy = closeOpenCalls(listOf(user, two, half), "q", 9)
        assertEquals(3, copy.size)
        assertEquals(listOf("k1", "k2"), copy.last().toolResults.map { it.callId })
    }

    @Test
    fun `a chat ending on the user's message gets the question in that message, not a second user turn`() {
        val r = sideQuestionRequest(chat, listOf(user), tools, "how long will this take?", id = "q", now = 9)
        assertEquals(1, r.messages.size)
        val parts = r.messages.single().parts
        assertEquals(Part.Text("tidy my notes"), parts.first())
        assertTrue((parts.last() as Part.Text).text.startsWith(SIDE_QUESTION_MARK))
    }

    @Test
    fun `earlier side questions are replayed in the question, not as turns`() {
        val earlier = listOf(SideExchange("e1", "which branch?", "main", done = true))
        val r = sideQuestionRequest(chat, listOf(user, call, result), tools, "and the remote?", earlier, id = "q", now = 9)
        assertEquals(4, r.messages.size)
        val text = r.messages.last().text
        assertTrue("Earlier side questions:\nQ: which branch?\nA: main" in text)
        assertTrue(text.endsWith("and the remote?"))
    }

    @Test
    fun `a task budget is sent as the task's requests send it`() {
        assertEquals(50_000L, sideQuestionRequest(chat, listOf(user), tools, "q", taskBudget = 50_000).taskBudget)
    }
}
