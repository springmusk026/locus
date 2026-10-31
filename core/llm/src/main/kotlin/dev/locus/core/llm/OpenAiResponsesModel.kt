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
 *
 * With [ApiFormat.CHATGPT] the same requests run on the user's ChatGPT plan, under the preview's
 * rules (developers.openai.com/siwc/token-sharing-open-source/preview-limitations): a fresh OAuth
 * token from [bearer] on every request, no `max_output_tokens`, function tools grouped in the
 * [NAMESPACE] namespace, and plan errors mapped by [ChatGptErrors].
 */
class OpenAiResponsesModel(
    private val http: HttpClient,
    private val config: ProviderConfig,
    private val apiKey: String?,
    private val bearer: BearerSource? = null,
) : ChatModel {

    private val chatGpt = config.format == ApiFormat.CHATGPT

    override val format = if (chatGpt) ApiFormat.CHATGPT else ApiFormat.OPENAI_RESPONSES

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        var withReasoning = true
        var withCacheKey = request.cacheKey != null
        while (true) {
            var retryWithout = false
            val token = bearer?.token() ?: apiKey
            http.preparePost("${config.baseUrl.trimEnd('/')}/responses") {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(LlmJson.encodeToString(JsonObject.serializer(), buildBody(request, withReasoning, withCacheKey)))
            }.execute { response ->
                // Models without reasoning reject the reasoning settings; ask once more without them.
                // `prompt_cache_key` is not on the ChatGPT preview's omit list, but if a server refuses it,
                // drop it the same way rather than fail the chat.
                if ((response.status.value == 400 || response.status.value == 422) && (withReasoning || withCacheKey)) {
                    val body = runCatching { response.bodyAsText() }.getOrDefault("")
                    if (withCacheKey && "prompt_cache_key" in body) {
                        withCacheKey = false
                        retryWithout = true
                        return@execute
                    }
                    if (withReasoning && "reasoning" in body) {
                        withReasoning = false
                        retryWithout = true
                        return@execute
                    }
                    val status = response.status.value
                    if (chatGpt) throw ChatGptErrors.fromHttp(status, body)
                    throw LlmException("${config.name} error $status: ${body.take(500)}", httpStatus = status)
                }
                if (chatGpt && response.status.value !in 200..299) {
                    throw ChatGptErrors.fromHttp(response.status.value, runCatching { response.bodyAsText() }.getOrDefault("").take(2000))
                }
                response.throwIfFailed(config.name)
                var final: JsonObject? = null
                // Finished output items as they stream. Some servers (the ChatGPT plan route among them)
                // send `response.completed` with an empty `output`; the turn is then rebuilt from these.
                val doneItems = mutableListOf<JsonObject>()
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
                        "response.output_item.done" -> (event["item"] as? JsonObject)?.let { doneItems += it }
                        "response.completed", "response.incomplete" -> final = event["response"] as? JsonObject
                        "response.failed" -> {
                            val error = (event["response"] as? JsonObject)?.get("error") as? JsonObject
                            if (chatGpt) throw ChatGptErrors.fromCode(error?.string("code"), error?.string("message"), param = error?.string("param"))
                            throw LlmException(
                                "${config.name}: ${error?.string("message") ?: "the response failed"}",
                                retryable = error?.string("code") in setOf("server_error", "rate_limit_exceeded"),
                            )
                        }
                        "error" -> if (chatGpt) throw ChatGptErrors.fromCode(event.string("code"), event.string("message")) else throw LlmException(
                            "${config.name} stream error: ${event.string("message")}",
                            retryable = event.string("code") in setOf("server_error", "rate_limit_exceeded"),
                        )
                    }
                }
                val response = final ?: throw LlmException("${config.name} ended the stream without a response.", retryable = true)
                emit(ChatEvent.Completed(toTurn(withStreamedOutput(response, doneItems))))
            }
            if (!retryWithout) break
        }
    }

    internal fun buildBody(request: ChatRequest, withReasoning: Boolean = true, withCacheKey: Boolean = true): JsonObject = buildJsonObject {
        put("model", config.model.trim())
        put("stream", true)
        put("store", false)
        if (withCacheKey) request.cacheKey?.let { put("prompt_cache_key", it) }
        if (request.system.isNotBlank()) put("instructions", request.system)
        put("input", JsonArray(toInputItems(request.messages)))
        if (request.tools.isNotEmpty()) {
            val functions = request.tools.sortedBy { it.name }.map { tool ->
                buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.inputSchema)
                    // Our schemas mark optional properties as optional, which strict mode doesn't allow.
                    put("strict", false)
                }
            }
            putJsonArray("tools") {
                if (chatGpt) {
                    // The plan route only takes function tools inside a namespace.
                    add(buildJsonObject {
                        put("type", "namespace")
                        put("name", NAMESPACE)
                        put("description", "Locus's tools on the user's phone: files, shell, browser, apps, memory and connectors.")
                        put("tools", JsonArray(functions))
                    })
                } else {
                    functions.forEach { add(it) }
                }
            }
        }
        if (withReasoning) {
            putJsonObject("reasoning") {
                put("effort", config.effort.ifBlank { "medium" })
                put("summary", "auto")
            }
        }
        // Not accepted on the ChatGPT plan route.
        if (!chatGpt) put("max_output_tokens", config.maxOutputTokens)
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
                    if (message.format == format && raw != null) {
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
                                if (chatGpt) put("namespace", NAMESPACE)
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

    /** [response] with [items] as its output when the server left `output` empty. */
    internal fun withStreamedOutput(response: JsonObject, items: List<JsonObject>): JsonObject {
        if ((response["output"] as? JsonArray).orEmpty().isNotEmpty() || items.isEmpty()) return response
        return JsonObject(response + ("output" to buildJsonArray { items.forEach { add(it) } }))
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
            format = format,
            rawPayload = buildJsonArray { output.forEach { add(it) } }.toString(),
            refusalDetail = refusal,
            // `output_tokens` already includes `output_tokens_details.reasoning_tokens`.
            usage = (response["usage"] as? JsonObject)?.let { u ->
                val reasoning = (u["output_tokens_details"] as? JsonObject)?.int("reasoning_tokens") ?: 0
                dev.locus.core.model.TokenUsage(
                    input = u.int("input_tokens") ?: 0,
                    output = maxOf(u.int("output_tokens") ?: 0, reasoning),
                    cacheRead = (u["input_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0,
                    reasoning = reasoning,
                )
            },
        )
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    companion object {
        /** The namespace Locus's function tools sit in on the ChatGPT plan route. */
        const val NAMESPACE = "locus"
    }
}
