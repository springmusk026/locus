package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What real streams do that the happy-path tests don't: keep-alives and comments, empty deltas, event
 * types we don't know, `[DONE]`, usage in its own chunk, mid-stream errors, and streams that just stop.
 */
class StreamEdgeCasesTest {
    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())

    private fun completed(events: List<ChatEvent>) = assertIs<ChatEvent.Completed>(events.last()).turn

    // ---- Chat Completions ------------------------------------------------------------------------

    private val chatConfig = ProviderConfig("p", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "m")

    private suspend fun chat(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        OpenAiChatModel(RecordingEngine(status, body).client, chatConfig, "k").stream(request).toList()

    @Test
    fun `chat completions skip empty and null deltas, comments and DONE`() = runTest {
        val body = ": OPENROUTER PROCESSING\n\n" + sse(
            null to """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            null to """{"choices":[{"delta":{"content":null}}]}""",
            null to """{"choices":[{"delta":{"content":"Hel"}}]}""",
            null to """{"choices":[]}""",
            null to """{"choices":[{"delta":{"content":"lo"}}]}""",
            null to """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            null to "[DONE]",
        )
        val events = chat(body)
        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
        val turn = completed(events)
        assertEquals("Hello", turn.text)
        assertEquals(StopReason.END_TURN, turn.stopReason)
        assertNull(turn.usage)
    }

    @Test
    fun `chat completions read usage from the trailing chunk with no choices`() = runTest {
        val body = sse(
            null to """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":null}""",
            null to """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":100}}}""",
            null to "[DONE]",
        )
        assertEquals(TokenUsage(120, 7, 100), completed(chat(body)).usage)
    }

    @Test
    fun `chat completions without DONE still finish, and a stream that only stops is a normal end`() = runTest {
        val body = sse(null to """{"choices":[{"delta":{"content":"partial"}}]}""")
        val turn = completed(chat(body))
        assertEquals("partial", turn.text)
        assertEquals(StopReason.END_TURN, turn.stopReason)
    }

    @Test
    fun `chat completions map length and content_filter, and a cut-off call is flagged`() = runTest {
        val cut = sse(
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"write_file","arguments":"{\"path\":\"a"}}]}}]}""",
            null to """{"choices":[{"delta":{},"finish_reason":"length"}]}""",
        )
        val turn = completed(chat(cut))
        assertEquals(StopReason.MAX_TOKENS, turn.stopReason)
        assertTrue(INVALID_INPUT_KEY in turn.toolCalls.single().argumentsJson)

        val filtered = sse(null to """{"choices":[{"delta":{"content":"I"},"finish_reason":"content_filter"}]}""")
        assertEquals(StopReason.REFUSAL, completed(chat(filtered)).stopReason)
    }

    @Test
    fun `chat completions surface an error chunk and HTTP errors`() = runTest {
        val e = assertFailsWith<LlmException> { chat(sse(null to """{"error":{"message":"upstream overloaded","code":502}}""")) }
        assertTrue("upstream overloaded" in e.message.orEmpty())
        val http = assertFailsWith<LlmException> { chat("""{"error":"slow down"}""", HttpStatusCode.TooManyRequests) }
        assertEquals(429, http.httpStatus)
        assertTrue(http.retryable)
        assertFalse(assertFailsWith<LlmException> { chat("bad key", HttpStatusCode.Unauthorized) }.retryable)
    }

    @Test
    fun `chat completions without ids or arguments still give answerable calls`() = runTest {
        val body = sse(
            null to """{"choices":[{"delta":{"tool_calls":[{"function":{"name":"list_files"}},{"function":{"name":"read_file","arguments":"{}"}}]}}]}""",
            null to """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        val calls = completed(chat(body)).toolCalls
        assertEquals(listOf("call_0", "call_1"), calls.map { it.id })
        assertEquals(listOf("{}", "{}"), calls.map { it.argumentsJson })
    }

    // ---- Anthropic Messages ----------------------------------------------------------------------

    private val anthropicConfig = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")

    private suspend fun anthropic(body: String) = AnthropicChatModel(RecordingEngine(body = body).client, anthropicConfig, "k").stream(request).toList()

    private val start = "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_read_input_tokens":90,"cache_creation_input_tokens":5,"output_tokens":1}}}"""
    private val textStart = "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
    private fun text(t: String, index: Int = 0) = "content_block_delta" to """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$t"}}"""
    private fun stop(reason: String) = "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":42}}"""

    @Test
    fun `anthropic ignores pings, unknown events and deltas for blocks it never saw`() = runTest {
        val events = anthropic(
            sse(
                start,
                "ping" to """{"type":"ping"}""",
                textStart,
                text("Hi"),
                "content_block_delta" to """{"type":"content_block_delta","index":7,"delta":{"type":"text_delta","text":"ghost"}}""",
                "brand_new_event" to """{"type":"brand_new_event","payload":{"x":1}}""",
                text(" there"),
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                stop("end_turn"),
                "message_stop" to """{"type":"message_stop"}""",
            ),
        )
        val turn = completed(events)
        assertEquals("Hi there", turn.text)
        assertEquals(StopReason.END_TURN, turn.stopReason)
        // Input is fresh + cache reads + cache writes; output is the final running count.
        assertEquals(TokenUsage(105, 42, 90, 5), turn.usage)
        assertFalse(events.filterIsInstance<ChatEvent.TextDelta>().any { it.text == "ghost" })
    }

    @Test
    fun `anthropic stream errors are retryable only for overload-type errors`() = runTest {
        val overloaded = assertFailsWith<LlmException> {
            anthropic(sse(start, "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        }
        assertTrue(overloaded.retryable)
        val invalid = assertFailsWith<LlmException> {
            anthropic(sse(start, "error" to """{"type":"error","error":{"type":"invalid_request_error","message":"bad"}}"""))
        }
        assertFalse(invalid.retryable)
    }

    @Test
    fun `anthropic maps every stop reason, and a stream without one is OTHER`() = runTest {
        val expected = mapOf(
            "end_turn" to StopReason.END_TURN, "stop_sequence" to StopReason.END_TURN, "tool_use" to StopReason.TOOL_USE,
            "max_tokens" to StopReason.MAX_TOKENS, "model_context_window_exceeded" to StopReason.MAX_TOKENS,
            "refusal" to StopReason.REFUSAL, "pause_turn" to StopReason.PAUSE_TURN, "something_new" to StopReason.OTHER,
        )
        expected.forEach { (wire, reason) -> assertEquals(reason, completed(anthropic(sse(start, textStart, text("x"), stop(wire)))).stopReason, wire) }
        assertEquals(StopReason.OTHER, completed(anthropic(sse(start, textStart, text("x")))).stopReason)
    }

    @Test
    fun `anthropic multi-line data and comment lines parse as one event`() = runTest {
        val body = ": keep-alive\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\n" +
            "data: \"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
            sse(text("joined"), stop("end_turn"))
        assertEquals("joined", completed(anthropic(body)).text)
    }

    // ---- OpenAI Responses ------------------------------------------------------------------------

    private val responsesConfig = ProviderConfig("r", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt")

    private suspend fun responses(body: String) = OpenAiResponsesModel(RecordingEngine(body = body).client, responsesConfig, "k").stream(request).toList()

    private val message = """{"type":"message","content":[{"type":"output_text","text":"Done."}]}"""

    @Test
    fun `responses skip empty deltas, unknown events and DONE, and read usage from the final response`() = runTest {
        val events = responses(
            sse(
                "response.created" to """{"type":"response.created","response":{"status":"in_progress"}}""",
                "response.output_text.delta" to """{"type":"response.output_text.delta","delta":""}""",
                "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Done."}""",
                "response.reasoning_summary_text.delta" to """{"type":"response.reasoning_summary_text.delta","delta":""}""",
                "response.some_future_event" to """{"type":"response.some_future_event"}""",
                "response.completed" to """{"type":"response.completed","response":{"status":"completed","output":[$message],"usage":{"input_tokens":50,"output_tokens":3,"input_tokens_details":{"cached_tokens":40}}}}""",
                null to "[DONE]",
            ),
        )
        assertEquals(listOf("Done."), events.filterIsInstance<ChatEvent.TextDelta>().map { it.text })
        assertTrue(events.none { it is ChatEvent.ReasoningDelta })
        val turn = completed(events)
        assertEquals("Done.", turn.text)
        assertEquals(StopReason.END_TURN, turn.stopReason)
        assertEquals(TokenUsage(50, 3, 40), turn.usage)
    }

    @Test
    fun `responses rebuild the turn from streamed items when the final output is empty`() = runTest {
        val call = """{"type":"function_call","call_id":"call_9","name":"list_files","arguments":""}"""
        val turn = completed(
            responses(
                sse(
                    "response.output_item.done" to """{"type":"response.output_item.done","item":$call}""",
                    "response.completed" to """{"type":"response.completed","response":{"status":"completed","output":[]}}""",
                ),
            ),
        )
        assertEquals("call_9", turn.toolCalls.single().id)
        assertEquals("{}", turn.toolCalls.single().argumentsJson)
        assertEquals(StopReason.TOOL_USE, turn.stopReason)
    }

    @Test
    fun `responses that end without a final event are a retryable failure`() = runTest {
        val e = assertFailsWith<LlmException> {
            responses(sse("response.output_text.delta" to """{"type":"response.output_text.delta","delta":"Hal"}"""))
        }
        assertTrue(e.retryable)
    }

    @Test
    fun `responses errors are retryable only for server and rate-limit codes`() = runTest {
        val server = assertFailsWith<LlmException> { responses(sse(null to """{"type":"error","code":"server_error","message":"oops"}""")) }
        assertTrue(server.retryable)
        val failed = assertFailsWith<LlmException> {
            responses(sse(null to """{"type":"response.failed","response":{"error":{"code":"invalid_prompt","message":"no"}}}"""))
        }
        assertFalse(failed.retryable)
        assertTrue("no" in failed.message.orEmpty())
    }

    @Test
    fun `responses incomplete for length is MAX_TOKENS`() = runTest {
        val turn = completed(
            responses(sse(null to """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[$message]}}""")),
        )
        assertEquals(StopReason.MAX_TOKENS, turn.stopReason)
    }
}
