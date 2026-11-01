package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Chat Completions over raw HTTP + SSE. One adapter covers every server that speaks this
 * protocol (OpenAI, OpenRouter, Groq, Ollama, LM Studio, llama.cpp server, vLLM), so it sends only
 * fields they all accept: no `max_tokens` (renamed by OpenAI, unsupported elsewhere) and no
 * `stream_options`.
 */
class OpenAiChatModel(
    private val http: HttpClient,
    private val config: ProviderConfig,
    private val apiKey: String?,
) : ChatModel {

    override val format = ApiFormat.OPENAI_CHAT

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        http.preparePost("${config.baseUrl.trimEnd('/')}/chat/completions") {
            if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(LlmJson.encodeToString(JsonObject.serializer(), buildBody(request)))
        }.execute { response ->
            response.throwIfFailed(config.name)
            val acc = StreamAccumulator()
            response.forEachSseEvent { sse ->
                val data = sse.data.trim()
                if (data.isEmpty() || data == "[DONE]") return@forEachSseEvent
                val chunk = LlmJson.parseToJsonElement(data).jsonObject
                acc.handle(chunk).forEach { emit(it) }
            }
            emit(ChatEvent.Completed(acc.finish()))
        }
    }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", config.model.trim())
        put("stream", true)
        // Usage in the stream is opt-in on OpenAI; only ask hosts known to accept the option.
        if (USAGE_HOSTS.any { config.baseUrl.contains(it) }) putJsonObject("stream_options") { put("include_usage", true) }
        putJsonArray("messages") {
            if (request.system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                })
            }
            toWireMessages(request.messages).forEach { add(it) }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.sortedBy { it.name }.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        }
                    })
                }
            }
        }
    }

    internal fun toWireMessages(messages: List<Message>): List<JsonObject> = buildList {
        for (message in messages) {
            when (message.role) {
                Role.USER -> add(buildJsonObject {
                    put("role", "user")
                    if (message.attachments.isEmpty()) {
                        put("content", message.text)
                    } else {
                        putJsonArray("content") {
                            message.attachments.forEach { a ->
                                when (val p = payload(a, allowPdf = false)) {
                                    is AttachmentPayload.Image -> add(buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", "data:${p.mimeType};base64,${p.base64}") }
                                    })
                                    is AttachmentPayload.Pdf, is AttachmentPayload.Note -> add(buildJsonObject {
                                        put("type", "text")
                                        put("text", note(a))
                                    })
                                }
                            }
                            if (message.text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", message.text) })
                        }
                    }
                })
                Role.ASSISTANT -> add(buildJsonObject {
                    val extras = if (message.format == ApiFormat.OPENAI_CHAT) toolCallExtras(message.rawPayload) else emptyMap()
                    put("role", "assistant")
                    if (message.text.isNotEmpty()) put("content", message.text) else put("content", JsonNull)
                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.argumentsJson)
                                    }
                                    // Provider data the server attached to this call (Gemini's thought signature), sent back as received.
                                    extras[call.id]?.let { put("extra_content", it) }
                                })
                            }
                        }
                    }
                })
                Role.TOOL -> message.toolResults.forEach { result ->
                    add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.callId)
                        put("content", if (result.isError) "ERROR: ${result.content}" else result.content)
                    })
                }
            }
        }
    }

    private inner class StreamAccumulator {
        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, CallBuilder>()
        private var finishReason: String? = null
        private var usage: JsonObject? = null

        fun handle(chunk: JsonObject): List<ChatEvent> {
            (chunk["usage"] as? JsonObject)?.let { usage = it }
            (chunk["error"] as? JsonObject)?.let { error ->
                // OpenRouter sends errors that happen after the stream opened as a chunk with the HTTP code
                // it would have used (https://openrouter.ai/docs/api-reference/errors).
                val code = error.string("code")?.toIntOrNull()
                if (code != null) {
                    val classified = httpError(config.name, code, LlmJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("error" to error))))
                    throw LlmException("${config.name} stream error: ${error.string("message") ?: error}", code, classified.retryable, kind = classified.kind)
                }
                throw LlmException("${config.name} stream error: ${error.string("message") ?: error}", retryable = false)
            }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
            choice.string("finish_reason")?.let { finishReason = it }
            val delta = choice["delta"] as? JsonObject ?: return emptyList()
            val events = mutableListOf<ChatEvent>()
            delta.string("content")?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                events += ChatEvent.TextDelta(it)
            }
            // Servers disagree on the reasoning field name; accept either.
            (delta.string("reasoning_content") ?: delta.string("reasoning"))?.takeIf { it.isNotEmpty() }?.let {
                events += ChatEvent.ReasoningDelta(it)
            }
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { position, element ->
                val call = element as? JsonObject ?: return@forEachIndexed
                val index = call.int("index") ?: position
                val builder = calls.getOrPut(index) { CallBuilder() }
                call.string("id")?.let { if (builder.id.isEmpty()) builder.id = it }
                (call["extra_content"] as? JsonObject)?.let { builder.extra = it }
                val function = call["function"] as? JsonObject
                function?.string("name")?.let {
                    if (builder.name.isEmpty()) {
                        builder.name = it
                        events += ChatEvent.ToolCallStarted(builder.id, it)
                    }
                }
                function?.string("arguments")?.let { builder.arguments.append(it) }
            }
            return events
        }

        fun finish(): AssistantTurn {
            val toolCalls = calls.values.mapIndexed { i, call ->
                val args = call.arguments.toString().ifBlank { "{}" }
                val valid = runCatching { LlmJson.parseToJsonElement(args) is JsonObject }.getOrDefault(false)
                Part.ToolCall(
                    id = call.id.ifEmpty { "call_$i" },
                    name = call.name,
                    argumentsJson = if (valid) args else buildJsonObject { put(INVALID_INPUT_KEY, args) }.toString(),
                )
            }
            return AssistantTurn(
                text = text.toString(),
                toolCalls = toolCalls,
                stopReason = when {
                    finishReason == "length" -> StopReason.MAX_TOKENS
                    finishReason == "content_filter" -> StopReason.REFUSAL
                    toolCalls.isNotEmpty() -> StopReason.TOOL_USE
                    finishReason == null || finishReason == "stop" -> StopReason.END_TURN
                    else -> StopReason.OTHER
                },
                format = ApiFormat.OPENAI_CHAT,
                usage = usage?.let(::tokenUsage),
                rawPayload = calls.values.mapIndexedNotNull { i, call -> call.extra?.let { toolCalls[i].id to it } }
                    .takeIf { it.isNotEmpty() }
                    ?.let { pairs -> buildJsonObject { put(EXTRAS_KEY, JsonObject(pairs.toMap())) }.toString() },
            )
        }
    }

    private class CallBuilder {
        var id = ""
        var name = ""
        val arguments = StringBuilder()
        var extra: JsonObject? = null
    }

    private companion object {
        /** In [Message.rawPayload] for this format: tool call id → the server's `extra_content` for it. */
        const val EXTRAS_KEY = "tool_call_extra_content"

        /** Hosts documented to accept `stream_options.include_usage`; others may reject unknown fields. */
        val USAGE_HOSTS = listOf("api.openai.com", "openrouter.ai", "api.groq.com")

        /**
         * OpenAI counts `completion_tokens_details.reasoning_tokens` inside `completion_tokens`, but some
         * compatible servers leave hidden reasoning out of `completion_tokens` while `total_tokens` still
         * has it. Output is therefore the largest of the three readings, so thinking is never dropped.
         */
        fun tokenUsage(u: JsonObject): dev.locus.core.model.TokenUsage {
            val input = u.int("prompt_tokens") ?: 0
            val completion = u.int("completion_tokens") ?: 0
            val reasoning = (u["completion_tokens_details"] as? JsonObject)?.int("reasoning_tokens") ?: 0
            val fromTotal = u.int("total_tokens")?.let { it - input } ?: 0
            return dev.locus.core.model.TokenUsage(
                input = input,
                output = maxOf(completion, reasoning, fromTotal),
                cacheRead = (u["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0,
                reasoning = reasoning,
            )
        }

        fun toolCallExtras(raw: String?): Map<String, JsonObject> {
            val obj = raw?.let { runCatching { LlmJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return emptyMap()
            return (obj[EXTRAS_KEY] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }?.toMap().orEmpty()
        }
    }
}
