package dev.locus.core.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One answer from the page helper (locus-agent.js), unwrapped from the envelope its `L.seal` builds:
 * `{"k": secret, "d": document id, "r": result}`.
 *
 * [verified] is true only when the envelope carries the tab's secret. The secret is baked into the copy
 * of the helper registered with `addDocumentStartJavaScript`, which runs before the page's own scripts
 * and then locks `window.__locus`. So an answer without it came from something else: a `__locus` the
 * page defined first, or the fallback copy that only got in after the page's scripts (WebViews without
 * `DOCUMENT_START_SCRIPT`, or a registration that failed). Facts in such an answer are unverified, and
 * the sensitive-action gate asks the user ([SensitiveActions.untrusted]).
 *
 * [doc] is the id of the document that answered, used to tell whether the page changed between a
 * check and the action it gates. Only meaningful when [verified].
 */
data class PageReply(val value: JsonElement, val verified: Boolean, val doc: String?) {
    companion object {
        /** Placeholder in locus-agent.js that the document-start copy has replaced with the tab's secret. */
        const val SECRET_PLACEHOLDER = "__LOCUS_SECRET__"

        /** A fresh per-tab secret: 128 random bits as hex, safe inside a JavaScript string literal. */
        fun newSecret(random: SecureRandom = SecureRandom()): String =
            ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        /**
         * The script to evaluate for [expression]: its result goes through the helper's seal when one is
         * present. Without one (the page broke it, or it never ran), the plain result comes back unverified.
         */
        fun wrap(expression: String): String =
            "(function(){var r=(function(){ return $expression; })();var L=window.__locus;" +
                "return (L&&typeof L.seal==='function')?L.seal(r):JSON.stringify({r:r});})()"

        /**
         * Reads what `evaluateJavascript` returned for a [wrap]ped script: the JSON encoding of the
         * envelope string. A reply that isn't an envelope is kept, unverified, as plain JSON or text.
         */
        fun open(raw: String?, secret: String): PageReply {
            val inner = runCatching { (Json.parseToJsonElement(raw ?: return unanswered) as? JsonPrimitive)?.contentOrNull }.getOrNull()
                ?: return unanswered
            val parsed = runCatching { Json.parseToJsonElement(inner) }.getOrElse { return PageReply(JsonPrimitive(inner), false, null) }
            val envelope = parsed as? JsonObject
            if (envelope == null || "r" !in envelope) return PageReply(parsed, false, null)
            val key = (envelope["k"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val verified = key != null && sameSecret(key, secret)
            val doc = (envelope["d"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { verified }
            return PageReply(envelope["r"] ?: JsonNull, verified, doc)
        }

        private val unanswered = PageReply(JsonNull, false, null)

        private fun sameSecret(a: String, b: String): Boolean =
            b.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
