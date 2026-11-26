package dev.locus.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Removes credentials and bulky binary data from text and JSON before Locus shows it to the user
 * (the "What was sent" viewer) or puts it on the clipboard.
 *
 * Two layers: the exact values the vault holds ([known], plus the parts of structured values such as
 * an MCP session's tokens or the password in an email URL), and patterns for credentials whatever
 * their source (`Bearer …`, `x-api-key: …`, `sk-…`, Telegram, Slack, Google, GitLab, Hugging Face and Groq tokens, JWTs, AWS key ids,
 * private key blocks). Every scrubber in Locus (the viewer, the Activity log, Diagnostics, the publish page)
 * uses this one list, so a format added here is covered everywhere. Attachment bytes and other base64 blobs become a
 * short label such as `[image, 1.2 MB]`.
 */
class SecretScrubber(
    known: Collection<String> = emptyList(),
    /** What a vault value becomes; patterns always leave [SECRET]. The Activity log says which kind it was ([AuditRedactor]). */
    private val vaultLabel: String = SECRET,
) {

    /** Longest first, so a value is replaced before any shorter value inside it. */
    private val secrets: List<String> = known.flatMap(::candidates).filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }

    /** [text] with every known secret and credential-looking token replaced. */
    fun scrub(text: String): String {
        var out = text
        for (s in secrets) if (out.contains(s)) out = out.replace(s, vaultLabel)
        for ((pattern, replacement) in PATTERNS) out = pattern.replace(out, replacement)
        return out
    }

    /**
     * [element] with attachment payloads replaced by labels and every string [scrub]bed. Object keys
     * are kept; only values change, so the structure still reads like the request.
     */
    fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> scrubObject(element)
        is JsonArray -> JsonArray(element.map(::scrub))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(scrubString(element.content)) else element
    }

    private fun scrubObject(obj: JsonObject): JsonObject {
        // Anthropic: {"type":"base64","media_type":"image/png","data":"…"}.
        val base64Source = (obj["type"] as? JsonPrimitive)?.contentOrNull == "base64" && obj["data"] is JsonPrimitive
        return JsonObject(
            obj.mapValues { (key, value) ->
                if (base64Source && key == "data") {
                    val media = (obj["media_type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    JsonPrimitive(label(kindOf(media), decodedSize((value as JsonPrimitive).content)))
                } else {
                    scrub(value)
                }
            },
        )
    }

    private fun scrubString(text: String): String {
        DATA_URL.matchEntire(text)?.let { m -> return label(kindOf(m.groupValues[1]), decodedSize(m.groupValues[2])) }
        if (text.length >= MIN_BLOB && BASE64.matches(text)) return label("base64 data", decodedSize(text))
        return scrub(text)
    }

    companion object {
        const val SECRET = "[secret]"

        /** A whole vault value shorter than this is too generic to replace safely (and too weak to matter). */
        private const val MIN_VALUE = 6

        /** Parts taken out of a structured value must be at least this long. */
        private const val MIN_PART = 8

        /** Shorter base64-alphabet strings are left alone: ids, hashes in prose, and so on. */
        private const val MIN_BLOB = 256

        private val DATA_URL = Regex("""data:([\w.+-]+/[\w.+-]+);base64,([A-Za-z0-9+/=\s]*)""")
        private val BASE64 = Regex("""[A-Za-z0-9+/=_\-\r\n]+""")

        /** Credentials found by the words around them; the value after the label is replaced. */
        private val LABELLED: List<Pair<Regex, String>> = listOf(
            Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{8,}""") to "$1 $SECRET",
            Regex("""(?i)\b(x-api-key|x-goog-api-key|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|secret)(["']?\s*[:=]\s*["']?)(?!\[secret|\[redacted|(?:bearer|basic)\s)[^\s"',;&]{6,}""") to "$1$2$SECRET",
        )

        /**
         * The shapes of well-known keys and tokens, where the whole match is the secret. Public so a scrubber
         * with its own wording (the chat export's `[redacted]`) uses the same list.
         */
        val TOKEN_SHAPES: List<Regex> = listOf(
            Regex("""\bsk-[A-Za-z0-9_\-]{16,}"""),
            Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\b"""),
            Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}\b"""),
            Regex("""\bxox[abprs]-[A-Za-z0-9\-]{10,}"""),
            Regex("""\bxapp-[A-Za-z0-9\-]{10,}"""),
            Regex("""https://hooks\.slack\.com/(?:services|workflows|triggers)/[A-Za-z0-9/_\-]{10,}"""),
            Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
            Regex("""\bya29\.[A-Za-z0-9_\-]{20,}"""),
            // Telegram bot tokens: `<bot id>:<35 characters>`, also inside `https://api.telegram.org/bot<token>/…`.
            Regex("""(?<![0-9])[0-9]{8,12}:[A-Za-z0-9_\-]{30,}"""),
            Regex("""\bgsk_[A-Za-z0-9]{20,}"""),
            Regex("""\bhf_[A-Za-z0-9]{20,}"""),
            Regex("""\bglpat-[A-Za-z0-9_\-]{20,}"""),
            Regex("""\b(?:pk|rk)-[A-Za-z0-9_\-]{16,}"""),
            // Private key blocks, JWTs and AWS access key ids.
            Regex("""(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"""),
            Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""),
            Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        )

        private val PATTERNS: List<Pair<Regex, String>> = LABELLED + TOKEN_SHAPES.map { it to SECRET }

        /**
         * What to look for in the transcript for one vault value: the value itself and, for structured
         * values, the parts a tool might echo on their own (JSON string fields, whitespace-separated
         * tokens, the password in a `scheme://user:password@host` URL).
         */
        internal fun candidates(value: String): List<String> {
            val v = value.trim()
            if (v.length < MIN_VALUE) return emptyList()
            val parts = mutableListOf<String>()
            runCatching { Json.parseToJsonElement(v) }.getOrNull()?.let { collectStrings(it, parts) }
            v.split(Regex("\\s+")).forEach { token ->
                parts += token
                USERINFO.find(token)?.let { m ->
                    val password = m.groupValues[1]
                    parts += password
                    parts += runCatching { java.net.URLDecoder.decode(password, "UTF-8") }.getOrDefault(password)
                }
            }
            return listOf(v) + parts.map { it.trim() }.filter { it.length >= MIN_PART && it != v }
        }

        private val USERINFO = Regex("""://[^/\s:]*:([^@\s/]+)@""")

        private fun collectStrings(e: JsonElement, into: MutableList<String>) {
            when (e) {
                is JsonObject -> e.values.forEach { collectStrings(it, into) }
                is JsonArray -> e.forEach { collectStrings(it, into) }
                is JsonPrimitive -> if (e.isString) into += e.content
            }
        }

        private fun kindOf(mediaType: String) = when {
            mediaType.startsWith("image/") -> "image"
            mediaType == "application/pdf" -> "PDF"
            mediaType.isBlank() -> "file"
            else -> mediaType
        }

        private fun label(kind: String, bytes: Long) = "[$kind, ${readableBytes(bytes)}]"

        /** Bytes the base64 text decodes to. */
        internal fun decodedSize(base64: String): Long {
            val chars = base64.count { !it.isWhitespace() }
            val padding = base64.trimEnd().takeLastWhile { it == '=' }.length
            return (chars.toLong() * 3 / 4 - padding).coerceAtLeast(0)
        }

        fun readableBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
            else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
        }
    }
}
