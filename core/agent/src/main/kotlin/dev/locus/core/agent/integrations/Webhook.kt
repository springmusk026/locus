package dev.locus.core.agent.integrations

import dev.locus.core.model.ServiceAddress
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A webhook the user points at their own automation (Home Assistant, n8n, Node-RED, a small server):
 * Locus POSTs a small JSON body there. The vault value is the URL, optionally followed by a signing
 * secret: `https://hooks.example.com/locus s3cret…`. The model never sees either, and the secret itself
 * is never sent: with one, each request carries an HMAC-SHA256 signature of the timestamp and body.
 *
 * The format is a stable contract, documented for users in docs/design/reach-me-channels.md:
 *
 * - Body (`Content-Type: application/json`, UTF-8):
 *   `{"version":1,"title":"…"|null,"message":"…","priority":1–5,"click":"https://…"|null,"sentAt":"2026-11-21T08:00:00Z"}`
 * - `X-Locus-Timestamp`: the Unix time in seconds when it was signed.
 * - `X-Locus-Signature` (only with a secret): `sha256=` and the lowercase hex HMAC-SHA256, keyed with
 *   the secret's UTF-8 bytes, of `<timestamp>.<body>`: the timestamp, a dot and the exact body bytes,
 *   like Stripe's `t.payload`, so a receiver can reject old timestamps (replays) and the timestamp
 *   can't be changed without breaking the signature. The `sha256=<hex>` form is GitHub's
 *   `X-Hub-Signature-256`.
 */
object Webhook {
    data class Target(val url: String, val secret: String?)

    const val SIGNATURE_HEADER = "X-Locus-Signature"
    const val TIMESTAMP_HEADER = "X-Locus-Timestamp"

    /** A signing secret: at least 16 printable characters with no spaces. */
    fun isSecret(secret: String): Boolean = secret.length in 16..256 && secret.all { it in '!'..'~' }

    /**
     * [input] as a webhook URL, or why not. Like a server address ([ServiceAddress]): https, or http only
     * on this phone or the local network, no user name or password, no fragment. Unlike one, a query
     * (`?key=…`) and the path are kept exactly as typed, since the receiver may need either.
     */
    fun check(input: String): ServiceAddress.Checked {
        val raw = input.trim()
        val base = raw.substringBefore('?')
        val query = raw.substringAfter('?', "")
        val checked = ServiceAddress.check(base)
        if (!checked.ok) return checked
        if (query.any { it.isWhitespace() || it.isISOControl() || it == '#' }) return ServiceAddress.check(raw)
        val text = if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(base)) base else "https://$base"
        val scheme = text.substringBefore("://").lowercase()
        return checked.copy(url = scheme + text.substring(scheme.length) + if (query.isNotEmpty()) "?$query" else "")
    }

    fun parse(value: String): Target? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size !in 1..2) return null
        val url = check(parts[0]).url?.takeIf { it == parts[0] } ?: return null
        val secret = parts.getOrNull(1)
        if (secret != null && !isSecret(secret)) return null
        return Target(url, secret)
    }

    /** The vault value for [url] (checked by [check]) and an optional [secret]. */
    fun format(url: String, secret: String?): String {
        val checked = requireNotNull(check(url).url) { "Not a usable webhook URL." }
        val s = secret?.trim()?.takeIf { it.isNotEmpty() }
        require(s == null || isSecret(s)) { "Not a usable signing secret." }
        return listOfNotNull(checked, s).joinToString(" ")
    }

    /** The JSON body, byte for byte what is signed. */
    fun body(title: String?, message: String, priority: Int?, click: String?, sentAtMillis: Long): String = buildJsonObject {
        put("version", 1)
        val t = title?.takeIf { it.isNotBlank() }?.replace(Regex("[\\r\\n]+"), " ")?.take(250)
        if (t != null) put("title", t) else put("title", JsonNull)
        put("message", message.take(Gotify.MAX_MESSAGE))
        put("priority", (priority ?: 3).coerceIn(1, 5))
        val c = click?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (c != null) put("click", c) else put("click", JsonNull)
        put("sentAt", Instant.ofEpochMilli(sentAtMillis).truncatedTo(ChronoUnit.SECONDS).toString())
    }.toString()

    /** `sha256=<hex>` over `<timestamp>.<body>`, keyed with [secret]. */
    fun signature(secret: String, timestamp: Long, body: String): String = "sha256=" + hmacSha256Hex(secret, "$timestamp.$body")

    fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun send(
        http: HttpClient,
        target: Target,
        title: String?,
        message: String,
        priority: Int? = null,
        click: String? = null,
        now: () -> Long = System::currentTimeMillis,
    ) {
        val millis = now()
        val timestamp = millis / 1000
        val body = body(title, message, priority, click, millis)
        val response = http.post(target.url) {
            contentType(ContentType.Application.Json)
            header(TIMESTAMP_HEADER, timestamp.toString())
            target.secret?.let { header(SIGNATURE_HEADER, signature(it, timestamp, body)) }
            setBody(body)
        }
        val code = response.status.value
        if (code in 200..299) return
        throw ChannelHttpException(
            code,
            when (code) {
                in 300..399 -> "The webhook redirects elsewhere. Ask the user to enter the address it redirects to in Settings → Integrations → Webhook."
                401, 403 -> "The webhook refused the request (HTTP $code). Ask the user to check its URL and signing secret in Settings → Integrations → Webhook."
                else -> "The webhook answered HTTP $code."
            },
        )
    }
}
