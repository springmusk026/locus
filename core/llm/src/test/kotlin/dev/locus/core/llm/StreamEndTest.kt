package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Streams that end before their final event (#306), Chat Completions calls without `index` (#307), and
 * what a mid-stream error keeps and when a 400 is retried without reasoning (#308).
 */
class StreamEndTest {
    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())

    private val chatConfig = ProviderConfig("p", "vLLM", ApiFormat.OPENAI_CHAT, "http://192.168.1.5:8000/v1", "m")
    private val anthropicConfig = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val responsesConfig = ProviderConfig("r", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6-astra", effort = "high")

    private suspend fun chat(body: String, config: ProviderConfig = chatConfig) = OpenAiChatModel(RecordingEngine(body = body).client, config, "k").stream(request).toList()
    private suspend fun anthropic(body: String) = AnthropicChatModel(RecordingEngine(body = body).client, anthropicConfig, "k").stream(request).toList()
    private suspend fun responses(body: String) = OpenAiResponsesModel(RecordingEngine(body = body).client, responsesConfig, "k").stream(request).toList()

    private fun assertCutOff(e: LlmException) {
        assertTrue(e.retryable, e.message)
        assertEquals(LlmFailure.TRANSIENT, e.kind)
    }

    // ---- #306: the SSE reader and each adapter's final event --------------------------------------

    @Test
    fun `an event the body ends in the middle of is not dispatched`() = runTest {
        val seen = mutableListOf<String>()
        ByteReadChannel("data: one\n\ndata: {\"half\":").forEachSseEvent { seen += it.data }
        assertEquals(listOf("one"), seen)
    }

    private val chatText = """{"choices":[{"delta":{"content":"Half an ans"}}]}"""

    @Test
    fun `chat completions cut mid-event or before the final event fail retryably`() = runTest {
        assertCutOff(assertFailsWith<LlmException> { chat(sse(null to chatText) + """data: {"choices":[{"delta":{"content":"wer"},"finish_rea""") })
        assertCutOff(assertFailsWith<LlmException> { chat(sse(null to chatText)) })
        // A complete event whose JSON is broken is the same failure, not a raw serialization error.
        assertCutOff(assertFailsWith<LlmException> { chat(sse(null to chatText) + "data: {\"choices\":[\n\n") })
    }

    @Test
    fun `chat completions with DONE but no finish_reason end as OTHER, not as a clean end`() = runTest {
        val turn = assertIs<ChatEvent.Completed>(chat(sse(null to """{"choices":[{"delta":{"content":"ok"}}]}""", null to "[DONE]")).last()).turn
        assertEquals("ok", turn.text)
        assertEquals(StopReason.OTHER, turn.stopReason)
    }

    private val start = "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}"""
    private val toolStart = "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"write_file","input":{}}}"""
    private val toolDelta = "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a"}}"""

    @Test
    fun `anthropic cut mid-event or before message_stop fails retryably`() = runTest {
        assertCutOff(assertFailsWith<LlmException> { anthropic(sse(start, toolStart, toolDelta) + "event: content_block_delta\ndata: {\"type\":\"content_blo") })
        assertCutOff(assertFailsWith<LlmException> { anthropic(sse(start, toolStart, toolDelta, "content_block_stop" to """{"type":"content_block_stop","index":0}""")) })
        assertCutOff(assertFailsWith<LlmException> { anthropic(sse(start) + "event: ping\ndata: {not json\n\n") })
    }

    @Test
    fun `responses cut mid-event or before response completed fail retryably`() = runTest {
        val delta = "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Half"}"""
        assertCutOff(assertFailsWith<LlmException> { responses(sse(delta) + "event: response.completed\ndata: {\"type\":\"response.completed\",\"resp") })
        assertCutOff(assertFailsWith<LlmException> { responses(sse(delta)) })
        assertCutOff(assertFailsWith<LlmException> { responses(sse(delta) + "data: <html>\n\n") })
    }

    // ---- #307: Chat Completions calls without `index` ---------------------------------------------

    @Test
    fun `parallel calls sent whole in their own chunks without index stay two calls`() = runTest {
        val events = chat(
            sse(
                null to """{"choices":[{"delta":{"tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}}]}}]}""",
                null to """{"choices":[{"delta":{"tool_calls":[{"id":"call_b","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"b\"}"}}]}}]}""",
                null to """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                null to "[DONE]",
            ),
        )
        val calls = assertIs<ChatEvent.Completed>(events.last()).turn.toolCalls
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals(listOf("""{"path":"a"}""", """{"path":"b"}"""), calls.map { it.argumentsJson })
        assertEquals(calls.map { it.id }, events.filterIsInstance<ChatEvent.ToolCallStarted>().map { it.id })
    }

    @Test
    fun `index-less calls without ids get one id from start to finish, and argument fragments still join`() = runTest {
        val events = chat(
            sse(
                null to """{"choices":[{"delta":{"tool_calls":[{"function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}""",
                null to """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"\"a\"}"}}]}}]}""",
                null to """{"choices":[{"delta":{"tool_calls":[{"function":{"name":"list_files","arguments":"{}"}}]}}]}""",
                null to """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )
        val calls = assertIs<ChatEvent.Completed>(events.last()).turn.toolCalls
        assertEquals(listOf("read_file", "list_files"), calls.map { it.name })
        assertEquals(listOf("""{"path":"a"}""", "{}"), calls.map { it.argumentsJson })
        val started = events.filterIsInstance<ChatEvent.ToolCallStarted>().map { it.id }
        assertEquals(listOf("call_0", "call_1"), started)
        assertEquals(started, calls.map { it.id })
    }

    // ---- #308: mid-stream errors and the retry without reasoning ----------------------------------

    @Test
    fun `a mid-stream 429 keeps its retry hint, its body and its words`() = runTest {
        val error = """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7s"}]}}"""
        val e = assertFailsWith<LlmException> { chat(sse(null to """{"choices":[{"delta":{"content":"a"}}]}""", null to error)) }
        assertEquals(429, e.httpStatus)
        assertEquals(LlmFailure.RATE_LIMIT, e.kind)
        assertTrue(e.retryable)
        assertEquals(7_000, e.retryAfterMs)
        assertNotNull(e.rawBody)
        assertNotNull(e.text)
        assertTrue("Resource has been exhausted" in e.message.orEmpty())
    }

    private fun refusing(status: HttpStatusCode, error: String, ok: String): Pair<HttpClient, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            bodies += (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) respond(error, status, headersOf(HttpHeaders.ContentType, "application/json"))
            else respond(ByteReadChannel(ok), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        return client to bodies
    }

    private val completed = sse(null to """{"type":"response.completed","response":{"status":"completed","output":[]}}""")

    @Test
    fun `a responses 400 about a replayed reasoning item is not sent again without reasoning`() = runTest {
        val replay = """{"error":{"message":"Item 'rs_68af' of type 'reasoning' was provided without its required following item.","type":"invalid_request_error","param":"input","code":null}}"""
        val (client, bodies) = refusing(HttpStatusCode.BadRequest, replay, completed)
        val e = assertFailsWith<LlmException> { OpenAiResponsesModel(client, responsesConfig, "sk").stream(request).toList() }
        assertEquals(400, e.httpStatus)
        assertEquals(1, bodies.size)

        // Without a `param`, the item id in the message still says it's about the history.
        val noParam = """{"error":{"message":"Encrypted content for item rs_68af could not be verified. Reasoning items must be replayed as received."}}"""
        val (again, sent) = refusing(HttpStatusCode.BadRequest, noParam, completed)
        assertFailsWith<LlmException> { OpenAiResponsesModel(again, responsesConfig, "sk").stream(request).toList() }
        assertEquals(1, sent.size)
    }

    @Test
    fun `a responses 400 naming the reasoning parameter is still retried without it`() = runTest {
        val refused = """{"error":{"message":"Unsupported value: 'high' is not supported with this model.","type":"invalid_request_error","param":"reasoning.effort","code":"unsupported_value"}}"""
        val (client, bodies) = refusing(HttpStatusCode.BadRequest, refused, completed)
        OpenAiResponsesModel(client, responsesConfig, "sk").stream(request).toList()
        assertEquals(2, bodies.size)
        assertTrue("\"reasoning\"" !in bodies[1])
    }

    @Test
    fun `a chat completions 400 that only mentions reasoning is not retried without the effort`() = runTest {
        val config = ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "o4-mini", effort = "high")
        val ok = sse(null to """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
        val other = """{"error":{"message":"Invalid 'messages[3].content': reasoning text must be a string.","type":"invalid_request_error","param":"messages[3].content"}}"""
        val (client, bodies) = refusing(HttpStatusCode.BadRequest, other, ok)
        assertFailsWith<LlmException> { OpenAiChatModel(client, config, "sk").stream(request).toList() }
        assertEquals(1, bodies.size)

        val refused = """{"error":{"message":"Unsupported parameter: 'reasoning_effort' is not supported with this model.","type":"invalid_request_error","param":"reasoning_effort"}}"""
        val (again, sent) = refusing(HttpStatusCode.BadRequest, refused, ok)
        OpenAiChatModel(again, config, "sk").stream(request).toList()
        assertEquals(2, sent.size)
        assertTrue("reasoning_effort" !in sent[1])
    }
}
