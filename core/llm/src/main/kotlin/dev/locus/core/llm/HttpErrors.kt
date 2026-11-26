package dev.locus.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

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
 * The message is [ProviderErrorText.message]: the provider's own words, or the status in plain words
 * when the body is a gateway's HTML page, a long dump or empty. The body itself is kept as
 * [LlmException.rawBody] for Diagnostics.
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
        val detail = error?.string("message")?.takeIf { it.isNotBlank() } ?: codes.firstOrNull()
        val text = if (detail != null) LlmErrorText(LlmErrorText.Kind.BILLING, listOf(provider, detail)) else LlmErrorText(LlmErrorText.Kind.BILLING_NO_DETAIL, listOf(provider))
        return LlmException(
            text.english,
            status,
            retryable = false,
            kind = LlmFailure.QUOTA,
            text = text,
        )
    }
    if (status == 429 && dailyQuota(error)) {
        val detail = error?.string("message")?.takeIf { it.isNotBlank() }
        val text = if (detail != null) LlmErrorText(LlmErrorText.Kind.DAILY_QUOTA, listOf(provider, detail)) else LlmErrorText(LlmErrorText.Kind.DAILY_QUOTA_NO_DETAIL, listOf(provider))
        return LlmException(text.english, status, retryable = false, kind = LlmFailure.QUOTA, retryAfterMs = wait, text = text)
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
    // The provider's own message when it gave one; otherwise the status in plain words. A gateway's HTML
    // page or a long dump never reaches the chat; the whole body stays on the exception for Diagnostics.
    val text = ProviderErrorText.messageText(provider, status, body)
    return LlmException(
        text.english,
        status,
        isRetryableStatus(status) || status == 498,
        kind = kind,
        retryAfterMs = wait,
        rawBody = body.takeIf { it.isNotBlank() },
        text = text,
    )
}

/** The `error` object of an error body, also when it comes wrapped in an array (Gemini's OpenAI-compatible endpoint). */
private fun errorObject(body: String): JsonObject? = runCatching {
    var root: JsonElement = LlmJson.parseToJsonElement(body)
    if (root is JsonArray) root = root.firstOrNull() ?: return null
    (root as? JsonObject)?.get("error") as? JsonObject
}.getOrNull()

/**
 * Whether a 400 or 422 [body] refuses the request's reasoning settings themselves (`reasoning_effort`,
 * `reasoning.effort`, `reasoning.summary`, the `include` of encrypted reasoning): the one case where the
 * adapters ask once more without them. Read from the error's `param` when it names one (OpenAI's errors
 * do); else from the message naming the field. An error that only mentions reasoning, such as one about a
 * replayed `rs_…` item ("Item 'rs_…' of type 'reasoning' was provided without its required following
 * item"), is not one: the same request without the settings would fail again, and if it didn't, the
 * encrypted reasoning later turns replay would be lost.
 */
internal fun refusesReasoningField(body: String): Boolean {
    val error = errorObject(body)
    error?.string("param")?.trim()?.takeIf { it.isNotEmpty() }?.let { param ->
        return param == "reasoning" || param.startsWith("reasoning.") || param.startsWith("reasoning_") || param == "include"
    }
    val text = error?.string("message") ?: body
    if (REASONING_ITEM.containsMatchIn(text)) return false
    return REASONING_FIELD.containsMatchIn(text)
}

/** A reasoning item's id (`rs_…`): an error naming one is about the history, not the request's settings. */
private val REASONING_ITEM = Regex("""\brs_[A-Za-z0-9]""")

/** A reasoning setting by its field name, or `reasoning` named as a parameter or field ("Unknown name \"reasoning\""). */
private val REASONING_FIELD = Regex(
    """reasoning(_effort|\.effort|\.summary|\.encrypted_content)|\b(parameter|field|argument|name|property|key)s?\b[^A-Za-z0-9]{0,4}reasoning\b""",
    RegexOption.IGNORE_CASE,
)

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
 * seconds or an HTTP date (RFC 9110 §10.2.3) in any of the three forms a recipient must accept
 * (§5.6.7): IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`), RFC 850 (`Sunday, 06-Nov-94 08:49:37 GMT`)
 * and asctime (`Sun Nov  6 08:49:37 1994`). Null when neither is present or readable.
 */
fun retryAfterMs(retryAfterMsHeader: String?, retryAfterHeader: String?, now: () -> Long = System::currentTimeMillis): Long? {
    retryAfterMsHeader?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return it.toLong() }
    val value = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toDoubleOrNull()?.let { return if (it >= 0) (it * 1000).toLong() else null }
    return httpDateMs(value, now)?.let { (it - now()).coerceAtLeast(0) }
}

/** [value] as an HTTP date (RFC 9110 §5.6.7), in epoch milliseconds, or null. */
private fun httpDateMs(value: String, now: () -> Long): Long? {
    runCatching { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
    // RFC 850's two-digit year: "a timestamp that appears to be more than 50 years in the future" is in the past (§5.6.7).
    val base = java.time.Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).toLocalDate().minusYears(49)
    val rfc850 = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("EEEE, dd-MMM-").appendValueReduced(ChronoField.YEAR, 2, 2, base).appendPattern(" HH:mm:ss 'GMT'")
        .toFormatter(Locale.US).withZone(ZoneOffset.UTC)
    runCatching { return ZonedDateTime.parse(value, rfc850).toInstant().toEpochMilli() }
    // asctime: the day of the month is space-padded, and the time is GMT though no zone is written.
    val asctime = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("EEE MMM ppd HH:mm:ss yyyy").toFormatter(Locale.US)
    runCatching { return LocalDateTime.parse(value, asctime).toInstant(ZoneOffset.UTC).toEpochMilli() }
    return null
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
