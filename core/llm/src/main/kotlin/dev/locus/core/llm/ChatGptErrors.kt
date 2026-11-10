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
    ): LlmException {
        fun text(kind: LlmErrorText.Kind, vararg values: String) = LlmErrorText(kind, values.toList())
        fun failure(
            text: LlmErrorText,
            status: Int?,
            retryable: Boolean = false,
            kind: LlmFailure = if (retryable) LlmFailure.TRANSIENT else LlmFailure.OTHER,
            errorCode: String? = code,
        ) = LlmException(text.english, status, retryable, kind = kind, errorCode = errorCode, text = text)
        return when (code) {
            in LIMIT_CODES -> failure(LlmErrorText(LlmErrorText.Kind.CHATGPT_LIMIT, resetsAt = resetsAt), status ?: 429, kind = LlmFailure.QUOTA)
            "usage_not_included" -> failure(text(LlmErrorText.Kind.CHATGPT_NOT_INCLUDED), status ?: 403, kind = LlmFailure.QUOTA)
            in BILLING_CODES -> if (message != null) {
                LlmException("ChatGPT: $message", status ?: 429, kind = LlmFailure.QUOTA, errorCode = code)
            } else {
                failure(text(LlmErrorText.Kind.CHATGPT_OUT_OF_CREDIT), status ?: 429, kind = LlmFailure.QUOTA)
            }
            USAGE_UNAVAILABLE, "subscription_sharing_user_unavailable" ->
                failure(text(LlmErrorText.Kind.CHATGPT_USAGE_UNAVAILABLE), status ?: 503, retryable = true)
            "subscription_sharing_user_not_eligible" -> failure(text(LlmErrorText.Kind.CHATGPT_NOT_ELIGIBLE), status ?: 403)
            "subscription_sharing_unsupported_capability" -> failure(
                when {
                    param != null && !message.isNullOrEmpty() -> text(LlmErrorText.Kind.CHATGPT_UNSUPPORTED_PARAM_MESSAGE, param, message)
                    param != null -> text(LlmErrorText.Kind.CHATGPT_UNSUPPORTED_PARAM, param)
                    !message.isNullOrEmpty() -> text(LlmErrorText.Kind.CHATGPT_UNSUPPORTED_MESSAGE, message)
                    else -> text(LlmErrorText.Kind.CHATGPT_UNSUPPORTED)
                },
                status ?: 400,
            )
            "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
                failure(text(LlmErrorText.Kind.CHATGPT_SIGN_IN_REFUSED), status ?: 401, kind = LlmFailure.AUTH)
            "subscription_sharing_route_not_supported" -> failure(text(LlmErrorText.Kind.CHATGPT_ROUTE_NOT_SUPPORTED), status ?: 403)
            "server_is_overloaded" -> failure(
                message?.let { text(LlmErrorText.Kind.CHATGPT_OVERLOADED_MESSAGE, it) } ?: text(LlmErrorText.Kind.CHATGPT_OVERLOADED),
                status,
                retryable = true,
                kind = LlmFailure.OVERLOADED,
            )
            "server_error", "rate_limit_exceeded", "slow_down" -> {
                val kind = if (code == "server_error") LlmFailure.TRANSIENT else LlmFailure.RATE_LIMIT
                if (message != null) {
                    LlmException("ChatGPT: $message", status, retryable = true, kind = kind, errorCode = code)
                } else {
                    failure(text(if (code == "server_error") LlmErrorText.Kind.CHATGPT_SERVER_ERROR else LlmErrorText.Kind.CHATGPT_TOO_MANY_REQUESTS), status, retryable = true, kind = kind)
                }
            }
            // Nothing to go on: say so, with the request id for a report. Retried, as Codex retries it; one that
            // comes back the same after Try again is treated as caused by the request (RepeatFailure).
            null -> if (message == null) {
                failure(unexplained("ChatGPT", requestId), status, retryable = true, kind = LlmFailure.TRANSIENT, errorCode = null)
            } else {
                LlmException("ChatGPT: $message", status, kind = if (ContextOverflow.matches(null, message)) LlmFailure.CONTEXT_OVERFLOW else LlmFailure.OTHER)
            }
            else -> {
                val kind = if (ContextOverflow.matches(code, message)) LlmFailure.CONTEXT_OVERFLOW else LlmFailure.OTHER
                if (message != null) {
                    LlmException("ChatGPT: $message", status, kind = kind, errorCode = code)
                } else {
                    failure(text(LlmErrorText.Kind.RESPONSE_FAILED_CODE, "ChatGPT", code), status, kind = kind)
                }
            }
        }
    }

    /** What the chat says when a provider ended a response with an error and no reason at all. */
    fun unexplainedText(provider: String, requestId: String?): String = unexplained(provider, requestId).english

    /** [unexplainedText] as data, for the chat to word in the user's language. */
    fun unexplained(provider: String, requestId: String?): LlmErrorText =
        if (requestId != null) LlmErrorText(LlmErrorText.Kind.UNEXPLAINED_WITH_ID, listOf(provider, requestId)) else LlmErrorText(LlmErrorText.Kind.UNEXPLAINED, listOf(provider))

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
            return LlmException(e.message.orEmpty(), e.httpStatus, e.retryable, kind = e.kind, rawBody = body.takeIf { it.isNotBlank() }, errorCode = code, text = e.text)
        }
        // The server's words when it gave some; never a gateway's HTML page (ProviderErrorText).
        val detail = ProviderErrorText.providerWords(body)?.take(300)
        val raw = body.takeIf { it.isNotBlank() }
        fun text(kind: LlmErrorText.Kind, vararg values: String) = LlmErrorText(kind, values.toList())
        val (text, kind) = when (status) {
            429 -> text(LlmErrorText.Kind.CHATGPT_LIMIT) to LlmFailure.QUOTA
            401 -> (detail?.let { text(LlmErrorText.Kind.CHATGPT_SIGN_IN_REFUSED_DETAIL, it) } ?: text(LlmErrorText.Kind.CHATGPT_SIGN_IN_REFUSED)) to LlmFailure.AUTH
            403 -> (detail?.let { text(LlmErrorText.Kind.CHATGPT_REFUSED_DETAIL, it) } ?: text(LlmErrorText.Kind.CHATGPT_REFUSED)) to LlmFailure.OTHER
            503 -> (detail?.let { text(LlmErrorText.Kind.CHATGPT_PLAN_UNAVAILABLE_DETAIL, it) } ?: text(LlmErrorText.Kind.CHATGPT_PLAN_UNAVAILABLE)) to LlmFailure.TRANSIENT
            else -> ProviderErrorText.messageText("ChatGPT", status, body) to if (isRetryableStatus(status)) LlmFailure.TRANSIENT else LlmFailure.OTHER
        }
        val retryable = if (status == 503) true else if (status in setOf(429, 401, 403)) false else isRetryableStatus(status)
        return LlmException(text.english, status, retryable, kind = kind, rawBody = raw, text = text)
    }
}
