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
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ChatGPT-plan mode of [OpenAiResponsesModel] and [ModelLister] (SIWC preview rules). */
class ChatGptPlanTest {
    private val config = ProviderConfig("p", "ChatGPT", ApiFormat.CHATGPT, "https://api.openai.com/v1", "gpt-6.1-sol", effort = "high", requiresKey = false)
    private val tools = listOf("read_file", "list_files").map { ToolSpec(it, "Does $it", JsonObject(mapOf("type" to JsonPrimitive("object")))) }
    private val request = ChatRequest("You are Locus.", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), tools)
    private val done = sse(null to """{"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}""")

    private fun model(engine: RecordingEngine, token: () -> String = { "at-1" }) =
        OpenAiResponsesModel(engine.client, config, apiKey = null, bearer = BearerSource { token() })

    @Test
    fun `request body follows the plan route's rules`() {
        val body = model(RecordingEngine(body = done)).buildBody(request)
        assertEquals(false, body["store"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("You are Locus.", body["instructions"]?.jsonPrimitive?.content)
        val forbidden = listOf(
            "max_output_tokens", "temperature", "top_p", "metadata", "user", "truncation", "prompt_cache_retention",
            "previous_response_id", "background", "conversation", "max_tool_calls", "safety_identifier", "prompt", "top_logprobs",
        )
        forbidden.forEach { assertNull(body[it], "$it must not be sent") }
        // No system-role items: the system prompt travels as instructions.
        assertTrue(body["input"]!!.jsonArray.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" })
        // Function tools only inside one namespace.
        val toolList = body["tools"]!!.jsonArray
        val ns = toolList.single().jsonObject
        assertEquals("namespace", ns["type"]!!.jsonPrimitive.content)
        assertEquals(OpenAiResponsesModel.NAMESPACE, ns["name"]!!.jsonPrimitive.content)
        assertTrue(ns["description"]!!.jsonPrimitive.content.isNotBlank())
        val inner = ns["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("list_files", "read_file"), inner.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(inner.all { it["type"]!!.jsonPrimitive.content == "function" && it["parameters"] is JsonObject })
    }

    @Test
    fun `no tools means no namespace and the api-key mode is unchanged`() {
        assertNull(model(RecordingEngine(body = done)).buildBody(request.copy(tools = emptyList()))["tools"])
        val apiKeyBody = OpenAiResponsesModel(RecordingEngine(body = done).client, config.copy(format = ApiFormat.OPENAI_RESPONSES), "sk").buildBody(request)
        assertTrue(apiKeyBody["max_output_tokens"] != null)
        assertTrue(apiKeyBody["tools"]!!.jsonArray.all { it.jsonObject["type"]!!.jsonPrimitive.content == "function" })
    }

    @Test
    fun `history replays own items verbatim and namespaces calls from other providers`() {
        val m = model(RecordingEngine(body = done))
        val own = """[{"type":"function_call","call_id":"c1","name":"read_file","namespace":"locus","arguments":"{}"}]"""
        val items = m.toInputItems(
            listOf(
                Message("a", "c", Role.ASSISTANT, listOf(Part.ToolCall("c1", "read_file", "{}")), 1, ApiFormat.CHATGPT, own),
                Message("b", "c", Role.ASSISTANT, listOf(Part.ToolCall("c2", "list_files", "{}")), 2, ApiFormat.ANTHROPIC_MESSAGES, "[]"),
                // An API-key OpenAI turn isn't replayed raw: its items (and reasoning) belong to another account.
                Message("d", "c", Role.ASSISTANT, listOf(Part.ToolCall("c3", "list_files", "{}")), 3, ApiFormat.OPENAI_RESPONSES, """[{"type":"reasoning","encrypted_content":"X"}]"""),
            ),
        )
        assertEquals(LlmJson.parseToJsonElement(own).jsonArray.single(), items[0])
        assertEquals(listOf("c2", "c3"), items.drop(1).map { it["call_id"]!!.jsonPrimitive.content })
        assertTrue(items.drop(1).all { it["namespace"]?.jsonPrimitive?.content == OpenAiResponsesModel.NAMESPACE })
        assertTrue(items.none { it["type"]?.jsonPrimitive?.content == "reasoning" })
    }

    @Test
    fun `each request asks the bearer source for a current token`() = runTest {
        val engine = RecordingEngine(body = done)
        var n = 0
        val m = model(engine) { "at-${++n}" }
        val turn = (m.stream(request).toList().last() as ChatEvent.Completed).turn
        m.stream(request).toList()
        assertEquals(listOf("Bearer at-1", "Bearer at-2"), engine.requests.map { it.headers[HttpHeaders.Authorization] })
        assertEquals(ApiFormat.CHATGPT, turn.format)
        assertEquals("Hello", turn.text)
    }

    @Test
    fun `usage limit mid-stream points to ChatGPT usage settings and isn't retried`() = runTest {
        val failed = sse(null to """{"type":"response.failed","response":{"status":"failed","error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}""")
        val e = assertFailsWith<LlmException> { model(RecordingEngine(body = failed)).stream(request).toList() }
        assertTrue("Settings → Usage" in e.message!! && ChatGptErrors.USAGE_URL in e.message!!)
        assertFalse(e.retryable)

        val unavailable = sse(null to """{"type":"response.failed","response":{"status":"failed","error":{"code":"subscription_sharing_usage_unavailable","message":"x"}}}""")
        assertTrue(assertFailsWith<LlmException> { model(RecordingEngine(body = unavailable)).stream(request).toList() }.retryable)
    }

    @Test
    fun `http errors before the stream map to plan messages`() = runTest {
        val limited = RecordingEngine(HttpStatusCode.TooManyRequests, """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}""")
        val e = assertFailsWith<LlmException> { model(limited).stream(request).toList() }
        assertEquals(ChatGptErrors.LIMIT_MESSAGE, e.message)
        assertFalse(e.retryable)

        // A bare 429 without a code is still a plan limit; {"detail":…} bodies are diagnostic only.
        assertEquals(ChatGptErrors.LIMIT_MESSAGE, assertFailsWith<LlmException> { model(RecordingEngine(HttpStatusCode.TooManyRequests, """{"detail":"slow down"}""")).stream(request).toList() }.message)
        val denied = assertFailsWith<LlmException> { model(RecordingEngine(HttpStatusCode.Unauthorized, """{"detail":"no"}""")).stream(request).toList() }
        assertTrue("Sign in again" in denied.message!!)
        val unsupported = assertFailsWith<LlmException> {
            model(RecordingEngine(HttpStatusCode.BadRequest, """{"error":{"code":"subscription_sharing_unsupported_capability","param":"tools[0]","message":"nope"}}""")).stream(request).toList()
        }
        assertTrue("tools[0]" in unsupported.message!!)
        assertFalse(unsupported.retryable)
        assertTrue(assertFailsWith<LlmException> { model(RecordingEngine(HttpStatusCode.ServiceUnavailable, """{"detail":"down"}""")).stream(request).toList() }.retryable)
    }

    @Test
    fun `model list keeps listed models in server order`() = runTest {
        var auth: String? = null
        val http = HttpClient(MockEngine { req ->
            auth = req.headers[HttpHeaders.Authorization]
            assertEquals("https://api.openai.com/v1/models", req.url.toString())
            respond(
                """{"models":[{"slug":"gpt-6.1-sol","display_name":"GPT-6.1 Sol","visibility":"list"},{"slug":"internal","display_name":"Hidden","visibility":"hide"},{"slug":"gpt-6-mini","display_name":"GPT-6 mini","visibility":"list"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val models = ModelLister.list(http, config, "at-1")
        assertEquals(listOf(RemoteModel("gpt-6.1-sol", "GPT-6.1 Sol"), RemoteModel("gpt-6-mini", "GPT-6 mini")), models)
        assertEquals("Bearer at-1", auth)
    }

    @Test
    fun `a ChatGPT provider without a sign-in can't make a model`() {
        assertFailsWith<LlmException> { ChatModels.create(RecordingEngine(body = done).client, config, apiKey = "sk") }
    }

    @Test
    fun `a completed event with empty output is rebuilt from the streamed items`() = runTest {
        val body = sse(
            null to """{"type":"response.output_text.delta","delta":"Hel"}""",
            null to """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}}""",
            null to """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"c1","name":"read_file","namespace":"locus","arguments":"{\"path\":\"a\"}"}}""",
            null to """{"type":"response.completed","response":{"status":"completed","output":[]}}""",
        )
        val turn = (model(RecordingEngine(body = body)).stream(request).toList().last() as ChatEvent.Completed).turn
        assertEquals("Hello", turn.text)
        assertEquals(listOf("read_file"), turn.toolCalls.map { it.name })
    }

    @Test
    fun `prompt_cache_key is sent on both OpenAI routes when given, and only then`() = runTest {
        val keyed = request.copy(cacheKey = PromptCacheKey.forChat("chat-1"))
        val engine = RecordingEngine(body = done)
        model(engine).stream(keyed).toList()
        model(engine).stream(keyed.copy(messages = keyed.messages + keyed.messages)).toList()
        val sent = engine.requests.indices.map { i ->
            kotlinx.serialization.json.Json.parseToJsonElement((engine.requests[i].body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString())
                .jsonObject["prompt_cache_key"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("locus-chat-chat-1", "locus-chat-chat-1"), sent, "stable across turns of one chat")
        val apiKey = OpenAiResponsesModel(RecordingEngine(body = done).client, config.copy(format = ApiFormat.OPENAI_RESPONSES), "sk")
        assertEquals("locus-chat-chat-1", apiKey.buildBody(keyed)["prompt_cache_key"]?.jsonPrimitive?.content)
        assertNull(model(RecordingEngine(body = done)).buildBody(request)["prompt_cache_key"])
    }

    @Test
    fun `a server that refuses prompt_cache_key gets the request again without it`() = runTest {
        val bodies = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            bodies += (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (bodies.size == 1) {
                respond(
                    """{"error":{"code":"subscription_sharing_unsupported_capability","param":"prompt_cache_key","message":"Unsupported parameter."}}""",
                    HttpStatusCode.BadRequest,
                )
            } else {
                respond(io.ktor.utils.io.ByteReadChannel(done), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        })
        val m = OpenAiResponsesModel(client, config, apiKey = null, bearer = BearerSource { "at-1" })
        val turn = (m.stream(request.copy(cacheKey = "locus-chat-x")).toList().last() as ChatEvent.Completed).turn
        assertEquals("Hello", turn.text)
        assertTrue("prompt_cache_key" in bodies[0])
        assertFalse("prompt_cache_key" in bodies[1])
        assertTrue("\"reasoning\"" in bodies[1], "only the refused field is dropped")
    }

    @Test
    fun `cache keys depend only on the chat or helper profile and stay short`() {
        assertEquals(PromptCacheKey.forChat("a"), PromptCacheKey.forChat("a"))
        assertTrue(PromptCacheKey.forChat("a") != PromptCacheKey.forChat("b"))
        assertEquals("locus-helper-researcher", PromptCacheKey.forHelper("researcher"))
        val long = PromptCacheKey.forHelper("x".repeat(64))
        assertTrue(long.length <= 64 && long == PromptCacheKey.forHelper("x".repeat(64)))
    }
}
