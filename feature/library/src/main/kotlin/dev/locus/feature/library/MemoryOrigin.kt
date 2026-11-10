package dev.locus.feature.library

import android.content.res.Resources
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
    resources: Resources,
    origin: MemoryProvenance,
    /** The chat it was saved in, when that chat still exists. */
    chat: MemoryChat?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): MemoryOriginView {
    val savedInChat = origin.chatId != null && origin.by != MemoryAuthor.USER
    val who = resources.getString(
        when (origin.by) {
            MemoryAuthor.USER -> R.string.library_memory_saved_by_you
            MemoryAuthor.REFLECTION -> R.string.library_memory_saved_after_task
            else -> if (savedInChat) R.string.library_memory_saved_in_chat else R.string.library_memory_saved_by_locus
        },
    )
    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(origin.savedAt).atZone(zone))
    val warning = if (!origin.tainted) null else resources.getString(R.string.library_memory_tainted, taintSources(resources, origin.taintedBy))
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
internal fun taintSources(resources: Resources, tools: List<String>): String = tools.map { tool ->
    when {
        tool == "web_search" -> resources.getString(R.string.library_memory_taint_search)
        tool == "fetch_url" || tool == "web_read" || tool == "wikipedia" || tool.startsWith("browser_") -> resources.getString(R.string.library_memory_taint_web_page)
        tool.startsWith("email_") -> resources.getString(R.string.library_memory_taint_email)
        tool.startsWith("github_") -> "GitHub"
        tool.startsWith("mcp_") || tool.startsWith("connector_") -> resources.getString(R.string.library_memory_taint_connector)
        tool == "run_command" || tool.startsWith("linux_") || tool.startsWith("ssh_") -> resources.getString(R.string.library_memory_taint_command)
        tool.startsWith("agent_") -> resources.getString(R.string.library_memory_taint_helper)
        // A tool Locus has no words for is named by its id.
        else -> tool.replace('_', ' ')
    }
}.distinct().joinToString(resources.getString(R.string.library_memory_taint_separator)).ifEmpty { resources.getString(R.string.library_memory_taint_outside) }
