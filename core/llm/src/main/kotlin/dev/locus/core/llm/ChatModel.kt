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
)

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
    val usage: dev.locus.core.model.TokenUsage? = null,
)

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
) : Exception(message, cause)

internal fun isRetryableStatus(status: Int) = status == 408 || status == 409 || status == 429 || status >= 500
