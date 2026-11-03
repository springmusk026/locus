package dev.locus.core.llm

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow

data class ChatRequest(
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolSpec>,
    /**
     * A stable routing hint for the provider's prompt cache (OpenAI `prompt_cache_key`), from
     * [PromptCacheKey]. Requests that share a prefix should share a key; null sends none.
     */
    val cacheKey: String? = null,
    /**
     * The task's token budget, for providers that let the model pace itself against one (Anthropic
     * `output_config.task_budget`). Advisory: Locus enforces the budget itself. Kept the same for every
     * request of a chat, since a changed value misses the prompt cache. Null sends none.
     */
    val taskBudget: Long? = null,
    /**
     * Whether the provider may write this request's prefix to its prompt cache (where caching is
     * opt-in, as on Anthropic). Off for one-off helper prompts that are never sent again, where a
     * cache write would only cost more.
     */
    val cache: Boolean = true,
    /**
     * Tools the chat can run but doesn't declare up front (tool discovery, docs/design/tool-discovery.md).
     * Adapters with provider-native tool search for this model send them as deferred tools beside a
     * search tool (Anthropic `defer_loading`, OpenAI `tool_search`), where the provider keeps them out of
     * the cached prefix; the others ignore them and the model reaches them through find_tools and call_tool.
     */
    val deferredTools: List<ToolSpec> = emptyList(),
)

/** The names of the client-side discovery tools, which adapters with native search defer too. */
internal val DISCOVERY_TOOL_NAMES = setOf(dev.locus.core.model.DiscoveryTools.FIND_TOOLS, dev.locus.core.model.DiscoveryTools.CALL_TOOL)

/** Names both Anthropic and OpenAI accept for a tool; deferred tools with other names stay reachable only through find_tools. */
internal val PORTABLE_TOOL_NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")

/**
 * Stable OpenAI `prompt_cache_key` values. OpenAI routes requests by a hash of the prompt's first
 * tokens plus this key ("Keep related requests on the same `prompt_cache_key` so they can reuse its
 * cache", developers.openai.com/api/docs/guides/prompt-caching), and reports a changed key as a
 * cause of a cache miss (`prompt_cache_key_changed`). So the key is derived only from what fixes the
 * prefix, never from message text or time: the chat (whose system prompt is frozen at creation), or
 * a helper's profile (whose system prompt is fixed per profile).
 */
object PromptCacheKey {
    /** OpenAI documents no length limit; long keys are hashed to stay short anyway. */
    private const val MAX_LENGTH = 64

    fun forChat(conversationId: String): String = bounded("locus-chat-$conversationId")

    fun forHelper(profileType: String): String = bounded("locus-helper-$profileType")

    private fun bounded(key: String): String {
        if (key.length <= MAX_LENGTH) return key
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return "locus-" + digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, PAUSE_TURN, OTHER }

/** A finished assistant turn, ready to append to the conversation. */
data class AssistantTurn(
    val text: String,
    val toolCalls: List<Part.ToolCall>,
    val stopReason: StopReason,
    val format: ApiFormat,
    /** Provider-native encoding replayed verbatim on later requests (see [Message.rawPayload]). */
    val rawPayload: String?,
    val refusalDetail: String? = null,
    /** Tokens of the attempt that produced this turn, at [servedModel]'s rates when set. */
    val usage: dev.locus.core.model.TokenUsage? = null,
    /**
     * The model that actually produced this turn when the provider switched models on its own (Anthropic's
     * server-side fallback after a refusal); null when it's the requested one.
     */
    val servedModel: String? = null,
    /** Billed attempts by other models that declined before this turn was produced, each at its own model's rates. */
    val declinedAttempts: List<AttemptUsage> = emptyList(),
)

/** One billed model attempt within a request: [model] and its own token counts. */
data class AttemptUsage(val model: String, val usage: dev.locus.core.model.TokenUsage)

sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent

    /** Reasoning or progress notes the provider chose to show. */
    data class ReasoningDelta(val text: String) : ChatEvent
    data class ToolCallStarted(val id: String, val name: String) : ChatEvent
    data class Completed(val turn: AssistantTurn) : ChatEvent
}

