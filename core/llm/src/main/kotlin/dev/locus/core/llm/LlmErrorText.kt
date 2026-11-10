package dev.locus.core.llm

/**
 * A provider failure's message as data (docs/design/localization.md): which message, and the values in
 * it. [english] is the [LlmException.message] Locus writes, for logs, Diagnostics and reports; the chat
 * words the same message in the user's language from [kind] and [values] (feature/chat).
 *
 * [values] hold, in order, what each [Kind] names: most start with the provider's name.
 */
data class LlmErrorText(val kind: Kind, val values: List<String> = emptyList(), val resetsAt: Long? = null) {

    enum class Kind {
        /** Provider, status, the provider's own words. */
        PROVIDER_WORDS,

        /** Provider (each status below names its own). */
        BAD_REQUEST,
        KEY_REFUSED,
        FORBIDDEN,
        NOT_FOUND,
        TIMED_OUT,
        TOO_LARGE,
        RATE_LIMITED,
        INTERNAL_ERROR,
        BAD_GATEWAY,
        UNAVAILABLE,
        GATEWAY_TIMEOUT,

        /** Provider, status (520, 521, 523). */
        GATEWAY_UNREACHABLE,
        GATEWAY_CONNECT_TIMEOUT,
        GATEWAY_MODEL_TIMEOUT,
        OVERLOADED,

        /** Provider, status. */
        SERVER_ERROR,

        /** Provider, status, the error page's title. */
        STATUS_TITLE,

        /** Provider, status. */
        STATUS,

        /** Provider, the provider's own words or code. */
        BILLING,

        /** Provider. */
        BILLING_NO_DETAIL,

        /** Provider, the provider's own words. */
        DAILY_QUOTA,

        /** Provider. */
        DAILY_QUOTA_NO_DETAIL,

        /** No values; [resetsAt] when ChatGPT said when it resets. */
        CHATGPT_LIMIT,
        CHATGPT_NOT_INCLUDED,
        CHATGPT_OUT_OF_CREDIT,
        CHATGPT_USAGE_UNAVAILABLE,
        CHATGPT_NOT_ELIGIBLE,
        CHATGPT_UNSUPPORTED,

        /** The request's part ChatGPT can't take. */
        CHATGPT_UNSUPPORTED_PARAM,

        /** ChatGPT's own words. */
        CHATGPT_UNSUPPORTED_MESSAGE,

        /** The request's part, ChatGPT's own words. */
        CHATGPT_UNSUPPORTED_PARAM_MESSAGE,
        CHATGPT_SIGN_IN_REFUSED,

        /** ChatGPT's own words. */
        CHATGPT_SIGN_IN_REFUSED_DETAIL,
        CHATGPT_ROUTE_NOT_SUPPORTED,
        CHATGPT_OVERLOADED,

        /** ChatGPT's own words. */
        CHATGPT_OVERLOADED_MESSAGE,
        CHATGPT_SERVER_ERROR,
        CHATGPT_TOO_MANY_REQUESTS,

        /** Provider, the error code. */
        RESPONSE_FAILED_CODE,

        /** ChatGPT's own words. */
        CHATGPT_REFUSED_DETAIL,
        CHATGPT_REFUSED,

        /** ChatGPT's own words. */
        CHATGPT_PLAN_UNAVAILABLE_DETAIL,
        CHATGPT_PLAN_UNAVAILABLE,

        /** Provider. */
        UNEXPLAINED,

        /** Provider, the request id. */
        UNEXPLAINED_WITH_ID,
    }

    /** The message in English, exactly as [LlmException.message] has it. */
    val english: String = render()

