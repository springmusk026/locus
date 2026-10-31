package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiResponsesModelTest {
    private val config = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6-astra", effort = "high")
    private val tool = ToolSpec("list_files", "List files", JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object"))))
    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), listOf(tool))

    private val reasoning = """{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"ENC"}"""
    private val call = """{"id":"fc_1","type":"function_call","call_id":"call_1","name":"list_files","arguments":"{\"path\":\".\"}","status":"completed"}"""

    @Test
    fun `streams text, reasoning and tool calls and keeps output items for replay`() = runTest {
        val stream = sse(
            null to """{"type":"response.reasoning_summary_text.delta","delta":"Looking","item_id":"rs_1","output_index":0,"summary_index":0,"sequence_number":1}""",
            null to """{"type":"response.output_text.delta","delta":"Sure.","item_id":"msg_1","output_index":1,"content_index":0,"sequence_number":2}""",
            null to """{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"list_files","arguments":""},"output_index":2,"sequence_number":3}""",
            null to """{"type":"response.completed","sequence_number":4,"response":{"id":"r","status":"completed","output":[$reasoning,{"id":"msg_1","type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Sure.","annotations":[]}]},$call]}}""",
        )
        val engine = RecordingEngine(body = stream)
        val model = OpenAiResponsesModel(engine.client, config, "sk")
        val events = model.stream(request).toList()
        assertTrue(ChatEvent.ReasoningDelta("Looking") in events && ChatEvent.TextDelta("Sure.") in events && ChatEvent.ToolCallStarted("call_1", "list_files") in events)
        val turn = (events.last() as ChatEvent.Completed).turn
        assertEquals(StopReason.TOOL_USE, turn.stopReason)
        assertEquals(Part.ToolCall("call_1", "list_files", """{"path":"."}"""), turn.toolCalls.single())

        val body = engine.lastBody()
        assertTrue("\"store\":false" in body && "\"instructions\":\"sys\"" in body && "\"effort\":\"high\"" in body)
        assertTrue(engine.requests.last().url.toString().endsWith("/v1/responses"))

        // Replay: the model's own items go back untouched (reasoning first), then the tool output.
        val items = model.toInputItems(
            listOf(
                Message("a", "c", Role.ASSISTANT, listOf(Part.Text("Sure.")) + turn.toolCalls, 1, ApiFormat.OPENAI_RESPONSES, turn.rawPayload),
                Message("t", "c", Role.TOOL, listOf(Part.ToolResult("call_1", "list_files", "a.txt")), 2),
            ),
        )
        assertEquals(listOf("reasoning", "message", "function_call", "function_call_output"), items.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("ENC", items[0]["encrypted_content"]!!.jsonPrimitive.content)
        assertEquals("call_1", items[3]["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `turns from another provider become plain items`() {
        val model = OpenAiResponsesModel(RecordingEngine(body = "").client, config, "sk")
        val items = model.toInputItems(listOf(Message("a", "c", Role.ASSISTANT, listOf(Part.Text("hi"), Part.ToolCall("x", "list_files", "{}")), 0, ApiFormat.ANTHROPIC_MESSAGES, "[]")))
        assertEquals("assistant", items[0]["role"]!!.jsonPrimitive.content)
        assertEquals("function_call", items[1]["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `incomplete and refusal map to stop reasons`() {
        val model = OpenAiResponsesModel(RecordingEngine(body = "").client, config, "sk")
        val cut = model.toTurn(kotlinx.serialization.json.Json.parseToJsonElement("""{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""").jsonObject)
        assertEquals(StopReason.MAX_TOKENS, cut.stopReason)
        val refused = model.toTurn(kotlinx.serialization.json.Json.parseToJsonElement("""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"no"}]}]}""").jsonObject)
        assertEquals(StopReason.REFUSAL, refused.stopReason)
        assertEquals("no", refused.refusalDetail)
    }

    @Test
    fun `retries without reasoning when the model rejects it`() = runTest {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            bodies += (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) respond("""{"error":{"message":"Unsupported parameter: 'reasoning.effort' is not supported with this model."}}""", HttpStatusCode.BadRequest)
            else respond(ByteReadChannel(sse(null to """{"type":"response.completed","response":{"status":"completed","output":[]}}""")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val turn = (OpenAiResponsesModel(client, config, "sk").stream(request).toList().last() as ChatEvent.Completed).turn
        assertEquals(StopReason.END_TURN, turn.stopReason)
        assertTrue("reasoning" in bodies[0])
        assertFalse("\"reasoning\"" in bodies[1])
    }
}
