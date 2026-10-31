package dev.locus.core.data.search

/** Snippet text on one line, with the ranges of [text] that matched the search. */
data class Highlighted(val text: String, val matches: List<IntRange> = emptyList()) {
    /** The text with each match wrapped in [open] and [close]. */
    fun marked(open: String, close: String): String = buildString {
        var at = 0
        for (r in matches) {
            append(text, at, r.first)
            append(open).append(text, r.first, r.last + 1).append(close)
            at = r.last + 1
        }
        append(text, at, text.length)
    }
}

/** Turns an FTS5 snippet (matches between [SearchText.MATCH_START] and [SearchText.MATCH_END]) into display text. */
object SearchSnippet {
    private val link = Regex("\\[([^\\]\\n]*)]\\([^)\\s]*\\)")
    private val heading = Regex("(^|\\n)\\s{0,3}#{1,6}\\s+")
    private val quote = Regex("(^|\\n)\\s{0,3}>\\s?")
    private val emphasis = Regex("\\*{1,3}|`+|~~")
    private val space = Regex("\\s+")

    /**
     * Markdown marks are dropped (links keep their text), whitespace is folded onto one line, and
     * markers that don't pair up are ignored, so a snippet always renders.
     */
    fun parse(raw: String): Highlighted {
        val plain = raw
            .replace(link, "$1")
            .replace(heading, "$1")
            .replace(quote, "$1")
            .replace(emphasis, "")
            .replace(space, " ")
            .trim()
        val text = StringBuilder()
        val matches = mutableListOf<IntRange>()
        var start = -1
        for (c in plain) {
            when (c) {
                SearchText.MATCH_START -> if (start < 0) start = text.length
                SearchText.MATCH_END -> {
                    if (start in 0 until text.length) matches += start until text.length
                    start = -1
                }
                else -> text.append(c)
            }
        }
        if (start in 0 until text.length) matches += start until text.length
        // Markers kept whitespace at the ends from being trimmed above; trim it now and keep the ranges on the text.
        val lead = text.length - text.trimStart().length
        val trimmed = text.trim().toString()
        return Highlighted(
            trimmed,
            matches.map { (it.first - lead).coerceAtLeast(0)..(it.last - lead).coerceAtMost(trimmed.length - 1) }
                .filter { !it.isEmpty() && trimmed.substring(it).isNotBlank() },
        )
    }
}
