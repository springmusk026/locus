package dev.locus.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the user reads when a provider answers with an error status. The provider's own words are kept
 * when it gave some (the `message` of a JSON error, or a short plain-text body); otherwise the status is
 * said in plain words. A gateway's HTML page ("504 Gateway Time-out" from nginx or Cloudflare), a long
 * body that isn't JSON, or an empty body never reach the chat: the full body goes to Diagnostics instead
 * ([LlmException.rawBody]), scrubbed of secrets there.
 */
object ProviderErrorText {
    /** A plain-text body longer than this is a page or a dump, not a message meant for a person. */
    const val MAX_PLAIN_CHARS = 300

    /** The provider's message, cut to this length for the chat. */
    private const val MAX_MESSAGE_CHARS = 500

    /** The message for a failed response from [provider]. */
    fun message(provider: String, status: Int, body: String): String = messageText(provider, status, body).english

    /** [message] as data, for the chat to word in the user's language. */
    fun messageText(provider: String, status: Int, body: String): LlmErrorText {
        val words = providerWords(body)
        return if (words != null) LlmErrorText(LlmErrorText.Kind.PROVIDER_WORDS, listOf(provider, status.toString(), words)) else statusMessage(provider, status, htmlTitle(body))
    }

    /**
     * The provider's own words in [body], or null when it has none worth showing: an empty body, markup,
     * a non-JSON body longer than [MAX_PLAIN_CHARS], or JSON without a message.
     *
     * JSON shapes read: `{"error": {"message": …}}` (OpenAI, Anthropic, Gemini, also wrapped in an array),
     * `{"error": "…"}` (Ollama), `{"message": …}` and `{"detail": …}` (FastAPI-style servers).
     */
    fun providerWords(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val json = runCatching { LlmJson.parseToJsonElement(trimmed) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
        val text = if (json != null) jsonMessage(json) else trimmed.takeIf { it.length <= MAX_PLAIN_CHARS }
        return text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() && !isMarkup(it) }?.let {
            if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS).trimEnd() + "…" else it
        }
    }

    /** Whether [text] holds HTML or XML tags. */
    fun isMarkup(text: String): Boolean = TAG.containsMatchIn(text)

    /**
     * The status in plain words, with what to do. Gateway statuses (502, 504 and Cloudflare's 52x) come
     * from a proxy in front of the model, not from the model's API, so they say so. [title] is a page's
     * `<title>`, used only for a status with no words of its own.
     */
    fun statusText(provider: String, status: Int, title: String? = null): String = statusMessage(provider, status, title).english

    /** [statusText] as data, for the chat to word in the user's language. */
    fun statusMessage(provider: String, status: Int, title: String? = null): LlmErrorText {
        fun of(kind: LlmErrorText.Kind, vararg more: String) = LlmErrorText(kind, listOf(provider, *more))
        return when (status) {
            400 -> of(LlmErrorText.Kind.BAD_REQUEST)
            401 -> of(LlmErrorText.Kind.KEY_REFUSED)
            403 -> of(LlmErrorText.Kind.FORBIDDEN)
            404 -> of(LlmErrorText.Kind.NOT_FOUND)
            408 -> of(LlmErrorText.Kind.TIMED_OUT)
            413 -> of(LlmErrorText.Kind.TOO_LARGE)
            429 -> of(LlmErrorText.Kind.RATE_LIMITED)
            500 -> of(LlmErrorText.Kind.INTERNAL_ERROR)
            502 -> of(LlmErrorText.Kind.BAD_GATEWAY)
            503 -> of(LlmErrorText.Kind.UNAVAILABLE)
            504 -> of(LlmErrorText.Kind.GATEWAY_TIMEOUT)
            520, 521, 523 -> of(LlmErrorText.Kind.GATEWAY_UNREACHABLE, status.toString())
            522 -> of(LlmErrorText.Kind.GATEWAY_CONNECT_TIMEOUT)
            524 -> of(LlmErrorText.Kind.GATEWAY_MODEL_TIMEOUT)
            529 -> of(LlmErrorText.Kind.OVERLOADED)
            else -> when {
                status >= 500 -> of(LlmErrorText.Kind.SERVER_ERROR, status.toString())
                title != null -> of(LlmErrorText.Kind.STATUS_TITLE, status.toString(), title)
                else -> of(LlmErrorText.Kind.STATUS, status.toString())
            }
        }
    }

    /** A page's `<title>` as plain text ("504 Gateway Time-out"), or null. */
    fun htmlTitle(body: String): String? {
        val raw = TITLE.find(body)?.groupValues?.get(1) ?: return null
        return raw.replace(TAG_ANY, " ").replace(ENTITY) { m -> ENTITIES[m.value.lowercase()] ?: " " }
            .replace(WHITESPACE, " ").trim().take(80).takeIf { it.isNotEmpty() && !isMarkup(it) }
    }

    private fun jsonMessage(json: JsonElement): String? {
        val root = (if (json is JsonArray) json.firstOrNull() else json) as? JsonObject ?: return null
        val error = root["error"]
        if (error is JsonPrimitive && error.isString) return error.content
        val obj = error as? JsonObject
        return obj?.string("message")?.takeIf { it.isNotBlank() }
            ?: root.string("message")?.takeIf { it.isNotBlank() }
            ?: root.string("detail")?.takeIf { it.isNotBlank() }
            ?: obj?.string("code")?.takeIf { it.isNotBlank() }
            ?: obj?.string("type")?.takeIf { it.isNotBlank() }
    }

    private val WHITESPACE = Regex("\\s+")
    private val TAG = Regex("<\\s*/?\\s*[a-zA-Z!?][^<>]*>")
    private val TAG_ANY = Regex("<[^>]*>")
    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ENTITY = Regex("&[a-zA-Z#0-9]+;")
    private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "‹", "&gt;" to "›", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")
}
