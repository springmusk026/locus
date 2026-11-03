package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The ChatGPT-route request after the histories a long agent run can leave behind (a tool error, Stop
 * mid-step, a crash, many replayed reasoning items, a huge result): its `input` must stay one the API
 * accepts ([ResponsesInput.problems] empty), and a sound history must go out exactly as stored.
 */
class ResponsesInputTest {
    private val config = ProviderConfig("p", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-6.1-sol", effort = "high", requiresKey = false)
    private val model = OpenAiResponsesModel(RecordingEngine(body = "").client, config, apiKey = null, bearer = BearerSource { "at-1" })
    private val tools = listOf(ToolSpec("run_command", "Runs", JsonObject(mapOf("type" to JsonPrimitive("object")))))

    private fun reasoning(n: Int) = """{"id":"rs_$n","type":"reasoning","summary":[],"encrypted_content":"ENC$n"}"""
    private fun call(n: Int) = """{"id":"fc_$n","type":"function_call","call_id":"call_$n","name":"run_command","namespace":"locus","arguments":"{}","status":"completed"}"""

    private fun assistant(n: Int, raw: String = "[${reasoning(n)},${call(n)}]") =
        Message("a$n", "c", Role.ASSISTANT, listOf(Part.ToolCall("call_$n", "run_command", "{}")), n * 10L, ApiFormat.CHATGPT, raw)

    private fun result(n: Int, content: String = "ok", error: Boolean = false) =
        Message("t$n", "c", Role.TOOL, listOf(Part.ToolResult("call_$n", "run_command", content, isError = error)), n * 10L + 1)

    private val user = Message("u", "c", Role.USER, listOf(Part.Text("Build the app")), 0)

    private fun input(history: List<Message>) = model.buildBody(ChatRequest("You are Locus.", history, tools, "locus-chat-c")) ["input"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `a long sound run goes out exactly as stored, every reasoning item replayed`() {
        val history = listOf(user) + (1..150).flatMap { listOf(assistant(it), result(it, "x".repeat(16_000))) }
        val items = input(history)
        assertEquals(emptyList(), ResponsesInput.problems(items))
        assertEquals(1 + 150 * 3, items.size)
        assertEquals(150, items.count { it["type"]!!.jsonPrimitive.content == "reasoning" })
        // normalize leaves a sound list untouched (the same instance: nothing rebuilt, the cache prefix holds).
        val raw = model.toInputItems(history)
        assertSame(raw, ResponsesInput.normalize(raw))
    }

    @Test
    fun `a tool error and a stop mid-step still give a valid request`() {
        val stopped = Message(
            "t2", "c", Role.TOOL,
            listOf(Part.ToolResult("call_2", "run_command", "Cancelled by the user before it finished.", isError = true)), 21,
        )
        val history = listOf(user, assistant(1), result(1, "exit 1: build failed", error = true), assistant(2), stopped, Message("u2", "c", Role.USER, listOf(Part.Text("go on")), 30))
        val items = input(history)
        assertEquals(emptyList(), ResponsesInput.problems(items))
        assertTrue(items.any { it["output"]?.jsonPrimitive?.content == "ERROR: exit 1: build failed" })
    }

    @Test
    fun `a call that lost its result gets one right after it`() {
        val history = listOf(user, assistant(1), result(1), assistant(2), Message("u2", "c", Role.USER, listOf(Part.Text("again")), 30))
        assertEquals(listOf("call call_2 has no output"), ResponsesInput.problems(model.toInputItems(history)))
        val items = input(history)
        assertEquals(emptyList(), ResponsesInput.problems(items))
        val at = items.indexOfFirst { it["call_id"]?.jsonPrimitive?.content == "call_2" && it["type"]!!.jsonPrimitive.content == "function_call" }
        assertEquals("function_call_output", items[at + 1]["type"]!!.jsonPrimitive.content)
        assertEquals(ResponsesInput.NO_RESULT, items[at + 1]["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an orphan output, reasoning without encrypted content and repeated ids are dropped`() {
        val noEnc = """{"id":"rs_9","type":"reasoning","summary":[]}"""
        val history = listOf(
            user,
            result(7),
            assistant(1, "[$noEnc,${reasoning(1)},${call(1)}]"),
            result(1),
            assistant(2, "[${reasoning(1)},${call(2)}]"),
            result(2),
        )
        assertEquals(3, ResponsesInput.problems(model.toInputItems(history)).size)
        val items = input(history)
        assertEquals(emptyList(), ResponsesInput.problems(items))
        assertTrue(items.none { it["call_id"]?.jsonPrimitive?.content == "call_7" })
        assertEquals(1, items.count { it["id"]?.jsonPrimitive?.content == "rs_1" })
        assertTrue(items.none { it["id"]?.jsonPrimitive?.content == "rs_9" })
    }

    @Test
    fun `turns from another provider, then this one, pair up`() {
        val other = Message("x", "c", Role.ASSISTANT, listOf(Part.Text("Let me check."), Part.ToolCall("toolu_1", "run_command", "{}")), 5, ApiFormat.ANTHROPIC_MESSAGES, "[]")
        val history = listOf(user, other, Message("y", "c", Role.TOOL, listOf(Part.ToolResult("toolu_1", "run_command", "ok")), 6), assistant(1), result(1))
        assertEquals(emptyList(), ResponsesInput.problems(input(history)))
    }

    @Test
    fun `the body asks for encrypted reasoning, keeps one cache key and no system item`() {
        val body = model.buildBody(ChatRequest("You are Locus.", listOf(user), tools, "locus-chat-c"))
        assertEquals("reasoning.encrypted_content", body["include"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("locus-chat-c", body["prompt_cache_key"]!!.jsonPrimitive.content)
        assertTrue(body["input"]!!.jsonArray.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" })
        assertEquals(null, model.buildBody(ChatRequest("s", listOf(user), tools), withReasoning = false)["include"])
    }
}
