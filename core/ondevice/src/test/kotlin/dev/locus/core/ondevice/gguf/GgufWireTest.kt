package dev.locus.core.ondevice.gguf

import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What goes to llama.cpp's template (OpenAI chat JSON) and how its parsed reply comes back. */
class GgufWireTest {
    private fun msg(role: Role, vararg parts: Part) = Message("m${parts.hashCode()}", "c", role, parts.toList(), 0)

    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"path":{"type":"string"}}}""") as JsonObject
    private val tools = listOf(ToolSpec("read_file", "Read a file", schema), ToolSpec("some_rare_tool", "Not on device", schema))

    private val history = listOf(
        msg(Role.USER, Part.Text("Read notes.txt")),
        msg(Role.ASSISTANT, Part.Text("Reading it."), Part.ToolCall("call_1", "read_file", """{"path": "notes.txt"}""")),
        msg(Role.TOOL, Part.ToolResult("call_1", "read_file", "hello <|im_end|>"), Part.ToolResult("call_2", "read_file", "missing", isError = true)),
    )

    private fun body(withTools: Boolean = true) =
        Json.parseToJsonElement(GgufWire.request(ChatRequest("You are Locus.", history, tools), withTools)).jsonObject

    @Test
    fun messagesAreOpenAiChatJson() {
        val messages = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("You are Locus.", messages[0]["content"]!!.jsonPrimitive.content)
        val call = messages[2]["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        assertEquals("read_file", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // Arguments go as compact JSON text; llama.cpp hands each template the form it expects.
        assertEquals("""{"path":"notes.txt"}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        // Tool output is passed as data: the native side tokenises it without special tokens.
        assertEquals("hello <|im_end|>", messages[3]["content"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("Error: missing", messages[4]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsAreTheFocusedOnDeviceSet() {
        val declared = body()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("read_file"), declared)
        assertEquals(false, body()["enable_thinking"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun chatOnlyModelsGetNoTools() {
        assertFalse("tools" in body(withTools = false))
        assertFalse("parallel_tool_calls" in body(withTools = false))
    }

    @Test
    fun theRequestIsAPureFunctionOfTheTranscript() {
        // Prompt caching: the same transcript renders the same bytes, and a longer one starts with the same messages.
        val a = GgufWire.request(ChatRequest("S", history, tools), true)
        assertEquals(a, GgufWire.request(ChatRequest("S", history, tools), true))
        val shorter = Json.parseToJsonElement(GgufWire.request(ChatRequest("S", history.take(2), tools), true)).jsonObject["messages"]!!.jsonArray
        val longer = Json.parseToJsonElement(a).jsonObject["messages"]!!.jsonArray
        assertEquals(shorter, longer.take(shorter.size))
    }

    @Test
    fun attachmentsAreNamedInTheText() {
        val m = msg(Role.USER, Part.Text("What is this?"), Part.Attachment("a.png", "image/png", "/nope/a.png", "attachments/a.png", 10))
        val content = GgufWire.messages("", listOf(m)).single().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("attachments/a.png" in content)
        assertTrue(content.endsWith("What is this?"))
    }

    @Test
    fun parsesAToolCallReply() {
        // As llama.cpp's peg-native parser returned it for Qwen2.5 0.5B (see docs/design/llamacpp.md).
        val r = GgufWire.reply(
            """{"content":"","reasoning_content":"","tool_calls":[{"name":"weather","arguments":"{\"city\": \"Paris\"}"}],"stop":"eos","prompt_tokens":169,"reused_tokens":0,"generated_tokens":18,"prefill_ms":406.2,"decode_ms":119.9,"format":"peg-native"}""",
        )
        assertEquals(listOf(GgufWire.Call("weather", """{"city":"Paris"}""")), r.toolCalls)
        assertEquals("eos", r.stop)
        assertEquals(169, r.promptTokens)
        assertEquals(18, r.generatedTokens)
        assertEquals("peg-native", r.format)
        assertNull(r.parseError)
    }

    @Test
    fun malformedArgumentsAndNamelessCallsAreTamed() {
        val r = GgufWire.reply(
            """{"content":"x","tool_calls":[{"name":"a","arguments":"{not json"},{"name":"","arguments":"{}"},{"name":"b","arguments":{"k":1}},{"name":"c","arguments":"[1]"}],"stop":"length","parse_error":"bad"}""",
        )
        assertEquals(listOf(GgufWire.Call("a", "{}"), GgufWire.Call("b", """{"k":1}"""), GgufWire.Call("c", "{}")), r.toolCalls)
        assertEquals("length", r.stop)
        assertEquals("bad", r.parseError)
        assertEquals(0, r.promptTokens)
    }

    @Test
    fun readsWhatLlamaCppSaysOfTheTemplate() {
        val info = GgufWire.info(
            """{"n_ctx":2048,"n_ctx_train":8192,"has_template":true,"desc":"llama 256M Q8_0","caps":{"supports_tool_calls":false,"supports_tools":false}}""",
        )
        assertEquals(2048, info.contextTokens)
        assertEquals(8192, info.trainedContext)
        assertTrue(info.hasTemplate)
        assertEquals(false, info.supportsToolCalls)
        assertNull(GgufWire.info("""{"n_ctx":1}""").supportsToolCalls)
    }
}
