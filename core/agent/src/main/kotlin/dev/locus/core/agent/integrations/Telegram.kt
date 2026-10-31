package dev.locus.core.agent.integrations

import dev.locus.core.agent.ToolException
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Telegram bot (core.telegram.org/bots/api): send the user a message from a bot they made with
 * @BotFather. The vault value is the bot token and the chat id separated by a space:
 * `123456:ABC… 987654321`. The model never sees either, and can only reach that one chat.
 *
 * Locus only calls the Bot API when it has something to send (and once during setup, to find the
 * chat id); no bot server keeps running. The token is part of every request path, so anything
 * that could echo it (errors, exception messages) is passed through [scrub].
 */
object Telegram {
    data class Target(val token: String, val chatId: Long)

    data class Chat(val id: Long, val name: String, val type: String)

    /** What `getMe` and `getUpdates` revealed during setup. */
    data class Discovery(val botUsername: String?, val chats: List<Chat>)

    /** A failed Bot API call, already safe to show (no token). */
    class Failure(val status: Int, val description: String, message: String) : Exception(message)

    const val API = "https://api.telegram.org"

    /** Real waiting; tests pass their own. */
    internal val realPause: suspend (Long) -> Unit = { ms -> delay(ms) }

    /** sendMessage takes 1–4096 characters after entities parsing; chunks stay well under it, leaving room for a title and link. */
    internal const val CHUNK = 3_500

    /** At most this many messages per notification, so a runaway answer can't flood the chat. */
    internal const val MAX_PARTS = 5

    /** Waits longer than this for a 429 aren't worth holding a tool call (or a background task) open. */
    internal const val MAX_RETRY_WAIT_S = 30L

    /** Telegram asks bots to keep to about one message a second in a single chat (core.telegram.org/bots/faq). */
    internal const val PAUSE_BETWEEN_PARTS_MS = 1_000L

