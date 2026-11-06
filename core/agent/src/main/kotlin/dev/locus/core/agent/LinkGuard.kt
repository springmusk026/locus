package dev.locus.core.agent

import dev.locus.core.model.LocalNetwork
import dev.locus.core.model.Message
import dev.locus.core.model.Role

/**
 * Whether tapping a link in a reply opens it at once or asks first (docs/design/taint.md, "Links in
 * replies"). A reply written after the run read someone else's text can carry a link that sends the
 * user's data out in its path or query the moment it's tapped, the same way `browser_open` could; so
 * can any link with a long query. The tap is the user's, so it's a confirmation, never a refusal.
 */
object LinkGuard {
    /**
     * Characters of query and fragment a link may carry and still open in one tap. Enough for a search
     * (`?q=kotlin+flows`) or an anchor, short of an API key or a sentence of someone's data.
     */
    const val DATA_CHARS = 40

    enum class Reason {
        /** The reply came after the run read outside content, and the host isn't one the user named or set up. */
        OUTSIDE_CONTENT,

        /** The query and fragment are longer than [DATA_CHARS]. */
        LONG_DATA,

        /** Not a web, mail or phone link (`intent:`, `file:`, `javascript:` …). */
        NOT_WEB,
    }

    /** A link that asks first: what to show and why. */
    data class Confirm(val url: String, val host: String?, val reasons: Set<Reason>, val dataChars: Int)

    /**
     * Null when [url] opens in one tap; otherwise why it asks. [outsideContent]: the reply came from a run
     * that had read outside text ([repliesAfterOutsideContent]). [namedHosts]: hosts the user typed in the
     * chat or set up in Locus ([namedHosts]).
     */
    fun check(url: String, outsideContent: Boolean, namedHosts: Set<String>): Confirm? {
        val scheme = url.substringBefore(':', "").lowercase()
        val host = when (scheme) {
            "http", "https" -> LocalNetwork.hostOf(url)
            // mailto:someone@host: the domain the mail goes to.
            "mailto" -> url.substringAfter(':').substringBefore('?').substringAfterLast('@').takeIf { '.' in it }
            else -> null
        }?.lowercase()?.trimEnd('.')
        val data = dataChars(url)
        val reasons = buildSet {
            if (scheme !in WEB_SCHEMES) add(Reason.NOT_WEB)
            if (data > DATA_CHARS) add(Reason.LONG_DATA)
            if (outsideContent && (host == null || !Taint.trusted(host, namedHosts))) add(Reason.OUTSIDE_CONTENT)
        }
        return if (reasons.isEmpty()) null else Confirm(url, host, reasons, data)
    }

    /** How many characters follow the first `?` or `#`: what the address carries beyond its path. */
    fun dataChars(url: String): Int {
        val at = url.indexOfAny(charArrayOf('?', '#'))
        return if (at < 0) 0 else url.length - at - 1
    }

    /**
     * The ids of assistant messages written after the chat first read outside content. Taint carries over
     * to every later run of the chat (the model still has that text in context), so every reply after the
     * first source counts.
     */
    fun repliesAfterOutsideContent(messages: List<Message>): Set<String> {
        val ids = mutableSetOf<String>()
        var tainted = false
        messages.forEach { m ->
            // An MCP tool run through call_tool is stored under call_tool, but its result is marked, so it counts.
            if (!tainted && Taint.sourcesIn(listOf(m)).isNotEmpty()) tainted = true
            if (tainted && m.role == Role.ASSISTANT) ids += m.id
        }
        return ids
    }

    /** Hosts the user named in this chat ([trustedUserMessages]) plus [setUp] (providers, connectors), normalised. */
    fun namedHosts(messages: List<Message>, setUp: Collection<String>): Set<String> =
        Taint.hostsIn(trustedUserMessages(messages, limit = Int.MAX_VALUE)) +
            setUp.mapNotNull { LocalNetwork.hostOf(it) ?: it.takeIf { h -> '/' !in h && h.isNotBlank() } }
                .map { it.lowercase().trimEnd('.').removePrefix("www.") }

    private val WEB_SCHEMES = setOf("http", "https", "mailto", "tel")
}
