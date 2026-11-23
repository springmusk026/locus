package dev.locus.core.agent.integrations

import dev.locus.core.model.ServiceAddress
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.UUID

/**
 * A Matrix bot account posting into one room the user chose, over the Client-Server API
 * (spec.matrix.org, v1.19). The vault value is the homeserver's client address, the bot's access token
 * and the room's ID: `https://matrix.example.org syt_… !abcdef:example.org`. The model never sees any
 * of them.
 *
 * Outbound only, like Gotify: nothing is read back from the room. End-to-end encrypted rooms aren't
 * supported (that needs Olm/Megolm), so every send first checks the room's `m.room.encryption` state
 * and refuses an encrypted room instead of posting plaintext into it.
 *
 * Facts used (see docs/design/reach-me-channels.md for the sections):
 * - `Authorization: Bearer <token>`; the `access_token` query parameter is deprecated and would put
 *   the token in URLs and logs.
 * - `PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}`: the transaction ID makes a
 *   retry idempotent, so a retry after a 429 reuses it.
 * - 429 `M_LIMIT_EXCEEDED`: wait for `Retry-After` (seconds), or the deprecated `retry_after_ms`.
 * - `format: org.matrix.custom.html` with `formatted_body`, and the plain `body` always present.
 * - A whole event must stay under 65,536 bytes in the federation format.
 */
object Matrix {
    data class Target(val url: String, val token: String, val roomId: String)

    /** What went wrong, so setup can say it in the user's language. */
    enum class Problem { TOKEN, NOT_JOINED, ENCRYPTED, FORBIDDEN, ALIAS_UNKNOWN, RATE_LIMITED, REDIRECT, NOT_MATRIX, OTHER }

    /** A failed call, already safe to show: [message] never contains the token. */
    class Failure(val problem: Problem, val status: Int, val errcode: String?, message: String, val retryAfterMs: Long? = null) : Exception(message)

    /** Real waiting; tests pass their own. */
    internal val realPause: suspend (Long) -> Unit = Telegram.realPause

    /** Characters of Markdown per message; with escaping and tags the event stays far below 65,536 bytes. */
    internal const val CHUNK = 4_000

    /** At most this many messages per notification, so a runaway answer can't flood the room. */
    internal const val MAX_PARTS = 5

    /**
     * The event content's budget in bytes. The spec caps the whole event at 65,536 bytes in the
     * federation format, which adds the room ID, sender, hashes, signatures and previous events.
     */
    internal const val MAX_CONTENT_BYTES = 48_000