/** One provider endpoint. Implementations translate [ChatRequest] to a wire format and stream back. */
interface ChatModel {
    val format: ApiFormat
    fun stream(request: ChatRequest): Flow<ChatEvent>
}

class LlmException(
    message: String,
    val httpStatus: Int? = null,
    /** True for rate limits, overload and transient server errors. */
    val retryable: Boolean = false,
    cause: Throwable? = null,
    /** What went wrong, for key rotation and model fallback ([Failover]). */
    val kind: LlmFailure = if (retryable) LlmFailure.TRANSIENT else LlmFailure.OTHER,
    /**
     * How long the provider asked us to wait before trying again (`retry-after-ms`, `retry-after`, or
     * Gemini's `RetryInfo.retryDelay`), or null when it didn't say.
     */
    val retryAfterMs: Long? = null,
    /**
     * The response body as the provider sent it (up to 2,000 characters), when the message doesn't show
     * it whole: a gateway's HTML page, a JSON error the message only quotes. Never shown in the chat; it is
     * for Diagnostics and logs, scrubbed of secrets there. Null when there was no body.
     */
    val rawBody: String? = null,
    /** The provider's own error code or type (`context_length_exceeded`, `usage_limit_reached`), when it gave one. */
    val errorCode: String? = null,
    /**
     * The id the provider gave this HTTP request (`x-request-id`, else `x-oai-request-id`, else Cloudflare's
     * `cf-ray`), for a support ticket. Not a secret: it names the request, not the account.
     */
    val requestId: String? = null,
    /** The id of the response that failed (`resp_…`), when the stream had started one. */
    val responseId: String? = null,
) : Exception(message, cause) {
    /**
     * This failure with another [message] (and [cause]), everything else kept: what decides retries and
     * fallback, and what Diagnostics and the error's details sheet show.
     */
    fun withMessage(message: String, cause: Throwable? = this.cause, retryAfterMs: Long? = this.retryAfterMs, rawBody: String? = this.rawBody): LlmException =
        LlmException(message, httpStatus, retryable, cause, kind, retryAfterMs, rawBody, errorCode, requestId, responseId)

    /** This failure with the request's ids filled in where it had none. */
    fun withIds(requestId: String?, responseId: String? = null): LlmException =
        if ((requestId == null || this.requestId != null) && (responseId == null || this.responseId != null)) {
            this
        } else {
            LlmException(message.orEmpty(), httpStatus, retryable, cause, kind, retryAfterMs, rawBody, errorCode, this.requestId ?: requestId, this.responseId ?: responseId)
        }
}

/**
 * The [LlmException] in [this] or its causes that says most about the failure: the first that carries a
 * raw body, an error code or an id, else the first one at all. Wrappers (fallback notes, waits) keep
 * the original as their cause.
 */
fun Throwable.llmFailure(): LlmException? {
    val chain = generateSequence(this) { it.cause }.take(8).filterIsInstance<LlmException>().toList()
    return chain.firstOrNull { it.rawBody != null || it.errorCode != null || it.requestId != null || it.responseId != null } ?: chain.firstOrNull()
}

/** Why a model request failed, as far as switching keys or models is concerned. See docs/design/fallbacks.md. */
enum class LlmFailure {
    /** A rate limit on this key or account (429 that isn't a billing limit). Retry later, or try another key. */
    RATE_LIMIT,

    /** The provider is overloaded (Anthropic 529 `overloaded_error`, 503 `UNAVAILABLE`, OpenAI `server_is_overloaded`). */
    OVERLOADED,

    /** Any other failure worth retrying: 5xx, 408, 409, a stream that ended early. */
    TRANSIENT,

    /** Out of credit, quota or spend limit (402, OpenAI `insufficient_quota`, a daily quota). Retrying won't help today. */
    QUOTA,

    /** The key was refused (401, 403). */
    AUTH,

    /**
     * The prompt doesn't fit the model's context window ([ContextOverflow]). Retrying the same request
     * can't help; the agent compacts the transcript and tries once more.
     */
    CONTEXT_OVERFLOW,

    /** Anything else: a bad request, an unknown model. Switching wouldn't fix it. */
    OTHER,
}

internal fun isRetryableStatus(status: Int) = status == 408 || status == 409 || status == 429 || status >= 500
