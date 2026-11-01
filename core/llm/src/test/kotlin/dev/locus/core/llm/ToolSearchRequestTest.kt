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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Request shapes for tool discovery per adapter (docs/design/tool-discovery.md): Anthropic's server-side
 * tool search with `defer_loading`, OpenAI's hosted `tool_search` with deferred functions in namespaces,
 * and the plain shape everywhere else, where find_tools and call_tool do the work.
 */
class ToolSearchRequestTest {
    private val http = HttpClient(MockEngine { respond("") })
    private fun spec(name: String) = ToolSpec(name, "Does $name", JsonObject(mapOf("type" to JsonPrimitive("object"))))
    private val loaded = listOf(spec("read_file"), spec("find_tools"), spec("call_tool"))
    private val deferred = listOf(spec("github_issues"), spec("device_alarm"), spec("mcp__linear__create_issue"), spec("mcp__bad name__x"))
    private fun request(deferredTools: List<ToolSpec> = deferred, messages: List<Message> = listOf(Message("u", "c", Role.USER, listOf(Part.Text("hi")), 0)), system: String = "sys") =
        ChatRequest(system, messages, loaded, "key", deferredTools = deferredTools)

    private fun anthropic(model: String = "claude-opus-5-5", baseUrl: String = "https://api.anthropic.com") =
        AnthropicChatModel(http, ProviderConfig("a", "A", ApiFormat.ANTHROPIC_MESSAGES, baseUrl, model), "k")

    private fun JsonObject.tools() = getValue("tools").jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `Anthropic defers the deferred tools and find and call behind the BM25 search tool`() {
        val tools = anthropic().buildBody(request()).tools()
        assertEquals(AnthropicChatModel.TOOL_SEARCH_TYPE, tools.first().str("type"))
        assertEquals("tool_search_tool_bm25", tools.first().str("name"))
        val upFront = tools.drop(1).filter { it["defer_loading"] == null }.map { it.str("name") }
        val later = tools.filter { it["defer_loading"] == JsonPrimitive(true) }.map { it.str("name") }
        assertEquals(listOf("read_file"), upFront)
        assertEquals(listOf("call_tool", "device_alarm", "find_tools", "github_issues", "mcp__linear__create_issue"), later)
        // A name the API wouldn't accept stays reachable through find_tools only.
        assertFalse(tools.any { it.str("name") == "mcp__bad name__x" })
        // No deferred tool carries cache_control (a 400); the system prompt holds the prefix breakpoint.
        assertTrue(tools.none { it["defer_loading"] != null && it["cache_control"] != null })
        // Without a system prompt the breakpoint goes on the last tool loaded up front, never a deferred one.
        val noSystem = anthropic().buildBody(request(system = "")).tools()
        assertEquals(listOf("read_file"), noSystem.filter { it["cache_control"] != null }.map { it.str("name") })
    }

    @Test
    fun `Anthropic keeps the plain shape without deferred tools, on older models and on gateways`() {
        listOf(
            anthropic().buildBody(request(deferredTools = emptyList())),
            anthropic(model = "claude-opus-4-1").buildBody(request()),
            anthropic(baseUrl = "https://gateway.example.com").buildBody(request()),
        ).forEach { body ->
            val tools = body.tools()
            assertEquals(listOf("call_tool", "find_tools", "read_file"), tools.map { it.str("name") })
            assertTrue(tools.none { it["defer_loading"] != null || it.str("type") != null })
        }
    }

    @Test
    fun `Anthropic tool lists are identical turn after turn`() {
        val model = anthropic()
        val first = model.buildBody(request()).tools()
        val later = model.buildBody(request(messages = request().messages + Message("a", "c", Role.ASSISTANT, listOf(Part.Text("ok")), 1))).tools()
        assertEquals(first, later)
    }

