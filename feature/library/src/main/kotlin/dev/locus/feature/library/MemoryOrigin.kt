package dev.locus.feature.library

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.agent.home.MemoryProvenance
import dev.locus.core.data.repository.MemoryChat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What Library → Memory shows about where a note came from (docs/design/memory-search.md): who saved
 * it, when, the chat it was saved in (a link when that chat still exists), and a warning when the run
 * that saved it had read outside content.
 */
internal data class MemoryOriginView(
    /** A [MemoryAuthor]. */
    val by: String,
    /** "Saved by you", "Saved by Locus in a chat", "Saved by Locus after a task". */
    val who: String,
    /** The date and time, in the user's locale: "2 Nov 2026, 14:05". */
    val date: String,
    /** The chat to open; null when it wasn't saved in one or that chat is gone. */
    val chat: MemoryChat?,
    /** Said instead of a link when the chat it was saved in has been deleted. */
    val chatGone: Boolean,
    /** Set when it was saved while reading outside content. */
    val warning: String?,
)

internal fun memoryOriginView(
    origin: MemoryProvenance,
    /** The chat it was saved in, when that chat still exists. */
    chat: MemoryChat?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): MemoryOriginView {
    val savedInChat = origin.chatId != null && origin.by != MemoryAuthor.USER
    val who = when (origin.by) {
        MemoryAuthor.USER -> "Saved by you"
        MemoryAuthor.REFLECTION -> "Saved by Locus after a task"
        else -> if (savedInChat) "Saved by Locus in a chat" else "Saved by Locus"
    }
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(origin.savedAt).atZone(zone))
    val warning = if (!origin.tainted) null else
        "Saved while reading outside content (${taintSources(origin.taintedBy)}). It may have put this here: " +
            "check it before Locus relies on it, then Edit and Save to confirm it."
    return MemoryOriginView(
        by = origin.by,
        who = who,
        date = date,
        chat = chat?.takeIf { savedInChat },
        chatGone = savedInChat && chat == null,
        warning = warning,
    )
}

/** The outside content a run had read, as kinds a person recognises: "a web page, an email". */
internal fun taintSources(tools: List<String>): String = tools.map { tool ->
    when {
        tool == "web_search" -> "web search results"
        tool == "fetch_url" || tool == "web_read" || tool == "wikipedia" || tool.startsWith("browser_") -> "a web page"
        tool.startsWith("email_") -> "an email"
        tool.startsWith("github_") -> "GitHub"
        tool.startsWith("mcp_") || tool.startsWith("connector_") -> "a connector"
        tool == "run_command" || tool.startsWith("linux_") || tool.startsWith("ssh_") -> "command output"
        tool.startsWith("agent_") -> "a helper's report"
        else -> tool.replace('_', ' ')
    }
}.distinct().joinToString(", ").ifEmpty { "outside content" }
