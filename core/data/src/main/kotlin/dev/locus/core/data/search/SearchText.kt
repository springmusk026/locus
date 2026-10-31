package dev.locus.core.data.search

import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role

/**
 * What search indexes for a message: what a person would look for, as plain text. A user message gives
 * the names of the files attached to it and the text they typed (without the notes Locus adds for the
 * model); an assistant reply gives its text. Reasoning, tool calls, tool results and compaction
 * checkpoints give nothing: they are long, mostly machine-made, and would make every search noisy.
 *
 * Stored in `messages.searchText` when a message is written. The v8 migration derives the same text
 * for older messages in SQL (`SearchMigration.BACKFILL_SEARCH_TEXT`); a test keeps the two in step.
 */
object SearchText {
    /** Where a snippet's match starts and ends (private-use characters, removed from indexed text). */
    const val MATCH_START = ''
    const val MATCH_END = ''

    fun of(role: Role, parts: List<Part>): String? {
        if (role == Role.TOOL) return null
        val names = if (role == Role.USER) parts.filterIsInstance<Part.Attachment>().joinToString(" ") { it.name } else ""
        val text = parts.filterIsInstance<Part.Text>()
            .filterNot { ModeNote.isNote(it.text) || ModeNote.isResult(it.text) }
            .joinToString("") { it.text }
        return "$names $text".filterNot { it == MATCH_START || it == MATCH_END }.trim().ifEmpty { null }
    }
}
