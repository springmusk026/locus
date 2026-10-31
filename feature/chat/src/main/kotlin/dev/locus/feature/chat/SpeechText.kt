package dev.locus.feature.chat

/**
 * Turns a reply's Markdown into what a voice should say: no syntax, "Code block." for code, "Table."
 * for tables, link text instead of URLs, and no thinking. Pure, so it is unit tested; [ReadAloud] speaks it.
 */
internal object SpeechText {

    const val CODE = "Code block."
    const val TABLE = "Table."
    const val LINK = "link"

    fun prepare(markdown: String): String {
        val text = markdown.replace(THINK, " ")
        val out = mutableListOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val fence = FENCE.matchEntire(trimmed)
            when {
                fence != null -> {
                    // Skip to the closing fence of the same kind (or the end: an unclosed block is still code).
                    val marker = fence.groupValues[1]
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(marker)) i++
                    out += CODE
                }
                trimmed.startsWith("|") || (trimmed.contains('|') && i + 1 < lines.size && TABLE_RULE.matches(lines[i + 1].trim())) -> {
                    while (i + 1 < lines.size && lines[i + 1].trim().let { it.startsWith("|") || (it.contains('|') && it.isNotBlank()) }) i++
                    out += TABLE
                }
                RULE.matches(trimmed) || trimmed.isEmpty() -> out += ""
                else -> inline(blockPrefix(trimmed)).takeIf { it.isNotBlank() }?.let { out += sentence(it) }
            }
            i++
        }
        return out.joinToString("\n").replace(BLANKS, "\n\n").trim()
    }

    /**
     * Splits [text] into pieces no longer than [max] characters, breaking between paragraphs, then
     * sentences, then words, and only mid-word when a single word is longer than [max].
     */
    fun chunks(text: String, max: Int): List<String> {
        require(max > 0)
        val pieces = text.split(PARAGRAPH).flatMap { p -> if (p.length <= max) listOf(p) else p.split(SENTENCE_END) }
            .flatMap { s -> if (s.length <= max) listOf(s) else s.split(' ') }
            .flatMap { w -> if (w.length <= max) listOf(w) else w.chunked(max) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        pieces.forEach { piece ->
            if (current.isNotEmpty() && current.length + 1 + piece.length > max) {
                result += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    /** Drops heading, quote and list markers from the start of a line. */
    private fun blockPrefix(line: String): String =
        line.replace(HEADING, "").replace(QUOTE, "").replace(TASK, "").replace(BULLET, "").replace(NUMBERED, "$1. ")

    private fun inline(text: String): String = text
        .replace(IMAGE) { m -> m.groupValues[1] }
        .replace(LINK_MD) { m -> m.groupValues[1] }
        .replace(AUTOLINK, LINK)
        .replace(BARE_URL, LINK)
        .replace(HTML_TAG, "")
        .replace(INLINE_CODE) { m -> m.groupValues[2] }
        .replace(BOLD_STARS) { m -> m.groupValues[1] }
        .replace(BOLD_UNDERSCORES) { m -> m.groupValues[1] }
        .replace(STRIKE) { m -> m.groupValues[1] }
        .replace(ITALIC_STAR) { m -> m.groupValues[1] }
        .replace(ITALIC_UNDERSCORE) { m -> m.groupValues[1] }
        .replace(ESCAPE) { m -> m.groupValues[1] }
        .replace(SPACES, " ")
        .trim()

    /** Ends a line with punctuation, so the voice pauses between headings and list items. */
    private fun sentence(text: String): String = if (text.last() in ".!?:;…") text else "$text."

    private val THINK = Regex("(?s)<(think|thinking|reasoning)>.*?(</\\1>|$)")
    private val FENCE = Regex("^(`{3,}|~{3,}).*$")
    private val TABLE_RULE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val RULE = Regex("^([-*_])(\\s*\\1){2,}$")
    private val HEADING = Regex("^#{1,6}\\s+")
    private val QUOTE = Regex("^(>\\s?)+")
    private val TASK = Regex("^[-*+]\\s+\\[[ xX]]\\s+")
    private val BULLET = Regex("^[-*+]\\s+")
    private val NUMBERED = Regex("^(\\d+)[.)]\\s+")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK_MD = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val AUTOLINK = Regex("<(https?://|mailto:)[^>\\s]+>")
    private val BARE_URL = Regex("\\bhttps?://[^\\s)]+")
    private val HTML_TAG = Regex("</?[a-zA-Z][^>]*>")
    private val INLINE_CODE = Regex("(`+)(.+?)\\1")
    private val BOLD_STARS = Regex("\\*\\*(.+?)\\*\\*")
    private val BOLD_UNDERSCORES = Regex("(?<![\\w])__(.+?)__(?![\\w])")
    private val STRIKE = Regex("~~(.+?)~~")
    private val ITALIC_STAR = Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])")
    private val ITALIC_UNDERSCORE = Regex("(?<![\\w])_(?!\\s)(.+?)(?<!\\s)_(?![\\w])")
    private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>~])")
    private val SPACES = Regex("[ \\t]+")
    private val BLANKS = Regex("\\n{3,}")
    private val PARAGRAPH = Regex("\\n+")
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
}
