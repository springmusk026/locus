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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Chat Completions over raw HTTP + SSE. One adapter covers every server that speaks this
 * protocol (OpenAI, OpenRouter, Groq, Ollama, LM Studio, llama.cpp server, vLLM), so it sends only
 * fields they all accept: no `max_tokens` (renamed by OpenAI, unsupported elsewhere), and
 * `stream_options` and an effort field only to the hosts documented to take them.
 */
class OpenAiChatModel(
    private val http: HttpClient,
    private val config: ProviderConfig,
    private val apiKey: String?,
) : ChatModel {

    override val format = ApiFormat.OPENAI_CHAT

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        var withEffort = effort() != null
        var withCache = cacheHints(request).any
        while (true) {
            var retryWithout = false
            http.preparePost("${config.baseUrl.trimEnd('/')}/chat/completions") {
                if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(LlmJson.encodeToString(JsonObject.serializer(), buildBody(request, withEffort, withCache)))
            }.execute { response ->
                val status = response.status.value
                if ((withEffort || withCache) && (status == 400 || status == 422)) {
                    val body = runCatching { response.bodyAsText() }.getOrDefault("")
                    val lower = body.lowercase()
                    // A model that doesn't reason (or not at this level) may refuse the effort field: ask once more
                    // without it, as the Responses adapter does for its reasoning settings.
                    // Only an error about the field itself: one that merely mentions reasoning (a replayed item, say)
                    // would fail again without it and cost a second request.
                    if (withEffort && refusesReasoningField(body)) {
                        withEffort = false
                        retryWithout = true
                        return@execute
                    }
                    // The cache hints are documented for these hosts, but a server behind them that refuses one
                    // gets the request once more without them: an uncached reply beats a failed one.
                    if (withCache && CACHE_FIELDS.any { it in lower }) {
                        withCache = false
                        retryWithout = true
                        return@execute
                    }
                    throw httpError(config.name, status, body.take(2000), response.retryAfterMs())
                }
                response.throwIfFailed(config.name)
                val acc = StreamAccumulator()
                var done = false
                response.forEachSseEvent { sse ->
                    val data = sse.data.trim()
                    if (data == "[DONE]") done = true
                    if (data.isEmpty() || data == "[DONE]") return@forEachSseEvent
                    acc.handle(sseJson(config.name, data)).forEach { emit(it) }
                }
                // A complete reply ends with `[DONE]`, and its last choice chunk has a finish_reason; a body
                // that ends with neither was cut off (docs/design/stream-timeouts.md, "Streams that end early").
                if (!done && !acc.finished) throw streamCutOff(config.name, "its final event ([DONE] or a finish_reason)")
                emit(ChatEvent.Completed(acc.finish()))
            }
            if (!retryWithout) break
        }
    }

    /**
     * The effort to send and the field it goes in, or null for none: only to the hosts whose docs define
     * one ([dev.locus.core.model.Effort.CHAT_EFFORT_HOSTS]), for a model not known to lack reasoning, when
     * the provider (or the chat, or a side job) set a level; blank leaves the model's default.
     *
     * - OpenAI: `reasoning_effort`, "Currently supported values are `none`, `minimal`, `low`, `medium`,
     *   `high`, `xhigh`, and `max`. … Not all reasoning models support every value" (API reference).
     * - Gemini's OpenAI-compatible endpoint: `reasoning_effort` of `minimal`, `low`, `medium` or `high`
     *   (aistudio.google.com/docs/openai); higher levels are sent as `high`, `none` as `minimal`.
     * - OpenRouter: `reasoning: {effort}` ("max", "xhigh", "high", "medium", "low", "minimal" or "none";
     *   it maps a level a model lacks to the nearest one), per its reasoning tokens guide.
     */
    internal fun effort(): Pair<EffortField, String>? {
        val level = config.effort.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        val host = dev.locus.core.model.Effort.chatEffortHost(config.baseUrl) ?: return null
        if (CapabilityRules.of(format, config.baseUrl, config.model).reasoning == Support.NO) return null
        return when (host) {
            "generativelanguage.googleapis.com" -> EffortField.REASONING_EFFORT to when (level) {
                "none", "minimal" -> "minimal"
                "low" -> "low"
                "medium" -> "medium"
                else -> "high"
            }
            "openrouter.ai" -> EffortField.REASONING_OBJECT to level
            else -> EffortField.REASONING_EFFORT to level
        }
    }

    internal enum class EffortField { REASONING_EFFORT, REASONING_OBJECT }

    /**
     * What a request adds for the provider's prompt cache, only where the host documents it
     * (docs/design/cost.md, "OpenRouter and Chat Completions caching"):
     *
     * - OpenRouter, `anthropic/` models: Claude is cached there only when the request marks it
     *   (openrouter.ai/docs/guides/best-practices/prompt-caching). Two breakpoints, as on Anthropic's own
     *   API: a content-part `cache_control` on the system message ends the frozen prefix (tools, then
     *   system), and the top-level `cache_control` puts the moving one on the last cacheable block, which
     *   "advances it forward as conversations grow". The top-level form rather than a marker on the last
     *   message, because that is often a tool result, whose `content` OpenRouter types as a plain string.
     *   Both keep the default 5-minute lifetime: OpenRouter's sticky routing ends "after 10 minutes of
     *   inactivity", so an hour-long entry would often sit on a provider the next request no longer
     *   reaches, and `cache_write_tokens` isn't split by lifetime, so a 2x write would be priced as 1.25x.
     *   Off for one-off requests ([ChatRequest.cache]), whose write would never be read.
     * - OpenRouter, every model: `session_id` = the chat's [ChatRequest.cacheKey]. With it, stickiness
     *   starts on the first successful request and keeps the chat on one upstream provider, whose cache the
     *   next request needs. No `provider.order`: "Sticky routing is not used when you specify a manual
     *   provider order". OpenAI, DeepSeek, Gemini, Grok and others cache automatically there: no markers.
     * - OpenAI's own API: `prompt_cache_key` (API reference: "Used by OpenAI to cache responses for
     *   similar requests to optimize your cache hit rates").
     * - Any other host (Groq, Ollama, LM Studio, vLLM, Gemini's endpoint): nothing, since an unknown
     *   field can be a 400 there.
     *
     * Every value is fixed per chat (the key comes from [PromptCacheKey]), so the bytes stay the same
     * from turn to turn.
     */
    internal fun cacheHints(request: ChatRequest): CacheHints {
        val host = runCatching { java.net.URI(config.baseUrl.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val key = request.cacheKey?.takeIf { it.isNotBlank() }
        return when (host) {
            "openrouter.ai" -> CacheHints(
                anthropicMarkers = request.cache && config.model.trim().lowercase().startsWith("anthropic/"),
                sessionId = key,
            )
            "api.openai.com" -> CacheHints(promptCacheKey = key)
            else -> CacheHints()
        }
    }

    internal data class CacheHints(
        /** Anthropic-style `cache_control`: on the system message, and at the top level for the conversation. */
        val anthropicMarkers: Boolean = false,
        /** OpenRouter's `session_id`, for sticky routing to one provider. */
        val sessionId: String? = null,
        /** OpenAI's `prompt_cache_key`. */
        val promptCacheKey: String? = null,
    ) {
        val any: Boolean get() = anthropicMarkers || sessionId != null || promptCacheKey != null
    }

    internal fun buildBody(request: ChatRequest, withEffort: Boolean = true, withCache: Boolean = true): JsonObject = buildJsonObject {
        val cache = if (withCache) cacheHints(request) else CacheHints()
        put("model", config.model.trim())
        put("stream", true)
        // Usage in the stream is opt-in on OpenAI; only ask hosts known to accept the option.
        if (USAGE_HOSTS.any { config.baseUrl.contains(it) }) putJsonObject("stream_options") { put("include_usage", true) }
        putJsonArray("messages") {
            if (request.system.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    if (cache.anthropicMarkers) {
                        // The frozen prefix's breakpoint, as a content part (OpenRouter's documented form).
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", request.system)
                                put("cache_control", EPHEMERAL)
                            })
                        }
                    } else {
                        put("content", request.system)
                    }
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
        // The conversation's breakpoint, which the provider moves to the last cacheable block.
        if (cache.anthropicMarkers) put("cache_control", EPHEMERAL)
        cache.sessionId?.let { put("session_id", it) }
        cache.promptCacheKey?.let { put("prompt_cache_key", it) }
        if (withEffort) {
            effort()?.let { (field, value) ->
                when (field) {
                    EffortField.REASONING_EFFORT -> put("reasoning_effort", value)
                    EffortField.REASONING_OBJECT -> putJsonObject("reasoning") { put("effort", value) }
                }
            }
        }
    }

    internal fun toWireMessages(messages: List<Message>): List<JsonObject> = buildList {
        // A tool message takes text only, so a step's tool images (view_image) follow its last tool message
        // as one user message (docs/design/view-image.md). Results that carry none add nothing.
        val pendingImages = mutableListOf<Part.ToolResult>()
        fun flushImages() {
            if (pendingImages.isEmpty()) return
            add(toolImagesMessage(pendingImages))
            pendingImages.clear()
        }
        for (message in messages) {
            if (message.role != Role.TOOL) flushImages()
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
                    if (result.images.isNotEmpty()) pendingImages += result
                }
            }
        }
        flushImages()
    }

    /**
     * The user message that carries tool images: for each result, a short label naming the call and its
     * images, then the images as data URLs. A copy that's gone becomes a note.
     */
    private fun toolImagesMessage(results: List<Part.ToolResult>): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            results.forEach { result ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Images from ${result.toolName} (call ${result.callId}): ${result.images.joinToString("; ") { toolImageLabel(it) }}")
                })
                result.images.forEach { a ->
                    when (val p = payload(a, allowPdf = false)) {
                        is AttachmentPayload.Image -> add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${p.mimeType};base64,${p.base64}")
                                put("detail", "auto")
                            }
                        })
                        is AttachmentPayload.Pdf, is AttachmentPayload.Note -> add(buildJsonObject { put("type", "text"); put("text", note(a)) })
                    }
                }
            }
        }
    }

    private inner class StreamAccumulator {
        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, CallBuilder>()
        /** The builder the last index-less call delta went to, which the next one may continue. */
        private var unindexed: Int? = null
        private var finishReason: String? = null

        /** Whether a choice said why it finished: the reply is complete even if `[DONE]` never came. */
        val finished: Boolean get() = finishReason != null
        private var usage: JsonObject? = null

        fun handle(chunk: JsonObject): List<ChatEvent> {
            (chunk["usage"] as? JsonObject)?.let { usage = it }
            (chunk["error"] as? JsonObject)?.let { error ->
                // OpenRouter sends errors that happen after the stream opened as a chunk with the HTTP code
                // it would have used (https://openrouter.ai/docs/api-reference/errors).
                val code = error.string("code")?.toIntOrNull()
                // Classified as that response would have been, with all it carries: the wait it asked for, the
                // provider's words in the user's language, and the body for Diagnostics.
                if (code != null) {
                    throw httpError(config.name, code, LlmJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("error" to error))))
                }
                val overflow = ContextOverflow.matches(error.string("code") ?: error.string("type"), error.string("message"))
                throw LlmException("${config.name} stream error: ${error.string("message") ?: error}", retryable = false, kind = if (overflow) LlmFailure.CONTEXT_OVERFLOW else LlmFailure.OTHER)
            }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
            choice.string("finish_reason")?.let { finishReason = it }
            val delta = choice["delta"] as? JsonObject ?: return emptyList()
            val events = mutableListOf<ChatEvent>()
            delta.string("content")?.takeIf { it.isNotEmpty() }?.let {
                text.appendCapped(it, StreamLimits.Default.textChars, "a reply's text")
                events += ChatEvent.TextDelta(it)
            }
            // Servers disagree on the reasoning field name; accept either.
            (delta.string("reasoning_content") ?: delta.string("reasoning"))?.takeIf { it.isNotEmpty() }?.let {
                events += ChatEvent.ReasoningDelta(it)
            }
            val deltas = delta["tool_calls"] as? JsonArray
            deltas?.forEachIndexed { position, element ->
                val call = element as? JsonObject ?: return@forEachIndexed
                val function = call["function"] as? JsonObject
                val id = call.string("id")?.takeIf { it.isNotEmpty() }
                val name = function?.string("name")?.takeIf { it.isNotEmpty() }
                val index = call.int("index") ?: unindexedSlot(position, deltas.size, id, name)
                val builder = calls.getOrPut(index) { CallBuilder("call_${calls.size}") }
                // The first id wins; one arriving after the call started under its fallback id is too late to use.
                if (id != null && !builder.idFromServer && builder.id.isEmpty()) {
                    builder.id = id
                    builder.idFromServer = true
                }
                (call["extra_content"] as? JsonObject)?.let { builder.extra = it }
                name?.let {
                    if (builder.name.isEmpty()) {
                        builder.name = it
                        // The id the finished turn will carry, so the started card and the call match.
                        if (builder.id.isEmpty()) builder.id = builder.fallbackId
                        events += ChatEvent.ToolCallStarted(builder.id, it)
                    }
                }
                function?.string("arguments")?.let { builder.arguments.appendCapped(it, StreamLimits.Default.toolArgsChars, "a tool call's arguments") }
            }
            return events
        }

        /**
         * Where a call delta without `index` goes. A chunk that lists several calls lists them by position,
         * as before. But some OpenAI-compatible servers send each complete call in its own chunk and leave
         * `index` out, where the position (0 every time) can't tell two calls apart: a lone delta continues
         * the call before it unless it brings a new id, or a name when that call already has one, and then
         * it starts the next call.
         */
        private fun unindexedSlot(position: Int, count: Int, id: String?, name: String?): Int {
            val previous = unindexed
            val last = previous?.let { calls[it] }
            val slot = when {
                count > 1 -> position
                previous != null && last != null && (id == null || !last.idFromServer || id == last.id) && (name == null || last.name.isEmpty()) -> previous
                else -> (calls.keys.maxOrNull() ?: -1) + 1
            }
            unindexed = slot
            return slot
        }

        fun finish(): AssistantTurn {
            val toolCalls = calls.values.map { call ->
                val args = call.arguments.toString().ifBlank { "{}" }
                val valid = runCatching { LlmJson.parseToJsonElement(args) is JsonObject }.getOrDefault(false)
                Part.ToolCall(
                    id = call.id.ifEmpty { call.fallbackId },
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
                    // No finish_reason (a server that sent `[DONE]` without one) isn't a known clean stop.
                    finishReason == "stop" -> StopReason.END_TURN
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

    /** [fallbackId]: the id the call gets when the server sends none, `call_<n>` in arrival order. */
    private class CallBuilder(val fallbackId: String) {
        var id = ""
        /** Whether [id] came from the server rather than [fallbackId]; only then can a different one mean a new call. */
        var idFromServer = false
        var name = ""
        val arguments = StringBuilder()
        var extra: JsonObject? = null
    }

    internal companion object {
        /** In [Message.rawPayload] for this format: tool call id → the server's `extra_content` for it. */
        const val EXTRAS_KEY = "tool_call_extra_content"

        /** Hosts documented to accept `stream_options.include_usage`; others may reject unknown fields. */
        val USAGE_HOSTS = listOf("api.openai.com", "openrouter.ai", "api.groq.com")

        /** Anthropic's default 5-minute cache lifetime. */
        private val EPHEMERAL = JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("ephemeral")))

        /** Error text that names a cache hint, for the one retry without them. */
        private val CACHE_FIELDS = listOf("cache_control", "session_id", "prompt_cache_key")

        /**
         * OpenAI counts `completion_tokens_details.reasoning_tokens` inside `completion_tokens`, but some
         * compatible servers leave hidden reasoning out of `completion_tokens` while `total_tokens` still
         * has it. Output is therefore the largest of the three readings, so thinking is never dropped.
         *
         * Cache counts come from `prompt_tokens_details`: `cached_tokens` (read) and `cache_write_tokens`
         * (written), which both OpenAI and OpenRouter report; OpenRouter reports writes "only … for models with
         * explicit caching and cache write pricing" (Claude), and normalises Claude's usage into this shape, so a
         * Claude chat through OpenRouter shows its reads and writes on receipts and in Insights. `prompt_tokens`
         * is taken as the whole prompt, cached parts included, as OpenAI defines it; OpenRouter's own example
         * (`cached_tokens` 10,318 of `prompt_tokens` 10,339) reads the same way.
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
                // OpenAI ("The unadjusted number of prompt tokens written to cache", API reference) and OpenRouter.
                cacheWrite = (u["prompt_tokens_details"] as? JsonObject)?.int("cache_write_tokens") ?: 0,
                reasoning = reasoning,
            )
        }

        fun toolCallExtras(raw: String?): Map<String, JsonObject> {
            val obj = raw?.let { runCatching { LlmJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return emptyMap()
            return (obj[EXTRAS_KEY] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }?.toMap().orEmpty()
        }
    }
}
