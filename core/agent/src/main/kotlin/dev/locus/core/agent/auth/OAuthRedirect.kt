package dev.locus.core.agent.auth

import java.net.URLDecoder

/** Parsing for the browser's redirect to a 127.0.0.1 loopback listener (RFC 8252 §7.3). */
object OAuthRedirect {
    /**
     * The query parameters of a request target such as `/auth/callback?code=…&state=…`, or null when
     * the path isn't exactly [path] (favicon requests, probes, a page poking the port). A repeated
     * parameter makes the whole request suspect, so it is refused too.
     */
    fun params(target: String, path: String): Map<String, String>? {
        if (target.substringBefore('?').substringBefore('#') != path) return null
        val query = target.substringAfter('?', "").substringBefore('#')
        val pairs = query.split('&').filter { it.isNotEmpty() }.map { part ->
            val name = decode(part.substringBefore('=')) ?: return null
            val value = decode(part.substringAfter('=', "")) ?: return null
            name to value
        }
        if (pairs.map { it.first }.toSet().size != pairs.size) return null
        return pairs.toMap()
    }

    private fun decode(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()
}
