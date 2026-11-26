package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a Responses request puts the chat's system prompt (docs/design/cost.md, "System prompt placement
 * on GPT-6.2 and later"). OpenAI's prompt-caching guide (developers.openai.com/api/docs/guides/prompt-caching,
 * "How prefix matching works", read 2026-11-23): "Models starting with GPT-6.2, including Astra and Sol,
 * append these instructions after `input`, beyond eligible cache breakpoints", billed "at the uncached
 * input-token rate on every request"; "To make reusable instructions eligible for caching, put them in a
 * `developer` message at the beginning of `input`." The ChatGPT plan route takes "instructions or developer
 * messages" (developers.openai.com/siwc/token-sharing-open-source/preview-limitations).
 *
 * Only chats whose turns were all sent that way move: inserting a message before replayed reasoning is a
 * `prefix_mismatch` on GPT-6.2 and later (reasoning guide, "Reasoning item removal across requests"), so
 * a chat already under way keeps `instructions`. Each turn sent with the developer message records
 * [Part.SystemInInput]; [inInput] reads those records alone, so every request of a chat is rebuilt the same.
 * A provider that refuses the developer message ([reject]) gets `instructions` until the app restarts.
 */
object SystemPlacement {
    private const val OPENAI_HOST = "api.openai.com"
    private val GPT_VERSION = Regex("^gpt-(\\d+)(?:\\.(\\d+))?(?:[.-]|$)")

    private val rejected = ConcurrentHashMap.newKeySet<String>()

    /** Whether OpenAI's docs say [model] appends top-level `instructions` after `input`: GPT-6.2 and later, on OpenAI. */
    fun documented(format: ApiFormat, baseUrl: String, model: String): Boolean {
        if (format != ApiFormat.OPENAI_RESPONSES && format != ApiFormat.CHATGPT) return false
        if (host(baseUrl) != OPENAI_HOST) return false
        val m = GPT_VERSION.find(model.trim().lowercase()) ?: return false
        val major = m.groupValues[1].toIntOrNull() ?: return false
        val minor = m.groupValues[2].toIntOrNull() ?: 0
        return major > 6 || (major == 6 && minor >= 2)
    }

    /** [documented], and not refused by this provider since the app started. */
    fun available(config: ProviderConfig): Boolean = documented(config.format, config.baseUrl, config.model) && key(config) !in rejected

    /**
     * Whether this request sends the system prompt as the first `developer` input message: [available], and
     * every earlier assistant turn of [format] in [messages] was sent that way (none at all for a new chat).
     */
    fun inInput(messages: List<Message>, format: ApiFormat, config: ProviderConfig): Boolean =
        available(config) && messages.none { it.role == Role.ASSISTANT && it.format == format && Part.SystemInInput !in it.parts }

    /** The provider refused the developer message: send `instructions` to it until the app restarts. */
    fun reject(config: ProviderConfig) {
        rejected += key(config)
    }

    internal fun clearRejections() = rejected.clear()

    private fun key(config: ProviderConfig) = "${config.id}|${config.format}|${config.baseUrl.trim().trimEnd('/')}|${config.model.trim()}"

    private fun host(baseUrl: String): String? = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull()
}
