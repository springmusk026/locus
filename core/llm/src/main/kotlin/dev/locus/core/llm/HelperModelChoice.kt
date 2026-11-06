package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import java.net.URI

/**
 * The "Automatic" helper model (docs/design/cost.md, "The helper model"): a cheaper model from the chat's
 * own provider, on the same key, for side jobs and for helpers set to use the helper model. Null means
 * "no cheaper model is known: use the chat's own".
 *
 * Why a cheaper tier: Anthropic's cost guide says "High-volume work with outputs you can check: try
 * Claude Haiku 5.5 first", and puts Haiku 5.5 at "about a twentieth of Claude Opus 5.5's cost per correct
 * answer" on GPQA Diamond (85% against 91%). For sub-agents the same guide is more careful: Haiku 5.5
 * helpers under Opus 5.5 "cut cost per attempt by 32% and the pass rate by about 4 percentage points" on
 * coding, and delegation came out ahead mainly for "routine work with a long cost tail". So only the
 * look-and-report helpers (researcher, explorer) default to it; coder, ops and browser keep the chat's
 * model (core/agent `AgentProfile`).
 *
 * How it's chosen, without a request:
 * 1. Not for a chat that is already on a cheap tier (a Haiku, mini, nano, Flash or Lite model), nor
 *    on-device or local servers.
 * 2. From the models.dev catalogue, when it's on: the newest model of the same maker and family on the
 *    same endpoint whose name marks a cheap tier ([TIERS]), that isn't deprecated, calls tools, has at
 *    least [MIN_CONTEXT] tokens of context, and costs less than the chat's model for both input and
 *    output. Narrower tiers (nano, Lite) and special-purpose models (audio, realtime, image, search) are
 *    left out: they're cheaper still, but weaker at tool use.
 * 3. Else the documented default: Claude Haiku 5.5 for Claude on Anthropic's API (`claude-haiku-5-5`) and
 *    through OpenRouter (`anthropic/claude-haiku-5.5`). Anthropic's models overview lists Haiku 5.5 at
 *    "From $0.10 / input MTok, From $0.50 / output MTok", below every other current Claude model, with
 *    tool use and a 1M window.
 *
 * The caller still checks the pick against what the provider says ([fits]), and the helper falls back
 * to the chat's model when it doesn't fit.
 */
object HelperModelChoice {
    /** Helpers read files and pages over many steps; a smaller window would compact constantly. */
    const val MIN_CONTEXT = 128_000

    /** Cheap tiers that still do agent work well, by name, in order of preference between equally new ones. */
    val TIERS = listOf("haiku", "mini", "flash")

    /** Cheaper tiers that are a step too small for helpers' tool use. */
    private val SMALLER_TIERS = setOf("nano", "lite")

    /** Names that mark a special-purpose model, never a general helper. */
    private val SPECIAL = setOf(
        "image", "audio", "tts", "realtime", "transcribe", "search", "embedding", "embed", "live", "moderation", "guard", "computer", "deep",
        "research", "codex", "instruct", "exp",
    )

    const val ANTHROPIC_HAIKU = "claude-haiku-5-5"
    const val OPENROUTER_HAIKU = "anthropic/claude-haiku-5.5"

    /** The helper model for a chat on [chatModel] at [baseUrl], or null for the chat's own. */
    fun automatic(format: ApiFormat, baseUrl: String, chatModel: String, catalog: ModelCatalogIndex?): String? {
        if (format == ApiFormat.ON_DEVICE || chatModel.isBlank() || isCheapTier(chatModel)) return null
        return catalog?.let { fromCatalog(format, baseUrl, chatModel, it) } ?: documented(format, baseUrl, chatModel)
    }

    /** Whether [modelId]'s name marks a cheap tier already (Haiku, mini, nano, Flash, Lite). */
    fun isCheapTier(modelId: String): Boolean = tokens(modelId).any { it in TIERS || it in SMALLER_TIERS }

    /**
     * Whether the [picked] model can do helper work as the provider describes it ([caps], and the provider's
     * own model list [listedIds] when it was fetched): it must call tools and have [MIN_CONTEXT] tokens. A
     * list that doesn't name it means this key can't use it.
     */
    fun fits(picked: String, caps: ModelCapabilities, listedIds: List<String>?): Boolean {
        if (listedIds != null && listedIds.isNotEmpty() && listedIds.none { it.equals(picked, ignoreCase = true) }) return false
        if (caps.tools == Support.NO) return false
        val window = caps.contextWindow
        return window == null || window >= MIN_CONTEXT
    }

    internal fun fromCatalog(format: ApiFormat, baseUrl: String, chatModel: String, catalog: ModelCatalogIndex): String? {
        val chatPrice = catalog.lookup(baseUrl, format, chatModel)?.price ?: return null
        val chatId = chatModel.trim()
        val vendor = vendorOf(chatId)
        val family = familyOf(chatId) ?: return null
        val candidates = catalog.providersFor(baseUrl, format).flatMap { pid -> catalog.providers[pid]?.models.orEmpty().entries }
            .filter { (id, m) ->
                val words = tokens(id)
                !id.equals(chatId, ignoreCase = true) &&
                    vendorOf(id) == vendor && familyOf(id) == family &&
                    words.any { it in TIERS } && words.none { it in SMALLER_TIERS || it in SPECIAL } &&
                    m.deprecated != true && m.tools == true &&
                    (m.context ?: 0) >= MIN_CONTEXT &&
                    m.price.let { p -> p != null && p.input < chatPrice.input && p.output <= chatPrice.output }
            }
        return candidates.sortedWith(
            compareByDescending<Map.Entry<String, CatalogModel>> { it.value.released.orEmpty() }
                .thenBy { e -> TIERS.indexOfFirst { it in tokens(e.key) } }
                .thenByDescending { it.value.price?.input ?: 0.0 }
                .thenBy { it.key },
        ).firstOrNull()?.key
    }

    internal fun documented(format: ApiFormat, baseUrl: String, chatModel: String): String? {
        val host = runCatching { URI(baseUrl.trim()).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val id = chatModel.trim().lowercase()
        return when {
            host == "api.anthropic.com" && format == ApiFormat.ANTHROPIC_MESSAGES && id.startsWith("claude-") -> ANTHROPIC_HAIKU
            host == "openrouter.ai" && id.startsWith("anthropic/claude-") -> OPENROUTER_HAIKU
            else -> null
        }
    }

    /** "anthropic" in "anthropic/claude-opus-5.5"; empty without a vendor prefix. */
    private fun vendorOf(id: String): String = id.trim().lowercase().removePrefix("models/").substringBeforeLast('/', "")

    /** The model line's first word: "claude", "gpt", "gemini". Null for an id without letters there. */
    private fun familyOf(id: String): String? = tokens(id).firstOrNull()?.takeIf { w -> w.any(Char::isLetter) }

    private fun tokens(id: String): List<String> =
        id.trim().lowercase().removePrefix("models/").substringAfterLast('/').split('-', '_', '.', ':', '@', ' ').filter { it.isNotEmpty() }
}
