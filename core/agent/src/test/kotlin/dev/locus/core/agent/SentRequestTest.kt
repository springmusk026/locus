package dev.locus.core.agent

import dev.locus.core.llm.AnthropicChatModel
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.OpenAiChatModel
import dev.locus.core.llm.OpenAiResponsesModel
import dev.locus.core.llm.StopReason
import dev.locus.core.llm.wireBody
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SentRequestTest {

    // ---- Scrubbing ---------------------------------------------------------------------------

    private val vault = listOf(
        "sk-ant-api03-PROVIDERKEY0123456789abcdef",
        "ghp_ConnectorToken0123456789abcdefABCD",
        """{"access_token":"mcp-access-0123456789","refresh_token":"mcp-refresh-0123456789","token_type":"Bearer"}""",
        "smtps://agent@example.com:app-pass-9876@smtp.example.com:465 imaps://imap.example.com:993 Locus Agent",
        "https://ntfy.sh/my_topic-1 tk_abcdef123456",
    )
    private val scrubber = SecretScrubber(vault)

    @Test
    fun `no vault value, key, bearer or api-key header survives`() {
        val text = """
            key sk-ant-api03-PROVIDERKEY0123456789abcdef and gh ghp_ConnectorToken0123456789abcdefABCD
            the session's mcp-access-0123456789 and mcp-refresh-0123456789
            mail password app-pass-9876, ntfy tk_abcdef123456
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig
            x-api-key: sk-live-unknown-0123456789abcdefgh
            {"api_key": "plainvalue123"}
            an OpenAI key sk-proj-AAAAAAAAAAAAAAAAAAAAAAAA and a Google key AIzaSyA0123456789abcdefghijklmnopqrstu
        """.trimIndent()
        val out = scrubber.scrub(text)
        listOf(
            "PROVIDERKEY", "ConnectorToken", "mcp-access-0123456789", "mcp-refresh-0123456789", "app-pass-9876",
            "tk_abcdef123456", "eyJhbGciOiJIUzI1NiJ9", "sk-live-unknown", "plainvalue123", "sk-proj-", "AIzaSy",
        ).forEach { assertFalse(it in out, "$it survived: $out") }
        assertTrue("Bearer [secret]" in out)
        assertTrue("x-api-key: [secret]" in out)
        // Ordinary words around the secrets stay readable.
        assertTrue("the session's" in out && "mail password" in out)
    }

    @Test
    fun `short or generic values are not used as secrets`() {
        val s = SecretScrubber(listOf("abc", "Bearer token here"))
        // A three-character value would wreck the text; a structured value's short parts are skipped.
        assertEquals("abc def", s.scrub("abc def"))
        assertEquals("[secret]", s.scrub("Bearer token here"))
    }

    @Test
    fun `base64 attachments become labels in every wire shape`() {
        val bytes = ByteArray(1_258_291) { (it % 251).toByte() }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val body = LlmJsonForTest.parse(
            """
            {"messages":[{"role":"user","content":[
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"$b64"}},
              {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"${b64.take(4000)}"}},
              {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$b64"}},
              {"type":"thinking","signature":"${"A".repeat(600)}"}
            ]}]}
            """,
        )
        val out = scrubber.scrub(body).toString()
        assertFalse(b64.take(64) in out)
        assertFalse("A".repeat(300) in out)
        assertTrue("[image, 1.2 MB]" in out, out)
        assertTrue("[PDF, 2.9 KB]" in out, out)
        assertTrue("[base64 data, " in out, out)
    }

    @Test
    fun `json scrubbing reaches nested strings and keeps keys`() {
        val body = buildJsonObject {
            put("model", "m")
            put("input", buildJsonObject { put("output", "token sk-ant-api03-PROVIDERKEY0123456789abcdef echoed") })
        }
        val out = scrubber.scrub(body) as JsonObject
        assertEquals("m", (out["model"] as JsonPrimitive).content)
        assertEquals("""{"output":"token [secret] echoed"}""", out["input"].toString())
    }

    // ---- The log's caps ----------------------------------------------------------------------

    private val dummyModel = object : ChatModel {
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { }
    }
    private val provider = ProviderConfig("p", "P", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt")

    private fun sent(reply: String?) = SentRequest(dummyModel, provider, ChatRequest("s", emptyList(), emptyList()), reply, 0)

    @Test
    fun `keeps only the last requests per chat and the most recent chats`() {
        val log = SentRequestLog(perChat = 3, maxChats = 2)
        repeat(5) { log.record("a", sent("r$it")) }
        assertEquals(3, log.size("a"))
        assertNull(log.forReply("a", "r1"))
        assertEquals("r4", log.latest("a")?.replyId)
        assertEquals("r2", log.forReply("a", "r2")?.replyId)

        log.record("b", sent("b0"))
        log.latest("a") // touch a: b is now the least recently used
        log.record("c", sent("c0"))
        assertEquals(0, log.size("b"))
        assertEquals(3, log.size("a"))
        assertEquals(1, log.size("c"))
    }

    @Test
    fun `default caps are small`() {
        val log = SentRequestLog()
        repeat(50) { i -> repeat(20) { j -> log.record("chat$i", sent("$i-$j")) } }
        assertEquals(SentRequestLog.PER_CHAT, log.size("chat49"))
        assertEquals(0, log.size("chat0"))
        assertEquals(SentRequestLog.MAX_CHATS, (0 until 50).count { log.size("chat$it") > 0 })
    }

    @Test
    fun `view text is capped and chunked`() {
        val long = (1..5000).joinToString("\n") { "line $it" }
        val chunks = SentView.chunks(long, 1000)
        assertEquals(long, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 1000 })
        assertTrue(chunks.dropLast(1).all { it.endsWith("\n") })
        val capped = SentView.capped("x".repeat(30), 10)
        assertTrue(capped.startsWith("x".repeat(10) + "\n…[20 more characters"))
        assertEquals("short", SentView.capped("short", 10))
    }

    // ---- Capture correctness -----------------------------------------------------------------

    private class Scripted(vararg turns: AssistantTurn) : ChatModel {
        val queue = ArrayDeque(turns.toList())
        val requests = mutableListOf<ChatRequest>()
        var fail = false
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            if (fail) throw LlmException("bad request", 400)
            emit(ChatEvent.Completed(queue.removeFirst()))
        }
    }

    private class Store : TranscriptStore {
        override suspend fun append(message: Message) = Unit
        override suspend fun record(entry: AuditEntry) = Unit
    }

    private val tool = object : Tool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec("look", "Looks", ObjectSchema().build())
        override fun describe(args: JsonObject) = "look"
        override suspend fun execute(args: JsonObject) = ToolResult("seen")
    }

    @Test
    fun `the loop reports each request it sent with the reply it produced`() = runTest {
        val model = Scripted(
            AssistantTurn("", listOf(Part.ToolCall("c1", "look", "{}")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null),
            AssistantTurn("Done.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null),
        )
        val seen = mutableListOf<Pair<ChatRequest, String?>>()
        var id = 0
        val loop = AgentLoop(
            model, ToolRegistry(listOf(tool)), { ToolPolicy.ALLOW }, ApprovalGate { true }, Store(),
            AgentConfig(onRequest = { r, reply -> seen += r to reply }), ids = { "id${id++}" },
        )
        val events = loop.run(Conversation("c", "t", "p", "sys", 0, 0), listOf(Message("u", "c", Role.USER, listOf(Part.Text("go")), 0))).toList()
        val replies = events.filterIsInstance<AgentEvent.MessageAppended>().map { it.message }.filter { it.role == Role.ASSISTANT }
        assertEquals(2, seen.size)
        // The very objects the model was given, not copies.
        assertSame(model.requests[0], seen[0].first)
        assertSame(model.requests[1], seen[1].first)
        assertEquals(replies.map { it.id }, seen.map { it.second })
    }

    @Test
    fun `a failed request is reported without a reply`() = runTest {
        val model = Scripted().apply { fail = true }
        val seen = mutableListOf<Pair<ChatRequest, String?>>()
        val loop = AgentLoop(
            model, ToolRegistry(listOf(tool)), { ToolPolicy.ALLOW }, ApprovalGate { true }, Store(),
            AgentConfig(onRequest = { r, reply -> seen += r to reply }), ids = { "x" },
        )
        loop.run(Conversation("c", "t", "p", "sys", 0, 0), listOf(Message("u", "c", Role.USER, listOf(Part.Text("go")), 0))).toList()
        assertEquals(1, seen.size)
        assertSame(model.requests.single(), seen.single().first)
        assertNull(seen.single().second)
    }

    // ---- The view ----------------------------------------------------------------------------

    private val image = File.createTempFile("locus-sent", ".png").apply {
        writeBytes(ByteArray(3000) { it.toByte() })
        deleteOnExit()
    }
    private val history = listOf(
        Message("u1", "c", Role.USER, listOf(Part.Attachment("p.png", "image/png", image.path, "uploads/p.png", 3000), Part.Text("What is this?")), 0),
        Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("Looking."), Part.ToolCall("call_1", "look", """{"q":"x"}""")), 1),
        Message("t1", "c", Role.TOOL, listOf(Part.ToolResult("call_1", "look", "result with ghp_ConnectorToken0123456789abcdefABCD")), 2),
    )
    private val request = ChatRequest("You are Locus.", history, listOf(tool.spec))
    private val http = HttpClient(MockEngine { respond("") })

    private fun viewFor(format: ApiFormat, model: ChatModel, base: String): SentView =
        SentView.fromBody(format, "P", "m", base, model.wireBody(request)!!, scrubber, reportedInputTokens = 1234)

    @Test
    fun `every format splits into the same sections`() {
        val anthropic = ProviderConfig("p", "A", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5", effort = "high")
        val chat = ProviderConfig("p", "O", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "x")
        val responses = ProviderConfig("p", "R", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6", effort = "low")
        val plan = ProviderConfig("p", "C", ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-6")
        val views = listOf(
            viewFor(ApiFormat.ANTHROPIC_MESSAGES, AnthropicChatModel(http, anthropic, "sk-ant-api03-PROVIDERKEY0123456789abcdef"), anthropic.baseUrl),
            viewFor(ApiFormat.OPENAI_CHAT, OpenAiChatModel(http, chat, "k"), chat.baseUrl),
            viewFor(ApiFormat.OPENAI_RESPONSES, OpenAiResponsesModel(http, responses, "k"), responses.baseUrl),
            viewFor(ApiFormat.CHATGPT, OpenAiResponsesModel(http, plan, null, bearer = { "t" }), plan.baseUrl),
        )
        for (v in views) {
            assertEquals("You are Locus.", v.system, v.format.name)
            assertTrue(v.tools.single().name.endsWith("look"), v.format.name)
            assertTrue(v.tools.single().schema.contains("\"type\""), v.format.name)
            val blocks = v.messages.flatMap { it.blocks }
            assertTrue(blocks.any { it is SentBlock.Attachment && it.label == "[image, 2.9 KB]" }, "${v.format}: $blocks")
            assertTrue(blocks.any { it is SentBlock.Text && it.text == "What is this?" }, v.format.name)
            assertTrue(blocks.any { it is SentBlock.ToolCall && it.name == "look" && it.arguments.contains("\"q\"") }, v.format.name)
            val result = blocks.filterIsInstance<SentBlock.ToolResult>().single()
            assertEquals("result with [secret]", result.content, v.format.name)
            assertTrue(v.params.any { it.first == "model" }, v.format.name)
            assertFalse("ConnectorToken" in v.json || "PROVIDERKEY" in v.json, v.format.name)
            assertFalse(Base64.getEncoder().encodeToString(image.readBytes()).take(40) in v.json, v.format.name)
            assertTrue(v.estimate.system > 0 && v.estimate.tools > 0 && v.estimate.messages > 0, v.format.name)
            assertEquals(1234, v.reportedInputTokens)
        }
        assertEquals("api.anthropic.com", views[0].host)
        assertTrue(views[0].params.any { it.first == "max_tokens" })
        assertTrue(views[0].params.any { it.first == "output_config" && "high" in it.second })
        assertTrue(views[2].params.any { it.first == "reasoning" && "low" in it.second })
        // Chat Completions sends the system prompt as a message; the view shows it once, as the system section.
        assertTrue(views[1].messages.none { it.role == "system" })
    }

    @Test
    fun `on-device view shows the conversation text`() {
        val v = SentView.fromOnDevice("Gemma", "gemma", "sys", listOf(tool.spec), listOf("user" to "hi", "model" to "hello"), scrubber)
        assertTrue(v.onDevice)
        assertNull(v.host)
        assertEquals(listOf("user", "model"), v.messages.map { it.role })
        assertTrue(v.json.startsWith("[system]\nsys"))
        assertTrue("[user]\nhi" in v.json)
    }

    @Test
    fun `the copy is capped for the clipboard`() {
        val huge = ChatRequest("word ".repeat(SentView.MAX_COPY_CHARS / 5 + 10), emptyList(), emptyList())
        val chat = ProviderConfig("p", "O", ApiFormat.OPENAI_CHAT, "https://x.test/v1", "x")
        val v = SentView.fromBody(ApiFormat.OPENAI_CHAT, "O", "x", chat.baseUrl, OpenAiChatModel(http, chat, null).wireBody(huge)!!, scrubber)
        assertTrue(v.json.length < SentView.MAX_COPY_CHARS + 200)
        assertTrue("cut for the clipboard" in v.json)
        assertIs<String>(v.system)
    }
}

private object LlmJsonForTest {
    fun parse(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text.trimIndent()) as JsonObject
}
