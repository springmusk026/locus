package dev.locus.core.agent.integrations

import dev.locus.core.model.ServiceAddress
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Gotify (the user's own server): push a message with an application token. The vault value is the
 * server's address and the token: `https://push.example.com AbCdEf…`. The model never sees either.
 *
 * Checked against the server's API spec (gotify/server `docs/spec.json`) and gotify.net/docs on
 * 2026-11-21: `POST /message` with a `CreateMessage` JSON body (`message` required; `title`,
 * `priority`, `extras`), the token in the `X-Gotify-Key` header (the spec's `appTokenHeader`; the
 * `token` query parameter would put it in URLs and logs), 200 on success, 400/401/403 otherwise.
 * See docs/design/reach-me-channels.md.
 */
object Gotify {
    data class Target(val url: String, val token: String)

    /** Gotify's application tokens are short printable strings ("A" and 14 characters today). */
    fun isToken(token: String): Boolean = token.length in 8..256 && token.all { it in '!'..'~' }

    fun parse(value: String): Target? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size != 2) return null
        val url = ServiceAddress.check(parts[0]).url ?: return null
        if (url != parts[0].trimEnd('/')) return null
        return Target(url, parts[1].takeIf(::isToken) ?: return null)
    }

    /** The vault value for [url] (checked by [ServiceAddress]) and [token]. */
    fun format(url: String, token: String): String {
        val checked = requireNotNull(ServiceAddress.check(url).url) { "Not a usable Gotify address." }
        require(isToken(token.trim())) { "Not a Gotify application token." }
        return "$checked ${token.trim()}"
    }

    /**
     * notify_me's 1–5 (ntfy's scale) on Gotify's 0–10, where the Android app plays a sound from 4 and
     * vibrates from 8 (gotify.net/docs/priority). Without one, the application's default applies.
     */
    fun priority(priority: Int?): Int? = when (priority?.coerceIn(1, 5)) {
        null -> null
        1 -> 1
        2 -> 3
        3 -> 5
        4 -> 8
        else -> 10
    }

    /** The JSON body: the message as Markdown, and a link to open on tap when given. */
    fun body(title: String?, message: String, priority: Int?, click: String?): String = buildJsonObject {
        title?.takeIf { it.isNotBlank() }?.let { put("title", it.replace(Regex("[\\r\\n]+"), " ").take(250)) }
        put("message", message.take(MAX_MESSAGE))
        priority(priority)?.let { put("priority", it) }
        putJsonObject("extras") {
            putJsonObject("client::display") { put("contentType", "text/markdown") }
            click?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                putJsonObject("client::notification") { putJsonObject("click") { put("url", url) } }
            }
        }
    }.toString()

    suspend fun publish(http: HttpClient, target: Target, title: String?, message: String, priority: Int? = null, click: String? = null) {
        val response = http.post("${target.url}/message") {
            header("X-Gotify-Key", target.token)
            contentType(ContentType.Application.Json)
            setBody(body(title, message, priority, click))
        }
        val code = response.status.value
        if (code in 200..299) return
        // Gotify's Error body: {"error":"Unauthorized","errorCode":401,"errorDescription":"…"}.
        val said = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject["errorDescription"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()?.take(200)
        throw ChannelHttpException(
            code,
            when (code) {
                in 300..399 -> "The Gotify address redirects elsewhere. Ask the user to enter the address it redirects to in Settings → Integrations → Gotify."
                401, 403 -> "Gotify refused the application token. Ask the user to check it in Settings → Integrations → Gotify."
                else -> if (said != null) "Gotify error $code: $said" else "Gotify error $code."
            },
        )
    }

    /** Longer text is cut so the message still arrives. */
    const val MAX_MESSAGE = 10_000
}

/** A reach-me channel answered with HTTP [status] instead of taking the message. [message] never contains a secret. */
class ChannelHttpException(val status: Int, message: String) : Exception(message)
