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
     * Whether the provider may write this request's prefix to its prompt cache (where caching is
     * opt-in, as on Anthropic). Off for one-off helper prompts that are never sent again, where a
     * cache write would only cost more.
     */
    val cache: Boolean = true,
)

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
