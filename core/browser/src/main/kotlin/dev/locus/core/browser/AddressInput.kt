package dev.locus.core.browser

import dev.locus.core.agent.Taint
import java.net.URLEncoder

/**
 * What the user typed into the full-screen browser's address bar: an address to open, or words to
 * search for. [Target.named] are the hosts the text names, by the same rule as a chat message
 * ([Taint.hostsIn]), so a site the user typed there counts as one they named for taint
 * (docs/design/taint.md, "The address bar"). Only this text counts: the page's own URL, its redirects
 * and links never add a host, because the bar starts empty when the user edits it.
 */
object AddressInput {
    data class Target(val url: String, val named: Set<String>)

    const val ONLY_WEB = "Only web addresses (http or https) open here."

    /** Null for blank text; throws [IllegalArgumentException] with [ONLY_WEB] for another scheme (file:, intent:…). */
    fun parse(raw: String): Target? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        if (scheme != null) {
            require(scheme == "http" || scheme == "https") { ONLY_WEB }
            return Target(text, Taint.hostsIn(listOf(text)))
        }
        if (HOST_LIKE.matches(text)) {
            val host = text.substringBefore('/').substringBefore('?').substringBefore('#')
            val url = (if (local(host)) "http://" else "https://") + text
            return Target(url, Taint.hostsIn(listOf(url)))
        }
        // Words: a search. Hosts the words name still count, as they would in a chat message.
        return Target(SEARCH + URLEncoder.encode(text, "UTF-8"), Taint.hostsIn(listOf(text)))
    }

    private fun local(hostPort: String): Boolean {
        val host = (if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']') else hostPort.substringBefore(':')).lowercase()
        return host == "localhost" || host.endsWith(".localhost") || host == "::1" || IPV4.matches(host)
    }

    private const val SEARCH = "https://duckduckgo.com/?q="
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val HOST_LIKE = Regex(
        "^(localhost|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\\.[a-z]{2,24}|\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9a-f:]+])" +
            "(?::\\d{1,5})?(?:[/?#]\\S*)?$",
        RegexOption.IGNORE_CASE,
    )
}
