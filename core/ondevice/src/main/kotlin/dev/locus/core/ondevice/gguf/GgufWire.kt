package dev.locus.core.ondevice.gguf

import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolSpec
import dev.locus.core.ondevice.OnDeviceText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What crosses the JNI boundary for one turn. The request is OpenAI chat-completions JSON, which
 * llama.cpp's common/chat.cpp reads (`common_chat_msgs_parse_oaicompat`, `common_chat_tools_parse_oaicompat`)
 * and renders through the model's own Jinja template; the reply is the parsed turn.
 *
 * Prompt caching: everything here is a pure function of the request, and the request is append-only
 * (frozen system prompt, a tool list fixed per chat, history only growing), so the rendered prompt of
 * step N+1 starts with step N's prompt plus its reply, and llama.cpp reuses that prefix from its KV
 * cache (locus_llama.cpp). Nothing time-dependent or random goes in.
 */
object GgufWire {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The request JSON. [withTools] is false for a chat-only model: its template can't show tools, so
     * none are sent and it answers in plain text. Tools are the same focused list as LiteRT-LM's.
     */
    fun request(request: ChatRequest, withTools: Boolean, temperature: Double = DEFAULT_TEMPERATURE): String {
        val tools = if (withTools) OnDeviceText.tools(request) else emptyList()
        val body = buildJsonObject {
            put("messages", messages(request.system, request.messages))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map(::tool)))
                put("parallel_tool_calls", true)
            }
            // Thinking costs many tokens at phone speeds; models that always think still stream it as reasoning.
            put("enable_thinking", false)
            put("temperature", temperature)
        }
        return body.toString()
    }

    fun tool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("parameters", spec.inputSchema)
            },
        )
    }

    /**
     * The system prompt, then each message as an OpenAI chat message. Attachments are named in the
     * user's text (GGUF models get no pixels in v1); each tool result is its own `tool` message, in
     * the order of the calls.
     */
    fun messages(system: String, messages: List<Message>): JsonArray = buildJsonArray {
        if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
        messages.forEach { m ->
            when (m.role) {
                Role.USER -> add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", OnDeviceText.userNote(m, emptyList(), m.attachments))
                    },
                )
                Role.ASSISTANT -> add(
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", m.text)
                        if (m.toolCalls.isNotEmpty()) put("tool_calls", JsonArray(m.toolCalls.map(::toolCall)))
                    },
                )
                Role.TOOL -> m.toolResults.forEach { r ->
                    add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", r.callId)
                            put("name", r.toolName)
                            put("content", if (r.isError) "Error: ${r.content}" else r.content)
                        },
                    )
                }
            }
        }
    }

    private fun toolCall(call: Part.ToolCall): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", call.name)
                // Templates differ on whether they print arguments as a string or an object; llama.cpp
                // takes the string and hands each template what it expects.
                put("arguments", normalisedArguments(call.argumentsJson))
            },
        )
    }

    /** Arguments as compact JSON text; "{}" when the model wrote something that isn't a JSON object. */
    fun normalisedArguments(raw: String): String {
        val parsed = runCatching { json.parseToJsonElement(raw.ifBlank { "{}" }) }.getOrNull()
        return (parsed as? JsonObject)?.toString() ?: "{}"
    }

    /** One finished turn from the native side. */
    data class Reply(
        val content: String,
        val reasoning: String,
        val toolCalls: List<Call>,
        /** "eos", "length", "context", "cancelled" or "error". */
        val stop: String,
        val promptTokens: Int,
        val reusedTokens: Int,
        val generatedTokens: Int,
        val prefillMs: Double,
        val decodeMs: Double,
        val format: String?,
        val parseError: String?,
    )

    data class Call(val name: String, val arguments: String)

    fun reply(text: String): Reply {
        val o = json.parseToJsonElement(text).jsonObject
        fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull ?: 0
        fun dbl(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val calls = ((o["tool_calls"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val name = c["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            Call(name, arguments(c["arguments"]))
        }
        return Reply(
            content = str("content"),
            reasoning = str("reasoning_content"),
            toolCalls = calls,
            stop = str("stop").ifEmpty { "eos" },
            promptTokens = int("prompt_tokens"),
            reusedTokens = int("reused_tokens"),
            generatedTokens = int("generated_tokens"),
            prefillMs = dbl("prefill_ms"),
            decodeMs = dbl("decode_ms"),
            format = str("format").ifEmpty { null },
            parseError = str("parse_error").ifEmpty { null },
        )
    }

    /** A call's arguments as JSON object text, whether the parser gave a string or an object. */
    private fun arguments(element: kotlinx.serialization.json.JsonElement?): String = when (element) {
        null -> "{}"
        is JsonObject -> element.toString()
        is JsonPrimitive -> if (element.isString) normalisedArguments(element.content) else "{}"
        else -> "{}"
    }

    /** What the loaded model's template can do, as llama.cpp detected it ([LlamaNative.nativeInfo]). */
    data class Info(val contextTokens: Int, val trainedContext: Int, val hasTemplate: Boolean, val supportsToolCalls: Boolean?, val description: String)

    fun info(text: String): Info {
        val o = json.parseToJsonElement(text).jsonObject
        val caps = o["caps"] as? JsonObject
        return Info(
            contextTokens = o["n_ctx"]?.jsonPrimitive?.intOrNull ?: 0,
            trainedContext = o["n_ctx_train"]?.jsonPrimitive?.intOrNull ?: 0,
            hasTemplate = o["has_template"]?.jsonPrimitive?.booleanOrNull ?: false,
            supportsToolCalls = caps?.get("supports_tool_calls")?.jsonPrimitive?.booleanOrNull,
            description = o["desc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    const val DEFAULT_TEMPERATURE = 0.6
}
