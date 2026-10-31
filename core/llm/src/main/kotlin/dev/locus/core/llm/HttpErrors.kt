package dev.locus.core.llm

import kotlinx.serialization.json.JsonObject

/**
 * Turns a failed HTTP response into an [LlmException], deciding whether it's worth retrying.
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
 */
internal fun httpError(provider: String, status: Int, body: String): LlmException {
    val error = runCatching { (LlmJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
    val codes = listOfNotNull(
        error?.string("code"),
        error?.string("type"),
        (error?.get("details") as? JsonObject)?.string("error_code"),
    )
    if (status == 429 && codes.any { it in BILLING_LIMIT_CODES }) {
        val detail = error?.string("message")?.takeIf { it.isNotBlank() } ?: codes.first()
        return LlmException(
            "$provider: $detail\nThis is a billing or spend limit, not a rate limit, so Locus won't retry. " +
                "Add credits or raise the limit in your $provider account, then try again.",
            status,
            retryable = false,
        )
    }
    return LlmException("$provider returned HTTP $status: $body", status, isRetryableStatus(status))
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
