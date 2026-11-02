package dev.locus.feature.workspace.terminal

/**
 * The chat draft for "Ask Locus about this": the selected terminal text in a code block. It only
 * prefills a new chat's composer; nothing is sent until the user sends it.
 */
object TerminalDraft {
    /** Enough for a stack trace or a build error without filling the composer with a whole log. */
    const val MAX_CHARS = 8_000

    /** Null when there's nothing to ask about. Over [MAX_CHARS], the end is kept (errors come last). */
    fun of(selected: String?): String? {
        val text = selected
            ?.replace("\r\n", "\n")
            ?.lines()?.joinToString("\n") { it.trimEnd() }
            ?.trim('\n')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val kept = if (text.length > MAX_CHARS) "…\n" + text.takeLast(MAX_CHARS).substringAfter('\n') else text
        // A fence longer than any backtick run in the text, so the block can't end early.
        val longestRun = Regex("`+").findAll(kept).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longestRun + 1))
        return "From my Locus terminal:\n$fence\n$kept\n$fence\n"
    }
}
