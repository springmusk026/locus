package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "What was sent" viewer rebuilds a request with [wireBody]; it must be exactly the body the
 * adapter put on the wire, for every HTTP format.
 */
class WireBodyTest {
    private val image = File.createTempFile("locus-wire", ".png").apply {
        writeBytes(ByteArray(2048) { it.toByte() })
        deleteOnExit()
    }
    private val tools = listOf("write_file", "read_file").map {
        ToolSpec(it, "Does $it", JsonObject(mapOf("type" to JsonPrimitive("object"))))
    }
    private val history = listOf(
        Message(
            "u1", "c", Role.USER,
            listOf(Part.Attachment("p.png", "image/png", image.path, "uploads/p.png", image.length()), Part.Text("What is this?")),
            0,
        ),
        Message("a1", "c", Role.ASSISTANT, listOf(Part.Text("Let me look."), Part.ToolCall("call_1", "read_file", """{"path":"a.txt"}""")), 1),
        Message("t1", "c", Role.TOOL, listOf(Part.ToolResult("call_1", "read_file", "hello", isError = false)), 2),
        Message("a2", "c", Role.ASSISTANT, listOf(Part.Text("It says hello.")), 3),
        Message("u2", "c", Role.USER, listOf(Part.Text("Thanks")), 4),
    )
    private val request = ChatRequest("You are Locus.", history, tools)

    private fun config(format: ApiFormat, base: String, model: String) =
        ProviderConfig("p", "P", format, base, model, effort = "high", requiresKey = format != ApiFormat.CHATGPT)

    private fun assertSameAsSent(model: ChatModel, engine: RecordingEngine) = runTest {
        runCatching { model.stream(request).toList() }
        val sent = LlmJson.parseToJsonElement(engine.lastBody())
        assertEquals<kotlinx.serialization.json.JsonElement?>(sent, model.wireBody(request))
    }

    @Test
    fun `anthropic rebuild equals the sent body`() {
        val engine = RecordingEngine(body = "")
        assertSameAsSent(AnthropicChatModel(engine.client, config(ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"), "sk-ant-key"), engine)
    }

    @Test
    fun `chat completions rebuild equals the sent body`() {
        val engine = RecordingEngine(body = "data: [DONE]\n\n")
        assertSameAsSent(OpenAiChatModel(engine.client, config(ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "x/y"), "sk-or-key"), engine)
    }

    @Test
    fun `responses rebuild equals the sent body`() {
        val engine = RecordingEngine(body = "")
        assertSameAsSent(OpenAiResponsesModel(engine.client, config(ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6"), "sk-key"), engine)
    }

    @Test
    fun `chatgpt rebuild equals the sent body`() {
        val engine = RecordingEngine(body = "")
        assertSameAsSent(
            OpenAiResponsesModel(engine.client, config(ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-6"), apiKey = null, bearer = BearerSource { "at-1" }),
            engine,
        )
    }

    @Test
    fun `models without an HTTP body have none`() {
        val other = object : ChatModel {
            override val format = ApiFormat.ON_DEVICE
            override fun stream(request: ChatRequest) = kotlinx.coroutines.flow.emptyFlow<ChatEvent>()
        }
        assertNull(other.wireBody(request))
    }
}
