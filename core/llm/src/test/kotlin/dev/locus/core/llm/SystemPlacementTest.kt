package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GPT-6.2 and later append top-level `instructions` after `input` and bill them uncached on every request
 * (OpenAI prompt caching guide); new chats there send the system prompt as the first `developer` input
 * message instead (docs/design/cost.md, "System prompt placement on GPT-6.2 and later").
 */
class SystemPlacementTest {
    @BeforeTest
    @AfterTest
    fun reset() {
        SystemPlacement.clearRejections()
        MidChatEffort.clearRejections()
    }

    private val system = "You are Locus."
    private val openai = ProviderConfig("openai-1", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6.2-sol", effort = "low")

    private val reply = sse(null to """{"type":"response.completed","response":{"status":"completed","output":[{"id":"rs_1","type":"reasoning","encrypted_content":"enc","summary":[]},{"id":"msg_x","type":"message","role":"assistant","content":[{"type":"output_text","text":"Done."}]}]}}""")

    /** Replies OK to every request, or with [failFirst] (a 400 body) to the first one; records each. */
    private class Server(private val ok: String, private val failFirst: String? = null) {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            requests += request
            if (failFirst != null && requests.size == 1) {
                respond(ByteReadChannel(failFirst), HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(ByteReadChannel(ok), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        })

        fun raw(i: Int = requests.lastIndex): String = (requests[i].body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

        fun body(i: Int = requests.lastIndex): JsonObject = LlmJson.parseToJsonElement(raw(i)).jsonObject
    }

    private suspend fun send(server: Server, config: ProviderConfig, messages: List<Message>): AssistantTurn {
        val events = OpenAiResponsesModel(server.client, config, "sk").stream(ChatRequest(system, messages, emptyList(), cacheKey = "locus-chat-c")).toList()
        return (events.last() as ChatEvent.Completed).turn
    }

    /** The turn as AgentLoop stores it: its hidden records among the parts. */
    private fun stored(turn: AssistantTurn, id: String) = Message(
        id, "c", Role.ASSISTANT,
        listOfNotNull(turn.text.takeIf { it.isNotEmpty() }?.let { Part.Text(it) }) + turn.toolCalls +
            listOfNotNull(turn.turnEffort, Part.SystemInInput.takeIf { turn.systemInInput }),
        0, turn.format, turn.rawPayload,
    )

    private fun user(id: String, text: String) = Message(id, "c", Role.USER, listOf(Part.Text(text)), 0)

    private fun JsonObject.input() = this["input"]!!.jsonArray

    private fun bytes(element: JsonElement) = LlmJson.encodeToString(JsonElement.serializer(), element)

    private val developer = """{"type":"message","role":"developer","content":[{"type":"input_text","text":"You are Locus."}]}"""

    @Test
    fun `on GPT-6_2 the system prompt is the first developer input item and instructions is absent`() = runTest {
        val server = Server(reply)
        val turn = send(server, openai, listOf(user("u1", "Hi")))
        val body = server.body()
        assertNull(body["instructions"])
        assertEquals(developer, bytes(body.input().first()))
        assertEquals("user", body.input()[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(turn.systemInInput)
    }

    @Test
    fun `two consecutive requests share the same prefix bytes, replayed reasoning included`() = runTest {
        val server = Server(reply)
        var history = listOf(user("u1", "Plan it."))
        history = history + stored(send(server, openai, history), "a1") + user("u2", "Go on.")
        val first = server.raw()
        val firstInput = server.body().input()
        send(server, openai, history)
        val second = server.body()
        // The first request's bytes, up to the end of its input items, start the second request.
        val inputAt = first.indexOf("\"input\":")
        assertTrue(server.raw().startsWith(first.substring(0, inputAt) + "\"input\":" + bytes(firstInput).dropLast(1)))
        assertEquals(bytes(firstInput), bytes(JsonArray(second.input().take(firstInput.size))))
        assertEquals(developer, bytes(second.input().first()))
        assertNull(second["instructions"])
        // The replayed reasoning item comes right after the first user message, untouched.
        assertEquals("""{"id":"rs_1","type":"reasoning","encrypted_content":"enc","summary":[]}""", bytes(second.input()[2]))
        // Everything outside input is the same.
        assertEquals(bytes(JsonObject(server.body(0).filterKeys { it != "input" })), bytes(JsonObject(second.filterKeys { it != "input" })))
    }

    @Test
    fun `earlier models, other hosts and Chat Completions send exactly what they did`() = runTest {
        for (model in listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6", "gpt-5.6", "gpt-5.5")) {
            val server = Server(reply)
            val turn = send(server, openai.copy(model = model), listOf(user("u1", "Hi")))
            val expected = """{"model":"$model","stream":true,"store":false,"prompt_cache_key":"locus-chat-c","instructions":"You are Locus.",""" +
                """"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"Hi"}]}],""" +
                """"reasoning":{"effort":"low","summary":"auto"},"include":["reasoning.encrypted_content"],"max_output_tokens":64000}"""
            assertEquals(expected, server.raw(), model)
            assertFalse(turn.systemInInput, model)
        }
        val elsewhere = Server(reply)
        send(elsewhere, openai.copy(baseUrl = "https://openrouter.ai/api/v1"), listOf(user("u1", "Hi")))
        assertEquals(system, elsewhere.body()["instructions"]!!.jsonPrimitive.content)
        assertTrue(elsewhere.body().input().none { it.jsonObject["role"]?.jsonPrimitive?.content == "developer" })
    }

    @Test
    fun `a chat already under way with instructions keeps them`() = runTest {
        val server = Server(reply)
        // A turn stored before this change: no SystemInInput record.
        val old = Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("Hello."), Part.TurnEffort("low", "low")), 0, ApiFormat.OPENAI_RESPONSES,
            """[{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello."}]}]""")
        var history = listOf(user("u1", "Hi"), old, user("u2", "More"))
        val turn = send(server, openai, history)
        assertEquals(system, server.body()["instructions"]!!.jsonPrimitive.content)
        assertTrue(server.body().input().none { it.jsonObject["role"]?.jsonPrimitive?.content == "developer" })
        assertFalse(turn.systemInInput)
        // And on every later request.
        history = history + stored(turn, "a2") + user("u3", "And?")
        send(server, openai, history)
        assertEquals(system, server.body()["instructions"]!!.jsonPrimitive.content)
        // Turns from another provider don't hold a chat back: their prefix is new to OpenAI anyway.
        val claude = Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("Hello.")), 0, ApiFormat.ANTHROPIC_MESSAGES, "[]")
        send(server, openai, listOf(user("u1", "Hi"), claude, user("u2", "More")))
        assertNull(server.body()["instructions"])
        assertEquals(developer, bytes(server.body().input().first()))
    }

    @Test
    fun `a refused developer message is retried once with instructions and remembered for the provider`() = runTest {
        val server = Server(reply, failFirst = """{"error":{"message":"Invalid value: 'developer'. Supported values are: 'assistant', 'system', and 'user'.","type":"invalid_request_error","param":"input[0].role"}}""")
        val turn = send(server, openai, listOf(user("u1", "Hi")))
        assertEquals(2, server.requests.size)
        assertEquals(developer, bytes(server.body(0).input().first()))
        val retry = server.body(1)
        assertEquals(system, retry["instructions"]!!.jsonPrimitive.content)
        assertTrue(retry.input().none { it.jsonObject["role"]?.jsonPrimitive?.content == "developer" })
        // Everything else is what the first attempt sent.
        assertEquals(bytes(JsonArray(server.body(0).input().drop(1))), bytes(retry.input()))
        assertFalse(turn.systemInInput)
        // Remembered: the next chat on this provider goes straight to instructions.
        send(server, openai, listOf(user("u9", "New chat")))
        assertEquals(3, server.requests.size)
        assertEquals(system, server.body()["instructions"]!!.jsonPrimitive.content)
    }

    @Test
    fun `configuration_update items stay before the next user message, after the developer message`() = runTest {
        val server = Server(reply)
        var history = listOf(user("u1", "Draft a plan."))
        history = history + stored(send(server, openai, history), "a1") + user("u2", "More.")
        val beforeTurn = send(server, openai, history)
        val before = server.body()

        val high = openai.copy(effort = "high")
        history = history + stored(beforeTurn, "a2") + user("u3", "Find the failure modes.")
        val changeTurn = send(server, high, history)
        val after = server.body()
        assertEquals(bytes(before.input()), bytes(JsonArray(after.input().take(before.input().size))))
        assertEquals(developer, bytes(after.input().first()))
        assertEquals("low", after["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val input = after.input()
        assertEquals("""{"type":"configuration_update","reasoning":{"effort":"high"}}""", bytes(input[input.size - 2]))
        assertEquals("user", input.last().jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(1, input.count { it.jsonObject["type"]?.jsonPrimitive?.content == "configuration_update" })
        assertEquals(Part.TurnEffort("high", "low"), changeTurn.turnEffort)
        assertTrue(changeTurn.systemInInput)

        // Replayed in place on the next request.
        history = history + stored(changeTurn, "a3") + user("u4", "Thanks.")
        send(server, high, history)
        assertEquals(bytes(input), bytes(JsonArray(server.body().input().take(input.size))))
    }

    @Test
    fun `the ChatGPT plan route on GPT-6_2 sends a developer message, no system items`() = runTest {
        val plan = ProviderConfig("p", "ChatGPT", ApiFormat.CHATGPT, "https://api.openai.com/v1", "gpt-6.2-sol", effort = "high", requiresKey = false)
        val server = Server(reply)
        val events = OpenAiResponsesModel(server.client, plan, apiKey = null, bearer = BearerSource { "at" }).stream(ChatRequest(system, listOf(user("u1", "Hi")), emptyList())).toList()
        val body = server.body()
        assertNull(body["instructions"])
        assertEquals(developer, bytes(body.input().first()))
        assertTrue(ResponsesInput.problems(body.input().map { it.jsonObject }).isEmpty())
        assertTrue((events.last() as ChatEvent.Completed).turn.systemInInput)
    }

    @Test
    fun `which models move the system prompt`() {
        val url = "https://api.openai.com/v1"
        for (m in listOf("gpt-6.2", "gpt-6.2-sol", "gpt-6.2-astra", "GPT-6.3-luna", "gpt-6.10", "gpt-7", "gpt-7.1-sol")) {
            assertTrue(SystemPlacement.documented(ApiFormat.OPENAI_RESPONSES, url, m), m)
        }
        for (m in listOf("gpt-6", "gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-5.6", "gpt-5.5-pro", "o4-mini", "gpt-6.2x")) {
            assertFalse(SystemPlacement.documented(ApiFormat.OPENAI_RESPONSES, url, m), m)
        }
        assertTrue(SystemPlacement.documented(ApiFormat.CHATGPT, url, "gpt-6.2-sol"))
        assertFalse(SystemPlacement.documented(ApiFormat.OPENAI_CHAT, url, "gpt-6.2-sol"))
        assertFalse(SystemPlacement.documented(ApiFormat.OPENAI_RESPONSES, "https://openrouter.ai/api/v1", "gpt-6.2-sol"))
    }
}
