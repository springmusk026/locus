package dev.locus.core.llm

/**
 * Recognises a request rejected because the prompt doesn't fit the model's context window, so the
 * agent can compact and retry instead of failing (see docs/design/compaction.md). Structured fields
 * first, then the message text, since OpenAI-compatible servers pass their own wording through:
 *
 * - Anthropic: 400 `invalid_request_error`, "prompt is too long" ("prompt is too long: N tokens > M
 *   maximum"); platform.claude.com/docs/en/build-with-claude/context-windows.
 * - OpenAI Chat Completions and Responses: `error.code == "context_length_exceeded"` ("This model's
 *   maximum context length is N tokens…"; Responses streams it as `response.failed`, as Codex's
 *   `codex-rs/codex-api/src/sse/responses_error.rs` handles). `string_above_max_length` is a field
 *   limit, not overflow (LiteLLM excludes it too).
 * - Gemini: 400 `INVALID_ARGUMENT`, "The input token count (N) exceeds the maximum number of tokens
 *   allowed (M)."
 * - OpenRouter: `error.metadata.error_type == "context_length_exceeded"`
 *   (openrouter.ai/docs/api/reference/errors-and-debugging); Groq, xAI, DeepSeek the OpenAI shape.
 * - vLLM "This model's maximum context length is…"; llama.cpp `exceed_context_size_error`, "exceeds the
 *   available context size"; Mistral "too large for model with N maximum context length"; LM Studio
 *   "greater than the context length".
 * - Ollama truncates old messages silently by default (`truncate`/`shift` default on); with shift off
 *   it says "the prompt is longer than the context length".
 *
 * The phrases follow LiteLLM's `is_error_str_context_window_exceeded` and opencode's
 * `provider-error.ts`. Rate limits ("too many tokens per day, please wait") are excluded first.
 */
object ContextOverflow {
    /** Error `code`, `type` or `error_type` values that mean overflow. */
    val CODES = setOf("context_length_exceeded", "exceed_context_size_error", "model_context_window_exceeded")

    private val PHRASES = listOf(
        "prompt is too long",
        "context_length_exceeded",
        "context length exceeded",
        "maximum context length",
        "exceeds the context window",
        "exceeds the maximum number of tokens allowed",
        "exceeds the available context size",
        "is larger than the max context size",
        "is longer than the context length",
        "is longer than the model's context length",
        "greater than the context length",
        "context window exceeded",
        "exceeds the context length",
        "input is too long",
        "reduce the length of the messages",
        "maximum context limit",
        "input tokens exceed the configured limit",
    )

    private val PATTERNS = listOf(
        Regex("""input token count.*exceeds the maximum"""),
        Regex("""too large for model with \d+ maximum context length"""),
    )

    private val NOT_OVERFLOW = listOf("string_above_max_length", "rate limit", "rate_limit", "too many requests", "please wait", "per day", "per minute")

    /** Whether an error whose code or type is [code] and whose text is [text] reports overflow. */
    fun matches(code: String?, text: String?): Boolean {
        if (code != null && code.lowercase() in CODES) return true
        val t = text?.lowercase() ?: return false
        if (NOT_OVERFLOW.any { it in t }) return false
        return PHRASES.any { it in t } || PATTERNS.any { it.containsMatchIn(t) }
    }

    /** Whether [e] is a context-overflow failure, classified at the source or recognised by its message. */
    fun isOverflow(e: Throwable): Boolean =
        e is LlmException && (e.kind == LlmFailure.CONTEXT_OVERFLOW || (e.kind == LlmFailure.OTHER && matches(null, e.message)))
}
