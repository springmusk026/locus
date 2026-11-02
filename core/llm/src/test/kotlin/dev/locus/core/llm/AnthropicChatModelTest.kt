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

    // Prompt caching ---------------------------------------------------------------------------

    private val model = AnthropicChatModel(RecordingEngine(body = "").client, config, "key")
    private fun JsonObject.cached() = this["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "ephemeral"
    private fun breakpoints(body: JsonObject): Int = Regex("\"cache_control\"").findAll(body.toString()).count()

    /** A three-turn agent transcript: a question, a tool call with its result, then a follow-up. */
    private fun transcript(turns: Int): List<Message> = buildList {
        add(Message("u1", "c", Role.USER, listOf(Part.Text("read notes.md")), 0))
        if (turns >= 2) {
            add(Message("a1", "c", Role.ASSISTANT, emptyList(), 0, format = ApiFormat.ANTHROPIC_MESSAGES,
                rawPayload = """[{"type":"thinking","thinking":"","signature":"s1"},{"type":"tool_use","id":"t1","name":"read_file","input":{"path":"notes.md"}}]"""))
            add(Message("r1", "c", Role.TOOL, listOf(Part.ToolResult("t1", "read_file", "# Notes")), 0))
        }
        if (turns >= 3) {
            add(Message("a2", "c", Role.ASSISTANT, emptyList(), 0, format = ApiFormat.ANTHROPIC_MESSAGES,
                rawPayload = """[{"type":"thinking","thinking":"","signature":"s2"},{"type":"text","text":"It says Notes."}]"""))
            add(Message("u2", "c", Role.USER, listOf(Part.Text("thanks")), 0))
        }
    }

    @Test
    fun `cache breakpoints end the system prompt and the last message`() {
        val body = model.buildBody(request.copy(messages = transcript(2)))
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("You are Locus.", system["text"]!!.jsonPrimitive.content)
        assertTrue(system.cached())
        // The system breakpoint already covers the tools before it.
        assertFalse(body["tools"]!!.jsonArray.any { it.jsonObject.cached() })
        val messages = body["messages"]!!.jsonArray
        assertTrue(messages.last().jsonObject["content"]!!.jsonArray.last().jsonObject.cached())
        assertTrue(messages.dropLast(1).none { m -> m.jsonObject["content"]!!.jsonArray.any { it.jsonObject.cached() } })
        assertEquals(2, breakpoints(body))
    }

    @Test
    fun `without a system prompt the last tool ends the frozen prefix`() {
        val body = model.buildBody(request.copy(system = ""))
        assertFalse("system" in body)
        assertTrue(body["tools"]!!.jsonArray.last().jsonObject.cached())
        assertEquals(2, breakpoints(body))
    }

    private fun JsonObject.ttl(): String? = this["cache_control"]?.jsonObject?.get("ttl")?.jsonPrimitive?.content

    @Test
    fun `the frozen prefix is cached for an hour and the moving breakpoint for five minutes`() {
        val body = model.buildBody(request.copy(messages = transcript(3)))
        assertEquals("1h", body["system"]!!.jsonArray.single().jsonObject.ttl())
        val last = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.last().jsonObject
        assertTrue(last.cached())
        assertEquals(null, last.ttl())
        // Longer lifetimes must come first: the 1-hour entry is the first breakpoint in the body.
        val text = body.toString()
        assertTrue(text.indexOf("\"ttl\":\"1h\"") in 0 until text.indexOf("\"messages\""))

        val noSystem = model.buildBody(request.copy(system = ""))
        assertEquals("1h", noSystem["tools"]!!.jsonArray.last().jsonObject.ttl())
    }

    @Test
    fun `the one-hour lifetime needs no beta header`() = runTest {
        val engine = RecordingEngine(body = toolStream)
        AnthropicChatModel(engine.client, config.copy(model = "claude-haiku-4-5"), "key").stream(request).toList()
        assertEquals("1h", LlmJson.parseToJsonElement(engine.lastBody()).jsonObject["system"]!!.jsonArray.single().jsonObject.ttl())
        assertEquals(null, engine.requests.last().headers["anthropic-beta"])
    }

    @Test
    fun `gateways get the default lifetime only`() {
        val gateway = AnthropicChatModel(RecordingEngine(body = "").client, config.copy(baseUrl = "https://gateway.example/anthropic"), "key")
        val body = gateway.buildBody(request.copy(messages = transcript(2)))
        assertTrue(body["system"]!!.jsonArray.single().jsonObject.cached())
        assertFalse("ttl" in body.toString())
    }

    @Test
    fun `one-hour cache writes are reported apart`() = runTest {
        val stream = sse(
            null to """{"type":"message_start","message":{"id":"m","role":"assistant","content":[],"usage":{"input_tokens":10,"cache_read_input_tokens":500,"cache_creation_input_tokens":248,"cache_creation":{"ephemeral_5m_input_tokens":148,"ephemeral_1h_input_tokens":100},"output_tokens":1}}}""",
            null to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"ok"}}""",
            null to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
        )
        val usage = (AnthropicChatModel(RecordingEngine(body = stream).client, config, "key").stream(request).toList().last() as ChatEvent.Completed).turn.usage!!
        assertEquals(758, usage.input)
        assertEquals(500, usage.cacheRead)
        assertEquals(248, usage.cacheWrite)
        assertEquals(100, usage.cacheWrite1h)
    }

    @Test
    fun `the prefix sent on one turn is byte-identical on the next`() {
        fun strip(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "cache_control" }.mapValues { strip(it.value) })
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::strip))
            else -> e
        }
        val turns = (1..3).map { model.buildBody(request.copy(messages = transcript(it))) }
        for ((earlier, later) in turns.zipWithNext()) {
            assertEquals(earlier["system"].toString(), later["system"].toString())
            assertEquals(earlier["tools"].toString(), later["tools"].toString())
            val sent = earlier["messages"]!!.jsonArray
            val next = later["messages"]!!.jsonArray
            // Only the moving breakpoint differs; the bytes of every earlier message are unchanged.
            assertEquals(strip(sent).toString(), strip(kotlinx.serialization.json.JsonArray(next.take(sent.size))).toString())
        }
        // And building the same turn twice gives the same bytes.
        assertEquals(turns[2].toString(), model.buildBody(request.copy(messages = transcript(3))).toString())
    }

    @Test
    fun `no breakpoint goes on blocks the API can't cache`() {
        val wire = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                put("content", LlmJson.parseToJsonElement("""[{"type":"text","text":"Searching."},{"type":"thinking","thinking":"","signature":"s"},{"type":"text","text":""}]"""))
            })
        }
        val marked = model.withCacheBreakpointOnLastMessage(wire)[0].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(true, false, false), marked.map { it.cached() })

        val nothingCacheable = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                put("content", LlmJson.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"s"}]"""))
            })
        }
        assertEquals(nothingCacheable, model.withCacheBreakpointOnLastMessage(nothingCacheable))
    }

    @Test
    fun `tool results take the conversation breakpoint`() {
        val last = model.buildBody(request.copy(messages = transcript(2)))["messages"]!!.jsonArray.last().jsonObject
        val block = last["content"]!!.jsonArray.last().jsonObject
        assertEquals("tool_result", block["type"]!!.jsonPrimitive.content)
        assertTrue(block.cached())
    }

    @Test
    fun `one-off requests are not cached`() {
        val body = model.buildBody(request.copy(cache = false))
        assertEquals("You are Locus.", body["system"]!!.jsonPrimitive.content)
        assertEquals(0, breakpoints(body))
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
