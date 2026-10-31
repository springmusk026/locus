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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Responses API over raw HTTP + SSE, stateless (`store: false`): every request carries the
 * whole history. Each turn's output items, including reasoning items with their encrypted content,
 * are kept in [Message.rawPayload] and replayed untouched, so the model keeps its reasoning across
 * tool calls. OpenAI's newest models only support function calling here, not in Chat Completions.
 * Field and event names follow the official openai-python types (`types/responses`).
 */
class OpenAiResponsesModel(
    private val http: HttpClient,
    private val config: ProviderConfig,
    private val apiKey: String?,
) : ChatModel {

    override val format = ApiFormat.OPENAI_RESPONSES

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        var withReasoning = true
        while (true) {
            var retryWithout = false
            http.preparePost("${config.baseUrl.trimEnd('/')}/responses") {
                if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(LlmJson.encodeToString(JsonObject.serializer(), buildBody(request, withReasoning)))
            }.execute { response ->
                // Models without reasoning reject the reasoning settings; ask once more without them.
                if (response.status.value == 400 && withReasoning) {
                    val body = runCatching { response.bodyAsText() }.getOrDefault("")
                    if ("reasoning" in body) {
                        retryWithout = true
                        return@execute
                    }
                    throw LlmException("${config.name} error 400: ${body.take(500)}", httpStatus = 400)
                }
                response.throwIfFailed(config.name)
                var final: JsonObject? = null
                response.forEachSseEvent { sse ->
                    val data = sse.data.trim()
                    if (data.isEmpty() || data == "[DONE]") return@forEachSseEvent
                    val event = LlmJson.parseToJsonElement(data).jsonObject
                    when (event.string("type")) {
                        "response.output_text.delta" -> event.string("delta")?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.TextDelta(it)) }
                        "response.reasoning_summary_text.delta" -> event.string("delta")?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.ReasoningDelta(it)) }
                        "response.output_item.added" -> (event["item"] as? JsonObject)?.takeIf { it.string("type") == "function_call" }?.let { item ->
                            emit(ChatEvent.ToolCallStarted(item.string("call_id").orEmpty(), item.string("name").orEmpty()))
                        }
                        "response.completed", "response.incomplete" -> final = event["response"] as? JsonObject
                        "response.failed" -> {
                            val error = (event["response"] as? JsonObject)?.get("error") as? JsonObject
                            throw LlmException(
                                "${config.name}: ${error?.string("message") ?: "the response failed"}",
                                retryable = error?.string("code") in setOf("server_error", "rate_limit_exceeded"),
                            )
                        }
                        "error" -> throw LlmException(
                            "${config.name} stream error: ${event.string("message")}",
                            retryable = event.string("code") in setOf("server_error", "rate_limit_exceeded"),
                        )
                    }
                }
                emit(ChatEvent.Completed(toTurn(final ?: throw LlmException("${config.name} ended the stream without a response.", retryable = true))))
            }
            if (!retryWithout) break
            withReasoning = false
        }
    }

    internal fun buildBody(request: ChatRequest, withReasoning: Boolean = true): JsonObject = buildJsonObject {
        put("model", config.model.trim())
        put("stream", true)
        put("store", false)
        if (request.system.isNotBlank()) put("instructions", request.system)
        put("input", JsonArray(toInputItems(request.messages)))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.sortedBy { it.name }.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                        // Our schemas mark optional properties as optional, which strict mode doesn't allow.
                        put("strict", false)
                    })
                }
            }
        }
        if (withReasoning) {
            putJsonObject("reasoning") {
                put("effort", config.effort.ifBlank { "medium" })
                put("summary", "auto")
            }
        }
        put("max_output_tokens", config.maxOutputTokens)
    }

    internal fun toInputItems(messages: List<Message>): List<JsonObject> = buildList {
        for (message in messages) {
            when (message.role) {
                Role.USER -> add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        message.attachments.forEach { a ->
                            when (val p = payload(a, allowPdf = false)) {
                                is AttachmentPayload.Image -> add(buildJsonObject {
                                    put("type", "input_image")
                                    put("image_url", "data:${p.mimeType};base64,${p.base64}")
                                    put("detail", "auto")
                                })
                                is AttachmentPayload.Pdf, is AttachmentPayload.Note -> add(inputText(note(a)))
                            }
                        }
                        if (message.text.isNotBlank() || message.attachments.isEmpty()) add(inputText(message.text))
                    }
                })
                Role.ASSISTANT -> {
                    val raw = message.rawPayload
                    if (message.format == ApiFormat.OPENAI_RESPONSES && raw != null) {
                        // This model's own output items, replayed untouched (reasoning included).
                        LlmJson.parseToJsonElement(raw).jsonArray.forEach { add(it.jsonObject) }
                    } else {
                        // Turns from another provider: plain text and calls, no reasoning to carry over.
                        if (message.text.isNotEmpty()) add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("content", message.text)
                        })
                        message.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            })
                        }
                    }
                }
                Role.TOOL -> message.toolResults.forEach { result ->
                    add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", result.callId)
                        put("output", if (result.isError) "ERROR: ${result.content}" else result.content)
                    })
                }
            }
        }
    }

    private fun inputText(text: String) = buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }

    internal fun toTurn(response: JsonObject): AssistantTurn {
        val output = (response["output"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val text = StringBuilder()
        var refusal: String? = null
        val calls = mutableListOf<Part.ToolCall>()
        for (item in output) {
            when (item.string("type")) {
                "message" -> (item["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { part ->
                    when (part.string("type")) {
                        "output_text" -> text.append(part.string("text").orEmpty())
                        "refusal" -> refusal = part.string("refusal")
                    }
                }
                "function_call" -> {
                    val args = item.string("arguments").orEmpty().ifBlank { "{}" }
                    val valid = runCatching { LlmJson.parseToJsonElement(args) is JsonObject }.getOrDefault(false)
                    calls += Part.ToolCall(
                        id = item.string("call_id").orEmpty(),
                        name = item.string("name").orEmpty(),
                        argumentsJson = if (valid) args else buildJsonObject { put(INVALID_INPUT_KEY, args) }.toString(),
                    )
                }
            }
        }
        val incomplete = (response["incomplete_details"] as? JsonObject)?.string("reason")
        return AssistantTurn(
            text = text.toString(),
            toolCalls = calls,
            stopReason = when {
                refusal != null || incomplete == "content_filter" -> StopReason.REFUSAL
                incomplete == "max_output_tokens" -> StopReason.MAX_TOKENS
                calls.isNotEmpty() -> StopReason.TOOL_USE
                response.string("status") == "completed" -> StopReason.END_TURN
                else -> StopReason.OTHER
            },
            format = ApiFormat.OPENAI_RESPONSES,
            rawPayload = buildJsonArray { output.forEach { add(it) } }.toString(),
            refusalDetail = refusal,
            usage = (response["usage"] as? JsonObject)?.let { u ->
                dev.locus.core.model.TokenUsage(
                    input = u.int("input_tokens") ?: 0,
                    output = u.int("output_tokens") ?: 0,
                    cacheRead = (u["input_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0,
                )
            },
        )
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