    private fun render(): String {
        val v = values
        return when (kind) {
            Kind.PROVIDER_WORDS -> "${v[0]} returned HTTP ${v[1]}: ${v[2]}"
            Kind.BAD_REQUEST -> "${v[0]} couldn't accept the request (HTTP 400)."
            Kind.KEY_REFUSED -> "${v[0]} didn't accept the API key (HTTP 401). Check it in Settings → Models."
            Kind.FORBIDDEN -> "${v[0]} refused the request (HTTP 403). The key may not have access to this model. Check it in Settings → Models."
            Kind.NOT_FOUND -> "${v[0]} couldn't find the model or the address (HTTP 404). Check the base URL and model in Settings → Models."
            Kind.TIMED_OUT -> "The request to ${v[0]} timed out (HTTP 408). Try again."
            Kind.TOO_LARGE -> "The request was too large for ${v[0]} (HTTP 413). Try a shorter message or fewer attachments."
            Kind.RATE_LIMITED -> "${v[0]} is limiting how often requests can be made (HTTP 429). Wait a moment and try again."
            Kind.INTERNAL_ERROR -> "${v[0]} had an internal error (HTTP 500). This is usually temporary. Try again."
            Kind.BAD_GATEWAY -> "${v[0]}'s gateway got no valid answer from the model server (HTTP 502). This is usually temporary. Try again."
            Kind.UNAVAILABLE -> "${v[0]} is unavailable or overloaded right now (HTTP 503). This is usually temporary. Try again in a moment."
            Kind.GATEWAY_TIMEOUT -> "${v[0]}'s gateway timed out (HTTP 504). This is usually temporary. Try again."
            Kind.GATEWAY_UNREACHABLE -> "${v[0]}'s gateway couldn't reach the model server (HTTP ${v[1]}). This is usually temporary. Try again."
            Kind.GATEWAY_CONNECT_TIMEOUT -> "${v[0]}'s gateway timed out connecting to the model server (HTTP 522). This is usually temporary. Try again."
            Kind.GATEWAY_MODEL_TIMEOUT -> "${v[0]}'s gateway timed out waiting for the model (HTTP 524). This is usually temporary. Try again."
            Kind.OVERLOADED -> "${v[0]} is overloaded right now (HTTP 529). Try again in a moment."
            Kind.SERVER_ERROR -> "${v[0]} had a server error (HTTP ${v[1]}). This is usually temporary. Try again."
            Kind.STATUS_TITLE -> "${v[0]} returned HTTP ${v[1]} (${v[2]})."
            Kind.STATUS -> "${v[0]} returned HTTP ${v[1]}."
            Kind.BILLING -> billing(v[0], v[1])
            Kind.BILLING_NO_DETAIL -> billing(v[0], "out of credit")
            Kind.DAILY_QUOTA -> dailyQuota(v[0], v[1])
            Kind.DAILY_QUOTA_NO_DETAIL -> dailyQuota(v[0], "daily quota reached")
            Kind.CHATGPT_LIMIT -> ChatGptErrors.LIMIT_MESSAGE + ChatGptErrors.resetText(resetsAt)
            Kind.CHATGPT_NOT_INCLUDED -> "This ChatGPT plan doesn't include use in other apps like Locus. Check the plan in ChatGPT, or pick another model."
            Kind.CHATGPT_OUT_OF_CREDIT -> "ChatGPT: out of credit for this plan"
            Kind.CHATGPT_USAGE_UNAVAILABLE -> "ChatGPT couldn't check your plan's usage right now. Try again in a moment."
            Kind.CHATGPT_NOT_ELIGIBLE -> "This ChatGPT account or workspace can't use its plan in other apps. ChatGPT plan use needs Plus or Pro."
            Kind.CHATGPT_UNSUPPORTED -> unsupported(null, null)
            Kind.CHATGPT_UNSUPPORTED_PARAM -> unsupported(v[0], null)
            Kind.CHATGPT_UNSUPPORTED_MESSAGE -> unsupported(null, v[0])
            Kind.CHATGPT_UNSUPPORTED_PARAM_MESSAGE -> unsupported(v[0], v[1])
            Kind.CHATGPT_SIGN_IN_REFUSED -> SIGN_IN_REFUSED
            Kind.CHATGPT_SIGN_IN_REFUSED_DETAIL -> "$SIGN_IN_REFUSED (${v[0]})"
            Kind.CHATGPT_ROUTE_NOT_SUPPORTED -> "ChatGPT doesn't allow this request type with a plan sign-in."
            Kind.CHATGPT_OVERLOADED -> "ChatGPT is overloaded right now."
            Kind.CHATGPT_OVERLOADED_MESSAGE -> "ChatGPT is overloaded right now: ${v[0]}"
            Kind.CHATGPT_SERVER_ERROR -> "ChatGPT: the server had an error."
            Kind.CHATGPT_TOO_MANY_REQUESTS -> "ChatGPT: too many requests for now."
            Kind.RESPONSE_FAILED_CODE -> "${v[0]}: the response failed with “${v[1]}”."
            Kind.CHATGPT_REFUSED_DETAIL -> "ChatGPT refused the request: ${v[0]}"
            Kind.CHATGPT_REFUSED -> "ChatGPT refused the request (HTTP 403)."
            Kind.CHATGPT_PLAN_UNAVAILABLE_DETAIL -> "ChatGPT plan use is unavailable right now: ${v[0]}"
            Kind.CHATGPT_PLAN_UNAVAILABLE -> "ChatGPT plan use is unavailable right now (HTTP 503). Try again in a moment."
            Kind.UNEXPLAINED -> "${v[0]} ended the response with an error and gave no reason."
            Kind.UNEXPLAINED_WITH_ID -> "${v[0]} ended the response with an error and gave no reason (request id ${v[1]})."
        }
    }

    private companion object {
        const val SIGN_IN_REFUSED = "ChatGPT didn't accept this sign-in. Sign in again in Settings → Models."

        fun billing(provider: String, detail: String) =
            "$provider: $detail\nThis is a billing or spend limit, not a rate limit, so Locus won't retry. " +
                "Add credits or raise the limit in your $provider account, then try again."

        fun dailyQuota(provider: String, detail: String) = "$provider: $detail\nThis is a daily quota, so retrying now won't help."

        fun unsupported(param: String?, message: String?) =
            "ChatGPT's plan doesn't support part of this request${param?.let { " ($it)" }.orEmpty()}: ${message.orEmpty()}".trimEnd(':', ' ')
    }
}
