package dev.locus.core.llm

/**
 * When a failed request should move to another API key of the same provider ([KeyPool]) or to the
 * next model of the fallback chain (the agent loop). One place, so the two can't disagree. See
 * docs/design/fallbacks.md for the research behind each rule.
 */
object Failover {
    /**
     * The longest `retry-after` Locus waits out, as LiteLLM and the Vercel AI SDK do (openai-python
     * allows 120 s). A longer wait is too long for someone watching a chat, so the chain moves on
     * instead, or the chat fails with the wait in the message.
     */
    const val MAX_WAIT_MS = 60_000L

    /** How long a key rests after a rate limit that didn't say how long. */
    const val RATE_LIMIT_REST_MS = 60_000L

    /** How long a key rests after a billing or quota error that didn't say how long. */
    const val QUOTA_REST_MS = 60 * 60_000L

    /**
     * Another key of the same provider may succeed: this key is out of quota, refused, or rate limited
     * (keys from different accounts have separate limits). Overload and server errors are the
     * provider's, not the key's, so they stay with the agent's retry.
     */
    fun rotatesKey(e: LlmException): Boolean = e.kind == LlmFailure.QUOTA || e.kind == LlmFailure.AUTH || e.kind == LlmFailure.RATE_LIMIT

    /**
     * Give up on this model at once and try the next in the chain: retrying can't help (quota, a refused
     * key), or the provider asked for a longer wait than [MAX_WAIT_MS].
     */
    fun switchesNow(e: LlmException): Boolean =
        e.kind == LlmFailure.QUOTA || e.kind == LlmFailure.AUTH || (e.retryable && (e.retryAfterMs ?: 0) > MAX_WAIT_MS)

    /** Once this model's retries are spent on [e], the next model in the chain may still answer. */
    fun switchesAfterRetries(e: LlmException): Boolean = e.retryable

    /** How long to wait before retry number [attempt] (1-based): the provider's `retry-after` if it gave one, else 2^attempt s. */
    fun backoffMs(attempt: Int, e: LlmException?): Long {
        val exponential = 1_000L shl attempt.coerceIn(0, 6)
        val asked = e?.retryAfterMs ?: return exponential
        return asked.coerceIn(0, MAX_WAIT_MS)
    }

    /** How long a key that failed with [e] is left out of its pool. */
    fun keyRestMs(e: LlmException): Long = when (e.kind) {
        LlmFailure.AUTH -> Long.MAX_VALUE
        LlmFailure.QUOTA -> e.retryAfterMs ?: QUOTA_REST_MS
        else -> e.retryAfterMs ?: RATE_LIMIT_REST_MS
    }
}