    @Test
    fun `Anthropic replays search blocks and defines tools a past search returned even if they're gone`() = runTest {
        val stream = sse(
            "message_start" to """{"type":"message_start","message":{"id":"m","role":"assistant","content":[]}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"tool_search_tool_bm25"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"linear issue\"}"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_search_tool_result","tool_use_id":"srvtoolu_1","content":{"type":"tool_search_tool_search_result","tool_references":[{"type":"tool_reference","tool_name":"mcp__old__thing"}]}}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"mcp__old__thing","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":2}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
        )
        val engine = RecordingEngine(body = stream)
        val model = AnthropicChatModel(engine.client, ProviderConfig("a", "A", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"), "k")
        val turn = (model.stream(request()).toList().last() as ChatEvent.Completed).turn
        // Only the client tool call is ours to run; the search is the server's.
        assertEquals(listOf("mcp__old__thing"), turn.toolCalls.map { it.name })
        val raw = LlmJson.parseToJsonElement(turn.rawPayload!!).jsonArray
        assertEquals("""{"query":"linear issue"}""", raw[0].jsonObject["input"].toString())
        assertEquals("tool_search_tool_result", raw[1].jsonObject.str("type"))

        val reply = Message("a", "c", Role.ASSISTANT, turn.toolCalls, 1, format = ApiFormat.ANTHROPIC_MESSAGES, rawPayload = turn.rawPayload)
        val result = Message("t", "c", Role.TOOL, listOf(Part.ToolResult("toolu_1", "mcp__old__thing", "done")), 2)
        val next = model.buildBody(request(messages = request().messages + reply + result))
        // Replayed as received, search blocks included, and no tool_result for the server's own call.
        val assistant = next.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray
        assertEquals(listOf("server_tool_use", "tool_search_tool_result", "tool_use"), assistant.map { it.jsonObject.str("type") })
        assertFalse("srvtoolu_1\",\"content\":\"" in next.getValue("messages").toString())
        // The MCP server's tool is gone from this request's deferred tools, so it gets a stub.
        val stub = next.tools().single { it.str("name") == "mcp__old__thing" }
        assertEquals(JsonPrimitive(true), stub["defer_loading"])
        assertTrue("No longer available" in stub.str("description")!!)
    }

    @Test
    fun `Responses puts deferred tools in namespaces beside hosted tool_search on GPT-5_4 and later`() {
        val model = OpenAiResponsesModel(http, ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.5"), "k")
        val tools = model.buildBody(request()).tools()
        assertEquals(listOf("read_file"), tools.filter { it.str("type") == "function" }.map { it.str("name") })
        assertEquals("tool_search", tools.last().str("type"))
        val spaces = tools.filter { it.str("type") == "namespace" }
        assertEquals(listOf("device", "discovery", "github", "mcp_linear"), spaces.map { it.str("name") })
        spaces.forEach { s ->
            val inner = (s["tools"] as JsonArray).map { it.jsonObject }
            assertTrue(inner.all { it["defer_loading"] == JsonPrimitive(true) }, s.toString())
            assertTrue(s.str("description")!!.length < 120, s.toString())
        }
        assertEquals(model.buildBody(request()).tools(), tools)
    }

    @Test
    fun `Responses keeps the plain shape before GPT-5_4, without deferred tools, and on the ChatGPT plan route`() {
        assertFalse(OpenAiResponsesModel.supportsToolSearch("gpt-5.2"))
        assertFalse(OpenAiResponsesModel.supportsToolSearch("o4-mini"))
        assertTrue(OpenAiResponsesModel.supportsToolSearch("gpt-5.4-mini"))
        assertTrue(OpenAiResponsesModel.supportsToolSearch("gpt-6"))
        val old = OpenAiResponsesModel(http, ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.2"), "k")
        val plan = OpenAiResponsesModel(http, ProviderConfig("o", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-5.5"), null)
        val current = OpenAiResponsesModel(http, ProviderConfig("o", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-5.5"), "k")
        listOf(old.buildBody(request()), current.buildBody(request(deferredTools = emptyList()))).forEach { body ->
            assertEquals(listOf("call_tool", "find_tools", "read_file"), body.tools().map { it.str("name") })
        }
        assertTrue("tool_search" !in plan.buildBody(request()).getValue("tools").toString())
    }

    @Test
    fun `OpenAI-compatible chat ignores deferred tools`() {
        val model = OpenAiChatModel(http, ProviderConfig("c", "C", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "x"), "k")
        val names = model.buildBody(request()).tools().map { it.getValue("function").jsonObject.str("name") }
        assertEquals(listOf("call_tool", "find_tools", "read_file"), names)
        assertEquals(model.buildBody(request(deferredTools = emptyList())), model.buildBody(request()))
    }
}
