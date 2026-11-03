package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [StreamLimits]: one endless line or event, a stream that never stops, and tool arguments or text that
 * grow without end each fail with a clear, retryable [LlmException] instead of filling the phone's
 * memory, while large but real replies still go through.
 */
class StreamLimitsTest {
    private val request = ChatRequest("sys", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())
    private val mb = StreamLimits.MB

    private suspend fun read(body: String, limits: StreamLimits = StreamLimits.Default): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        ByteReadChannel(body).forEachSseEvent(limits = limits) { events += it }
        return events
    }

    private fun assertTooLarge(e: LlmException, what: String) {
        assertTrue(what in e.message.orEmpty(), e.message)
        assertTrue(e.retryable, "the run retries it, then reports it")
        assertEquals(LlmFailure.TRANSIENT, e.kind)
    }

    @Test
    fun `one line longer than the limit fails, even with no line break at all`() = runTest {
        val e = assertFailsWith<LlmException> { read("data: " + "x".repeat(9 * mb)) }
        assertTooLarge(e, "a single line over 8 MB")
    }

    @Test
    fun `an event built from many lines fails past its limit`() = runTest {
        val limits = StreamLimits(eventChars = 1_000)
        val body = (1..20).joinToString("") { "data: " + "y".repeat(100) + "\n" } + "\n"
        assertTooLarge(assertFailsWith<LlmException> { read(body, limits) }, "one event")
        // Under it, the same lines make one event.
        assertEquals(1, read(body, StreamLimits(eventChars = 10_000)).size)
    }

    @Test
    fun `a stream that never ends fails past its total`() = runTest {
        val body = (1..200).joinToString("") { "data: {\"n\":$it}\n\n" }
        assertTooLarge(assertFailsWith<LlmException> { read(body, StreamLimits(streamChars = 1_000)) }, "in all")
        assertEquals(200, read(body).size)
    }

    // ---- Providers -------------------------------------------------------------------------------

    private val anthropicConfig = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val chatConfig = ProviderConfig("p", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "m")

    private suspend fun anthropic(body: String) = AnthropicChatModel(RecordingEngine(body = body).client, anthropicConfig, "k").stream(request).toList()
    private suspend fun chat(body: String) = OpenAiChatModel(RecordingEngine(body = body).client, chatConfig, "k").stream(request).toList()

    /** An Anthropic tool call whose input arrives as [chunks] deltas of [size] characters each. */
    private fun anthropicToolCall(chunks: Int, size: Int): String {
        val piece = "a".repeat(size)
        return sse("message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":1}}}""") +
            sse("content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"write_file","input":{}}}""") +
            sse("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"content\":\""}}""") +
            (1..chunks).joinToString("") {
                sse("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"$piece"}}""")
            } +
            sse("content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"}"}}""") +
            sse("content_block_stop" to """{"type":"content_block_stop","index":0}""") +
            sse("message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""") +
            sse("message_stop" to """{"type":"message_stop"}""")
    }

    @Test
    fun `anthropic tool arguments past the limit fail, a large real one passes`() = runTest {
        val e = assertFailsWith<LlmException> { anthropic(anthropicToolCall(chunks = 9, size = mb)) }
        assertTooLarge(e, "a tool call's arguments")

        // A 3 MB file written in one call is fine.
        val turn = assertIs<ChatEvent.Completed>(anthropic(anthropicToolCall(chunks = 3, size = mb)).last()).turn
        assertTrue(turn.toolCalls.single().argumentsJson.length > 3 * mb)
    }

    @Test
    fun `chat completions tool arguments and text past their limits fail`() = runTest {
        val piece = "b".repeat(mb)
        val args = sse(null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"write_file","arguments":""}}]}}]}""") +
            (1..9).joinToString("") { sse(null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$piece"}}]}}]}""") }
        assertTooLarge(assertFailsWith<LlmException> { chat(args) }, "a tool call's arguments")

        val text = (1..17).joinToString("") { sse(null to """{"choices":[{"delta":{"content":"$piece"}}]}""") }
        assertTooLarge(assertFailsWith<LlmException> { chat(text) }, "a reply's text")
    }
}
