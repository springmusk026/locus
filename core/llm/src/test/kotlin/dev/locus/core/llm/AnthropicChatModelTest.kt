package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicChatModelTest {
    private val config = ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val tool = ToolSpec("read_file", "Read a file", JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("object"))))
    private val request = ChatRequest("You are Locus.", listOf(Message("m1", "c", Role.USER, listOf(Part.Text("hi")), 0)), listOf(tool))

    private val toolStream = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Checking the file."}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"notes.md\"}"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun `parses thinking and tool use and keeps blocks for replay`() = runTest {
        val engine = RecordingEngine(body = toolStream)
        val events = AnthropicChatModel(engine.client, config, "key").stream(request).toList()

        assertTrue(events.any { it is ChatEvent.ReasoningDelta && it.text == "Checking the file." })
        assertTrue(events.any { it is ChatEvent.ToolCallStarted && it.name == "read_file" })
        val turn = (events.last() as ChatEvent.Completed).turn
        assertEquals(StopReason.TOOL_USE, turn.stopReason)
        assertEquals("""{"path":"notes.md"}""", turn.toolCalls.single().argumentsJson)

        val raw = LlmJson.parseToJsonElement(turn.rawPayload!!).jsonArray
        assertEquals("sig123", raw[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("Checking the file.", raw[0].jsonObject["thinking"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request follows the 2026-10-21 API shape`() = runTest {
        val engine = RecordingEngine(body = toolStream)
        AnthropicChatModel(engine.client, config, "key").stream(request).toList()

        val headers = engine.requests.last().headers
        assertEquals("2026-10-21", headers["anthropic-version"])
        assertEquals("key", headers["x-api-key"])
        assertTrue(headers["anthropic-beta"]!!.contains("server-side-fallback-2026-07-01"))

        val body = LlmJson.parseToJsonElement(engine.lastBody()).jsonObject
        assertFalse("temperature" in body)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("true", body["tools"]!!.jsonArray[0].jsonObject["eager_input_streaming"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant turns are replayed verbatim and tool results merge into one user message`() {
        val model = AnthropicChatModel(RecordingEngine(body = "").client, config, "key")
        val raw = """[{"type":"thinking","thinking":"x","signature":"s"},{"type":"tool_use","id":"t1","name":"read_file","input":{}}]"""
        val wire = model.toWireMessages(
            listOf(
                Message("1", "c", Role.USER, listOf(Part.Text("hi")), 0),
                Message("2", "c", Role.ASSISTANT, listOf(Part.ToolCall("t1", "read_file", "{}")), 1, ApiFormat.ANTHROPIC_MESSAGES, raw),
                Message("3", "c", Role.TOOL, listOf(Part.ToolResult("t1", "read_file", "contents")), 2),
                Message("4", "c", Role.USER, listOf(Part.Text("thanks")), 3),
            ),
        )
        assertEquals(3, wire.size)
        assertEquals(LlmJson.parseToJsonElement(raw), wire[1].jsonObject["content"])
        val lastContent = wire[2].jsonObject["content"]!!.jsonArray
        assertEquals("tool_result", lastContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("text", lastContent[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blocks from a declined attempt are dropped before the fallback marker`() = runTest {
        val stream = sse(
            null to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            null to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"Partial "}}""",
            null to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"old","name":"read_file","input":{}}}""",
            null to """{"type":"content_block_start","index":3,"content_block":{"type":"fallback"}}""",
            null to """{"type":"content_block_start","index":4,"content_block":{"type":"text","text":"answer"}}""",
            null to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
        )
        val events = AnthropicChatModel(RecordingEngine(body = stream).client, config, "key").stream(request).toList()
        val turn = (events.last() as ChatEvent.Completed).turn
        val types = LlmJson.parseToJsonElement(turn.rawPayload!!).jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("text", "fallback", "text"), types)
        assertTrue(turn.toolCalls.isEmpty())
    }

    @Test
    fun `invalid streamed tool input is flagged instead of guessed`() = runTest {
        val stream = sse(
            null to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"write_file","input":{}}}""",
            null to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\": \"a"}}""",
            null to """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
        )
        val turn = (AnthropicChatModel(RecordingEngine(body = stream).client, config, "key").stream(request).toList().last() as ChatEvent.Completed).turn
        assertEquals(StopReason.MAX_TOKENS, turn.stopReason)
        assertTrue(INVALID_INPUT_KEY in turn.toolCalls.single().argumentsJson)
    }

    @Test
    fun `http errors carry status and retryability`() = runTest {
        val engine = RecordingEngine(HttpStatusCode.TooManyRequests, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")
        val error = assertFailsWith<LlmException> { AnthropicChatModel(engine.client, config, "key").stream(request).toList() }
        assertEquals(429, error.httpStatus)
        assertTrue(error.retryable)
    }

    @Test
    fun `unknown models get the conservative request`() = runTest {
        val engine = RecordingEngine(body = toolStream)
        AnthropicChatModel(engine.client, config.copy(model = "claude-haiku-4-5"), "key").stream(request).toList()
        val body = LlmJson.parseToJsonElement(engine.lastBody()).jsonObject
        assertFalse("output_config" in body)
        assertFalse("fallbacks" in body)
        assertFalse("thinking" in body)
        assertEquals(null, engine.requests.last().headers["anthropic-beta"])
    }
}

class AttachmentWireTest {
    private val img = java.io.File.createTempFile("img", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
    private val csv = java.io.File.createTempFile("csv", ".csv").apply { writeText("x,y"); deleteOnExit() }
    private val message = dev.locus.core.model.Message(
        "m", "c", dev.locus.core.model.Role.USER,
        listOf(
            dev.locus.core.model.Part.Attachment("a.png", "image/png", img.path, "inbox/a.png", 3),
            dev.locus.core.model.Part.Attachment("b.csv", "text/csv", csv.path, "inbox/b.csv", 3),
            dev.locus.core.model.Part.Text("what is this?"),
        ),
        0,
    )

    @Test
    fun `anthropic sends images inline and other files as workspace notes`() {
        val config = dev.locus.core.model.ProviderConfig("p", "A", dev.locus.core.model.ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
        val blocks = AnthropicChatModel(RecordingEngine(body = "").client, config, "k").toWireMessages(listOf(message))[0].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("image", "text", "text"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("AQID", blocks[0].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertTrue("inbox/b.csv" in blocks[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai-compatible sends images as data URLs`() {
        val config = dev.locus.core.model.ProviderConfig("p", "O", dev.locus.core.model.ApiFormat.OPENAI_CHAT, "https://x.test/v1", "m")
        val content = OpenAiChatModel(RecordingEngine(body = "").client, config, "k").toWireMessages(listOf(message))[0]["content"]!!.jsonArray
        assertEquals("data:image/png;base64,AQID", content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("what is this?", content.last().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `usage is summed across cache fields and compaction blocks are kept whole`() = kotlinx.coroutines.test.runTest {
        val stream = sse(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":120,"cache_read_input_tokens":9000,"cache_creation_input_tokens":300,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"compaction","content":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"compaction_delta","content":"Earlier: the user deployed a site."}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Done."}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
        )
        val engine = RecordingEngine(body = stream)
        val turn = (AnthropicChatModel(engine.client, dev.locus.core.model.ProviderConfig("p", "A", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"), "k").stream(ChatRequest("s", listOf(dev.locus.core.model.Message("m", "c", dev.locus.core.model.Role.USER, listOf(dev.locus.core.model.Part.Text("hi")), 0)), emptyList()))
            .toList().last() as ChatEvent.Completed).turn
        assertEquals(dev.locus.core.model.TokenUsage(9420, 42, 9000, 300), turn.usage)
        assertTrue("Earlier: the user deployed a site." in turn.rawPayload!!)
        val body = engine.lastBody()
        assertTrue("compact_20260112" in body)
        assertTrue("compact-2026-01-12" in engine.requests.last().headers["anthropic-beta"].orEmpty())
    }
}
