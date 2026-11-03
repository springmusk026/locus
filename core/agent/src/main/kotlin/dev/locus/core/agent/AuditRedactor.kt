package dev.locus.core.agent

import dev.locus.core.model.AuditEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Activity log as the user sees it: an [AuditEntry]'s arguments and result preview with secrets
 * taken out, each replaced by a label saying what kind of value was there (`[redacted: password]`).
 * The log keeps the call as it was made, and what the model gets is never touched; this is display
 * only. See docs/design/taint.md, "What the Activity log shows".
 *
 * It reuses [SecretScrubber] (the vault's values and credential patterns, as the "What was sent" viewer
 * does) and adds what an argument list has that a request body doesn't:
 * - a value under a secret-shaped key (`Authorization`, `password`, `api_key`, `token`, `cookie`…);
 * - a form field whose name or label says it's a password, card or one-time code;
 * - the password in a `scheme://user:password@host` URL;
 * - secret-looking query parameters (`?token=…`, `&sig=…`, `&code=…`).
 */
class AuditRedactor(vaultValues: Collection<String> = emptyList()) {
    private val scrubber = SecretScrubber(vaultValues, vaultLabel = label(VAULT))

    /** [entry] as the Activity screen shows it. */
    fun entry(entry: AuditEntry): AuditEntry =
        entry.copy(argumentsJson = arguments(entry.argumentsJson), resultPreview = text(entry.resultPreview))

    /** A call's arguments: JSON with secret values replaced; text that isn't JSON goes through [text]. */
    fun arguments(json: String): String {
        val parsed = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return text(json)
        return redact(parsed, key = null).toString()
    }

    /** Free text (a result preview, an argument that isn't JSON). */
    fun text(value: String): String {
        var out = scrubber.scrub(value)
        out = URL_PASSWORD.replace(out) { m -> m.groupValues[1] + ":" + label(URL_CREDENTIAL) + "@" }
        out = QUERY_SECRET.replace(out) { m -> m.groupValues[1] + label(QUERY) }
        return out.replace(SecretScrubber.SECRET, label(CREDENTIAL))
    }

    private fun redact(element: JsonElement, key: String?): JsonElement = when (element) {
        is JsonObject -> redactObject(element)
        is JsonArray -> JsonArray(element.map { redact(it, key) })
        is JsonPrimitive -> {
            val kind = key?.let(::kindOfKey)
            when {
                // Only strings: `max_tokens: 4000` or `password_required: true` hold no secret.
                kind != null && element.isString && element.content.isNotEmpty() -> JsonPrimitive(label(kind))
                element.isString -> JsonPrimitive(text(element.content))
                else -> element
            }
        }
    }

    private fun redactObject(obj: JsonObject): JsonObject {
        // A form field described by its name or label: {"name": "password", "value": "…"}.
        val sensitiveField = obj.entries.any { (k, v) ->
            k.lowercase() in FIELD_NAME_KEYS && (v as? JsonPrimitive)?.contentOrNull?.let { SENSITIVE_FIELD.containsMatchIn(it) } == true
        }
        return JsonObject(
            obj.mapValues { (k, v) ->
                if (sensitiveField && k.lowercase() in FIELD_VALUE_KEYS && v is JsonPrimitive && v.contentOrNull?.isNotEmpty() == true) {
                    JsonPrimitive(label(FORM_FIELD))
                } else {
                    redact(v, k)
                }
            },
        )
    }

    companion object {
        const val VAULT = "saved secret"
        const val AUTHORIZATION = "Authorization header"
        const val COOKIE = "cookie"
        const val PASSWORD = "password"
        const val TOKEN = "token or key"
        const val FORM_FIELD = "password field"
        const val URL_CREDENTIAL = "password in URL"
        const val QUERY = "secret query value"
        const val CREDENTIAL = "credential"

        fun label(kind: String) = "[redacted: $kind]"

        /** The kind of secret a value under [key] is, or null for an ordinary key. */
        fun kindOfKey(key: String): String? {
            val k = key.lowercase().replace('-', '_')
            return when {
                "authorization" in k -> AUTHORIZATION
                "cookie" in k -> COOKIE
                PASSWORD_KEY.containsMatchIn(k) -> PASSWORD
                TOKEN_KEY.containsMatchIn(k) -> TOKEN
                else -> null
            }
        }

        private val PASSWORD_KEY = Regex("""passw|passwd|passphrase|^pass$|^pin$|^otp$|^cvv$|^cvc$|card_?number""")
        private val TOKEN_KEY = Regex("""token|secret|api_?key|apikey|private_?key|credential|signature|^sig$|^x_api_key$|session_?id""")

        private val FIELD_NAME_KEYS = setOf("name", "label", "field", "id", "type", "autocomplete", "placeholder")
        private val FIELD_VALUE_KEYS = setOf("value", "text")
        private val SENSITIVE_FIELD = Regex("""(?i)passw|passcode|\bpin\b|one[- ]time|\botp\b|cvv|cvc|card ?number|security code""")

        private val URL_PASSWORD = Regex("""(\b[a-zA-Z][a-zA-Z0-9+.\-]*://[^/\s:@"]*):(?!\[)([^@\s/"]+)@""")
        private val QUERY_SECRET = Regex(
            """(?i)([?&](?:access_token|refresh_token|id_token|token|api_key|apikey|key|secret|client_secret|sig|signature|password|pass|auth|code|session|sid|x-amz-signature|x-amz-credential)=)(?!\[)[^&#\s"]+""",
        )
    }
}
