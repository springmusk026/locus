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
                val requestId = requestIdOf(response.headers)
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
                    if (chatGpt) throw ChatGptErrors.fromHttp(status, body).withIds(requestId)
                    throw httpError(config.name, status, body.take(2000)).withIds(requestId)
                }
                if (chatGpt && response.status.value !in 200..299) {
                    throw ChatGptErrors.fromHttp(response.status.value, runCatching { response.bodyAsText() }.getOrDefault("").take(2000)).withIds(requestId)
                }
                try {
                    response.throwIfFailed(config.name)
                } catch (e: LlmException) {
                    throw e.withIds(requestId)
                }
                var final: JsonObject? = null
                var responseId: String? = null
                var incomplete: JsonObject? = null
                // Finished output items as they stream. Some servers (the ChatGPT plan route among them)
                // send `response.completed` with an empty `output`; the turn is then rebuilt from these.
                val doneItems = mutableListOf<JsonObject>()
                response.forEachSseEvent { sse ->
                    val data = sse.data.trim()
                    if (data.isEmpty() || data == "[DONE]") return@forEachSseEvent
                    val event = LlmJson.parseToJsonElement(data).jsonObject
                    responseIdOf(event)?.let { responseId = it }
                    when (event.string("type")) {
                        "response.output_text.delta" -> event.string("delta")?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.TextDelta(it)) }
                        "response.reasoning_summary_text.delta" -> event.string("delta")?.takeIf { it.isNotEmpty() }?.let { emit(ChatEvent.ReasoningDelta(it)) }
                        "response.output_item.added" -> (event["item"] as? JsonObject)?.takeIf { it.string("type") == "function_call" }?.let { item ->
                            emit(ChatEvent.ToolCallStarted(item.string("call_id").orEmpty(), item.string("name").orEmpty()))
                        }
                        "response.output_item.done" -> (event["item"] as? JsonObject)?.let { doneItems += it }
                        "response.completed" -> final = event["response"] as? JsonObject
                        // Kept as a turn (toTurn reads `incomplete_details`), unless it stopped before saying anything.
                        "response.incomplete" -> {
                            final = event["response"] as? JsonObject
                            incomplete = event
                        }
                        "response.failed" -> throw streamFailure(ResponsesError.ofFailed(event), event, requestId, responseId)
                        "error" -> throw streamFailure(ResponsesError.ofErrorEvent(event), event, requestId, responseId)
                    }
                }
                val response = final ?: throw LlmException("${config.name} ended the stream without a response.", retryable = true, requestId = requestId, responseId = responseId)
                val turn = toTurn(withStreamedOutput(response, doneItems))
                // Stopped short with nothing to show or run (all its output went on reasoning, say): a turn
                // that says nothing would end the task as if it were done, so it fails with the reason.
                incomplete?.let { event ->
                    if (turn.text.isBlank() && turn.toolCalls.isEmpty() && turn.stopReason != StopReason.REFUSAL) {
                        throw incompleteFailure(incompleteReason(response), event, requestId, responseId)
                    }
                }
                emit(ChatEvent.Completed(turn))
            }
            if (!retryWithout) break
        }
    }

    /**
     * A `response.failed` or `error` event as an [LlmException]: classified from wherever its code and
     * message sit ([ResponsesError]), with the scrubbed event kept as [LlmException.rawBody] for
     * Diagnostics and the details sheet, and the request and response ids. When the provider said
     * nothing at all, the words say exactly that, with the request id.
     */
    internal fun streamFailure(error: ResponsesError, event: JsonObject, requestId: String?, responseId: String?): LlmException {
        val raw = scrubbedFailureEvent(event)
        val code = error.codeOrType
        val base = if (chatGpt) {
            ChatGptErrors.fromCode(code, error.message, error.status, error.param, error.resetsAt, requestId)
        } else {
            val retryable = code in RETRYABLE_CODES || error.unexplained
            LlmException(
                when {
                    error.unexplained -> ChatGptErrors.unexplainedText(config.name, requestId)
                    error.message != null -> "${config.name}: ${error.message}"
                    else -> "${config.name}: the response failed with “$code”."
                },
                error.status,
                retryable = retryable,
                kind = when {
                    error.unexplained -> LlmFailure.TRANSIENT
                    else -> responsesFailure(code, error.message)
                },
                errorCode = code,
            )
        }
        return LlmException(
            base.message.orEmpty(), base.httpStatus ?: error.status, base.retryable, kind = base.kind, retryAfterMs = base.retryAfterMs,
            rawBody = raw, errorCode = base.errorCode ?: code, requestId = requestId, responseId = responseId,
        )
    }

    /** A `response.incomplete` with nothing to keep: why it stopped, in words, and the event for Diagnostics. */
    internal fun incompleteFailure(reason: String?, event: JsonObject, requestId: String?, responseId: String?): LlmException {
        val why = when (reason) {
            "max_output_tokens" -> "it reached its output limit before writing anything (its reasoning used it up)"
            "content_filter" -> "its content filter stopped it"
            "max_messages" -> "it reached the most messages it takes in one response"
            null -> "it gave no reason"
            else -> "reason “$reason”"
        }
        return LlmException(
            "${config.name} stopped the response early: $why.",
            retryable = reason == null,
            kind = if (reason == null) LlmFailure.TRANSIENT else LlmFailure.OTHER,
            rawBody = scrubbedFailureEvent(event),
            errorCode = reason?.let { "incomplete:$it" },
            requestId = requestId,
            responseId = responseId,
        )
    }

    internal fun buildBody(request: ChatRequest, withReasoning: Boolean = true, withCacheKey: Boolean = true): JsonObject = buildJsonObject {
        put("model", config.model.trim())
        put("stream", true)
        put("store", false)
        if (withCacheKey) request.cacheKey?.let { put("prompt_cache_key", it) }
        // No cache lifetime is sent (checked 2026-11-01, developers.openai.com/api/docs/guides/prompt-caching):
        // on models before GPT-5.6, `prompt_cache_retention` already defaults to "24h" for organisations
        // without Zero Data Retention (a ZDR org's "in_memory" is its own policy, which Locus shouldn't
        // override); GPT-5.6 and later replace it with `prompt_cache_options.ttl`, whose default is a
        // 30-minute minimum. Setting either would add nothing over the defaults.
        if (request.system.isNotBlank()) put("instructions", request.system)
        // Normalised: a stored history that lost a call's output (or kept an item the API can't resolve)
        // is sent valid; a sound one goes as it is (ResponsesInput).
        put("input", JsonArray(ResponsesInput.normalize(toInputItems(request.messages))))
        if (request.tools.isNotEmpty()) {
            val search = toolSearchFor(request)
            val loaded = if (search) request.tools.filter { it.name !in DISCOVERY_TOOL_NAMES } else request.tools
            val functions = loaded.sortedBy { it.name }.map { tool -> function(tool, deferred = false) }
            putJsonArray("tools") {
                if (search) {
                    functions.forEach { add(it) }
                    // Deferred functions go in namespaces: the model sees only each namespace's name and
                    // description until tool_search loads what it needs (tool search guide, "Use namespaces").
                    val deferred = (request.tools.filter { it.name in DISCOVERY_TOOL_NAMES } + request.deferredTools.filter { PORTABLE_TOOL_NAME.matches(it.name) })
                        .distinctBy { it.name }.filter { d -> loaded.none { it.name == d.name } }
                    deferred.groupBy { namespaceOf(it.name) }.toSortedMap().forEach { (space, tools) ->
                        add(buildJsonObject {
                            put("type", "namespace")
                            put("name", space)
                            put("description", namespaceDescription(space, tools.map { it.name }))
                            put("tools", JsonArray(tools.sortedBy { it.name }.map { function(it, deferred = true) }))
                        })
                    }
                    add(buildJsonObject { put("type", "tool_search") })
                } else if (chatGpt) {
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
            // Reasoning items come back with encrypted_content by default now, and the API still takes this
            // value (reasoning guide, "stateless use", read 2026-11-03); Codex sends it on the ChatGPT route
            // (codex-rs/core/src/client.rs). Asked for explicitly, so replayed reasoning always resolves.
            putJsonArray("include") { add("reasoning.encrypted_content") }
        }
        // Not accepted on the ChatGPT plan route.
        if (!chatGpt) put("max_output_tokens", config.maxOutputTokens)
    }

    private fun function(tool: dev.locus.core.model.ToolSpec, deferred: Boolean) = buildJsonObject {
        put("type", "function")
        put("name", tool.name)
        put("description", tool.description)
        if (deferred) put("defer_loading", true)
        put("parameters", tool.inputSchema)
        // Our schemas mark optional properties as optional, which strict mode doesn't allow.
        put("strict", false)
    }

    /**
     * Whether this request uses hosted `tool_search`: the chat has deferred tools and the model is
     * GPT-5.4 or later ("Only gpt-5.4 and later models support tool_search" in the Responses API, tool
     * search guide, 2026-11-01). Not on the ChatGPT plan route, whose preview rules don't mention it.
     */
    internal fun toolSearchFor(request: ChatRequest): Boolean =
        !chatGpt && request.deferredTools.isNotEmpty() && supportsToolSearch(config.model)

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

        /** GPT-5.4 and later (gpt-5.4, gpt-5.5-mini, gpt-6, …). */
        internal fun supportsToolSearch(model: String): Boolean {
            val m = Regex("^gpt-(\\d+)(?:\\.(\\d+))?").find(model.trim().lowercase()) ?: return false
            val major = m.groupValues[1].toInt()
            val minor = m.groupValues[2].toIntOrNull() ?: 0
            return major > 5 || (major == 5 && minor >= 4)
        }

        /** A deferred tool's namespace: its MCP server, or its family (`github_issues` → `github`). */
        internal fun namespaceOf(name: String): String {
            dev.locus.core.model.DiscoveryTools.parseMcpName(name)?.let { (connector, _) ->
                return "mcp_" + connector.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(50)
            }
            if (name in DISCOVERY_TOOL_NAMES) return "discovery"
            // The phone integrations share the phone apps' namespace, whose one-line description stays as it was.
            if (dev.locus.core.model.DeviceIntegrations.isIntegration(name)) return "device"
            return when (val family = name.substringBefore('_')) {
                "sftp" -> "ssh"
                "notify", "weather", "wikipedia" -> "quick"
                else -> family
            }
        }

        /**
         * One short line per namespace: the model sees only this until tool_search loads the tools, so it's
         * kept to what the family is (listing every name cost more than the find_tools pair it replaces).
         */
        internal fun namespaceDescription(space: String, tools: List<String>): String = when {
            space.startsWith("mcp_") -> "Tools of the user's MCP server “${space.removePrefix("mcp_")}” (${tools.size})."
            space == "discovery" -> "Locus's own tool finder; tool_search already does its job."
            space == "device" -> "The phone's own apps: calendar, alarms, timers, SMS and email drafts, calls, directions, sharing."
            space == "quick" -> "Weather, Wikipedia and push notifications."
            else -> "Locus's ${space.replaceFirstChar { it.uppercase() }} tools (${tools.size})."
        }
    }
}

/** Responses error codes worth retrying as they are (codex `responses_error.rs`). */
private val RETRYABLE_CODES = setOf("server_error", "rate_limit_exceeded", "slow_down", "server_is_overloaded")

/** The [LlmFailure] of a Responses-stream error code (`response.failed` or an `error` event). */
internal fun responsesFailure(code: String?, message: String? = null): LlmFailure = when {
    ContextOverflow.matches(code, message) -> LlmFailure.CONTEXT_OVERFLOW
    else -> responsesCode(code)
}

private fun responsesCode(code: String?): LlmFailure = when (code) {
    "rate_limit_exceeded", "slow_down" -> LlmFailure.RATE_LIMIT
    "server_error" -> LlmFailure.TRANSIENT
    "server_is_overloaded" -> LlmFailure.OVERLOADED
    "insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded" -> LlmFailure.QUOTA
    else -> LlmFailure.OTHER
}
