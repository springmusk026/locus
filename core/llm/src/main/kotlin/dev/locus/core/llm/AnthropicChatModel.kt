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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Messages API over raw HTTP + SSE.
 *
 * Request shape follows the `2026-10-21` API version: no sampling parameters, adaptive thinking,
 * `output_config.effort`, and `eager_input_streaming` on every client tool. Assistant turns are
 * stored as the exact content blocks received (thinking blocks and signatures included) and
 * replayed unchanged, which the API requires for reasoning continuity.
 */
class AnthropicChatModel(
    private val http: HttpClient,
    private val config: ProviderConfig,
    private val apiKey: String,
) : ChatModel {

    override val format = ApiFormat.ANTHROPIC_MESSAGES

    private val model = config.model.trim()
    private val firstParty = config.baseUrl.trimEnd('/').equals(DEFAULT_BASE_URL, ignoreCase = true)

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        // Capabilities come from the Models API when it answers (once per model per process).
        if (firstParty) AnthropicCapabilities.ensure(http, config.baseUrl, apiKey, model)
        val betas = buildList {
            if (supportsProgressUpdates()) add(BETA_PROGRESS_UPDATES)
            if (firstParty && supportsDefaultFallback()) add(BETA_SERVER_FALLBACK)
            if (supportsCompaction()) add(BETA_COMPACTION)
            if (taskBudgetFor(request) != null) add(BETA_TASK_BUDGETS)
        }
        val body = buildBody(request)
        http.preparePost("${config.baseUrl.trimEnd('/')}/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", API_VERSION)
            if (betas.isNotEmpty()) header("anthropic-beta", betas.joinToString(","))
            contentType(ContentType.Application.Json)
            setBody(LlmJson.encodeToString(JsonObject.serializer(), body))
        }.execute { response ->
            response.throwIfFailed("Anthropic")
            val acc = StreamAccumulator()
            response.forEachSseEvent { sse ->
                if (sse.data.isBlank()) return@forEachSseEvent
                val event = LlmJson.parseToJsonElement(sse.data).jsonObject
                acc.handle(event)?.let { emit(it) }
            }
            emit(ChatEvent.Completed(acc.finish()))
        }
    }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", config.maxOutputTokens)
        put("stream", true)
        // Prompt caching (see the companion's CACHE notes): one breakpoint ends the frozen prefix
        // (tools + system) and is kept for an hour; one ends the conversation so far, moves forward
        // every turn and keeps the default 5 minutes. The longer one comes first, as the API requires.
        val cache = request.cache
        val hasSystem = request.system.isNotBlank()
        if (hasSystem) {
            if (cache) {
                putJsonArray("system") { add(withCacheBreakpoint(textBlock(request.system), prefixCacheControl)) }
            } else {
                put("system", request.system)
            }
        }
        val messages = toWireMessages(request.messages)
        put("messages", if (cache) withCacheBreakpointOnLastMessage(messages) else messages)
        val search = toolSearchFor(request)
        if (request.tools.isNotEmpty()) {
            // With server-side tool search, find_tools and call_tool are deferred with the rest: the search
            // tool replaces them, but a turn that used them (on another model) still names declared tools.
            val (deferredHere, loaded) = if (search) request.tools.partition { it.name in DISCOVERY_TOOL_NAMES } else emptyList<dev.locus.core.model.ToolSpec>() to request.tools
            val sorted = loaded.sortedBy { it.name }
            putJsonArray("tools") {
                if (search) add(buildJsonObject { put("type", TOOL_SEARCH_TYPE); put("name", TOOL_SEARCH_NAME) })
                // Sorted by name so the tool block (part of the cached, signed prefix) is stable.
                sorted.forEachIndexed { i, tool ->
                    val definition = toolDefinition(tool, deferred = false)
                    // Without a system prompt, the frozen prefix ends at the last tool instead. Never on a
                    // deferred tool, which can't carry cache_control.
                    add(if (cache && !hasSystem && i == sorted.lastIndex) withCacheBreakpoint(definition, prefixCacheControl) else definition)
                }
                if (search) {
                    // Every deferred tool's full definition goes in every request; the API keeps them out of
                    // the cached prefix and expands only those a search returns (tool search tool docs).
                    val deferred = (deferredHere + request.deferredTools.filter { PORTABLE_TOOL_NAME.matches(it.name) })
                        .distinctBy { it.name }.filter { d -> loaded.none { it.name == d.name } }
                    val names = deferred.map { it.name }.toSet() + loaded.map { it.name }
                    // A tool a past search returned must still be defined (else a 400), even if it's gone since
                    // (an MCP server removed): it gets a stub that says so.
                    val gone = referencedTools(request.messages).filter { it !in names }
                    (deferred + gone.map { stubTool(it) }).sortedBy { it.name }.forEach { add(toolDefinition(it, deferred = true)) }
                }
            }
        }
        if (supportsProgressUpdates()) {
            putJsonObject("thinking") {
                put("type", "adaptive")
                put("display", "updates")
            }
        }
        val effort = config.effort.takeIf { supportsEffort() && it.isNotBlank() }
        val taskBudget = taskBudgetFor(request)
        if (effort != null || taskBudget != null) {
            putJsonObject("output_config") {
                effort?.let { put("effort", it) }
                // Only `total`: the server keeps the countdown across the requests of one turn, and a
                // `remaining` that changes per request would miss the prompt cache.
                taskBudget?.let { total -> putJsonObject("task_budget") { put("type", "tokens"); put("total", total) } }
            }
        }
        if (firstParty && supportsDefaultFallback()) put("fallbacks", "default")
        // Server-side compaction: near the context limit the API summarises earlier turns into a
        // `compaction` block, which comes back in rawPayload and is replayed like any other block.
        if (supportsCompaction()) {
            putJsonObject("context_management") {
                putJsonArray("edits") { add(buildJsonObject { put("type", "compact_20260112") }) }
            }
        }
    }

    private fun toolDefinition(tool: dev.locus.core.model.ToolSpec, deferred: Boolean) = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("input_schema", tool.inputSchema)
        put("eager_input_streaming", true)
        if (deferred) put("defer_loading", true)
    }

    /**
     * Whether this request uses Anthropic's server-side tool search: the chat has deferred tools, the
     * model is one the tool search docs list, and it's Anthropic's own API (gateways may not know the
     * tool type). Fixed per chat and model, so the request shape never changes between turns.
     */
    internal fun toolSearchFor(request: ChatRequest): Boolean =
        firstParty && request.deferredTools.isNotEmpty() && TOOL_SEARCH_MODEL_PREFIXES.any { model.startsWith(it) }

    /** Tools that past searches in [messages] returned (`tool_reference` blocks in replayed turns). */
    private fun referencedTools(messages: List<Message>): Set<String> = buildSet {
        messages.forEach { m ->
            val raw = m.rawPayload?.takeIf { m.role == Role.ASSISTANT && m.format == ApiFormat.ANTHROPIC_MESSAGES && "tool_reference" in it } ?: return@forEach
            val blocks = runCatching { LlmJson.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return@forEach
            blocks.forEach { b ->
                val content = ((b as? JsonObject)?.takeIf { it.string("type") == "tool_search_tool_result" }?.get("content") as? JsonObject) ?: return@forEach
                (content["tool_references"] as? JsonArray)?.forEach { r -> (r as? JsonObject)?.string("tool_name")?.let(::add) }
            }
        }
    }

    private fun stubTool(name: String) = dev.locus.core.model.ToolSpec(
        name, "No longer available (its MCP server was removed or changed). Don't call it.",
        JsonObject(mapOf("type" to JsonPrimitive("object"))),
    )

    internal fun toWireMessages(messages: List<Message>): JsonArray {
        val wire = mutableListOf<Pair<String, MutableList<JsonElement>>>()
        fun append(role: String, blocks: List<JsonElement>) {
            if (blocks.isEmpty()) return
            val last = wire.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else wire += role to blocks.toMutableList()
        }
        for (message in messages) {
            when (message.role) {
                Role.USER -> append("user", message.attachments.map(::attachmentBlock) + message.parts.filterIsInstance<Part.Text>().map { textBlock(it.text) })
                Role.TOOL -> append("user", message.toolResults.map { result ->
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", result.callId)
                        put("content", result.content)
                        if (result.isError) put("is_error", true)
                    }
                })
                Role.ASSISTANT -> {
                    val raw = message.rawPayload
                    if (message.format == ApiFormat.ANTHROPIC_MESSAGES && raw != null) {
                        append("assistant", LlmJson.parseToJsonElement(raw).jsonArray)
                    } else {
                        append("assistant", buildList {
                            if (message.text.isNotEmpty()) add(textBlock(message.text))
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", parseObjectOrEmpty(call.argumentsJson))
                                })
                            }
                        })
                    }
                }
            }
        }
        return buildJsonArray {
            wire.forEach { (role, blocks) ->
                add(buildJsonObject {
                    put("role", role)
                    put("content", JsonArray(blocks))
                })
            }
        }
    }

    /**
     * Marks the last block of the last message that can carry `cache_control`, walking back over
     * blocks the API won't cache (thinking, empty text, server-side markers). Earlier messages lose
     * their marker on the next turn; the API still finds the entry they wrote within its lookback.
     */
    internal fun withCacheBreakpointOnLastMessage(messages: JsonArray): JsonArray {
        val last = messages.lastOrNull()?.jsonObject ?: return messages
        val content = last["content"] as? JsonArray ?: return messages
        val index = content.indexOfLast { block -> (block as? JsonObject)?.let(::isCacheable) == true }
        if (index < 0) return messages
        val marked = JsonArray(content.mapIndexed { i, block -> if (i == index) withCacheBreakpoint(block.jsonObject) else block })
        return JsonArray(messages.dropLast(1) + JsonObject(last + ("content" to marked)))
    }

    private fun isCacheable(block: JsonObject): Boolean = when (block.string("type")) {
        "text" -> !block.string("text").isNullOrEmpty()
        "image", "document", "tool_use", "tool_result" -> true
        else -> false
    }

    private fun withCacheBreakpoint(block: JsonObject, control: JsonObject = EPHEMERAL) = JsonObject(block + ("cache_control" to control))

    /**
     * The frozen prefix's breakpoint: one hour on Anthropic's own API, the default 5 minutes
     * elsewhere. A gateway that speaks this format may not know the `ttl` field, and an unknown field
     * can be a 400, so only the first-party API gets it.
     */
    private val prefixCacheControl: JsonObject get() = if (firstParty) EPHEMERAL_1H else EPHEMERAL

    // Images and PDFs go before the text, as the API docs recommend.
    private fun attachmentBlock(a: Part.Attachment): JsonObject = when (val p = payload(a, allowPdf = true)) {
        is AttachmentPayload.Image -> buildJsonObject {
            put("type", "image")
            putJsonObject("source") { put("type", "base64"); put("media_type", p.mimeType); put("data", p.base64) }
        }
        is AttachmentPayload.Pdf -> buildJsonObject {
            put("type", "document")
            putJsonObject("source") { put("type", "base64"); put("media_type", "application/pdf"); put("data", p.base64) }
        }
        is AttachmentPayload.Note -> textBlock(p.text)
    }

    private fun textBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    // Capability tables from the Claude API docs (bundled claude-api skill, cached 2026-10-06).
    // Unknown model IDs get the conservative request: no betas, no effort.
    private fun supportsProgressUpdates() = PROGRESS_UPDATE_MODELS.any { model == it }
    private fun supportsDefaultFallback() = DEFAULT_FALLBACK_MODELS.any { model == it }
    private fun supportsEffort() = EFFORT_MODEL_PREFIXES.any { model.startsWith(it) }
    private fun supportsCompaction() = AnthropicCapabilities.compaction(model)

    /** The task budget to send, or null: first-party API, a model the docs list, and at least the documented minimum. */
    internal fun taskBudgetFor(request: ChatRequest): Long? =
        request.taskBudget?.takeIf { firstParty && it >= MIN_TASK_BUDGET && TASK_BUDGET_MODELS.any { m -> model == m } }

    private inner class StreamAccumulator {
        private val blocks = sortedMapOf<Int, BlockBuilder>()
        private var stopReason: String? = null
        private var stopDetails: JsonObject? = null
        private var usage: JsonObject? = null

        /** Anthropic reports input in message_start and the running output count in message_delta. */
        private fun mergeUsage(u: JsonObject?) {
            if (u == null) return
            usage = JsonObject((usage ?: JsonObject(emptyMap())) + u.filterValues { it !is kotlinx.serialization.json.JsonNull })
        }

        fun handle(event: JsonObject): ChatEvent? {
            when (event.string("type")) {
                "message_start" -> mergeUsage((event["message"] as? JsonObject)?.get("usage") as? JsonObject)
                "content_block_start" -> {
                    val index = event.int("index") ?: return null
                    val block = event["content_block"]?.jsonObject ?: return null
                    val builder = BlockBuilder(block)
                    blocks[index] = builder
                    if (builder.type == "tool_use") {
                        return ChatEvent.ToolCallStarted(block.string("id").orEmpty(), block.string("name").orEmpty())
                    }
                }
                "content_block_delta" -> {
                    val builder = blocks[event.int("index") ?: return null] ?: return null
                    val delta = event["delta"]?.jsonObject ?: return null
                    when (delta.string("type")) {
                        "text_delta" -> delta.string("text")?.let {
                            builder.text.append(it)
                            return ChatEvent.TextDelta(it)
                        }
                        "thinking_delta" -> delta.string("thinking")?.let {
                            builder.thinking.append(it)
                            if (it.isNotEmpty()) return ChatEvent.ReasoningDelta(it)
                        }
                        "signature_delta" -> delta.string("signature")?.let { builder.signature.append(it) }
                        "input_json_delta" -> delta.string("partial_json")?.let { builder.inputJson.append(it) }
                        // Other block types (compaction, …): keep every streamed string field so the
                        // block is replayed complete.
                        else -> delta.forEach { (k, v) -> if (k != "type") (v as? JsonPrimitive)?.takeIf { it.isString }?.let { builder.extra.getOrPut(k) { StringBuilder() }.append(it.content) } }
                    }
                }
                "message_delta" -> {
                    val delta = event["delta"]?.jsonObject
                    delta?.string("stop_reason")?.let { stopReason = it }
                    (delta?.get("stop_details") as? JsonObject)?.let { stopDetails = it }
                    mergeUsage(event["usage"] as? JsonObject)
                }
                "error" -> {
                    val error = event["error"]?.jsonObject
                    val type = error?.string("type").orEmpty()
                    throw LlmException(
                        "Anthropic stream error ($type): ${error?.string("message").orEmpty()}",
                        retryable = type in RETRYABLE_ERROR_TYPES,
                        kind = when {
                            ContextOverflow.matches(type, error?.string("message")) -> LlmFailure.CONTEXT_OVERFLOW
                            else -> when (type) {
                            "overloaded_error" -> LlmFailure.OVERLOADED
                            "rate_limit_error" -> LlmFailure.RATE_LIMIT
                            "api_error" -> LlmFailure.TRANSIENT
                            "billing_error" -> LlmFailure.QUOTA
                            "authentication_error", "permission_error" -> LlmFailure.AUTH
                            else -> LlmFailure.OTHER
                            }
                        },
                    )
                }
            }
            return null
        }

        fun finish(): AssistantTurn {
            var finalBlocks = blocks.values.map { it.build() }
            // After a mid-stream server-side fallback, blocks before the last `fallback` marker came
            // from the declined attempt: only their text is kept when echoing the turn back.
            val boundary = finalBlocks.indexOfLast { it.string("type") == "fallback" }
            if (boundary >= 0) {
                finalBlocks = finalBlocks.filterIndexed { i, block ->
                    i >= boundary || block.string("type") == "text"
                }
            }
            val toolCalls = finalBlocks.filter { it.string("type") == "tool_use" }.map { block ->
                Part.ToolCall(
                    id = block.string("id").orEmpty(),
                    name = block.string("name").orEmpty(),
                    argumentsJson = block["input"]?.toString() ?: "{}",
                )
            }
            val text = finalBlocks.filter { it.string("type") == "text" }.joinToString("") { it.string("text").orEmpty() }
            return AssistantTurn(
                text = text,
                toolCalls = toolCalls,
                stopReason = when (stopReason) {
                    "end_turn", "stop_sequence" -> StopReason.END_TURN
                    "tool_use" -> StopReason.TOOL_USE
                    "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
                    "refusal" -> StopReason.REFUSAL
                    "pause_turn" -> StopReason.PAUSE_TURN
                    else -> StopReason.OTHER
                },
                format = ApiFormat.ANTHROPIC_MESSAGES,
                rawPayload = JsonArray(finalBlocks).toString(),
                // Anthropic bills thinking as output: `output_tokens` already includes it (no separate count).
                usage = usage?.let { u ->
                    val fresh = u.int("input_tokens") ?: 0
                    val read = u.int("cache_read_input_tokens") ?: 0
                    val write = u.int("cache_creation_input_tokens") ?: 0
                    // The 1-hour part of the writes (`cache_creation` splits the total by lifetime), billed at 2x input.
                    val write1h = (u["cache_creation"] as? JsonObject)?.int("ephemeral_1h_input_tokens") ?: 0
                    dev.locus.core.model.TokenUsage(fresh + read + write, u.int("output_tokens") ?: 0, read, write, cacheWrite1h = write1h.coerceIn(0, write))
                },
                refusalDetail = stopDetails?.let { details ->
                    listOfNotNull(details.string("category"), details.string("explanation")).joinToString(": ").ifEmpty { null }
                },
            )
        }
    }

    private class BlockBuilder(private val start: JsonObject) {
        val type = start.string("type")
        val text = StringBuilder()
        val thinking = StringBuilder()
        val signature = StringBuilder()
        val inputJson = StringBuilder()
        val extra = linkedMapOf<String, StringBuilder>()

        fun build(): JsonObject = when (type) {
            "text" -> JsonObject(start + ("text" to JsonPrimitive(start.string("text").orEmpty() + text)))
            "thinking" -> JsonObject(
                start + mapOf(
                    "thinking" to JsonPrimitive(start.string("thinking").orEmpty() + thinking),
                    "signature" to JsonPrimitive(start.string("signature").orEmpty() + signature),
                ),
            )
            // Eager input streaming means the API no longer validates tool input; an unparseable
            // input is kept as {} here and reported back to the model as INVALID_JSON by the agent.
            // The tool search's own call streams its query the same way; it's replayed, never run here.
            "tool_use", "server_tool_use" -> JsonObject(start + ("input" to parseInput()))
            else -> if (extra.isEmpty()) start else JsonObject(start + extra.mapValues { (k, v) -> JsonPrimitive(start.string(k).orEmpty() + v) })
        }

        private fun parseInput(): JsonElement {
            val raw = inputJson.toString()
            if (raw.isBlank()) return start["input"] ?: JsonObject(emptyMap())
            return runCatching { LlmJson.parseToJsonElement(raw) as JsonObject }.getOrElse {
                JsonObject(mapOf(INVALID_INPUT_KEY to JsonPrimitive(raw)))
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"

        /** Whether this model compacts long chats itself (server-side), so Locus needn't. */
        fun compactsOnServer(model: String) = AnthropicCapabilities.compaction(model)
        const val API_VERSION = "2026-10-21"

        /**
         * Prompt caching (https://platform.claude.com/docs/en/build-with-claude/prompt-caching):
         * explicit block-level breakpoints rather than the top-level automatic `cache_control`,
         * because the legacy Bedrock integration rejects the top-level field with a 400 and
         * Anthropic-compatible gateways are more likely to know the long-standing block form. Two of
         * the four allowed breakpoints are used.
         *
         * Lifetimes (read 2026-11-01): the frozen prefix (tools + system) is written with `ttl: "1h"`,
         * the moving message breakpoint with the default 5 minutes. On a phone the gaps between
         * requests often pass 5 minutes (the user reads, approves a call, answers ask_user), and each
         * such gap rewrote the whole prefix. A 1-hour write costs 2x base input instead of 1.25x; a
         * read costs 0.1x (0.05x on Opus 5.5 and Sonnet 5.5, 0.025x on Fable 5.1) and refreshes
         * either lifetime for free. "Cache entries with longer TTL must appear before shorter TTLs":
         * the prefix always comes before the messages, so the order holds. No beta header is needed,
         * and the pricing table lists a 1-hour write price for every model. Prompts under the
         * per-model minimum (512 to 4,096 tokens) are simply not cached; the API returns no error.
         */
        private val EPHEMERAL = JsonObject(mapOf("type" to JsonPrimitive("ephemeral")))
        private val EPHEMERAL_1H = JsonObject(mapOf("type" to JsonPrimitive("ephemeral"), "ttl" to JsonPrimitive("1h")))
        private const val BETA_PROGRESS_UPDATES = "thinking-display-updates-2026-08-18"
        private const val BETA_SERVER_FALLBACK = "server-side-fallback-2026-07-01"
        private const val BETA_COMPACTION = "compact-2026-01-12"

        /**
         * Task budgets (https://platform.claude.com/docs/en/build-with-claude/task-budgets, beta): an
         * advisory token budget for one agentic turn that the model paces itself against. The API
         * rejects a `total` under 20,000 with a 400.
         */
        private const val BETA_TASK_BUDGETS = "task-budgets-2026-03-13"
        const val MIN_TASK_BUDGET = 20_000L

        /**
         * Models the task-budgets page lists (read 2026-10-31), less Claude Sonnet 5.5 and the Mythos
         * models. The Sonnet 5.5 migration guide says not to use a task budget on interactive sessions
         * (the countdown after each tool result can make it read the user's words as an injection), and
         * the Mythos models are limited to Project Glasswing. Unknown ids get none.
         */
        private val TASK_BUDGET_MODELS = listOf(
            "claude-fable-5-1", "claude-fable-5", "claude-opus-5-5", "claude-opus-5", "claude-haiku-5-5", "claude-opus-4-8", "claude-opus-4-7",
        )

        private val PROGRESS_UPDATE_MODELS = listOf("claude-fable-5-1", "claude-mythos-5-1", "claude-fable-5", "claude-opus-5-5", "claude-sonnet-5-5")
        private val DEFAULT_FALLBACK_MODELS = listOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
        private val EFFORT_MODEL_PREFIXES = listOf(
            "claude-fable-5", "claude-mythos-5", "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
            "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-5",
        )
        private val RETRYABLE_ERROR_TYPES = setOf("overloaded_error", "api_error", "rate_limit_error")

        /**
         * Server-side tool search (https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool,
         * read 2026-11-01): stable, no beta header. BM25 because Locus's own find_tools is BM25 too and the
         * model writes natural-language queries for it.
         */
        const val TOOL_SEARCH_TYPE = "tool_search_tool_bm25_20251119"
        const val TOOL_SEARCH_NAME = "tool_search_tool_bm25"

        /** The tool search docs' model table (2026-11-01); Opus 4.1 and earlier don't support it. */
        private val TOOL_SEARCH_MODEL_PREFIXES = listOf(
            "claude-fable-5", "claude-mythos-5", "claude-mythos-preview", "claude-opus-5", "claude-sonnet-5", "claude-haiku-5",
            "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6", "claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5",
        )    }
}

/** Set on a tool call's arguments when the model's streamed input was not valid JSON. */
const val INVALID_INPUT_KEY = "__locus_invalid_json"

internal fun parseObjectOrEmpty(json: String): JsonObject =
    runCatching { LlmJson.parseToJsonElement(json) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
