package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiChatModelTest {
    private val config = ProviderConfig("p", "Ollama", ApiFormat.OPENAI_CHAT, "http://10.0.0.2:11434/v1", "qwen3", requiresKey = false)
    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())

    @Test
    fun `assembles streamed tool call fragments by index`() = runTest {
        val stream = sse(
            null to """{"choices":[{"delta":{"content":"Let me look."}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"list_files","arguments":""}}]}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\".\"}"}}]}}]}""",
            null to """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            null to "[DONE]",
        )
        val engine = RecordingEngine(body = stream)
        val turn = (OpenAiChatModel(engine.client, config, null).stream(request).toList().last() as ChatEvent.Completed).turn

        assertEquals("Let me look.", turn.text)
        assertEquals(StopReason.TOOL_USE, turn.stopReason)
        assertEquals(Part.ToolCall("call_a", "list_files", """{"path":"."}"""), turn.toolCalls.single())
        assertNull(engine.requests.last().headers["Authorization"])
    }

    @Test
    fun `extra_content on tool calls is kept and sent back`() = runTest {
        val stream = sse(
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"list_files","arguments":"{}"},"extra_content":{"google":{"thought_signature":"sig=="}}}]}}]}""",
            null to """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            null to "[DONE]",
        )
        val model = OpenAiChatModel(RecordingEngine(body = stream).client, config, null)
        val turn = (model.stream(request).toList().last() as ChatEvent.Completed).turn
        val replay = model.toWireMessages(listOf(Message("a", "c", Role.ASSISTANT, turn.toolCalls, 0, ApiFormat.OPENAI_CHAT, turn.rawPayload)))
        val call = replay[0]["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("sig==", call["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `calls without extra_content send none`() = runTest {
        val model = OpenAiChatModel(RecordingEngine(body = "").client, config, null)
        val wire = model.toWireMessages(listOf(Message("a", "c", Role.ASSISTANT, listOf(Part.ToolCall("c1", "x", "{}")), 0, ApiFormat.OPENAI_CHAT, null)))
        assertNull(wire[0]["tool_calls"]!!.jsonArray[0].jsonObject["extra_content"])
    }

    @Test
    fun `tool turns map to assistant tool_calls and tool messages`() {
        val model = OpenAiChatModel(RecordingEngine(body = "").client, config, "k")
        val wire = model.toWireMessages(
            listOf(
                Message("1", "c", Role.ASSISTANT, listOf(Part.ToolCall("call_a", "list_files", "{}")), 0, ApiFormat.ANTHROPIC_MESSAGES, "[]"),
                Message("2", "c", Role.TOOL, listOf(Part.ToolResult("call_a", "list_files", "a.txt")), 1),
            ),
        )
        assertEquals(JsonNull, wire[0]["content"])
        assertEquals("call_a", wire[0]["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("tool", wire[1]["role"]!!.jsonPrimitive.content)
        assertEquals("call_a", wire[1]["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning deltas are surfaced under either field name`() = runTest {
        val stream = sse(
            null to """{"choices":[{"delta":{"reasoning_content":"step one"}}]}""",
            null to """{"choices":[{"delta":{"reasoning":"step two"}}]}""",
            null to """{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""",
        )
        val events = OpenAiChatModel(RecordingEngine(body = stream).client, config, null).stream(request).toList()
        assertEquals(listOf("step one", "step two"), events.filterIsInstance<ChatEvent.ReasoningDelta>().map { it.text })
        assertTrue((events.last() as ChatEvent.Completed).turn.stopReason == StopReason.END_TURN)
    }
}
