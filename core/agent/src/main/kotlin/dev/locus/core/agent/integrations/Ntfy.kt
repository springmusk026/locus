package dev.locus.core.agent.integrations

import dev.locus.core.agent.ToolException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText

/**
 * ntfy (ntfy.sh or the user's own server): push a message to a topic the user subscribes to on
 * their other devices. The vault value is the topic URL, optionally followed by an access token:
 * `https://ntfy.sh/my-secret-topic tk_…`. The model never sees either; it can only send to the
 * user's own topic.
 */
object Ntfy {
    data class Target(val url: String, val token: String?)

    private val TOPIC = Regex("^https?://[^/\\s]+(/[^/\\s]+)*/[-_A-Za-z0-9]{1,64}$")

    fun parse(value: String): Target? {
        val parts = value.trim().split(Regex("\\s+"))
        val url = parts.firstOrNull()?.trimEnd('/') ?: return null
        if (!TOPIC.matches(url) || parts.size > 2) return null
        val token = parts.getOrNull(1)
        if (token != null && !token.startsWith("tk_")) return null
        return Target(url, token)
    }

    /** ntfy caps a message at 4,096 bytes; longer text is cut so it still arrives. */
    suspend fun publish(http: HttpClient, target: Target, title: String?, message: String, priority: Int? = null, click: String? = null) {
        val response = http.post(target.url) {
            target.token?.let { header("Authorization", "Bearer $it") }
            title?.takeIf { it.isNotBlank() }?.let { header("Title", it.replace(Regex("[\\r\\n]+"), " ").take(250)) }
            priority?.let { header("Priority", it.coerceIn(1, 5).toString()) }
            click?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { header("Click", it) }
            header("Markdown", "yes")
            setBody(truncateUtf8(message, 4_000))
        }
        if (response.status.value !in 200..299) {
            throw ToolException(
                when (response.status.value) {
                    401, 403 -> "ntfy refused the message. The topic may need an access token; ask the user to check it in Settings → Integrations → ntfy."
                    429 -> "ntfy rate limit reached; try again later."
                    else -> "ntfy error ${response.status.value}: ${response.bodyAsText().take(200)}"
                },
            )
        }
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray().size <= maxBytes) return s
        var cut = s.take(maxBytes)
        while (cut.toByteArray().size > maxBytes - 3) cut = cut.dropLast(1)
        return "$cut…"
    }
}
