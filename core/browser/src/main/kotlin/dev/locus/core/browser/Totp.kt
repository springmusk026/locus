package dev.locus.core.browser

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords, RFC 6238 (https://www.rfc-editor.org/rfc/rfc6238, May 2011), computed
 * on the phone for a saved login's authenticator key. The code is HOTP (RFC 4226) of the number of
 * [STEP_SECONDS] steps since the Unix epoch (T0 = 0, X = 30, the RFC's defaults), HMAC-SHA1 and six
 * digits unless the key says otherwise: what authenticator apps use. No library needed.
 */
object Totp {
    const val STEP_SECONDS = 30L
    const val DIGITS = 6

    /** The code for [secret] (raw key bytes) at [epochSeconds]. [algorithm] is a JCA HMAC name. */
    fun code(secret: ByteArray, epochSeconds: Long, digits: Int = DIGITS, algorithm: String = "HmacSHA1", step: Long = STEP_SECONDS): String {
        require(digits in 6..10) { "digits must be 6 to 10" }
        val counter = Math.floorDiv(epochSeconds, step)
        val message = ByteArray(8) { i -> (counter ushr (8 * (7 - i))).toByte() }
        val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, "RAW")) }
        val hash = mac.doFinal(message)
        // RFC 4226 §5.3, dynamic truncation.
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val mod = (1..digits).fold(1L) { acc, _ -> acc * 10 }
        return (binary.toLong() % mod).toString().padStart(digits, '0')
    }

    /**
     * The code for a key as the user pasted it: a base32 secret, or an `otpauth://totp/…?secret=…` URI
     * (its `digits`, `period` and `algorithm` are honoured). Null when it can't be read.
     */
    fun codeFor(key: String, epochSeconds: Long): String? {
        val parsed = parse(key) ?: return null
        return runCatching { code(parsed.secret, epochSeconds, parsed.digits, parsed.algorithm, parsed.period) }.getOrNull()
    }

    /** The codes of the steps around [epochSeconds] (previous, current, next), for scrubbing; empty when unreadable. */
    fun codesAround(key: String, epochSeconds: Long): List<String> {
        val parsed = parse(key) ?: return emptyList()
        return listOf(-1L, 0L, 1L).mapNotNull { d ->
            runCatching { code(parsed.secret, epochSeconds + d * parsed.period, parsed.digits, parsed.algorithm, parsed.period) }.getOrNull()
        }
    }

    /** Whether [key] can be used: for the editor's check before saving. */
    fun valid(key: String): Boolean = parse(key) != null

    data class Key(val secret: ByteArray, val digits: Int, val algorithm: String, val period: Long)

    fun parse(key: String): Key? {
        val k = key.trim()
        if (k.startsWith("otpauth://", ignoreCase = true)) {
            val uri = runCatching { java.net.URI(k) }.getOrNull() ?: return null
            if (!uri.host.equals("totp", ignoreCase = true)) return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else p.substring(0, i).lowercase() to java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8")
            }.toMap()
            val secret = base32(params["secret"] ?: return null) ?: return null
            val digits = params["digits"]?.toIntOrNull() ?: DIGITS
            val period = params["period"]?.toLongOrNull() ?: STEP_SECONDS
            val algorithm = when (params["algorithm"]?.uppercase()) {
                null, "SHA1" -> "HmacSHA1"
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> return null
            }
            if (digits !in 6..10 || period !in 1..300) return null
            return Key(secret, digits, algorithm, period)
        }
        return base32(k)?.let { Key(it, DIGITS, "HmacSHA1", STEP_SECONDS) }
    }

    /** RFC 4648 base32, ignoring spaces, dashes, case and padding; null for anything else or fewer than 10 bytes. */
    internal fun base32(text: String): ByteArray? {
        val clean = text.filterNot { it == ' ' || it == '-' || it == '=' }.uppercase()
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer ushr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray().takeIf { it.size >= 10 }
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
}
