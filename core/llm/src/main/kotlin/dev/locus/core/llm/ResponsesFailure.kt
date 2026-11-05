package dev.locus.core.llm

import io.ktor.http.Headers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a failed Responses stream said, read from every place the error can sit. Checked 2026-11-03:
 *
 * - **Platform `error` event** (openai-python `types/responses/response_error_event.py`): `code`,
 *   `message`, `param` at the top level of the event, beside `type` and `sequence_number`.
 * - **ChatGPT backend `error` event** (openai/codex `codex-api/src/sse/responses.rs` reads `event.error`;
 *   `endpoint/responses_websocket.rs` tests): nested, `{"type":"error","status":429,"error":{"type":
 *   "usage_limit_reached","message":…,"plan_type":"plus","resets_at":1738888888},"headers":{…}}`.
 *   The error may carry only a `type`, with no `code` and no `message`.
 * - **`response.failed`** (openai-python `response_failed_event.py`, `response_error.py`): the whole
 *   response, its `error` holding `code`, `message`, `param`, `type`; on the ChatGPT backend also
 *   `plan_type`, `resets_at` and `headers` (codex `codex-api/src/sse/responses_error.rs`). Codex treats
 *   a `response.failed` without a readable error as a retryable stream error ("response.failed event
 *   received").
 * - **`response.incomplete`** (openai-python `response_incomplete_event.py`, `response.py`): the
 *   response, with `incomplete_details.reason` one of `max_output_tokens`, `max_messages`,
 *   `content_filter` or `steered` (WebSocket only).
 * - **Request ids** are response headers: `x-request-id`, else `x-oai-request-id`, else Cloudflare's
 *   `cf-ray` (codex `codex-api/src/api_bridge.rs`, `extract_request_tracking_id`); the response's own
 *   id is `response.id` on `response.created` and on the terminal event.
 *
 * The ChatGPT plan's errors page (developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery)
 * documents codes and HTTP statuses but no streaming shapes, so both are read.
 */
internal data class ResponsesError(
    val code: String?,
    val type: String?,
    val message: String?,
    val param: String?,
    /** The HTTP status the backend put on a wrapped `error` event. */
    val status: Int?,
    /** When a usage limit lifts (epoch seconds), from `resets_at`. */
    val resetsAt: Long?,
) {
    /** The code that classifies it: `code`, else `type` (the ChatGPT backend's usage errors have only a type). */
    val codeOrType: String? get() = code ?: type?.takeUnless { it == "error" }

    /** Whether the provider said nothing at all about why: no code, no type, no message. */
    val unexplained: Boolean get() = codeOrType == null && message == null

    companion object {
        /** The error of a `response.failed` event. */
        fun ofFailed(event: JsonObject): ResponsesError {
            val response = event["response"] as? JsonObject
            val error = response?.get("error") as? JsonObject ?: event["error"] as? JsonObject
            return of(error, fallback = event, status = null)
        }

        /** The error of an `error` event, flat (platform) or nested under `error` (ChatGPT backend). */
        fun ofErrorEvent(event: JsonObject): ResponsesError {
            val nested = event["error"] as? JsonObject
            val status = (event["status"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            return of(nested ?: event, fallback = event, status = status)
        }

        private fun of(error: JsonObject?, fallback: JsonObject, status: Int?): ResponsesError {
            fun text(key: String) = (error?.get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            fun outer(key: String) = (fallback[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val type = text("type")?.takeUnless { error === fallback && it == "error" }
            return ResponsesError(
                code = text("code") ?: (if (error !== fallback) outer("code") else null),
                type = type,
                message = text("message") ?: text("detail") ?: (if (error !== fallback) outer("message") else null),
                param = text("param"),
                status = status,
                resetsAt = (error?.get("resets_at") as? JsonPrimitive)?.longOrNull,
            )
        }
    }
}

/** Why a `response.incomplete` stopped short, or null when it gave no reason. */
internal fun incompleteReason(response: JsonObject?): String? =
    ((response?.get("incomplete_details") as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull

/** The request's tracking id from its response headers ([ResponsesError] lists the sources). */
internal fun requestIdOf(headers: Headers): String? =
    sequenceOf("x-request-id", "x-oai-request-id", "cf-ray").firstNotNullOfOrNull { headers[it]?.trim()?.takeIf(String::isNotEmpty) }?.take(MAX_ID_CHARS)

/** A response id (`resp_…`) from an event's `response`. */
internal fun responseIdOf(event: JsonObject): String? =
    ((event["response"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.take(MAX_ID_CHARS)

private const val MAX_ID_CHARS = 120

/**
 * A copy of a failure event that is safe to keep and show ([LlmException.rawBody]): only the parts that
 * describe the failure, never what the model wrote or what Locus sent.
 *
 * - The event's own fields (`type`, `sequence_number`, `code`, `message`, `param`, `status`, `error`).
 * - Of `response`, only its metadata: id, status, model, error, `incomplete_details`, usage. Its
 *   `output` (the model's words and encrypted reasoning), `instructions` (the system prompt), `tools`
 *   and any echoed input are dropped.
 * - Headers the backend echoed, minus anything naming a credential or cookie.
 *
 * Every string then goes through [FailureText.scrub] (tokens, keys, long base64), and the whole is cut
 * to [FailureText.MAX_CHARS].
 */
internal fun scrubbedFailureEvent(event: JsonObject): String {
    val kept = JsonObject(
        event.filterKeys { it in EVENT_KEYS }.mapValues { (key, value) ->
            when (key) {
                "response" -> (value as? JsonObject)?.let { r -> JsonObject(r.filterKeys { it in RESPONSE_KEYS }) } ?: value
                "headers" -> safeHeaders(value)
                "error" -> (value as? JsonObject)?.let { e -> JsonObject(e.mapValues { (k, v) -> if (k == "headers") safeHeaders(v) else v }) } ?: value
                else -> value
            }
        },
    )
    return FailureText.cap(FailureText.scrub(scrubStrings(kept).toString()))
}

private fun safeHeaders(value: JsonElement): JsonElement =
    (value as? JsonObject)?.let { h -> JsonObject(h.filterKeys { k -> SECRET_HEADER.none { it in k.lowercase() } }) } ?: value

private fun scrubStrings(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (k, v) -> if (k == "encrypted_content") JsonPrimitive("[encrypted]") else scrubStrings(v) })
    is JsonArray -> JsonArray(element.map(::scrubStrings))
    is JsonPrimitive -> if (element.isString) JsonPrimitive(FailureText.scrub(element.content)) else element
}

private val EVENT_KEYS = setOf("type", "sequence_number", "code", "message", "param", "status", "error", "headers", "response", "detail")
private val RESPONSE_KEYS = setOf("id", "object", "status", "created_at", "completed_at", "model", "error", "incomplete_details", "usage", "service_tier", "end_turn")
private val SECRET_HEADER = listOf("authorization", "cookie", "token", "api-key", "apikey", "secret", "session")

/**
 * Credential patterns taken out of failure text in core/llm, before anything is kept. Diagnostics and
 * the details sheet scrub again with the vault's values (SecretScrubber), so this is the first layer.
 */
object FailureText {
    /** What a kept failure body is cut to. */
    const val MAX_CHARS = 4_000

    private val PATTERNS = listOf(
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""") to "Bearer [secret]",
        // JWTs: ChatGPT sign-in access and id tokens.
        Regex("""\beyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}""") to "[token]",
        Regex("""\bsk-[A-Za-z0-9_-]{12,}""") to "[secret]",
        Regex("""(?i)("?(?:api[_-]?key|access_token|refresh_token|id_token|authorization|password)"?\s*[:=]\s*"?)[^"\s,}]{4,}""") to "$1[secret]",
        // Base64 blobs (images, encrypted content) say nothing about the failure.
        Regex("""[A-Za-z0-9+/_-]{200,}={0,2}""") to "[data]",
    )

    /** [text] with tokens, keys and long base64 replaced. */
    fun scrub(text: String): String = PATTERNS.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /** [text] cut to [MAX_CHARS], saying so. */
    fun cap(text: String): String = if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "… [cut at $MAX_CHARS characters]"
}
