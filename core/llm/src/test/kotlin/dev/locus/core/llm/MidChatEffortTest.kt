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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cache-safe effort changes (docs/design/midchat-config.md): a chat that changes effort after some turns
 * sends the change as an item inside the conversation, and everything up to the earlier request's cache
 * breakpoint stays byte for byte the same.
 */
class MidChatEffortTest {
    @BeforeTest
    @AfterTest
    fun reset() = MidChatEffort.clearRejections()

    private val tools = listOf(ToolSpec("read_file", "Read a file.", JsonObject(mapOf("type" to JsonPrimitive("object")))))

    /** Replies OK to every request, or with [failFirst] (a 400 body) to the first one; records each. */
    private class Server(private val ok: String, private val failFirst: String? = null) {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            // The Models API lookup (AnthropicCapabilities) isn't part of the conversation.
            if (request.method != HttpMethod.Post) return@MockEngine respond("", HttpStatusCode.NotFound)
            requests += request
            if (failFirst != null && requests.size == 1) {
                respond(ByteReadChannel(failFirst), HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(ByteReadChannel(ok), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        })

        fun body(i: Int = requests.lastIndex): JsonObject =
            LlmJson.parseToJsonElement((requests[i].body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

        fun betas(i: Int = requests.lastIndex): List<String> = requests[i].headers["anthropic-beta"].orEmpty().split(",").filter { it.isNotBlank() }
    }

    /** The turn a stream produced, stored as AgentLoop stores it (its effort record among the parts). */
    private fun stored(turn: AssistantTurn, id: String) = Message(
        id, "c", Role.ASSISTANT, listOfNotNull(turn.text.takeIf { it.isNotEmpty() }?.let { Part.Text(it) }) + turn.toolCalls + listOfNotNull(turn.turnEffort),
        0, turn.format, turn.rawPayload,
    )

    private fun user(id: String, text: String) = Message(id, "c", Role.USER, listOf(Part.Text(text)), 0)

    private fun JsonObject.messages(key: String) = this[key]!!.jsonArray

    /** [element] without any `cache_control`: the moving conversation breakpoint is the only marker that may differ. */
    private fun unmarked(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { it != "cache_control" }.mapValues { unmarked(it.value) })
        is JsonArray -> JsonArray(element.map(::unmarked))
        else -> element
    }

    private fun bytes(element: JsonElement) = LlmJson.encodeToString(JsonElement.serializer(), element)

    // ---- Anthropic -------------------------------------------------------------------------------

    private val anthropic = ProviderConfig("anthropic-1", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, AnthropicChatModel.DEFAULT_BASE_URL, "claude-opus-5-5", effort = "high")

    private val claudeReply = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg","role":"assistant","content":[],"usage":{"input_tokens":10}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
    )

    private suspend fun claude(server: Server, config: ProviderConfig, messages: List<Message>): AssistantTurn {
        val events = AnthropicChatModel(server.client, config, "key").stream(ChatRequest("You are Locus.", messages, tools, longCache = true)).toList()
        return (events.last() as ChatEvent.Completed).turn
    }

