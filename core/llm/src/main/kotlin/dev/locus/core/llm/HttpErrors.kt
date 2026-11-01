package dev.locus.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Turns a failed HTTP response into an [LlmException], deciding whether it's worth retrying and
 * what kind of failure it is ([LlmFailure]), for key rotation and model fallback.
 *
 * A 429 is usually a rate limit (retry with backoff), but providers also use it for limits that
 * retrying can't lift:
 * - OpenAI: `credit_balance_exhausted`, `organization_spend_limit_exceeded`,
 *   `project_spend_limit_exceeded`, `organization_usage_limit_exceeded`, and `insufficient_quota`
 *   as the billing `error.type`. "Retrying billing, spend, or quota errors won't restore API
 *   access" (https://developers.openai.com/api/docs/guides/error-codes). `rate_limit_exceeded` and
 *   `slow_down` stay retryable, as do 500 and 503 (`server_is_overloaded`).
 * - Anthropic: the monthly spend cap is a 429 `rate_limit_error` with
 *   `error.details.error_code: "enforced_spend_limit_reached"` and no `retry-after`; it "fails
 *   until access resumes" (https://platform.claude.com/docs/en/api/rate-limits#reaching-your-spend-cap).
 *   An empty balance is a 402 `billing_error`, an overloaded API a 529 `overloaded_error`
 *   (https://platform.claude.com/docs/en/api/errors).
 * - Gemini: every limit is a 429 `RESOURCE_EXHAUSTED`; a per-day quota (`QuotaFailure` violation
 *   whose `quotaId` names a day) won't lift by retrying, a per-minute one will, after the
 *   `RetryInfo.retryDelay` it gives (https://ai.google.dev/gemini-api/docs/troubleshooting). The
 *   OpenAI-compatible endpoint may wrap the same body in a one-element array.
 * - OpenRouter, DeepSeek: 402 means the account is out of credit.
 *
 * [retryAfterMs] is what the response's headers asked for ([retryAfterMs] parses them); Gemini's
 * `retryDelay` in the body is used when the headers say nothing.
 */
internal fun httpError(provider: String, status: Int, body: String, retryAfterMs: Long? = null): LlmException {
    val error = errorObject(body)
    val codes = listOfNotNull(
        error?.string("code"),
        error?.string("type"),
        error?.string("status"),
        (error?.get("details") as? JsonObject)?.string("error_code"),
    )
    val wait = retryAfterMs ?: retryDelayMs(error)
    // Anthropic answers a spend limit the user set with a 400 whose message names it (platform.claude.com/docs/en/api/errors).
    val usageLimit = status == 400 && error?.string("message")?.startsWith("You have reached your specified API usage limits") == true
    if (status == 429 && codes.any { it in BILLING_LIMIT_CODES } || status == 402 || "billing_error" in codes || usageLimit) {
        val detail = error?.string("message")?.takeIf { it.isNotBlank() } ?: codes.firstOrNull() ?: "out of credit"
        return LlmException(
            "$provider: $detail\nThis is a billing or spend limit, not a rate limit, so Locus won't retry. " +
                "Add credits or raise the limit in your $provider account, then try again.",
            status,
            retryable = false,
            kind = LlmFailure.QUOTA,
        )
    }
    if (status == 429 && dailyQuota(error)) {
        val detail = error?.string("message")?.takeIf { it.isNotBlank() } ?: "daily quota reached"
        return LlmException("$provider: $detail\nThis is a daily quota, so retrying now won't help.", status, retryable = false, kind = LlmFailure.QUOTA, retryAfterMs = wait)
    }
    // A prompt too long for the window: a 4xx (Anthropic and OpenAI 400, Cloudflare 413) whose code,
    // OpenRouter's `metadata.error_type`, or text says so (see [ContextOverflow]). Ollama's body is
    // `{"error": "…"}` with a string, so the whole body is checked when there's no error object.
    val overflow = status in 400..499 && status != 429 && (
        codes.any { it.lowercase() in ContextOverflow.CODES } ||
            ((error?.get("metadata") as? JsonObject)?.string("error_type")?.let { it in ContextOverflow.CODES } == true) ||
            ContextOverflow.matches(null, error?.string("message") ?: body)
        )
    val kind = when {
        overflow -> LlmFailure.CONTEXT_OVERFLOW
        status == 429 -> LlmFailure.RATE_LIMIT
        status == 401 || status == 403 -> LlmFailure.AUTH
        // 498: Groq's flex tier is at capacity (console.groq.com/docs/errors).
        status == 529 || status == 503 || status == 498 || "overloaded_error" in codes || "server_is_overloaded" in codes -> LlmFailure.OVERLOADED
        isRetryableStatus(status) -> LlmFailure.TRANSIENT
        else -> LlmFailure.OTHER
    }
    return LlmException("$provider returned HTTP $status: $body", status, isRetryableStatus(status) || status == 498, kind = kind, retryAfterMs = wait)
}

/** The `error` object of an error body, also when it comes wrapped in an array (Gemini's OpenAI-compatible endpoint). */
private fun errorObject(body: String): JsonObject? = runCatching {
    var root: JsonElement = LlmJson.parseToJsonElement(body)
    if (root is JsonArray) root = root.firstOrNull() ?: return null
    (root as? JsonObject)?.get("error") as? JsonObject
}.getOrNull()

/** Gemini's `google.rpc.RetryInfo` detail: `"retryDelay": "37s"` (or `"0.5s"`). */
private fun retryDelayMs(error: JsonObject?): Long? {
    val details = error?.get("details") as? JsonArray ?: return null
    return details.firstNotNullOfOrNull { d ->
        val o = d as? JsonObject ?: return@firstNotNullOfOrNull null
        if (o.string("@type")?.endsWith("google.rpc.RetryInfo") != true) return@firstNotNullOfOrNull null
        o.string("retryDelay")?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }
}

/** A Gemini `QuotaFailure` whose violated quota is counted per day: it lifts tomorrow, not in a minute. */
private fun dailyQuota(error: JsonObject?): Boolean {
    val details = error?.get("details") as? JsonArray ?: return false
    return details.any { d ->
        val o = d as? JsonObject ?: return@any false
        if (o.string("@type")?.endsWith("google.rpc.QuotaFailure") != true) return@any false
        (o["violations"] as? JsonArray).orEmpty().any { v -> (v as? JsonObject)?.string("quotaId")?.let { q -> q.contains("PerDay", ignoreCase = true) || q.contains("Daily", ignoreCase = true) } == true }
    }
}

/**
 * The wait a response asked for, in milliseconds: `retry-after-ms` (OpenAI), else `retry-after` as
 * seconds or an HTTP date (RFC 9110 §10.2.3). Null when neither is present or readable.
 */
fun retryAfterMs(retryAfterMsHeader: String?, retryAfterHeader: String?, now: () -> Long = System::currentTimeMillis): Long? {
    retryAfterMsHeader?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return it.toLong() }
    val value = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toDoubleOrNull()?.let { return if (it >= 0) (it * 1000).toLong() else null }
    return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now() }
        .getOrNull()?.coerceAtLeast(0)
}

private val BILLING_LIMIT_CODES = setOf(
    // OpenAI
    "insufficient_quota",
    "credit_balance_exhausted",
    "organization_spend_limit_exceeded",
    "project_spend_limit_exceeded",
    "organization_usage_limit_exceeded",
    // Anthropic
    "enforced_spend_limit_reached",
)
