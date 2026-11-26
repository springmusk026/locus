package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Effort
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import java.util.concurrent.ConcurrentHashMap

/**
 * Changing a chat's effort after it has messages without changing the cached prefix
 * (docs/design/midchat-config.md): the request keeps its top-level effort and carries the change as an
 * item inside the conversation, where the provider documents one.
 *
 * - Anthropic, per-message effort (beta `mid-conversation-output-config-2026-07-01`, read 2026-11-23,
 *   platform.claude.com/docs/en/build-with-claude/effort-per-message): `{"role": "system", "content": [],
 *   "output_config": {"effort": …}}` in `messages`, on the models the page lists, on Anthropic's own API.
 * - OpenAI Responses (developers.openai.com/api/docs/guides/reasoning, "Change reasoning mid-conversation"):
 *   `{"type": "configuration_update", "reasoning": {"effort": …}}` before the next user message, on "GPT-6
 *   and later models", on OpenAI's own API. Not the ChatGPT plan route, where it isn't documented.
 *
 * Each assistant turn on such a model records what it was asked for ([Part.TurnEffort]); [plan] rebuilds
 * every request of a chat from those records alone, so a change stays where it was first sent, as both
 * providers require. A provider that refuses the item ([reject]) gets the old request for the rest of the
 * process.
 */
object MidChatEffort {
    const val ANTHROPIC_BETA = "mid-conversation-output-config-2026-07-01"

    /** The per-message effort page's `supportedModels` (2026-11-23). Claude Fable 5, Sonnet 5 and older aren't on it. */
    internal val ANTHROPIC_MODELS = setOf(
        "claude-fable-5-2", "claude-mythos-5-2", "claude-fable-5-1", "claude-mythos-5-1",
        "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-haiku-5-5",
    )

    private const val OPENAI_HOST = "api.openai.com"
    private val GPT_MAJOR = Regex("^gpt-(\\d+)(?:[.-]|$)")

    private val rejected = ConcurrentHashMap.newKeySet<String>()

    /** Whether the provider's docs give [model] at [baseUrl] a per-message effort change. */
    fun documented(format: ApiFormat, baseUrl: String, model: String): Boolean {
        val id = model.trim()
        return when (format) {
            ApiFormat.ANTHROPIC_MESSAGES -> baseUrl.trim().trimEnd('/').equals(AnthropicChatModel.DEFAULT_BASE_URL, ignoreCase = true) && id in ANTHROPIC_MODELS
            ApiFormat.OPENAI_RESPONSES -> host(baseUrl) == OPENAI_HOST &&
                (GPT_MAJOR.find(id.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 6
            else -> false
        }
    }

    /** [documented], and not refused by this provider since the app started: what a request uses. */
    fun available(config: ProviderConfig): Boolean = documented(config.format, config.baseUrl, config.model) && key(config) !in rejected

    /** Whether a change of effort in a chat on [model] keeps its cache, for the composer's effort sheet. */
    fun keepsCache(providerId: String, format: ApiFormat, baseUrl: String, model: String): Boolean =
        documented(format, baseUrl, model) && key(providerId, format, baseUrl, model) !in rejected

    /** The provider refused the per-message item: send the old request to it until the app restarts. */
    fun reject(config: ProviderConfig) {
        rejected += key(config)
    }

    internal fun clearRejections() = rejected.clear()

    private fun key(config: ProviderConfig) = key(config.id, config.format, config.baseUrl, config.model)

    private fun key(id: String, format: ApiFormat, baseUrl: String, model: String) = "$id|$format|${baseUrl.trim().trimEnd('/')}|${model.trim()}"

    private fun host(baseUrl: String): String? = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull()

    /** The turn's record for [format], or null (another provider's turn, or one from before this). */
    fun recordOf(message: Message, format: ApiFormat): Part.TurnEffort? =
        if (message.role == Role.ASSISTANT && message.format == format) message.parts.firstNotNullOfOrNull { it as? Part.TurnEffort } else null

    /**
     * What a request sends: [topLevel] as the request's own effort (blank sends none), a change before the
     * assistant message at each index of [changes], and [pending] for the reply being asked for. Values in
     * [changes] and [pending] are concrete levels (a blank one is the model's default).
     */
    data class Plan(val topLevel: String, val changes: Map<Int, String>, val pending: String?) {
        val any: Boolean get() = changes.isNotEmpty() || pending != null
    }

    /**
     * The request for [messages] when the chat's effort is now [current] (blank: the model's own, which
     * [modelDefault] names). Only turns recorded for [format] count:
     *
     * 1. The top-level value is the newest record's `topLevel`; with none, [current], as before.
     * 2. Walking the records, a turn asked for another level than the one in effect gets a change before
     *    it. A turn recorded under another top-level value (sent the old way) resets the level instead.
     * 3. After a turn for which [resetsAfter] holds (Anthropic server compaction: the API drops what came
     *    before), the level is the top-level one again.
     * 4. When [current] differs from the level in effect at the end, it's [pending].
     *
     * Records only ever get added, so every request rebuilds each earlier one exactly.
     */
    fun plan(messages: List<Message>, format: ApiFormat, current: String, modelDefault: String, resetsAfter: (Message) -> Boolean = { false }): Plan {
        val records = messages.mapNotNull { recordOf(it, format) }
        val top = records.lastOrNull()?.topLevel ?: current
        fun level(value: String) = value.trim().ifEmpty { modelDefault }
        var inEffect = level(top)
        val changes = linkedMapOf<Int, String>()
        messages.forEachIndexed { i, message ->
            val record = recordOf(message, format)
            if (record != null) {
                if (record.topLevel != top) {
                    inEffect = level(top)
                } else if (level(record.effort) != inEffect) {
                    inEffect = level(record.effort)
                    changes[i] = inEffect
                }
            }
            if (message.role == Role.ASSISTANT && resetsAfter(message)) inEffect = level(top)
        }
        val pending = level(current).takeIf { it != inEffect }
        return Plan(top, changes, pending)
    }

    /** The concrete level [effort] means on [config]'s model: blank is the model's documented default. */
    fun modelDefault(config: ProviderConfig): String =
        Effort.modelDefault(config.format, config.baseUrl, config.model)?.wireValue(config.format) ?: "medium"
}
