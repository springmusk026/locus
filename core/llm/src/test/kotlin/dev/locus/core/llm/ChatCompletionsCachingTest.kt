package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prompt caching and sticky routing on Chat Completions (docs/design/cost.md, "OpenRouter and Chat
 * Completions caching"): what each host and model family gets, that it stays the same from turn to turn,
 * that hosts without documented support get nothing, and how cache usage is read back.
 */
class ChatCompletionsCachingTest {
    private val done = sse(null to """{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""", null to "[DONE]")
    private val tools = listOf(ToolSpec("read_file", "Reads a file", JsonObject(mapOf("type" to JsonPrimitive("object")))))
    private val key = PromptCacheKey.forChat("chat-42")

    private fun provider(baseUrl: String, model: String) = ProviderConfig("p", "P", ApiFormat.OPENAI_CHAT, baseUrl, model)
    private val openRouter = "https://openrouter.ai/api/v1"

    private fun user(id: String, text: String) = Message(id, "c", Role.USER, listOf(Part.Text(text)), 0)

    /** Turn 1: the user's message. Turn 2: a tool call and its result after it, so the last message is a tool result. */
    private val turn1 = listOf(user("u1", "Read notes.md"))
    private val turn2 = turn1 + listOf(
        Message("a1", "c", Role.ASSISTANT, listOf(Part.ToolCall("call_1", "read_file", """{"path":"notes.md"}""")), 1),
        Message("t1", "c", Role.TOOL, listOf(Part.ToolResult("call_1", "read_file", "# Notes")), 2),
    )

    private suspend fun body(config: ProviderConfig, messages: List<Message> = turn1, cache: Boolean = true, cacheKey: String? = key): JsonObject {
        val engine = RecordingEngine(body = done)
        OpenAiChatModel(engine.client, config, "k").stream(ChatRequest("You are Locus.", messages, tools, cacheKey = cacheKey, cache = cache)).toList()
        return LlmJson.parseToJsonElement(engine.lastBody()).jsonObject
    }

    private fun JsonObject.system(): JsonObject = this["messages"]!!.jsonArray.first().jsonObject

    // ---- OpenRouter, Claude ---------------------------------------------------------------------

    @Test
    fun `claude through openrouter marks the system prompt and the conversation, and sends the session`() = runTest {
        val b = body(provider(openRouter, "anthropic/claude-opus-5.5"))
        // The prefix breakpoint: a content part on the system message.
        val part = b.system()["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", part["type"]!!.jsonPrimitive.content)
        assertEquals("You are Locus.", part["text"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("ephemeral"))), part["cache_control"])
        // The moving breakpoint: top-level, so it reaches tool results too. Default lifetime: no ttl.
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("ephemeral"))), b["cache_control"])
        assertEquals(key, b["session_id"]!!.jsonPrimitive.content)
        // Stickiness needs no manual provider order, which would turn it off.
        assertNull(b["provider"])
        assertNull(b["prompt_cache_key"])
        // Messages themselves are unchanged: the user's text stays a plain string, the tool result too.
        assertEquals("Read notes.md", b["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the cache fields are the same on every turn, so the prefix stays byte for byte`() = runTest {
        val config = provider(openRouter, "anthropic/claude-sonnet-5.5")
        val first = body(config, turn1)
        val second = body(config, turn2)
        assertEquals(first["cache_control"], second["cache_control"])
        assertEquals(first["session_id"], second["session_id"])
        assertEquals(first.system(), second.system())
        assertEquals(first["tools"], second["tools"])
        // The earlier messages are sent as they were.
        assertEquals(first["messages"]!!.jsonArray, JsonArrayOf(second["messages"]!!.jsonArray.take(first["messages"]!!.jsonArray.size)))
        // The tool result, now last, keeps its string content: the top-level marker covers it.
        assertTrue(second["messages"]!!.jsonArray.last().jsonObject["content"] is JsonPrimitive)
    }

    @Test
    fun `a one-off request gets no markers, only the session`() = runTest {
        val b = body(provider(openRouter, "anthropic/claude-haiku-5.5"), cache = false)
        assertNull(b["cache_control"])
        assertTrue(b.system()["content"] is JsonPrimitive)
        assertEquals(key, b["session_id"]!!.jsonPrimitive.content)
        // And without a key, no session either.
        assertNull(body(provider(openRouter, "anthropic/claude-haiku-5.5"), cache = false, cacheKey = null)["session_id"])
    }

    // ---- OpenRouter, other models: automatic caching, sticky routing only -----------------------

    @Test
    fun `other models through openrouter get the session and no markers`() = runTest {
        for (model in listOf("openai/gpt-6.1", "google/gemini-3-pro", "deepseek/deepseek-v4", "x-ai/grok-5", "qwen/qwen3.5-max")) {
            val b = body(provider(openRouter, model))
            assertNull(b["cache_control"], model)
            assertTrue(b.system()["content"] is JsonPrimitive, model)
            assertEquals(key, b["session_id"]!!.jsonPrimitive.content, model)
            assertNull(b["prompt_cache_key"], model)
            assertNull(b["provider"], model)
        }
    }

    // ---- OpenAI's own API -----------------------------------------------------------------------

    @Test
    fun `openai's own chat completions gets prompt_cache_key and nothing else`() = runTest {
        val b = body(provider("https://api.openai.com/v1", "gpt-6.1"))
        assertEquals(key, b["prompt_cache_key"]!!.jsonPrimitive.content)
        assertNull(b["session_id"])
        assertNull(b["cache_control"])
        assertTrue(b.system()["content"] is JsonPrimitive)
    }

    // ---- Hosts that would reject unknown fields -------------------------------------------------

    @Test
    fun `hosts without documented cache fields get none of them`() = runTest {
        val hosts = listOf(
            "https://api.groq.com/openai/v1" to "llama-4-scout",
            "http://localhost:11434/v1" to "qwen3:8b",
            "http://192.168.1.20:1234/v1" to "anthropic/claude-opus-5.5",
            "https://generativelanguage.googleapis.com/v1beta/openai" to "gemini-3-pro",
            "https://api.deepseek.com/v1" to "deepseek-chat",
            "https://my-proxy.example.com/v1" to "anthropic/claude-opus-5.5",
        )
        for ((url, model) in hosts) {
            val b = body(provider(url, model))
            for (field in listOf("cache_control", "session_id", "prompt_cache_key", "provider")) assertNull(b[field], "$url sent $field")
            assertTrue(b.system()["content"] is JsonPrimitive, url)
        }
    }

    @Test
    fun `a 400 naming a cache field is retried once without the hints`() = runTest {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            bodies += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) {
                respond(ByteReadChannel("""{"error":{"message":"Unrecognized request argument: session_id"}}"""), HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respond(ByteReadChannel(done), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        })
        val events = OpenAiChatModel(client, provider(openRouter, "anthropic/claude-opus-5.5"), "k")
            .stream(ChatRequest("You are Locus.", turn1, tools, cacheKey = key)).toList()
        assertTrue(events.last() is ChatEvent.Completed)
        assertEquals(2, bodies.size)
        val retried = LlmJson.parseToJsonElement(bodies[1]).jsonObject
        assertNull(retried["session_id"])
        assertNull(retried["cache_control"])
        assertTrue(retried.system()["content"] is JsonPrimitive)
    }

    // ---- Usage: what OpenRouter reports for cache reads and writes ------------------------------

    private suspend fun usageOf(usage: String): dev.locus.core.model.TokenUsage {
        val stream = sse(
            null to """{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
            null to """{"choices":[],"usage":$usage}""",
            null to "[DONE]",
        )
        val engine = RecordingEngine(body = stream)
        val turn = (OpenAiChatModel(engine.client, provider(openRouter, "anthropic/claude-opus-5.5"), "k").stream(ChatRequest("s", turn1, emptyList())).toList().last() as ChatEvent.Completed).turn
        return turn.usage!!
    }

    @Test
    fun `openrouter's cache write and read counts reach the usage and the price`() = runTest {
        // First request: the prefix is written (OpenRouter's usage accounting shape, with its cost and discount fields).
        val write = usageOf(
            """{"prompt_tokens":10339,"completion_tokens":60,"total_tokens":10399,"cost":0.0,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":10318},"cache_discount":-0.01}""",
        )
        assertEquals(10_339, write.input)
        assertEquals(0, write.cacheRead)
        assertEquals(10_318, write.cacheWrite)
        // Next request: read back.
        val read = usageOf(
            """{"prompt_tokens":10400,"completion_tokens":60,"total_tokens":10460,"prompt_tokens_details":{"cached_tokens":10318,"cache_write_tokens":0},"cache_discount":0.04}""",
        )
        assertEquals(10_318, read.cacheRead)
        assertEquals(0, read.cacheWrite)
        // Priced like Anthropic's own: Opus 5.5 at $4 input, $0.20 read, $5 write (5 minutes), $20 output.
        val opus = ModelPrice(4.0, 20.0, cacheRead = 0.2, cacheWrite = 5.0)
        assertEquals((21 * 4.0 + 10_318 * 5.0 + 60 * 20.0) / 1e6, opus.cost(write), 1e-9)
        assertEquals((82 * 4.0 + 10_318 * 0.2 + 60 * 20.0) / 1e6, opus.cost(read), 1e-9)
        assertTrue(opus.cost(read) < opus.cost(write) / 5)
        assertFalse(read.cacheWrite1h > 0)
    }

    private fun JsonArrayOf(items: List<kotlinx.serialization.json.JsonElement>) = kotlinx.serialization.json.JsonArray(items)
}
