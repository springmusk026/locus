package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * view_image's images in each provider's own shape (docs/design/view-image.md): the exact JSON, for a
 * step with three calls, two of which showed images.
 */
class ToolImageWireTest {
    private val png = File.createTempFile("shot", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
    private val jpg = File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(4, 5, 6)); deleteOnExit() }
    private val a = Part.Attachment("home.png", "image/png", png.path, "shots/home.png", 3, 800, 600)
    private val b = Part.Attachment("cat.jpg", "image/jpeg", jpg.path, "photos/cat.jpg", 3, 1568, 1176)
    private val gone = Part.Attachment("old.png", "image/png", "/nonexistent/old.png", "shots/old.png", 3, 10, 10)

    private val history = listOf(
        Message("u", "c", Role.USER, listOf(Part.Text("check these")), 0),
        Message(
            "a", "c", Role.ASSISTANT,
            listOf(
                Part.ToolCall("t1", "view_image", """{"path":"shots/home.png"}"""),
                Part.ToolCall("t2", "read_file", """{"path":"notes.md"}"""),
                Part.ToolCall("t3", "view_image", """{"paths":["photos/cat.jpg","shots/old.png"]}"""),
            ),
            1,
        ),
        Message(
            "r", "c", Role.TOOL,
            listOf(
                Part.ToolResult("t1", "view_image", "Showing shots/home.png (800×600).", images = listOf(a)),
                Part.ToolResult("t2", "read_file", "1\thello"),
                Part.ToolResult("t3", "view_image", "Showing photos/cat.jpg (1568×1176).", images = listOf(b, gone)),
            ),
            2,
        ),
    )

    private fun json(text: String): JsonElement = LlmJson.parseToJsonElement(text)

    @Test
    fun `anthropic puts text and image blocks in the tool_result, results first`() {
        val config = ProviderConfig("p", "A", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
        val wire = AnthropicChatModel(RecordingEngine(body = "").client, config, "k").toWireMessages(history)
        val noteGone = note(gone)
        assertEquals(
            json(
                """[
                {"type":"tool_result","tool_use_id":"t1","content":[
                  {"type":"text","text":"Showing shots/home.png (800×600)."},
                  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AQID"}}]},
                {"type":"tool_result","tool_use_id":"t2","content":"1\thello"},
                {"type":"tool_result","tool_use_id":"t3","content":[
                  {"type":"text","text":"Showing photos/cat.jpg (1568×1176)."},
                  {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"BAUG"}},
                  {"type":"text","text":${quote(noteGone)}}]}
                ]""",
            ),
            wire[2].jsonObject["content"],
        )
        assertEquals("user", wire[2].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic still takes the conversation breakpoint on the last tool_result`() {
        val config = ProviderConfig("p", "A", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
        val model = AnthropicChatModel(RecordingEngine(body = "").client, config, "k")
        val last = model.buildBody(ChatRequest("You are Locus.", history, emptyList()))["messages"]!!.jsonArray.last().jsonObject
        val block = last["content"]!!.jsonArray.last().jsonObject
        assertEquals("tool_result", block["type"]!!.jsonPrimitive.content)
        assertTrue("cache_control" in block)
    }

    @Test
    fun `responses puts input_text and input_image in the function_call_output`() {
        val config = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6-astra")
        val items = OpenAiResponsesModel(RecordingEngine(body = "").client, config, "k").toInputItems(history).filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(
            json(
                """[
                {"type":"function_call_output","call_id":"t1","output":[
                  {"type":"input_text","text":"Showing shots/home.png (800×600)."},
                  {"type":"input_image","image_url":"data:image/png;base64,AQID","detail":"auto"}]},
                {"type":"function_call_output","call_id":"t2","output":"1\thello"},
                {"type":"function_call_output","call_id":"t3","output":[
                  {"type":"input_text","text":"Showing photos/cat.jpg (1568×1176)."},
                  {"type":"input_image","image_url":"data:image/jpeg;base64,BAUG","detail":"auto"},
                  {"type":"input_text","text":${quote(note(gone))}}]}
                ]""",
            ),
            kotlinx.serialization.json.JsonArray(items),
        )
        // Normalising for the stateless request keeps the outputs as they are.
        val normalised = ResponsesInput.normalize(OpenAiResponsesModel(RecordingEngine(body = "").client, config, "k").toInputItems(history))
        assertEquals(3, normalised.count { it["type"]?.jsonPrimitive?.content == "function_call_output" })
    }

    @Test
    fun `chat completions keeps tool messages text and adds one user message after the last of them`() {
        val config = ProviderConfig("p", "O", ApiFormat.OPENAI_CHAT, "https://x.test/v1", "m")
        val wire = OpenAiChatModel(RecordingEngine(body = "").client, config, "k").toWireMessages(history)
        assertEquals(listOf("user", "assistant", "tool", "tool", "tool", "user"), wire.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(
            json(
                """[
                {"role":"tool","tool_call_id":"t1","content":"Showing shots/home.png (800×600)."},
                {"role":"tool","tool_call_id":"t2","content":"1\thello"},
                {"role":"tool","tool_call_id":"t3","content":"Showing photos/cat.jpg (1568×1176)."},
                {"role":"user","content":[
                  {"type":"text","text":"Images from view_image (call t1): home.png, 800×600"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,AQID","detail":"auto"}},
                  {"type":"text","text":"Images from view_image (call t3): cat.jpg, 1568×1176; old.png, 10×10"},
                  {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,BAUG","detail":"auto"}},
                  {"type":"text","text":${quote(note(gone))}}]}
                ]""",
            ),
            kotlinx.serialization.json.JsonArray(wire.drop(2)),
        )
    }

    @Test
    fun `chat completions puts the images before the next message, and tool messages still follow their calls`() {
        val config = ProviderConfig("p", "O", ApiFormat.OPENAI_CHAT, "https://x.test/v1", "m")
        val more = history + Message("a2", "c", Role.ASSISTANT, listOf(Part.Text("Looks fine.")), 3) + Message("u2", "c", Role.USER, listOf(Part.Text("thanks")), 4)
        val roles = OpenAiChatModel(RecordingEngine(body = "").client, config, "k").toWireMessages(more).map { it["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "tool", "tool", "tool", "user", "assistant", "user"), roles)
    }

    @Test
    fun `results without images send exactly what they always did`() {
        val plain = history.map { m -> if (m.role == Role.TOOL) m.copy(parts = m.toolResults.map { it.copy(images = emptyList()) }) else m }
        val anthropic = AnthropicChatModel(RecordingEngine(body = "").client, ProviderConfig("p", "A", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"), "k")
        anthropic.toWireMessages(plain)[2].jsonObject["content"]!!.jsonArray.forEach { assertTrue(it.jsonObject["content"] is kotlinx.serialization.json.JsonPrimitive) }
        val chat = OpenAiChatModel(RecordingEngine(body = "").client, ProviderConfig("p", "O", ApiFormat.OPENAI_CHAT, "https://x.test/v1", "m"), "k").toWireMessages(plain)
        assertEquals(5, chat.size)
        val responses = OpenAiResponsesModel(RecordingEngine(body = "").client, ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1", "gpt-6-astra"), "k").toInputItems(plain)
        responses.filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }.forEach { assertTrue(it["output"] is kotlinx.serialization.json.JsonPrimitive) }
    }

    @Test
    fun `tool images need vision and count like attachment images`() {
        val request = ChatRequest("s", history, emptyList())
        assertTrue(ModelRequirements.of(request).images)
        val withoutImages = ChatRequest("s", history.map { m -> if (m.role == Role.TOOL) m.copy(parts = m.toolResults.map { it.copy(images = emptyList()) }) else m }, emptyList())
        assertFalse(ModelRequirements.of(withoutImages).images)
        assertEquals(3 * ModelRequirements.IMAGE_TOKENS, ModelRequirements.estimateTokens(request) - ModelRequirements.estimateTokens(withoutImages))
        val blind = ModelCapabilities(vision = Support.NO).fit(ModelRequirements.of(request))
        assertFalse(blind.usable)
        assertEquals("no vision", blind.reason)
    }

    @Test
    fun `models that get no pixels from tool results are told what they missed`() {
        assertEquals(null, toolImagesNotShown(emptyList()))
        assertEquals("[Not shown: home.png, 800×600; cat.jpg, 1568×1176. This model can't see images in tool results.]", toolImagesNotShown(listOf(a, b)))
    }

    private fun quote(text: String) = kotlinx.serialization.json.JsonPrimitive(text).toString()
}