    // BotFather tokens look like 123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11.
    private val TOKEN = Regex("^[0-9]{3,20}:[A-Za-z0-9_-]{20,80}$")
    private val TOKEN_ANYWHERE = Regex("[0-9]{3,20}:[A-Za-z0-9_-]{20,80}")
    private val CHAT_ID = Regex("^-?[0-9]{1,19}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun isToken(value: String) = TOKEN.matches(value.trim())

    fun parseChatId(value: String): Long? = value.trim().takeIf { CHAT_ID.matches(it) }?.toLongOrNull()

    fun parse(value: String): Target? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size != 2 || !isToken(parts[0])) return null
        return Target(parts[0], parseChatId(parts[1]) ?: return null)
    }

    fun format(token: String, chatId: Long) = "${token.trim()} $chatId"

    /** Removes the token (and anything shaped like one) from text that might be shown or logged. */
    fun scrub(text: String, token: String?): String {
        var out = text
        if (!token.isNullOrBlank()) out = out.replace(token.trim(), "<token>")
        return out.replace(TOKEN_ANYWHERE, "<token>")
    }

    /**
     * Sends [message] (Markdown, as the model writes it) to the user's chat as HTML, split into
     * as many messages as needed (up to [MAX_PARTS]). [silent] delivers without sound.
     */
    suspend fun send(
        http: HttpClient,
        target: Target,
        title: String?,
        message: String,
        silent: Boolean = false,
        click: String? = null,
        pause: suspend (Long) -> Unit = Telegram.realPause,
    ) {
        val cleanTitle = title?.replace(Regex("[\\r\\n]+"), " ")?.trim()?.take(250)?.takeIf { it.isNotEmpty() }
        val link = click?.trim()?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length <= 300 && it.none(Char::isWhitespace) }
        val body = message.ifBlank { cleanTitle ?: "(empty)" }
        val all = split(body.take(CHUNK * MAX_PARTS * 2), CHUNK)
        val parts = if (all.size <= MAX_PARTS && body.length <= CHUNK * MAX_PARTS * 2) all
        else all.take(MAX_PARTS - 1) + (all[MAX_PARTS - 1].take(CHUNK - 20).trimEnd() + "\n…(truncated)")
        parts.forEachIndexed { i, part ->
            if (i > 0) pause(PAUSE_BETWEEN_PARTS_MS)
            val head = cleanTitle?.takeIf { i == 0 }
            val tail = link?.takeIf { i == parts.lastIndex }
            val html = buildString {
                head?.let { append("<b>").append(escape(it)).append("</b>\n") }
                append(toHtml(part))
                tail?.let { append("\n\n<a href=\"").append(escapeAttr(it)).append("\">").append(escape(it)).append("</a>") }
            }
            try {
                sendMessage(http, target, html, "HTML", silent, pause)
            } catch (e: Failure) {
                // The Markdown → HTML conversion is best effort; if Telegram can't parse it, send the same part as plain text.
                if (e.status != 400 || !e.description.contains("parse entities", ignoreCase = true)) throw e
                val plain = listOfNotNull(head, part, tail).joinToString("\n")
                sendMessage(http, target, plain, null, silent, pause)
            }
        }
    }

    private suspend fun sendMessage(http: HttpClient, target: Target, text: String, parseMode: String?, silent: Boolean, pause: suspend (Long) -> Unit) {
        call(http, target.token, "sendMessage", buildJsonObject {
            put("chat_id", target.chatId)
            put("text", text)
            parseMode?.let { put("parse_mode", it) }
            putJsonObject("link_preview_options") { put("is_disabled", true) }
            if (silent) put("disable_notification", true)
        }, pause)
    }

    /**
     * Setup step: checks the token with `getMe`, then reads pending updates once with `getUpdates`
     * to list the chats that have messaged the bot. It doesn't confirm (consume) them; Telegram
     * drops them after 24 hours anyway.
     */
    suspend fun findChats(http: HttpClient, token: String, pause: suspend (Long) -> Unit = Telegram.realPause): Discovery {
        val me = call(http, token, "getMe", JsonObject(emptyMap()), pause) as? JsonObject
        val username = (me?.get("username") as? JsonPrimitive)?.contentOrNull
        val updates = call(http, token, "getUpdates", buildJsonObject { put("timeout", 0); put("limit", 100) }, pause) as? JsonArray
        val chats = LinkedHashMap<Long, Chat>()
        updates.orEmpty().forEach { update ->
            val u = update as? JsonObject ?: return@forEach
            val msg = sequenceOf("message", "edited_message", "channel_post", "my_chat_member").firstNotNullOfOrNull { u[it] as? JsonObject } ?: return@forEach
            val chat = msg["chat"] as? JsonObject ?: return@forEach
            val id = (chat["id"] as? JsonPrimitive)?.longOrNull ?: return@forEach
            val type = chat.text("type") ?: "private"
            val name = chat.text("title")
                ?: listOfNotNull(chat.text("first_name"), chat.text("last_name")).joinToString(" ").ifBlank { null }
                ?: chat.text("username")?.let { "@$it" }
                ?: id.toString()
            chats.remove(id)
            chats[id] = Chat(id, name, type)
        }
        // Most recent first: the user just messaged the bot.
        return Discovery(username, chats.values.reversed())
    }

    /** POSTs a Bot API method; returns `result`, waits out short 429s, and throws [Failure] with a token-free message. */
    internal suspend fun call(http: HttpClient, token: String, method: String, body: JsonObject, pause: suspend (Long) -> Unit): JsonElement? {
        var retries = 0
        while (true) {
            val (status, text) = try {
                val response = http.post("$API/bot$token/$method") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
                response.status.value to response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = scrub(e.message ?: e::class.simpleName.orEmpty(), token).take(200)
                throw Failure(0, reason, "Couldn't reach Telegram: $reason")
            }
            val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            if (status in 200..299 && (obj?.get("ok") as? JsonPrimitive)?.booleanOrNull == true) return obj["result"]
            val description = scrub((obj?.get("description") as? JsonPrimitive)?.contentOrNull ?: text.take(200), token)
            val retryAfter = ((obj?.get("parameters") as? JsonObject)?.get("retry_after") as? JsonPrimitive)?.longOrNull
            if (status == 429 && retryAfter != null && retryAfter <= MAX_RETRY_WAIT_S && retries < 2) {
                retries++
                pause(retryAfter.coerceAtLeast(1) * 1_000)
                continue
            }
            throw Failure(status, description, explain(status, description, retryAfter))
        }
    }

    private fun explain(status: Int, description: String, retryAfter: Long?): String = when {
        status == 401 || status == 404 -> "Telegram rejected the bot token. Check it in Settings → Integrations → Telegram."
        status == 409 -> "This bot is set up with a webhook elsewhere, so Locus can't read its messages. Use a bot made just for Locus, or remove the webhook."
        status == 429 -> "Telegram rate limit reached" + (retryAfter?.let { "; try again in $it seconds." } ?: ".")
        status == 403 || description.contains("chat not found", ignoreCase = true) ->
            "Telegram can't reach that chat ($description). Open the bot in Telegram, press Start, and check the chat in Settings → Integrations → Telegram."
        else -> "Telegram error $status: $description"
    }

    // ---- Text -----------------------------------------------------------------------------------

    /** Telegram's HTML mode needs only <, > and & escaped outside tags (core.telegram.org/bots/api#html-style). */
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttr(s: String) = escape(s).replace("\"", "&quot;")

    private val FENCE = Regex("^\\s*```\\s*([A-Za-z0-9_+#.-]*)\\s*$")
    private val LINK = Regex("\\[([^\\]\\n]+)]\\((https?://[^)\\s\"]+)\\)")
    private val BARE_URL = Regex("https?://[^\\s<>\"]+")
    private val INLINE_CODE = Regex("`([^`\\n]+)`")
    private val HEADING = Regex("^#{1,6}\\s+(.+)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+")
    private val BOLD = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*|__(?=\\S)(.+?)(?<=\\S)__")
    private val ITALIC = Regex("(?<![*\\w])\\*(?=[^\\s*])(.+?)(?<=[^\\s*])\\*(?![*\\w])|(?<![_\\w])_(?=[^\\s_])(.+?)(?<=[^\\s_])_(?![_\\w])")
    private val STRIKE = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")

    /**
     * Best-effort Markdown → Telegram HTML: code blocks, inline code, links, bold, italic,
     * strikethrough, headings (as bold), bullets and quotes. Everything else is escaped text.
     * Visible length never exceeds the input's, which [split] relies on.
     */
    fun toHtml(markdown: String): String {
        val out = StringBuilder()
        var codeLang: String? = null
        val code = StringBuilder()
        var quote = mutableListOf<String>()
        fun flushQuote() {
            if (quote.isEmpty()) return
            out.append("<blockquote>").append(quote.joinToString("\n")).append("</blockquote>\n")
            quote = mutableListOf()
        }
        for (line in markdown.split('\n')) {
            val fence = FENCE.matchEntire(line)
            if (codeLang != null) {
                if (fence != null && fence.groupValues[1].isEmpty()) {
                    out.append(pre(codeLang, code.toString().removeSuffix("\n"))).append('\n')
                    code.clear()
                    codeLang = null
                } else {
                    code.append(line).append('\n')
                }
                continue
            }
            if (fence != null) {
                flushQuote()
                codeLang = fence.groupValues[1]
                continue
            }
            if (line.startsWith(">")) {
                quote += inline(line.removePrefix(">").removePrefix(" "))
                continue
            }
            flushQuote()
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.find(line)?.takeIf { it.range.first == 0 }
            when {
                heading != null -> out.append("<b>").append(inline(heading.groupValues[1])).append("</b>")
                bullet != null -> out.append(bullet.groupValues[1]).append("• ").append(inline(line.substring(bullet.range.last + 1)))
                else -> out.append(inline(line))
            }
            out.append('\n')
        }
        flushQuote()
        codeLang?.let { out.append(pre(it, code.toString().removeSuffix("\n"))).append('\n') }
        return out.toString().trimEnd('\n')
    }

    private fun pre(lang: String, body: String) =
        if (lang.isEmpty()) "<pre>${escape(body)}</pre>" else "<pre><code class=\"language-${escapeAttr(lang)}\">${escape(body)}</code></pre>"

    /** One line of inline Markdown. Code spans and links are set aside first so emphasis never reaches into them. */
    private fun inline(line: String): String {
        val held = mutableListOf<String>()
        fun hold(html: String): String { held += html; return "\u0000${held.lastIndex}\u0000" }
        var s = INLINE_CODE.replace(line) { hold("<code>${escape(it.groupValues[1])}</code>") }
        s = LINK.replace(s) { hold("<a href=\"${escapeAttr(it.groupValues[2])}\">${escape(it.groupValues[1])}</a>") }
        s = BARE_URL.replace(s) { hold(escape(it.value)) }
        s = escape(s)
        s = BOLD.replace(s) { "<b>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</b>" }
        s = STRIKE.replace(s) { "<s>${it.groupValues[1]}</s>" }
        s = ITALIC.replace(s) { "<i>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</i>" }
        return Regex("\u0000(\\d+)\u0000").replace(s) { held[it.groupValues[1].toInt()] }
    }

    /**
     * Splits text into pieces of at most [limit] characters, at line breaks where possible. A code
     * block cut in two is closed at the end of one piece and reopened at the start of the next.
     */
    fun split(text: String, limit: Int = CHUNK): List<String> {
        if (text.length <= limit) return listOf(text)
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var openFence: String? = null
        for (line in text.split('\n')) {
            for (piece in hardWrap(line, limit - 40)) {
                val closing = if (openFence != null) 4 else 0
                if (current.isNotEmpty() && current.length + 1 + piece.length + closing > limit) {
                    if (openFence != null) current.append("\n```")
                    parts += current.toString()
                    current.clear()
                    openFence?.let { current.append(it) }
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(piece)
                if (FENCE.matches(piece)) openFence = if (openFence == null) piece.trim() else null
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }

    private fun hardWrap(line: String, max: Int): List<String> {
        if (line.length <= max) return listOf(line)
        val pieces = mutableListOf<String>()
        var rest = line
        while (rest.length > max) {
            var cut = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
            if (Character.isHighSurrogate(rest[cut - 1])) cut--
            pieces += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart(' ')
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
