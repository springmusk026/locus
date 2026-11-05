package dev.locus.core.llm

import kotlinx.serialization.json.JsonObject

/** Hands out a current bearer token per request, refreshing it when needed (ChatGPT sign-in). */
fun interface BearerSource {
    suspend fun token(): String
}

/**
 * Turns ChatGPT-plan failures into messages a user can act on. Codes and recovery follow
 * developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery: a usage limit points to
 * ChatGPT Settings → Usage and is not retried; "unavailable" codes are retried with the agent's
 * bounded backoff; anything about the request itself is not retried.
 */
object ChatGptErrors {
    const val USAGE_URL = "https://chatgpt.com/settings/usage"

    const val USAGE_LIMIT = "subscription_sharing_usage_limit_exceeded"
    const val USAGE_UNAVAILABLE = "subscription_sharing_usage_unavailable"

    val LIMIT_MESSAGE = "You've reached your ChatGPT usage limit for Locus. Review it or raise the limit in ChatGPT Settings → Usage ($USAGE_URL)."

    /** Usage-limit codes and types: the plan's own (errors-and-recovery page) and the ChatGPT backend's (codex `api_bridge.rs`). */
    private val LIMIT_CODES = setOf(
        USAGE_LIMIT, "usage_limit_reached", "workspace_owner_usage_limit_reached", "workspace_member_usage_limit_reached",
        "workspace_owner_credits_depleted", "workspace_member_credits_depleted",
    )

    private val BILLING_CODES = setOf("insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded")

    /**
     * A Responses-layer error (`response.failed`, an `error` event, or an `error` object in a non-2xx
     * body). [code] is the error's `code`, else its `type`. [resetsAt] is the backend's `resets_at`
     * (epoch seconds) on a usage limit. [requestId] goes into the words when the error says nothing else.
     */
    fun fromCode(
        code: String?,
        message: String?,
        status: Int? = null,
        param: String? = null,
        resetsAt: Long? = null,
        requestId: String? = null,
    ): LlmException = when (code) {
        in LIMIT_CODES -> LlmException(LIMIT_MESSAGE + resetText(resetsAt), status ?: 429, kind = LlmFailure.QUOTA, errorCode = code)
        "usage_not_included" -> LlmException(
            "This ChatGPT plan doesn't include use in other apps like Locus. Check the plan in ChatGPT, or pick another model.",
            status ?: 403,
            kind = LlmFailure.QUOTA,
            errorCode = code,
        )
        in BILLING_CODES -> LlmException("ChatGPT: ${message ?: "out of credit for this plan"}", status ?: 429, kind = LlmFailure.QUOTA, errorCode = code)
        USAGE_UNAVAILABLE, "subscription_sharing_user_unavailable" ->
            LlmException("ChatGPT couldn't check your plan's usage right now. Try again in a moment.", status ?: 503, retryable = true, errorCode = code)
        "subscription_sharing_user_not_eligible" ->
            LlmException("This ChatGPT account or workspace can't use its plan in other apps. ChatGPT plan use needs Plus or Pro.", status ?: 403, errorCode = code)
        "subscription_sharing_unsupported_capability" -> LlmException(
            "ChatGPT's plan doesn't support part of this request${param?.let { " ($it)" }.orEmpty()}: ${message.orEmpty()}".trimEnd(':', ' '),
            status ?: 400,
            errorCode = code,
        )
        "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
            LlmException("ChatGPT didn't accept this sign-in. Sign in again in Settings → Models.", status ?: 401, kind = LlmFailure.AUTH, errorCode = code)
        "subscription_sharing_route_not_supported" -> LlmException("ChatGPT doesn't allow this request type with a plan sign-in.", status ?: 403, errorCode = code)
        "server_is_overloaded" ->
            LlmException("ChatGPT is overloaded right now" + (message?.let { ": $it" } ?: "."), status, retryable = true, kind = LlmFailure.OVERLOADED, errorCode = code)
        "server_error", "rate_limit_exceeded", "slow_down" -> LlmException(
            "ChatGPT: ${message ?: if (code == "server_error") "the server had an error." else "too many requests for now."}",
            status,
            retryable = true,
            kind = if (code == "server_error") LlmFailure.TRANSIENT else LlmFailure.RATE_LIMIT,
            errorCode = code,
        )
        // Nothing to go on: say so, with the request id for a report. Retried, as Codex retries it; one that
        // comes back the same after Try again is treated as caused by the request (RepeatFailure).
        null -> if (message == null) {
            LlmException(unexplainedText("ChatGPT", requestId), status, retryable = true, kind = LlmFailure.TRANSIENT)
        } else {
            LlmException("ChatGPT: $message", status, kind = if (ContextOverflow.matches(null, message)) LlmFailure.CONTEXT_OVERFLOW else LlmFailure.OTHER)
        }
        else -> LlmException(
            "ChatGPT: ${message ?: "the response failed with “$code”."}",
            status,
            kind = if (ContextOverflow.matches(code, message)) LlmFailure.CONTEXT_OVERFLOW else LlmFailure.OTHER,
            errorCode = code,
        )
    }