    /** 429 waits longer than this aren't worth holding a tool call (or a background task) open. */
    internal const val MAX_RETRY_WAIT_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true }

    /** Access tokens are opaque; Synapse's look like `syt_…`. Printable, no spaces. */
    fun isToken(token: String): Boolean = token.length in 8..1024 && token.all { it in '!'..'~' }

    /** A room ID: `!opaque:server`, or just `!opaque` from room version 12. At most 255 bytes. */
    fun isRoomId(value: String): Boolean = value.length in 2..255 && value.startsWith('!') && value.all { it in '!'..'~' }

    /** A room alias: `#name:server`. */
    fun isAlias(value: String): Boolean =
        value.length in 4..255 && value.startsWith('#') && value.indexOf(':') > 1 && !value.endsWith(':') && value.all { it in '!'..'~' }

    fun parse(value: String): Target? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size != 3) return null
        val url = ServiceAddress.check(parts[0]).url?.takeIf { it == parts[0] } ?: return null
        if (!isToken(parts[1]) || !isRoomId(parts[2])) return null
        return Target(url, parts[1], parts[2])
    }

    /** The vault value for [url] (checked by [ServiceAddress]), [token] and [roomId]. */
    fun format(url: String, token: String, roomId: String): String {
        val checked = requireNotNull(ServiceAddress.check(url).url) { "Not a usable homeserver address." }
        require(isToken(token.trim())) { "Not a Matrix access token." }
        require(isRoomId(roomId.trim())) { "Not a Matrix room ID." }
        return "$checked ${token.trim()} ${roomId.trim()}"
    }

    /** Takes the token out of text that might be shown or logged. */
    fun scrub(text: String, token: String): String = if (token.length >= 6) text.replace(token, "<token>") else text

    // ---- Sending --------------------------------------------------------------------------------

    /**
     * Posts [message] (Markdown, as the model writes it) to the room, as HTML with a plain-text
     * `body`, split into up to [MAX_PARTS] messages. [silent] sends `m.notice`, which the default push
     * rule `.m.rule.suppress_notices` keeps from notifying; otherwise `m.text`.
     */
    suspend fun send(
        http: HttpClient,
        target: Target,
        title: String?,
        message: String,
        silent: Boolean = false,
        click: String? = null,
        pause: suspend (Long) -> Unit = realPause,
        txnId: () -> String = { "locus-" + UUID.randomUUID() },
    ) {
        if (isEncrypted(http, target.url, target.token, target.roomId, pause)) throw encrypted()
        val cleanTitle = title?.replace(Regex("[\\r\\n]+"), " ")?.trim()?.take(250)?.takeIf { it.isNotEmpty() }
        val link = click?.trim()?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length <= 300 && it.none(Char::isWhitespace) }
        val text = message.ifBlank { cleanTitle ?: "(empty)" }
        val all = Telegram.split(text.take(CHUNK * MAX_PARTS * 2), CHUNK)
        val parts = if (all.size <= MAX_PARTS && text.length <= CHUNK * MAX_PARTS * 2) all
        else all.take(MAX_PARTS - 1) + (all[MAX_PARTS - 1].take(CHUNK - 20).trimEnd() + "\n…(truncated)")
        parts.forEachIndexed { i, part ->
            val head = cleanTitle?.takeIf { i == 0 }
            val tail = link?.takeIf { i == parts.lastIndex }
            val rich = content(head, part, tail, silent, html = true)
            val fits = rich.toString().toByteArray(Charsets.UTF_8).size <= MAX_CONTENT_BYTES
            try {
                sendEvent(http, target, if (fits) rich else content(head, part, tail, silent, html = false), txnId(), pause)
            } catch (e: Failure) {
                // A server that refuses the formatted event (too large, or bad HTML) still gets the plain text, under a new transaction.
                if (!fits || e.status !in setOf(400, 413) || e.errcode !in setOf("M_TOO_LARGE", "M_BAD_JSON", "M_NOT_JSON", "M_UNKNOWN")) throw e
                sendEvent(http, target, content(head, part, tail, silent, html = false), txnId(), pause)
            }
        }
    }

    /** One `m.room.message` content: plain `body` always, and the HTML version when [html]. */
    fun content(title: String?, text: String, link: String?, notice: Boolean, html: Boolean): JsonObject = buildJsonObject {
        put("msgtype", if (notice) "m.notice" else "m.text")
        put("body", listOfNotNull(title, text, link).joinToString("\n\n"))
        if (html) {
            put("format", "org.matrix.custom.html")
            put(
                "formatted_body",
                buildString {
                    title?.let { append("<b>").append(Telegram.escape(it)).append("</b><br>") }
                    append(html(text))
                    link?.let { append("<br><br><a href=\"").append(Telegram.escape(it).replace("\"", "&quot;")).append("\">").append(Telegram.escape(it)).append("</a>") }
                },
            )
        }
    }

    /**
     * Markdown as the HTML Matrix allows (bold, italic, strikethrough, code, pre, links, blockquotes):
     * Telegram's converter, which only emits tags on the spec's list, with line breaks as `<br>`
     * outside `<pre>` (HTML collapses plain newlines).
     */
    fun html(markdown: String): String {
        val source = Telegram.toHtml(markdown)
        val out = StringBuilder()
        var rest = source
        while (rest.isNotEmpty()) {
            val start = rest.indexOf("<pre>")
            if (start < 0) {
                out.append(breaks(rest))
                break
            }
            val end = rest.indexOf("</pre>", start).let { if (it < 0) rest.length else it + "</pre>".length }
            out.append(breaks(rest.substring(0, start))).append(rest, start, end)
            rest = rest.substring(end).removePrefix("\n")
        }
        return out.toString()
    }

    private fun breaks(s: String) = s.replace("</blockquote>\n", "</blockquote>").replace("\n", "<br>")

    private suspend fun sendEvent(http: HttpClient, target: Target, content: JsonObject, txn: String, pause: suspend (Long) -> Unit) {
        val url = "${target.url}/_matrix/client/v3/rooms/${path(target.roomId)}/send/m.room.message/${path(txn)}"
        try {
            call(http, target.token, pause) { http.put(url) { auth(target.token); contentType(ContentType.Application.Json); setBody(content.toString()) } }
        } catch (e: Failure) {
            // The membership check before sending passed, so a 403 here is the room's permissions (power levels).
            if (e.problem != Problem.NOT_JOINED) throw e
            throw Failure(Problem.FORBIDDEN, e.status, e.errcode, "The Matrix room doesn't let the bot post messages. Ask the user to check the room's permissions for it.")
        }
    }

    // ---- Checks ---------------------------------------------------------------------------------

    /** `GET /account/whoami`: the bot's user ID, or [Failure] (TOKEN when the token isn't recognised). */
    suspend fun whoami(http: HttpClient, url: String, token: String, pause: suspend (Long) -> Unit = realPause): String {
        val obj = call(http, token, pause, whoami = true) { http.get("$url/_matrix/client/v3/account/whoami") { auth(token) } }
        return (obj["user_id"] as? JsonPrimitive)?.contentOrNull ?: throw notMatrix()
    }

    /** `GET /directory/room/{roomAlias}`: the alias's room ID. */
    suspend fun resolveAlias(http: HttpClient, url: String, token: String, alias: String, pause: suspend (Long) -> Unit = realPause): String {
        val obj = try {
            call(http, token, pause) { http.get("$url/_matrix/client/v3/directory/room/${path(alias)}") { auth(token) } }
        } catch (e: Failure) {
            if (e.status == 404 || e.errcode == "M_NOT_FOUND" || e.errcode == "M_INVALID_PARAM") {
                throw Failure(Problem.ALIAS_UNKNOWN, e.status, e.errcode, "The homeserver doesn't know that room address.")
            }
            throw e
        }
        return (obj["room_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(::isRoomId) ?: throw notMatrix()
    }

    /** `GET /joined_rooms`: the rooms the bot has joined. */
    suspend fun joinedRooms(http: HttpClient, url: String, token: String, pause: suspend (Long) -> Unit = realPause): List<String> {
        val obj = call(http, token, pause) { http.get("$url/_matrix/client/v3/joined_rooms") { auth(token) } }
        return (obj["joined_rooms"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    /**
     * Whether the room has an `m.room.encryption` state event (`GET /rooms/{roomId}/state/m.room.encryption/`,
     * empty state key): 200 is encrypted, 404 isn't, 403 means the bot isn't in the room.
     */
    suspend fun isEncrypted(http: HttpClient, url: String, token: String, roomId: String, pause: suspend (Long) -> Unit = realPause): Boolean = try {
        call(http, token, pause) { http.get("$url/_matrix/client/v3/rooms/${path(roomId)}/state/m.room.encryption/") { auth(token) } }
        true
    } catch (e: Failure) {
        if (e.status == 404 && e.errcode == "M_NOT_FOUND") false else throw e
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) = header("Authorization", "Bearer $token")

    /** A path segment: room IDs, aliases and transaction IDs carry `!`, `#` and `:`, so everything is escaped. */
    internal fun path(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** Runs [request], waits out short 429s (the same request, so a send keeps its transaction ID), and maps errors to [Failure]. */
    private suspend fun call(
        http: HttpClient,
        token: String,
        pause: suspend (Long) -> Unit,
        whoami: Boolean = false,
        request: suspend () -> HttpResponse,
    ): JsonObject {
        var retries = 0
        while (true) {
            val response = request()
            val status = response.status.value
            val text = response.bodyAsText()
            val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            if (status in 200..299) return obj ?: throw notMatrix()
            val errcode = (obj?.get("errcode") as? JsonPrimitive)?.contentOrNull
            val said = scrub((obj?.get("error") as? JsonPrimitive)?.contentOrNull.orEmpty(), token).replace(Regex("\\s+"), " ").take(200)
            if (status == 429 || errcode == "M_LIMIT_EXCEEDED") {
                val wait = response.headers["Retry-After"]?.trim()?.toLongOrNull()?.times(1_000)
                    ?: (obj?.get("retry_after_ms") as? JsonPrimitive)?.longOrNull
                if (wait != null && wait <= MAX_RETRY_WAIT_MS && retries < 2) {
                    retries++
                    pause(wait.coerceAtLeast(500))
                    continue
                }
                throw Failure(
                    Problem.RATE_LIMITED, status, errcode,
                    "The Matrix homeserver is rate limiting the bot" + (wait?.let { "; try again in ${(it + 999) / 1000} seconds." } ?: "."),
                    wait,
                )
            }
            throw when {
                status in 300..399 -> Failure(Problem.REDIRECT, status, errcode, "The homeserver address redirects elsewhere. Ask the user to enter the address it redirects to in Settings → Integrations → Matrix.")
                status == 401 || errcode == "M_UNKNOWN_TOKEN" || errcode == "M_MISSING_TOKEN" ->
                    Failure(Problem.TOKEN, status, errcode, "The Matrix homeserver didn't accept the bot's access token. Ask the user to check it in Settings → Integrations → Matrix.")
                obj == null || errcode == null -> if (whoami || status == 404) notMatrix(status) else Failure(Problem.OTHER, status, null, "Matrix error $status.")
                status == 403 && !whoami -> Failure(Problem.NOT_JOINED, status, errcode, "The Matrix bot can't post in that room ($errcode${if (said.isNotEmpty()) ": $said" else ""}). Ask the user to invite it and accept the invite.")
                else -> Failure(Problem.OTHER, status, errcode, "Matrix error $status $errcode" + if (said.isNotEmpty()) ": $said" else ".")
            }
        }
    }

    private fun notMatrix(status: Int = 200) =
        Failure(Problem.NOT_MATRIX, status, null, "That address didn't answer as a Matrix homeserver's client API. Ask the user to check it in Settings → Integrations → Matrix.")

    fun encrypted() = Failure(
        Problem.ENCRYPTED, 200, null,
        "The Matrix room is end-to-end encrypted, and Locus can't encrypt messages, so nothing was sent there. Ask the user to pick a room without encryption in Settings → Integrations → Matrix.",
    )

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