    @Test
    fun `Anthropic - an effort change after N turns keeps every byte up to the old breakpoint and adds one system message`() = runTest {
        val server = Server(claudeReply)
        // Two turns at high: the requests before the change.
        var history = listOf(user("u1", "Plan the migration."))
        history = history + stored(claude(server, anthropic, history), "a1") + user("u2", "Go on.")
        val beforeTurn = claude(server, anthropic, history)
        val before = server.body()
        assertFalse(MidChatEffort.ANTHROPIC_BETA in server.betas())
        assertEquals("high", before["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        // The user picks Low, then sends a message.
        val low = anthropic.copy(effort = "low")
        history = history + stored(beforeTurn, "a2") + user("u3", "Summarise it in one line.")
        val changeTurn = claude(server, low, history)
        val after = server.body()

        // (a) The prefix: tools, system prompt, top-level config and every earlier message are unchanged.
        assertEquals(bytes(JsonObject(before.filterKeys { it != "messages" })), bytes(JsonObject(after.filterKeys { it != "messages" })))
        val old = before.messages("messages")
        // The earlier request's breakpoint was the last block of its last message; up to it, the bytes are the same.
        assertTrue(old.last().jsonObject["content"]!!.jsonArray.last().jsonObject.containsKey("cache_control"))
        assertEquals(bytes(unmarked(old)), bytes(unmarked(JsonArray(after.messages("messages").take(old.size)))))
        // (b) The change: an effort-only system message after the new user turn, in the documented shape, with the beta.
        val change = after.messages("messages").last().jsonObject
        assertEquals("""{"role":"system","content":[],"output_config":{"effort":"low"}}""", bytes(change))
        assertTrue(MidChatEffort.ANTHROPIC_BETA in server.betas())
        // The conversation breakpoint skips the system message and stays on the user turn before it.
        val lastUser = after.messages("messages")[after.messages("messages").size - 2].jsonObject
        assertEquals("user", lastUser["role"]!!.jsonPrimitive.content)
        assertTrue(lastUser["content"]!!.jsonArray.last().jsonObject.containsKey("cache_control"))
        // Two breakpoints, as before: the system prompt and the last user turn.
        assertEquals(2, Regex("\"cache_control\"").findAll(after.toString()).count())
        // The reply records what it was asked for and what the top level carried.
        assertEquals(Part.TurnEffort("low", "high"), changeTurn.turnEffort)

        // The next request keeps the system message where it was first sent and adds nothing.
        history = history + stored(changeTurn, "a3") + user("u4", "Thanks.")
        claude(server, low, history)
        val next = server.body()
        val sent = after.messages("messages")
        assertEquals(bytes(unmarked(sent)), bytes(unmarked(JsonArray(next.messages("messages").take(sent.size)))))
        assertEquals(1, next.messages("messages").count { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
        assertEquals("high", next["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(MidChatEffort.ANTHROPIC_BETA in server.betas())
    }

    @Test
    fun `Anthropic - a chat whose effort never changed sends exactly what it sent before`() = runTest {
        val server = Server(claudeReply)
        var history = listOf(user("u1", "Hi"))
        history = history + stored(claude(server, anthropic, history), "a1") + user("u2", "Again")
        claude(server, anthropic, history)
        val withRecords = server.body()
        // The same chat with no records (turns from before this change) is built identically.
        val plain = AnthropicChatModel(server.client, anthropic, "key").buildBody(
            ChatRequest("You are Locus.", history.map { m -> m.copy(parts = m.parts.filterNot { it is Part.TurnEffort }) }, tools, longCache = true),
        )
        assertEquals(bytes(plain), bytes(withRecords))
        assertFalse(MidChatEffort.ANTHROPIC_BETA in server.betas())
    }

    @Test
    fun `Anthropic - a new chat or one with nothing sent uses the top-level config as before`() = runTest {
        val server = Server(claudeReply)
        claude(server, anthropic.copy(effort = "low"), listOf(user("u1", "Hi")))
        val body = server.body()
        assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(body.messages("messages").none { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
        assertFalse(MidChatEffort.ANTHROPIC_BETA in server.betas())
    }

    @Test
    fun `Anthropic - a refused beta is retried once the old way and the provider keeps the old way`() = runTest {
        val refusal = """{"type":"error","error":{"type":"invalid_request_error","message":"messages.3.output_config: Extra inputs are not permitted"}}"""
        val server = Server(claudeReply, failFirst = refusal)
        val history = listOf(
            user("u1", "Hi"),
            stored(AssistantTurn("Hello.", emptyList(), StopReason.END_TURN, ApiFormat.ANTHROPIC_MESSAGES, """[{"type":"text","text":"Hello."}]""", turnEffort = Part.TurnEffort("high", "high")), "a1"),
            user("u2", "Quick one"),
        )
        val low = anthropic.copy(effort = "low")
        val turn = claude(server, low, history)
        assertEquals(2, server.requests.size)
        assertTrue(server.body(0).messages("messages").any { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
        assertTrue(MidChatEffort.ANTHROPIC_BETA in server.betas(0))
        // The retry: top-level effort changed, no system message, no beta.
        val retry = server.body(1)
        assertEquals("low", retry["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(retry.messages("messages").none { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
        assertFalse(MidChatEffort.ANTHROPIC_BETA in server.betas(1))
        // Recorded as sent: a later attempt starts from this top level, not the chat's first one.
        assertEquals(Part.TurnEffort("low", "low"), turn.turnEffort)
        assertFalse(MidChatEffort.keepsCache(low.id, low.format, low.baseUrl, low.model))

        // Remembered: the next request goes the old way at once.
        claude(server, low, history + stored(turn, "a2") + user("u3", "And?"))
        assertEquals(3, server.requests.size)
        assertFalse(MidChatEffort.ANTHROPIC_BETA in server.betas())
        assertTrue(server.body().messages("messages").none { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
    }

    @Test
    fun `Anthropic - another 400 is not retried`() = runTest {
        val server = Server(claudeReply, failFirst = """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: too large"}}""")
        val history = listOf(
            user("u1", "Hi"),
            stored(AssistantTurn("Hello.", emptyList(), StopReason.END_TURN, ApiFormat.ANTHROPIC_MESSAGES, """[{"type":"text","text":"Hello."}]""", turnEffort = Part.TurnEffort("high", "high")), "a1"),
            user("u2", "Quick one"),
        )
        assertFailsWith<LlmException> { claude(server, anthropic.copy(effort = "low"), history) }
        assertEquals(1, server.requests.size)
        assertTrue(MidChatEffort.keepsCache(anthropic.id, anthropic.format, anthropic.baseUrl, anthropic.model))
    }

    @Test
    fun `Anthropic - models off the list and gateways change the top level as before and record nothing`() = runTest {
        for (config in listOf(anthropic.copy(model = "claude-sonnet-5"), anthropic.copy(model = "claude-fable-5"), anthropic.copy(baseUrl = "https://gateway.example.com"))) {
            val server = Server(claudeReply)
            var history = listOf(user("u1", "Hi"))
            val first = claude(server, config, history)
            assertNull(first.turnEffort, config.toString())
            history = history + stored(first, "a1") + user("u2", "Again")
            claude(server, config.copy(effort = "low"), history)
            val body = server.body()
            assertEquals("low", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content, config.toString())
            assertTrue(body.messages("messages").none { it.jsonObject["role"]!!.jsonPrimitive.content == "system" }, config.toString())
            assertFalse(MidChatEffort.ANTHROPIC_BETA in server.betas(), config.toString())
        }
    }

    @Test
    fun `Anthropic - after a server compaction the chosen level is set again after it`() {
        fun turn(id: String, effort: String, raw: String = """[{"type":"text","text":"ok"}]""") =
            Message(id, "c", Role.ASSISTANT, listOf(Part.Text("ok"), Part.TurnEffort(effort, "high")), 0, ApiFormat.ANTHROPIC_MESSAGES, raw)
        val messages = listOf(
            user("u1", "a"), turn("a1", "high"),
            user("u2", "b"), turn("a2", "low"),
            user("u3", "c"), turn("a3", "low", """[{"type":"compaction","content":"Summary."},{"type":"text","text":"ok"}]"""),
            user("u4", "d"), turn("a4", "low"),
        )
        val plan = AnthropicChatModel(Server(claudeReply).client, anthropic.copy(effort = "low"), "k").midChatPlan(ChatRequest("s", messages, emptyList()))!!
        assertEquals("high", plan.topLevel)
        assertEquals(mapOf(3 to "low", 7 to "low"), plan.changes)
        assertNull(plan.pending)
    }

    @Test
    fun `Anthropic - back to the model's own default sends its documented level`() = runTest {
        val server = Server(claudeReply)
        val blank = anthropic.copy(effort = "")
        var history = listOf(user("u1", "Hi"))
        history = history + stored(claude(server, blank, history), "a1") + user("u2", "Think hard.")
        val high = claude(server, blank.copy(effort = "high"), history)
        assertFalse("output_config" in server.body(), "the top level still sends no effort")
        assertEquals("high", server.body().messages("messages").last().jsonObject["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        history = history + stored(high, "a2") + user("u3", "Now quick.")
        claude(server, blank, history)
        // Opus 5.5's own level is medium (effort docs).
        assertEquals("medium", server.body().messages("messages").last().jsonObject["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    // ---- Plans -----------------------------------------------------------------------------------

    @Test
    fun `turns sent the old way start a new top level and get no change`() {
        fun turn(id: String, effort: String, top: String) = Message(id, "c", Role.ASSISTANT, listOf(Part.TurnEffort(effort, top)), 0, ApiFormat.OPENAI_RESPONSES)
        val messages = listOf(user("u1", "a"), turn("a1", "high", "high"), user("u2", "b"), turn("a2", "low", "low"), user("u3", "c"), turn("a3", "low", "low"))
        assertEquals(MidChatEffort.Plan("low", emptyMap(), null), MidChatEffort.plan(messages, ApiFormat.OPENAI_RESPONSES, "low", "medium"))
        // Other providers' records don't count.
        assertEquals(MidChatEffort.Plan("high", emptyMap(), null), MidChatEffort.plan(messages, ApiFormat.ANTHROPIC_MESSAGES, "high", "high"))
    }

    // ---- OpenAI Responses ------------------------------------------------------------------------

    private val openai = ProviderConfig("openai-1", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6.2-sol", effort = "low")

    private val gptReply = sse(null to """{"type":"response.completed","response":{"status":"completed","output":[{"id":"msg_x","type":"message","role":"assistant","content":[{"type":"output_text","text":"Done."}]}]}}""")

    private suspend fun gpt(server: Server, config: ProviderConfig, messages: List<Message>): AssistantTurn {
        val events = OpenAiResponsesModel(server.client, config, "sk").stream(ChatRequest("You are Locus.", messages, tools, cacheKey = "locus-chat-c")).toList()
        return (events.last() as ChatEvent.Completed).turn
    }

    private fun JsonObject.updates() = messages("input").filter { it.jsonObject["type"]?.jsonPrimitive?.content == "configuration_update" }

    @Test
    fun `Responses - an effort change after N turns keeps the input prefix and adds one configuration_update before the new message`() = runTest {
        val server = Server(gptReply)
        var history = listOf(user("u1", "Draft a plan."))
        history = history + stored(gpt(server, openai, history), "a1") + user("u2", "More.")
        val beforeTurn = gpt(server, openai, history)
        val before = server.body()

        val high = openai.copy(effort = "high")
        history = history + stored(beforeTurn, "a2") + user("u3", "Find the failure modes.")
        val changeTurn = gpt(server, high, history)
        val after = server.body()

        // (a) Everything but input is unchanged, request-level effort included; the input starts with the old input.
        assertEquals(bytes(JsonObject(before.filterKeys { it != "input" })), bytes(JsonObject(after.filterKeys { it != "input" })))
        assertEquals("low", after["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val old = before.messages("input")
        assertEquals(bytes(old), bytes(JsonArray(after.messages("input").take(old.size))))
        // (b) The update, in the documented shape, right before the new user message.
        val input = after.messages("input")
        assertEquals("""{"type":"configuration_update","reasoning":{"effort":"high"}}""", bytes(input[input.size - 2]))
        assertEquals("user", input.last().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(Part.TurnEffort("high", "low"), changeTurn.turnEffort)

        // Replayed in its original position on the next request.
        history = history + stored(changeTurn, "a3") + user("u4", "Thanks.")
        gpt(server, high, history)
        val next = server.body()
        assertEquals(bytes(input), bytes(JsonArray(next.messages("input").take(input.size))))
        assertEquals(1, next.updates().size)
    }

    @Test
    fun `Responses - a change before Continue goes after the tool output and stays there`() = runTest {
        val server = Server(gptReply)
        val call = Message("a1", "c", Role.ASSISTANT, listOf(Part.ToolCall("call_1", "read_file", "{}"), Part.TurnEffort("low", "low")), 0, ApiFormat.OPENAI_RESPONSES,
            """[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read_file","arguments":"{}"}]""")
        val output = Message("r1", "c", Role.TOOL, listOf(Part.ToolResult("call_1", "read_file", "text")), 0)
        var history = listOf(user("u1", "Read it."), call, output)
        val medium = openai.copy(effort = "medium")
        val turn = gpt(server, medium, history)
        val input = server.body().messages("input")
        assertEquals("function_call_output", input[input.size - 2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"configuration_update","reasoning":{"effort":"medium"}}""", bytes(input.last()))

        history = history + stored(turn, "a2") + user("u2", "Next.")
        gpt(server, medium, history)
        assertEquals(bytes(input), bytes(JsonArray(server.body().messages("input").take(input.size))))
    }

    @Test
    fun `Responses - a refused configuration_update is retried once with the effort at the top level`() = runTest {
        val server = Server(gptReply, failFirst = """{"error":{"message":"The 'configuration_update' item type is not supported for this model.","type":"invalid_request_error","param":"input[3]"}}""")
        val history = listOf(
            user("u1", "Hi"),
            Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("Hello."), Part.TurnEffort("low", "low")), 0, ApiFormat.OPENAI_RESPONSES,
                """[{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello."}]}]"""),
            user("u2", "Harder question"),
        )
        val high = openai.copy(effort = "high")
        val turn = gpt(server, high, history)
        assertEquals(2, server.requests.size)
        assertEquals(1, server.body(0).updates().size)
        val retry = server.body(1)
        assertTrue(retry.updates().isEmpty())
        assertEquals("high", retry["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(Part.TurnEffort("high", "high"), turn.turnEffort)
        // Remembered for this provider.
        gpt(server, high, history + stored(turn, "a2") + user("u3", "And?"))
        assertEquals(3, server.requests.size)
        assertTrue(server.body().updates().isEmpty())
    }

    @Test
    fun `Responses - the ChatGPT route, other hosts and models before GPT-6 get no updates`() = runTest {
        for (config in listOf(openai.copy(model = "gpt-5.5"), openai.copy(baseUrl = "https://openrouter.ai/api/v1"), openai.copy(format = ApiFormat.CHATGPT, baseUrl = "https://chatgpt.com/backend-api/codex"))) {
            val server = Server(gptReply)
            val model = OpenAiResponsesModel(server.client, config, "sk")
            var history = listOf(user("u1", "Hi"))
            val first = (model.stream(ChatRequest("s", history, tools)).toList().last() as ChatEvent.Completed).turn
            assertNull(first.turnEffort, config.toString())
            history = history + stored(first, "a1") + user("u2", "Again")
            OpenAiResponsesModel(server.client, config.copy(effort = "high"), "sk").stream(ChatRequest("s", history, tools)).toList()
            val body = server.body()
            assertTrue(body.updates().isEmpty(), config.toString())
            assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content, config.toString())
        }
    }

    @Test
    fun `which models keep the cache`() {
        assertTrue(MidChatEffort.documented(ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com/", "claude-haiku-5-5"))
        assertFalse(MidChatEffort.documented(ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-sonnet-5"))
        assertTrue(MidChatEffort.documented(ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6-luna"))
        assertTrue(MidChatEffort.documented(ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6.2-astra"))
        assertFalse(MidChatEffort.documented(ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6"))
        assertFalse(MidChatEffort.documented(ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-6.2-sol"))
        assertFalse(MidChatEffort.documented(ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-6.2-sol"))
    }
}
