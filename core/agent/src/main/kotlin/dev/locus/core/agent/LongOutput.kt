package dev.locus.core.agent

/**
 * Shortens a long tool result to its start and end, with a line saying how much was cut and where all
 * of it is saved ([SavedOutput]). Marked outside text ([UntrustedContent]) stays balanced: a block the
 * cut runs through is closed before the note and opened again after it, so the note, which is
 * Locus's own, always sits outside any block and no text loses its marking. See
 * docs/design/tool-output.md.
 */
object LongOutput {
    /** What a chat's agent sees inline of a long result. */
    const val CHAT_INLINE_CHARS = 16_000

    /** What a helper sees inline: helpers read a lot and have small budgets. */
    const val HELPER_INLINE_CHARS = 8_000

    /** The share of the inline text taken from the start; the rest is the end. */
    private const val HEAD_SHARE = 2.0 / 3

    /** How far a cut may move to land on a line break. */
    private const val LINE_SLACK = 400

    /**
     * [content] shortened to about [limit] characters (head and tail), with a note naming [savedTo],
     * where the whole of it was saved. When [content] already fits and [savedTo] is set (a tool that
     * shortened its own output, like run_command), only the note is added. Unchanged when it fits and
     * nothing was saved.
     */
    fun shorten(content: String, limit: Int, savedTo: String?): String {
        if (content.length <= limit) return if (savedTo == null) content else "$content\n${note(0, savedTo)}"
        var headEnd = lineBreakBefore(content, (limit * HEAD_SHARE).toInt().coerceAtLeast(1))
        var tailStart = lineBreakAfter(content, (content.length - (limit - headEnd)).coerceAtLeast(headEnd))
        headEnd = outsideTag(content, headEnd)
        tailStart = outsideTag(content, tailStart)
        if (tailStart <= headEnd) return content
        val head = content.substring(0, headEnd)
        val tail = content.substring(tailStart)
        // Inside a block at the cut: close it before the note, open it again after.
        val headOpen = head.lastIndexOf(UntrustedContent.OPEN) > head.lastIndexOf(UntrustedContent.CLOSE)
        val firstClose = tail.indexOf(UntrustedContent.CLOSE)
        val firstOpen = tail.indexOf(UntrustedContent.OPEN)
        val tailInside = firstClose >= 0 && (firstOpen < 0 || firstClose < firstOpen)
        return buildString {
            append(head.trimEnd('\n'))
            if (headOpen) append('\n').append(UntrustedContent.CLOSE)
            append('\n').append(note(tailStart - headEnd, savedTo)).append('\n')
            if (tailInside) append(UntrustedContent.OPEN).append('\n')
            append(tail.trimStart('\n'))
        }
    }

    /** The line between head and tail; [cut] is 0 when nothing was cut here (the tool shortened it). */
    fun note(cut: Int, savedTo: String?): String {
        val what = if (cut > 0) "[… ${"%,d".format(java.util.Locale.ROOT, cut)} characters cut from the middle." else "[This output was shortened."
        return if (savedTo == null) {
            "$what It wasn't saved; ask for less at a time.]"
        } else {
            "$what Full output: $savedTo. Search it with search_files (path=$savedTo) or page through it with read_file (start_line, max_lines).]"
        }
    }

    private fun lineBreakBefore(text: String, at: Int): Int {
        val i = text.lastIndexOf('\n', at)
        val end = if (i >= 0 && at - i <= LINE_SLACK) i + 1 else at
        return noSplitSurrogate(text, end)
    }

    private fun lineBreakAfter(text: String, at: Int): Int {
        val i = text.indexOf('\n', at)
        val start = if (i >= 0 && i - at <= LINE_SLACK) i + 1 else at
        return noSplitSurrogate(text, start)
    }

    private fun noSplitSurrogate(text: String, at: Int): Int =
        if (at in 1 until text.length && text[at - 1].isHighSurrogate() && text[at].isLowSurrogate()) at - 1 else at

    /** [at], moved to the start of a marker it falls inside, so no marker is cut in two. */
    private fun outsideTag(text: String, at: Int): Int {
        for (tag in listOf(UntrustedContent.OPEN, UntrustedContent.CLOSE)) {
            val from = (at - tag.length + 1).coerceAtLeast(0)
            val i = text.indexOf(tag, from)
            if (i in from until at && i + tag.length > at) return i
        }
        return at
    }
}
