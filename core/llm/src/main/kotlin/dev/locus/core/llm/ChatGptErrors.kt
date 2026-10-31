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

    /** A Responses-layer error (`response.failed`, or an `error` object in a non-2xx body). */
    fun fromCode(code: String?, message: String?, status: Int? = null, param: String? = null): LlmException = when (code) {
        USAGE_LIMIT -> LlmException(LIMIT_MESSAGE, status ?: 429)
        USAGE_UNAVAILABLE, "subscription_sharing_user_unavailable" ->
            LlmException("ChatGPT couldn't check your plan's usage right now. Try again in a moment.", status ?: 503, retryable = true)
        "subscription_sharing_user_not_eligible" ->
            LlmException("This ChatGPT account or workspace can't use its plan in other apps. ChatGPT plan use needs Plus or Pro.", status ?: 403)
        "subscription_sharing_unsupported_capability" ->
            LlmException("ChatGPT's plan doesn't support part of this request${param?.let { " ($it)" }.orEmpty()}: ${message.orEmpty()}".trimEnd(':', ' '), status ?: 400)
        "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
            LlmException("ChatGPT didn't accept this sign-in. Sign in again in Settings → Models.", status ?: 401)
        "subscription_sharing_route_not_supported" -> LlmException("ChatGPT doesn't allow this request type with a plan sign-in.", status ?: 403)
        "server_error", "rate_limit_exceeded" -> LlmException("ChatGPT: ${message ?: "the response failed"}", status, retryable = true)
        else -> LlmException("ChatGPT: ${message ?: code ?: "the response failed"}", status)
    }

    /** A non-2xx answer before the stream opened; the body may be an API `error` object or `{"detail": …}`. */
    fun fromHttp(status: Int, body: String): LlmException {
        val obj = runCatching { LlmJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = obj?.get("error") as? JsonObject
        val code = error?.string("code")
        if (code != null) return fromCode(code, error.string("message"), status, error.string("param"))
        val detail = (error?.string("message") ?: obj?.string("detail") ?: body).take(300)
        return when (status) {
            429 -> LlmException(LIMIT_MESSAGE, 429)
            401 -> LlmException("ChatGPT didn't accept this sign-in. Sign in again in Settings → Models. ($detail)", 401)
            403 -> LlmException("ChatGPT refused the request: $detail", 403)
            503 -> LlmException("ChatGPT plan use is unavailable right now: $detail", 503, retryable = true)
            else -> LlmException("ChatGPT returned HTTP $status: $detail", status, isRetryableStatus(status))
        }
    }
}
