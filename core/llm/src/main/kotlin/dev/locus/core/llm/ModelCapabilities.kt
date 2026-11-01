package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import java.net.URI

/** Whether a model has an ability: known yes, known no, or nobody said. Unknown is never "no". */
enum class Support {
    YES,
    NO,
    UNKNOWN,
    ;

    /** This answer when it's known, else [other]'s. */
    fun or(other: Support): Support = if (this == UNKNOWN) other else this

    companion object {
        fun of(value: Boolean?): Support = when (value) {
            true -> YES
            false -> NO
            null -> UNKNOWN
        }
    }
}

/**
 * How a model's provider caches prompts, which decides what a switch to it costs.
 *
 * - [EXPLICIT]: the request marks what to cache (Anthropic `cache_control`, also through OpenRouter).
 * - [AUTOMATIC]: the provider caches repeated prefixes by itself (OpenAI, Gemini implicit caching, DeepSeek).
 * - [NONE]: no provider cache (on-device models keep their own KV cache instead).
 */
enum class CacheStyle { EXPLICIT, AUTOMATIC, NONE, UNKNOWN }

/**
 * What one model can do, each ability tri-state ([Support]). [contextWindow] is the input window in
 * tokens, null when unknown. See docs/design/model-capabilities.md.
 */
data class ModelCapabilities(
    val tools: Support = Support.UNKNOWN,
    val vision: Support = Support.UNKNOWN,
    val reasoning: Support = Support.UNKNOWN,
    val structuredOutput: Support = Support.UNKNOWN,
    val contextWindow: Int? = null,
    val caching: CacheStyle = CacheStyle.UNKNOWN,
) {
    /** What this knows, with the gaps filled from [fallback]: the earlier source wins field by field. */
    infix fun orElse(fallback: ModelCapabilities) = ModelCapabilities(
        tools = tools.or(fallback.tools),
        vision = vision.or(fallback.vision),
        reasoning = reasoning.or(fallback.reasoning),
        structuredOutput = structuredOutput.or(fallback.structuredOutput),
        contextWindow = contextWindow ?: fallback.contextWindow,
        caching = if (caching == CacheStyle.UNKNOWN) fallback.caching else caching,
    )

    /** How well this model serves [needs]: what it's known to lack, and what nobody could confirm. */
    fun fit(needs: ModelRequirements): Fit {
        val lacks = mutableListOf<Need>()
        val unconfirmed = mutableListOf<Need>()
        fun check(needed: Boolean, support: Support, need: Need) {
            if (!needed) return
            when (support) {
                Support.NO -> lacks += need
                Support.UNKNOWN -> unconfirmed += need
                Support.YES -> Unit
            }
        }
        check(needs.tools, tools, Need.TOOLS)
        check(needs.images, vision, Need.VISION)
        check(needs.reasoning, reasoning, Need.REASONING)
        check(needs.structuredOutput, structuredOutput, Need.STRUCTURED_OUTPUT)
        val wanted = needs.contextTokens + ModelRequirements.headroom(contextWindow)
        when {
            needs.contextTokens <= 0 -> Unit
            contextWindow != null -> if (contextWindow < wanted) lacks += Need.CONTEXT
            // Nearly every current model takes 32K; below that an unknown window isn't worth a word.
            wanted > ModelRequirements.COMMON_WINDOW -> unconfirmed += Need.CONTEXT
        }
        return Fit(lacks, unconfirmed, contextWindow, wanted)
    }

    companion object {
        val UNKNOWN = ModelCapabilities()
    }
}

/** One thing a chat needs from a model. Soft needs ([hard] false) degrade the reply when missing but never fail it. */
enum class Need(val label: String, val lacking: String, val hard: Boolean) {
    TOOLS("tool calling", "no tool calling", hard = true),
    VISION("vision", "no vision", hard = true),
    CONTEXT("context window", "context too small", hard = true),
    REASONING("reasoning", "doesn't reason", hard = false),
    STRUCTURED_OUTPUT("structured output", "no structured output", hard = true),
}

/**
 * How a model fits a chat's [ModelRequirements]: needs it's known to lack ([lacks]) and needs no
 * source could confirm ([unconfirmed]). Only a hard lack makes it unusable; unknown never does.
 */
data class Fit(val lacks: List<Need>, val unconfirmed: List<Need>, val window: Int? = null, val wanted: Int = 0) {
    val usable: Boolean get() = lacks.none { it.hard }

    /** The hard lacks, as the picker and notes say them: "no vision", "context too small (128K, needs ~150K)". */
    val reason: String? get() = lacks.filter { it.hard }.takeIf { it.isNotEmpty() }?.joinToString(", ") { describe(it) }