    /** What the chat says when a provider ended a response with an error and no reason at all. */
    fun unexplainedText(provider: String, requestId: String?): String =
        "$provider ended the response with an error and gave no reason" + (requestId?.let { " (request id $it)" }.orEmpty()) + "."

    /** " It resets at 14:30." (a date too when it's not today) for a usage limit with a known reset time. */
    internal fun resetText(resetsAt: Long?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String {
        val at = resetsAt?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochSecond(it) } ?: return ""
        val time = at.atZone(zone)
        val sameDay = time.toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pattern = if (sameDay) "HH:mm" else "d MMM, HH:mm"
        return " It resets at ${time.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH))}."
    }

    /** A non-2xx answer before the stream opened; the body may be an API `error` object or `{"detail": …}`. */
    fun fromHttp(status: Int, body: String): LlmException {
        val obj = runCatching { LlmJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = obj?.get("error") as? JsonObject
        // `code`, else the backend's `type` for its usage errors (`usage_limit_reached`, codex `api_bridge.rs`).
        val code = error?.string("code") ?: error?.string("type")?.takeIf { it in LIMIT_CODES || it == "usage_not_included" }
        if (code != null) {
            val resetsAt = error?.get("resets_at").stringOrNull()?.toLongOrNull()
            val e = fromCode(code, error?.string("message"), status, error?.string("param"), resetsAt)
            return LlmException(e.message.orEmpty(), e.httpStatus, e.retryable, kind = e.kind, rawBody = body.takeIf { it.isNotBlank() }, errorCode = code)
        }
        // The server's words when it gave some; never a gateway's HTML page (ProviderErrorText).
        val detail = ProviderErrorText.providerWords(body)?.take(300)
        val raw = body.takeIf { it.isNotBlank() }
        return when (status) {
            429 -> LlmException(LIMIT_MESSAGE, 429, kind = LlmFailure.QUOTA, rawBody = raw)
            401 -> LlmException("ChatGPT didn't accept this sign-in. Sign in again in Settings → Models." + (detail?.let { " ($it)" } ?: ""), 401, kind = LlmFailure.AUTH, rawBody = raw)
            403 -> LlmException(detail?.let { "ChatGPT refused the request: $it" } ?: "ChatGPT refused the request (HTTP 403).", 403, rawBody = raw)
            503 -> LlmException(
                detail?.let { "ChatGPT plan use is unavailable right now: $it" } ?: "ChatGPT plan use is unavailable right now (HTTP 503). Try again in a moment.",
                503,
                retryable = true,
                rawBody = raw,
            )
            else -> LlmException(ProviderErrorText.message("ChatGPT", status, body), status, isRetryableStatus(status), rawBody = raw)
        }
    }
}
