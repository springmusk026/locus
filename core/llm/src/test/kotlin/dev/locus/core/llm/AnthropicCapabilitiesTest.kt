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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnthropicCapabilitiesTest {
    @BeforeTest
    fun before() = AnthropicCapabilities.clear()

    @AfterTest
    fun after() = AnthropicCapabilities.clear()

    private fun modelJson(id: String, compact: Boolean?) =
        """{"type":"model","id":"$id","display_name":"$id","capabilities":{"context_management":{"supported":true,""" +
            """"clear_tool_uses_20250919":{"supported":true},"compact_20260112":""" +
            (if (compact == null) "null" else """{"supported":$compact}""") + "}}}"

    private fun record(json: String) = AnthropicCapabilities.record(LlmJson.parseToJsonElement(json) as JsonObject)

    @Test
    fun `static list covers the models in the compaction docs`() {
        for (id in listOf("claude-fable-5-1", "claude-sonnet-5-5", "claude-sonnet-5", "claude-haiku-5-5", "claude-opus-5-5", "claude-sonnet-4-6")) {
            assertTrue(AnthropicCapabilities.compaction(id), id)
        }
        assertFalse(AnthropicCapabilities.compaction("claude-haiku-4-5"))
        assertTrue(AnthropicChatModel.compactsOnServer(" claude-sonnet-5-5 "))
    }

    @Test
    fun `the Models API answer wins over the static list`() {
        record(modelJson("claude-sonnet-4-6", false))
        record(modelJson("claude-new-6", true))
        record(modelJson("claude-null-6", null))
        assertFalse(AnthropicCapabilities.compaction("claude-sonnet-4-6"))
        assertTrue(AnthropicCapabilities.compaction("claude-new-6"))
        assertFalse(AnthropicCapabilities.compaction("claude-null-6"))
    }

    @Test
    fun `model objects without capabilities are ignored`() {
        record("""{"id":"claude-opus-5-5","capabilities":null}""")
        assertFalse(AnthropicCapabilities.known("claude-opus-5-5"))
        assertTrue(AnthropicCapabilities.compaction("claude-opus-5-5"))
    }

    // Real time: the lookup's timeout would fire at once on runTest's virtual clock.
    @Test
    fun `a chat looks the model up once and then sends compaction`() = runBlocking {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            seen += request
            if (request.method == HttpMethod.Get) respond(modelJson("claude-next-7", true)) else respond("", HttpStatusCode.OK)
        })
        val config = ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, AnthropicChatModel.DEFAULT_BASE_URL, "claude-next-7")
        val request = ChatRequest("s", listOf(Message("m", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())
        val model = AnthropicChatModel(http, config, "k")
        runCatching { model.stream(request).toList() }
        runCatching { model.stream(request).toList() }

        val gets = seen.filter { it.method == HttpMethod.Get }
        assertEquals(1, gets.size)
        assertEquals("https://api.anthropic.com/v1/models/claude-next-7", gets.single().url.toString())
        assertEquals("k", gets.single().headers["x-api-key"])
        assertTrue("compact-2026-01-12" in seen.last().headers["anthropic-beta"].orEmpty())
    }

    // Real time: the lookup's timeout would fire at once on runTest's virtual clock.
    @Test
    fun `a failed lookup falls back and is retried only after an hour`() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond("""{"error":"nope"}""", HttpStatusCode.InternalServerError) })
        AnthropicCapabilities.ensure(http, AnthropicChatModel.DEFAULT_BASE_URL, "k", "claude-opus-5-5", now = 0)
        AnthropicCapabilities.ensure(http, AnthropicChatModel.DEFAULT_BASE_URL, "k", "claude-opus-5-5", now = 1_000)
        assertEquals(1, calls)
        assertTrue(AnthropicCapabilities.compaction("claude-opus-5-5"))
        AnthropicCapabilities.ensure(http, AnthropicChatModel.DEFAULT_BASE_URL, "k", "claude-opus-5-5", now = 60 * 60 * 1000L + 1)
        assertEquals(2, calls)
    }

    @Test
    fun `other Anthropic-format servers are not asked`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { seen += it; respond("") })
        val config = ProviderConfig("p", "Gateway", ApiFormat.ANTHROPIC_MESSAGES, "https://gateway.example/anthropic", "claude-opus-5-5")
        runCatching { AnthropicChatModel(http, config, "k").stream(ChatRequest("s", emptyList(), emptyList())).toList() }
        assertTrue(seen.none { it.method == HttpMethod.Get })
    }

    @Test
    fun `listing models records their capabilities`() = runTest {
        val http = HttpClient(MockEngine { respond("""{"data":[${modelJson("claude-sonnet-4-6", false)}],"has_more":false}""") })
        val config = ProviderConfig("p", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, AnthropicChatModel.DEFAULT_BASE_URL, "")
        ModelLister.list(http, config, "k")
        assertFalse(AnthropicCapabilities.compaction("claude-sonnet-4-6"))
    }
}