    /** "vision, tool calling": what couldn't be confirmed. */
    val unconfirmedLabel: String? get() = unconfirmed.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label }

    private fun describe(need: Need): String =
        if (need == Need.CONTEXT && window != null) "${need.lacking} (${tokensLabel(window)}, needs ~${tokensLabel(wanted)})" else need.lacking

    companion object {
        val OK = Fit(emptyList(), emptyList())

        /** "200K", "1M". */
        fun tokensLabel(tokens: Int): String = when {
            tokens >= 1_000_000 -> {
                val m = tokens / 1_000_000.0
                if (m % 1.0 < 0.05) "${m.toInt()}M" else "%.1fM".format(java.util.Locale.ROOT, m)
            }
            tokens >= 1_000 -> "${(tokens + 500) / 1_000}K"
            else -> tokens.toString()
        }
    }
}

/**
 * What a chat's next request needs from whichever model serves it.
 *
 * [reasoning] and [structuredOutput] are asked for by the caller: a request doesn't say whether the
 * chat chose a thinking effort, and chats don't constrain output today (the field is there for
 * helpers that will).
 */
data class ModelRequirements(
    val tools: Boolean = false,
    val images: Boolean = false,
    val reasoning: Boolean = false,
    val structuredOutput: Boolean = false,
    /** The estimated size of the prompt in tokens; 0 when not estimated. */
    val contextTokens: Int = 0,
) {
    companion object {
        /** Room left for the reply on top of the prompt when judging whether a window is big enough. */
        const val HEADROOM_TOKENS = 8_192

        /**
         * The margin for a [window]: [HEADROOM_TOKENS], but at most an eighth of a small window (the
         * on-device model's 16K would otherwise lose half its room to the margin).
         */
        fun headroom(window: Int?): Int = if (window == null) HEADROOM_TOKENS else minOf(HEADROOM_TOKENS, window / 8)

        /** Below this, an unknown window isn't flagged: nearly every current model takes 32K. */
        const val COMMON_WINDOW = 32_768

        /** About what one inline image costs (Anthropic: ~1,600 tokens for a 1-megapixel image). */
        const val IMAGE_TOKENS = 1_600

        /** Image types the adapters send inline (see Attachments.kt); anything else goes as a note. */
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        /**
         * What [request] needs: tools when it declares any, vision when any message it sends carries an
         * image, and room for its estimated size ([estimateTokens]). [pending] is a message about to be
         * added (the composer's text and attachments), [reasoning] whether the chat set a thinking effort.
         */
        fun of(
            request: ChatRequest,
            reasoning: Boolean = false,
            pending: List<Part> = emptyList(),
            structuredOutput: Boolean = false,
        ): ModelRequirements = ModelRequirements(
            tools = request.tools.isNotEmpty(),
            images = request.messages.any { m -> m.attachments.any(::isImage) } || pending.any { it is Part.Attachment && isImage(it) },
            reasoning = reasoning,
            structuredOutput = structuredOutput,
            contextTokens = estimateTokens(request) + estimate(pending),
        )

        fun isImage(a: Part.Attachment): Boolean = a.mimeType.lowercase() in IMAGE_TYPES

        /**
         * The prompt's size: the provider's own count for the latest reply that has one (input plus
         * output: everything up to and including that reply), plus an estimate for what came after it.
         * Without any reported usage, an estimate of the whole request (system prompt, tools, messages).
         * Estimates count about four characters a token, like core/agent's TokenEstimate.
         */
        fun estimateTokens(request: ChatRequest): Int {
            val lastCounted = request.messages.indexOfLast { it.usage != null }
            if (lastCounted >= 0) {
                val counted = request.messages[lastCounted].usage!!.context
                return counted + request.messages.drop(lastCounted + 1).sumOf(::estimate)
            }
            val tools = request.tools.sumOf { chars(it.name) + chars(it.description) + chars(it.inputSchema.toString()) }
            return chars(request.system) + tools + request.messages.sumOf(::estimate)
        }

        private fun estimate(message: Message): Int = estimate(message.parts)

        private fun estimate(parts: List<Part>): Int = parts.sumOf { p ->
            when (p) {
                is Part.Text -> chars(p.text)
                is Part.ToolCall -> chars(p.name) + chars(p.argumentsJson)
                is Part.ToolResult -> chars(p.content)
                is Part.Attachment -> if (isImage(p)) IMAGE_TOKENS else 50
                is Part.Checkpoint -> chars(p.summary) + p.recentUserMessages.sumOf(::chars) + chars(p.todos.orEmpty())
                is Part.Reasoning -> 0
            }
        }

        private fun chars(text: String): Int = (text.length + 3) / 4
    }
}

/**
 * Resolves what a model can do from, in order: what its provider's model list reports
 * ([RemoteModel]), the user's own setting for the provider's context window, the models.dev
 * catalogue ([CatalogModel], when the user turned it on), and [CapabilityRules] for well-known
 * families. Each ability comes from the first source that knows it; otherwise it stays unknown.
 */
object ModelCapabilityResolver {
    fun resolve(
        format: ApiFormat,
        baseUrl: String,
        modelId: String,
        listed: RemoteModel? = null,
        catalog: CatalogModel? = null,
        /** The context window the user set for the provider; applies to its default model only, so pass it only for that. */
        configuredContext: Int? = null,
    ): ModelCapabilities = fromProvider(listed, configuredContext) orElse fromCatalog(catalog) orElse CapabilityRules.of(format, baseUrl, modelId)

