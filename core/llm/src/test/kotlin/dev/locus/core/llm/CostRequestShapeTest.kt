package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.Part
import dev.locus.core.model.PriceTier
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Request shapes and usage parsing behind cost accounting (docs/design/cost.md): server-side compaction
 * billing, cache writes, the compaction trigger below a price step, cache lifetimes and effort fields.
 */
class CostRequestShapeTest {
    private val messages = listOf(Message("m1", "c", Role.USER, listOf(Part.Text("hi")), 0))
    private val done = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[],"usage":{"input_tokens":10,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    )

    private fun anthropic(model: String, baseUrl: String = "https://api.anthropic.com", effort: String = "") =
        ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, baseUrl, model, effort = effort)

    private suspend fun anthropicBody(config: ProviderConfig, request: ChatRequest = ChatRequest("You are Locus.", messages, emptyList())): JsonObject {
        val engine = RecordingEngine(body = done)
        AnthropicChatModel(engine.client, config, "key").stream(request).toList()
        return LlmJson.parseToJsonElement(engine.lastBody()).jsonObject
    }

    private fun JsonObject.messageCacheControl(): JsonObject? =
        this["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.mapNotNull { it.jsonObject["cache_control"] as? JsonObject }.lastOrNull()

    private fun JsonObject.systemCacheControl(): JsonObject? = this["system"]!!.jsonArray.single().jsonObject["cache_control"] as? JsonObject

    // ---- Anthropic: server-side compaction is billed in usage.iterations -----------------------

    @Test
    fun `a compaction iteration is kept as the request's server-side summary, outside its own counts`() = runTest {
        // As the threshold compaction docs show it: top-level usage leaves the compaction iteration out.
        val stream = sse(
            "message_start" to """{"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[],"usage":{"input_tokens":23000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1000,"iterations":[""" +
                """{"type":"compaction","input_tokens":180000,"output_tokens":3500},{"type":"message","input_tokens":23000,"output_tokens":1000}]}}""",
        )
        val engine = RecordingEngine(body = stream)
        val turn = (AnthropicChatModel(engine.client, anthropic("claude-opus-5-5"), "key").stream(ChatRequest("s", messages, emptyList())).toList().last() as ChatEvent.Completed).turn
        val usage = turn.usage!!
        assertEquals(23_000, usage.input)
        assertEquals(1_000, usage.output)
        assertEquals(TokenUsage(180_000, 3_500), usage.serverCompaction)
        // The context the chat is at is the request's own, not the summary's.
        assertEquals(24_000, usage.context)
        // Priced and budgeted as two requests: Opus 5.5 at $4 / $20.
        val opus = ModelPrice(4.0, 20.0, cacheRead = 0.2, cacheWrite = 5.0)
        assertEquals((23_000 * 4.0 + 1_000 * 20.0 + 180_000 * 4.0 + 3_500 * 20.0) / 1e6, opus.cost(usage), 1e-9)
        assertEquals(23_000L + 1_000 + 180_000 + 3_500, dev.locus.core.model.TaskBudget.budgetTokens(usage))
    }

    @Test
    fun `without a compaction iteration the usage has no server-side summary`() = runTest {
        val stream = sse(
            "message_start" to """{"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[],"usage":{"input_tokens":50}}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5,"iterations":[{"type":"message","input_tokens":50,"output_tokens":5}]}}""",
        )
        val engine = RecordingEngine(body = stream)
        val turn = (AnthropicChatModel(engine.client, anthropic("claude-opus-5-5"), "key").stream(ChatRequest("s", messages, emptyList())).toList().last() as ChatEvent.Completed).turn
        assertNull(turn.usage!!.serverCompaction)
    }

    // ---- Anthropic: compaction trigger below the price step ------------------------------------

    @Test
    fun `Haiku 5_5 compacts at 90,000 tokens, below its 100,000-token price step`() = runTest {
        val edit = anthropicBody(anthropic("claude-haiku-5-5"))["context_management"]!!.jsonObject["edits"]!!.jsonArray.single().jsonObject
        assertEquals("compact_20260112", edit["type"]!!.jsonPrimitive.content)
        val trigger = edit["trigger"]!!.jsonObject
        assertEquals("input_tokens", trigger["type"]!!.jsonPrimitive.content)
        assertEquals(90_000, trigger["value"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a model without a price step keeps the API's default trigger`() = runTest {
        val edit = anthropicBody(anthropic("claude-opus-5-5"))["context_management"]!!.jsonObject["edits"]!!.jsonArray.single().jsonObject
        assertEquals("compact_20260112", edit["type"]!!.jsonPrimitive.content)
        assertFalse("trigger" in edit)
    }

    @Test
    fun `the trigger is the same on every request of a chat, so context_management never misses the cache`() = runTest {
        val first = anthropicBody(anthropic("claude-haiku-5-5"))["context_management"]
        val longer = messages + Message("a", "c", Role.ASSISTANT, listOf(Part.Text("hello")), 1) + Message("u", "c", Role.USER, listOf(Part.Text("more")), 2)
        val second = anthropicBody(anthropic("claude-haiku-5-5"), ChatRequest("You are Locus.", longer, emptyList()))["context_management"]
        assertEquals(first, second)
    }

    // ---- Anthropic: cache lifetimes ------------------------------------------------------------

    @Test
    fun `a main chat keeps the conversation for an hour on Anthropic's own API`() = runTest {
        val body = anthropicBody(anthropic("claude-opus-5-5"), ChatRequest("You are Locus.", messages, emptyList(), longCache = true))
        assertEquals("1h", body.systemCacheControl()!!["ttl"]!!.jsonPrimitive.content)
        assertEquals("1h", body.messageCacheControl()!!["ttl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `helpers and side calls keep the 5-minute default on the conversation, after the 1-hour prefix`() = runTest {
        val body = anthropicBody(anthropic("claude-opus-5-5"))
        assertEquals("1h", body.systemCacheControl()!!["ttl"]!!.jsonPrimitive.content)
        val message = body.messageCacheControl()!!
        assertEquals("ephemeral", message["type"]!!.jsonPrimitive.content)
        assertFalse("ttl" in message)
    }

    @Test
    fun `a gateway gets no ttl at all, even for a main chat`() = runTest {
        val body = anthropicBody(anthropic("claude-opus-5-5", baseUrl = "https://gateway.example.com"), ChatRequest("You are Locus.", messages, emptyList(), longCache = true))
        assertFalse("ttl" in body.systemCacheControl()!!)
        assertFalse("ttl" in body.messageCacheControl()!!)
    }

    @Test
    fun `an uncached side job carries no breakpoints whatever its lifetime`() = runTest {
        val body = anthropicBody(anthropic("claude-opus-5-5"), ChatRequest("You are Locus.", messages, emptyList(), cache = false, longCache = true))
        assertFalse("cache_control" in body.toString())
    }

    // ---- Effort: the model's own default when none is set --------------------------------------

    @Test
    fun `a blank effort sends none, so the model's default applies`() = runTest {
        val body = anthropicBody(anthropic("claude-opus-5-5"))
        assertFalse("output_config" in body)
        val low = anthropicBody(anthropic("claude-opus-5-5", effort = "low"))
        assertEquals("low", low["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Responses leaves effort out when blank and keeps the rest of the reasoning settings`() {
        val config = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.6")
        val model = OpenAiResponsesModel(RecordingEngine(body = "").client, config, "sk")
        val reasoning = model.buildBody(ChatRequest("s", messages, emptyList()), true, false)["reasoning"]!!.jsonObject
        assertFalse("effort" in reasoning)
        assertEquals("auto", reasoning["summary"]!!.jsonPrimitive.content)
        val set = OpenAiResponsesModel(RecordingEngine(body = "").client, config.copy(effort = "low"), "sk")
        assertEquals("low", set.buildBody(ChatRequest("s", messages, emptyList()), true, false)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    // ---- OpenAI: cache writes ------------------------------------------------------------------

    @Test
    fun `Responses usage reads cache writes and their 12-hour part`() {
        val usage = OpenAiResponsesModel.responsesUsage(
            LlmJson.parseToJsonElement(
                """{"input_tokens":100000,"output_tokens":2000,"input_tokens_details":{"cached_tokens":60000,"cache_write_tokens":30000,
                "cache_write_tokens_details":{"ttl_30m_tokens":20000,"ttl_12h_tokens":10000}},"output_tokens_details":{"reasoning_tokens":500}}""",
            ).jsonObject,
        )
        assertEquals(TokenUsage(100_000, 2_000, cacheRead = 60_000, cacheWrite = 30_000, reasoning = 500, cacheWrite12h = 10_000), usage)
        // GPT-5.6 at $4 input: 10k fresh, 60k read at 0.1x, 20k written for 30 minutes at 1.25x, 10k for 12 hours at 2.5x.
        val gpt = ModelPrice(4.0, 20.0, cacheRead = 0.4, cacheWrite = 5.0)
        assertEquals((10_000 * 4.0 + 60_000 * 0.4 + 20_000 * 5.0 + 10_000 * 10.0 + 2_000 * 20.0) / 1e6, gpt.cost(usage), 1e-9)
    }

    @Test
    fun `Chat Completions usage reads cache writes`() {
        val usage = OpenAiChatModel.tokenUsage(
            LlmJson.parseToJsonElement(
                """{"prompt_tokens":5000,"completion_tokens":100,"total_tokens":5100,"prompt_tokens_details":{"cached_tokens":1000,"cache_write_tokens":3000}}""",
            ).jsonObject,
        )
        assertEquals(1_000, usage.cacheRead)
        assertEquals(3_000, usage.cacheWrite)
    }

    // ---- Chat Completions: reasoning effort where documented -----------------------------------

    private fun chat(baseUrl: String, model: String, effort: String) =
        OpenAiChatModel(RecordingEngine(body = "").client, ProviderConfig("p", "P", ApiFormat.OPENAI_CHAT, baseUrl, model, effort = effort), "k")

    private fun OpenAiChatModel.body() = buildBody(ChatRequest("s", messages, emptyList()))

    @Test
    fun `OpenAI and Gemini get reasoning_effort, OpenRouter its reasoning object`() {
        assertEquals("low", chat("https://api.openai.com/v1", "gpt-5.5", "low").body()["reasoning_effort"]!!.jsonPrimitive.content)
        // Gemini's compatible endpoint takes minimal, low, medium and high: higher levels go as high.
        assertEquals("high", chat("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3-flash", "xhigh").body()["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("minimal", chat("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "none").body()["reasoning_effort"]!!.jsonPrimitive.content)
        val openRouter = chat("https://openrouter.ai/api/v1", "anthropic/claude-opus-5-5", "medium").body()
        assertEquals("medium", openRouter["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("reasoning_effort" in openRouter)
    }

    @Test
    fun `no effort field without a level, on other hosts, or for a model known not to reason`() {
        assertFalse("reasoning_effort" in chat("https://api.openai.com/v1", "gpt-5.5", "").body())
        assertFalse("reasoning_effort" in chat("https://api.groq.com/openai/v1", "openai/gpt-oss-120b", "low").body())
        assertFalse("reasoning_effort" in chat("http://192.168.1.10:11434/v1", "qwen3", "low").body())
        assertFalse("reasoning_effort" in chat("https://api.openai.com/v1", "gpt-4o", "low").body())
        assertFalse("reasoning" in chat("https://openrouter.ai/api/v1", "openai/gpt-4o", "low").body())
    }

    @Test
    fun `a host that rejects the effort gets the request once more without it`() = runTest {
        val bodies = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { req ->
                bodies += (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                if (bodies.size == 1) {
                    respond("""{"error":{"message":"Unrecognized request argument supplied: reasoning_effort"}}""", HttpStatusCode.BadRequest)
                } else {
                    respond(ByteReadChannel(sse(null to """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", null to "[DONE]")), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
            },
        )
        val config = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5.5", effort = "low")
        val turn = (OpenAiChatModel(client, config, "k").stream(ChatRequest("s", messages, emptyList())).toList().last() as ChatEvent.Completed).turn
        assertEquals("ok", turn.text)
        assertTrue("reasoning_effort" in bodies[0])
        assertFalse("reasoning_effort" in bodies[1])
    }

    @Test
    fun `another 400 is still an error, sent once`() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond("""{"error":{"message":"Invalid model"}}""", HttpStatusCode.BadRequest) })
        val config = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5.5", effort = "low")
        val failure = runCatching { OpenAiChatModel(client, config, "k").stream(ChatRequest("s", messages, emptyList())).toList() }.exceptionOrNull()
        assertTrue(failure is LlmException)
        assertEquals(1, calls)
    }

    // ---- models.dev price tiers ----------------------------------------------------------------

    @Test
    fun `price tiers are read from the catalogue, and a request is priced at its own step`() {
        val haiku = ModelCatalogIndex.parseModelsDev(
            """{"anthropic":{"models":{"claude-haiku-5-5":{"cost":{"input":0.1,"output":0.5,"cache_read":0.01,"cache_write":0.125,
            "tiers":[{"input":0.5,"output":2.5,"cache_read":0.05,"cache_write":0.625,"tier":{"type":"context","size":100000}},
                     {"input":9,"output":9,"tier":{"type":"unknown","size":5}}]}}}}}""",
        ).providers.getValue("anthropic").models.getValue("claude-haiku-5-5").price!!
        assertEquals(listOf(PriceTier(100_000, ModelPrice(0.5, 2.5, 0.05, 0.625))), haiku.tiers)
        assertEquals(100_000, haiku.priceStep)
        // At 100,000 the prompt isn't over the step; one token more and all of it, output included, is 5x.
        assertEquals((100_000 * 0.1 + 1_000 * 0.5) / 1e6, haiku.cost(TokenUsage(100_000, 1_000)), 1e-12)
        assertEquals((100_001 * 0.5 + 1_000 * 2.5) / 1e6, haiku.cost(TokenUsage(100_001, 1_000)), 1e-12)
        // Cache reads and writes count toward the step ("A request's prompt length counts all of its input tokens").
        assertEquals((10_000 * 0.5 + 95_000 * 0.05) / 1e6, haiku.cost(TokenUsage(105_000, 0, cacheRead = 95_000)), 1e-12)
    }

    @Test
    fun `tiers win over the older 200K field, which still applies on its own`() {
        val both = ModelPrice(4.0, 20.0, over200k = ModelPrice(8.0, 30.0), tiers = listOf(PriceTier(272_000, ModelPrice(8.0, 30.0))))
        assertEquals(272_000, both.priceStep)
        assertEquals(250_000 * 4.0 / 1e6, both.cost(TokenUsage(250_000, 0)), 1e-12)
        val legacy = ModelPrice(2.0, 12.0, over200k = ModelPrice(4.0, 18.0))
        assertEquals(200_000, legacy.priceStep)
        assertNull(ModelPrice(2.0, 12.0).priceStep)
    }
}
