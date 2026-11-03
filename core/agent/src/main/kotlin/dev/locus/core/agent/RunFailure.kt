package dev.locus.core.agent

import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmFailure

/**
 * A model request that stopped a run, as the chat's error banner and its details sheet show it
 * (docs/design/provider-failures.md). Everything here is about the failure itself: the provider's
 * error payload ([rawBody], scrubbed of credentials before it gets here) and ids, never the request.
 */
data class RunFailure(
    val provider: String,
    val model: String,
    val httpStatus: Int?,
    val errorCode: String?,
    val requestId: String?,
    val responseId: String?,
    /** When it failed (wall clock). */
    val at: Long,
    /** The provider's error payload, scrubbed; null when it sent none. */
    val rawBody: String?,
    val kind: LlmFailure,
    /** How many times in a row the same failure came back for the same transcript; 1 the first time. */
    val occurrences: Int = 1,
    val cause: LikelyCause = LikelyCause.OTHER,
    /** Tokens the chat's last answered request used, when known: how large the context is. */
    val contextTokens: Int? = null,
    /** The model's context window, when known. */
    val contextWindow: Int? = null,
    /** The transcript point it failed at (the last message's id), to tell a repeat from a new failure. */
    val transcriptMark: String? = null,
) {
    /** The same failure came back after Try again, on the same transcript. */
    val repeated: Boolean get() = occurrences >= 2

    /** What makes two failures "the same": who failed, and how. */
    val signature: String get() = listOf(provider, model, httpStatus, errorCode, kind).joinToString("|")

    /** The context is big enough that summarising it may be the way out (from about 64k tokens, or half the window). */
    val contextLarge: Boolean get() {
        val used = contextTokens ?: return false
        val window = contextWindow?.takeIf { it > 0 }
        return used >= LARGE_CONTEXT_TOKENS || (window != null && used >= window / 2)
    }

    /** Offer "Summarise chat": the context may be the problem, or the request is, and a summary replaces it. */
    val suggestCompact: Boolean get() = cause == LikelyCause.CONTEXT || (cause == LikelyCause.REQUEST && contextLarge)

    /** Offer "Switch model": this model can't take the chat now (a usage limit), or keeps failing on it. */
    val suggestSwitchModel: Boolean get() = cause == LikelyCause.USAGE_LIMIT || cause == LikelyCause.REQUEST || (repeated && cause != LikelyCause.AUTH)

    /** One or two sentences for the banner, under the error itself, saying what this probably is and what to try. */
    val advice: String? get() = when {
        cause == LikelyCause.REQUEST -> buildString {
            append("This failed the same way ")
            append(if (occurrences == 2) "twice" else "$occurrences times")
            append(", so it's probably something in this chat's request rather than a passing glitch")
            if (contextLarge) append(": the chat is large (about ${compactTokens(contextTokens ?: 0)} tokens)")
            append(". ")
            append(
                when {
                    suggestCompact && suggestSwitchModel -> "Summarising the chat replaces the long history with a short summary; another model may also take it."
                    suggestSwitchModel -> "Another model may take it."
                    else -> "Details has what the provider sent."
                },
            )
        }
        cause == LikelyCause.USAGE_LIMIT -> "Another model can carry on with this chat while the limit lasts."
        repeated && cause == LikelyCause.TRANSIENT -> "It failed the same way again; the provider seems to be having trouble. Another model may answer meanwhile."
        else -> null
    }

    companion object {
        const val LARGE_CONTEXT_TOKENS = 64_000

        /**
         * The failure for [error], counted against [previous] (the chat's last one): the same [signature]
         * at the same [transcriptMark] is a repeat. A repeat that the provider didn't explain, or called
         * transient, is put down to the request (an oversized tool output, an attachment, an item the
         * provider can't take): a passing glitch doesn't come back on every try at the same point.
         * Rate limits and overload are the provider's state, so they stay transient however often.
         */
        fun of(
            error: LlmException?,
            message: String,
            provider: String,
            model: String,
            at: Long,
            previous: RunFailure?,
            transcriptMark: String?,
            rawBody: String?,
            contextTokens: Int? = null,
            contextWindow: Int? = null,
        ): RunFailure {
            val kind = error?.kind ?: LlmFailure.OTHER
            val first = RunFailure(
                provider = provider,
                model = model,
                httpStatus = error?.httpStatus,
                errorCode = error?.errorCode,
                requestId = error?.requestId,
                responseId = error?.responseId,
                at = at,
                rawBody = rawBody,
                kind = kind,
                contextTokens = contextTokens,
                contextWindow = contextWindow,
                transcriptMark = transcriptMark,
            )
            val occurrences = if (previous != null && previous.signature == first.signature && previous.transcriptMark == transcriptMark) previous.occurrences + 1 else 1
            return first.copy(occurrences = occurrences, cause = causeOf(kind, error?.errorCode, message, occurrences >= 2))
        }

        internal fun causeOf(kind: LlmFailure, code: String?, message: String, repeated: Boolean): LikelyCause = when (kind) {
            LlmFailure.QUOTA -> LikelyCause.USAGE_LIMIT
            LlmFailure.AUTH -> LikelyCause.AUTH
            LlmFailure.CONTEXT_OVERFLOW -> LikelyCause.CONTEXT
            LlmFailure.RATE_LIMIT, LlmFailure.OVERLOADED -> LikelyCause.TRANSIENT
            LlmFailure.TRANSIENT -> when {
                // A dropped connection is the phone's network, however often it happens.
                message.startsWith(AgentLoop.CONNECTION_DROPPED) -> LikelyCause.TRANSIENT
                repeated && (code == null || code == "server_error") -> LikelyCause.REQUEST
                else -> LikelyCause.TRANSIENT
            }
            LlmFailure.OTHER -> if (repeated) LikelyCause.REQUEST else LikelyCause.OTHER
        }

        private fun compactTokens(n: Int): String = if (n >= 1_000) "${n / 1_000}k" else "$n"
    }
}

/** What a failed request most likely came from, for what the banner offers. */
enum class LikelyCause {
    /** Out of plan usage, quota or credit. */
    USAGE_LIMIT,

    /** The sign-in or key was refused. */
    AUTH,

    /** The prompt is too long for the model. */
    CONTEXT,

    /** Something in the request itself: it fails the same way on every try. */
    REQUEST,

    /** The provider's passing state: overload, rate limit, a server error seen once. */
    TRANSIENT,

    /** Anything else, seen once. */
    OTHER,
}