    fun fromProvider(listed: RemoteModel?, configuredContext: Int? = null) = ModelCapabilities(
        tools = Support.of(listed?.tools),
        vision = Support.of(listed?.vision),
        reasoning = Support.of(listed?.reasoning),
        structuredOutput = Support.of(listed?.structuredOutput),
        contextWindow = configuredContext ?: listed?.contextWindow,
    )

    fun fromCatalog(catalog: CatalogModel?) = ModelCapabilities(
        tools = Support.of(catalog?.tools),
        vision = Support.of(catalog?.vision),
        reasoning = Support.of(catalog?.reasoning),
        structuredOutput = Support.of(catalog?.structuredOutput),
        contextWindow = catalog?.context,
    )
}

/**
 * A few rules for model families whose abilities are well known, used when neither the provider nor
 * the catalogue says. Deliberately small: anything not matched stays unknown. Sources, read 2026-11-01:
 * platform.claude.com/docs/en/about-claude/models/overview, developers.openai.com/api/docs/models,
 * ai.google.dev/gemini-api/docs/models, api-docs.deepseek.com/quick_start/pricing.
 */
object CapabilityRules {
    /** The on-device engine's window (core/ondevice ON_DEVICE_CONTEXT_TOKENS). */
    const val ON_DEVICE_CONTEXT = 16_384

    private val Y = Support.YES
    private val N = Support.NO

    fun of(format: ApiFormat, baseUrl: String, modelId: String): ModelCapabilities {
        val caching = cachingOf(format, baseUrl)
        // Gemma 4 on the phone: tools are declared to LiteRT-LM, and the latest user message's images are sent.
        if (format == ApiFormat.ON_DEVICE) return ModelCapabilities(tools = Y, vision = Y, contextWindow = ON_DEVICE_CONTEXT, caching = CacheStyle.NONE)
        val id = modelId.trim().lowercase().removePrefix("models/").substringAfterLast('/')
        return family(id).copy(caching = if (caching == CacheStyle.UNKNOWN && id.startsWith("claude-")) CacheStyle.EXPLICIT else caching)
    }

    private fun family(id: String): ModelCapabilities = when {
        id.startsWith("claude-2") || id.startsWith("claude-instant") -> ModelCapabilities(tools = N, vision = N, reasoning = N)
        id.startsWith("claude-") -> ModelCapabilities(
            tools = Y,
            vision = Y,
            // Extended thinking: Claude 3.7 Sonnet and every model from Claude 4 on.
            reasoning = if (Regex("""^claude-(3-7|[4-9]|(opus|sonnet|haiku|fable|mythos)-([4-9]|\d{2}))""").containsMatchIn(id)) Y else N,
        )
        id.startsWith("gpt-3.5") -> ModelCapabilities(tools = Y, vision = N, reasoning = N)
        id.startsWith("gpt-4o") || id.startsWith("gpt-4.1") || id.startsWith("gpt-4-turbo") -> ModelCapabilities(tools = Y, vision = Y, reasoning = N)
        Regex("""^gpt-([5-9]|\d{2})""").containsMatchIn(id) -> ModelCapabilities(tools = Y, vision = Y, reasoning = if ("-chat" in id) Support.UNKNOWN else Y)
        id == "o1-mini" || id.startsWith("o1-mini-") -> ModelCapabilities(tools = N, vision = N, reasoning = Y)
        id == "o3-mini" || id.startsWith("o3-mini-") -> ModelCapabilities(tools = Y, vision = N, reasoning = Y)
        Regex("""^o[1-9](-|$)""").containsMatchIn(id) -> ModelCapabilities(tools = Y, vision = Y, reasoning = Y)
        id.startsWith("gemini-1.5") || id.startsWith("gemini-2.0") -> ModelCapabilities(tools = Y, vision = Y, reasoning = if ("thinking" in id) Y else N)
        Regex("""^gemini-(2\.5|[3-9])""").containsMatchIn(id) -> ModelCapabilities(tools = Y, vision = Y, reasoning = Y)
        id == "deepseek-chat" -> ModelCapabilities(tools = Y, vision = N, reasoning = N)
        id == "deepseek-reasoner" -> ModelCapabilities(vision = N, reasoning = Y)
        else -> ModelCapabilities.UNKNOWN
    }

    /** Prompt caching follows the endpoint more than the model. */
    fun cachingOf(format: ApiFormat, baseUrl: String): CacheStyle {
        when (format) {
            ApiFormat.ANTHROPIC_MESSAGES -> return CacheStyle.EXPLICIT
            ApiFormat.CHATGPT, ApiFormat.OPENAI_RESPONSES -> return CacheStyle.AUTOMATIC
            ApiFormat.ON_DEVICE -> return CacheStyle.NONE
            ApiFormat.OPENAI_CHAT -> Unit
        }
        val host = runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull() ?: return CacheStyle.UNKNOWN
        return when (host) {
            "api.openai.com", "generativelanguage.googleapis.com", "api.deepseek.com" -> CacheStyle.AUTOMATIC
            else -> CacheStyle.UNKNOWN
        }
    }
}
