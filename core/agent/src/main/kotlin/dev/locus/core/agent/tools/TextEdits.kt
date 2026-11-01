package dev.locus.core.agent.tools

import dev.locus.core.agent.ToolException

/**
 * Exact-text replacement as `edit_file` and `multi_edit` do it: the text must be found, and found once
 * unless every occurrence is wanted. A miss says why and shows the file's real text nearby, so the
 * model can copy it rather than guess again (the most common way coding agents stall).
 */
internal object TextEdits {
    data class Applied(val content: String, val replacements: Int, val firstOffset: Int, val newLength: Int)

    fun apply(content: String, oldText: String, newText: String, replaceAll: Boolean): Applied {
        if (oldText.isEmpty()) throw ToolException("old_text is empty. To create a file or replace all of it, use write_file.")
        if (oldText == newText) throw ToolException("old_text and new_text are the same, so there is nothing to change.")
        // Files with Windows line endings: the model writes \n, the file has \r\n.
        val crlf = "\r\n" in content && '\r' !in oldText
        val old = if (crlf) oldText.replace("\n", "\r\n") else oldText
        val new = if (crlf) newText.replace("\n", "\r\n") else newText
        val offsets = occurrences(content, old)
        when {
            offsets.isEmpty() -> throw ToolException(notFound(content, oldText))
            offsets.size > 1 && !replaceAll -> throw ToolException(
                "old_text appears ${offsets.size} times (at lines ${offsets.take(10).joinToString { lineOf(content, it).toString() }}${if (offsets.size > 10) ", …" else ""}). " +
                    "Include more surrounding lines so it matches once, or set replace_all to true to change every occurrence.",
            )
        }
        val out = StringBuilder(content.length + (new.length - old.length) * offsets.size)
        var last = 0
        for (i in offsets) {
            out.append(content, last, i).append(new)
            last = i + old.length
        }
        out.append(content, last, content.length)
        return Applied(out.toString(), offsets.size, offsets.first(), new.length)
    }

    /** Non-overlapping start offsets of [needle] in [text]. */
    fun occurrences(text: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return found
            found += i
            from = i + needle.length
        }
    }

    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    /** Lines [from]..[to] (1-based, clamped) with read_file's number prefix. */
    fun numbered(lines: List<String>, from: Int, to: Int): String {
        val a = from.coerceAtLeast(1)
        val b = to.coerceAtMost(lines.size)
        return (a..b).joinToString("\n") { "$it\t${lines[it - 1].removeSuffix("\r").take(MAX_LINE)}" }
    }

    /** The edited region with a few lines either side, so the model sees the result without a re-read. */
    fun snippet(content: String, offset: Int, length: Int): String {
        val lines = content.split('\n')
        val start = lineOf(content, offset)
        val end = lineOf(content, offset + length)
        val to = if (end - start > MAX_SNIPPET) start + MAX_SNIPPET else end + CONTEXT
        return numbered(lines, start - CONTEXT, to) + if (end - start > MAX_SNIPPET) "\n…" else ""
    }

    private fun notFound(content: String, oldText: String): String {
        val lines = content.split('\n')
        val wanted = oldText.split('\n').let { if (it.size > 1 && it.last().isBlank()) it.dropLast(1) else it }
        val hint = StringBuilder("old_text was not found in the file.")
        if (LINE_NUMBER_PREFIX.containsMatchIn(wanted.first())) {
            hint.append(" It looks like it includes read_file's line-number prefixes (\"12\\t\"); leave those out.")
        }
        // Same lines apart from indentation or trailing spaces.
        val trimmed = wanted.map { it.trim() }
        if (trimmed.any { it.isNotEmpty() }) {
            for (i in 0..lines.size - wanted.size) {
                if ((wanted.indices).all { lines[i + it].trim() == trimmed[it] }) {
                    return "$hint Lines ${i + 1}–${i + wanted.size} match it if whitespace is ignored, so the indentation or spacing differs. " +
                        "Copy the text exactly as it is:\n${numbered(lines, i + 1, i + wanted.size)}"
                }
            }
        }
        // Otherwise point at the line most like old_text's first line.
        val first = wanted.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val words = tokens(first)
        if (words.isNotEmpty()) {
            val best = lines.indices.maxByOrNull { similarity(words, tokens(lines[it])) }
            if (best != null && similarity(words, tokens(lines[best])) >= 0.5) {
                return "$hint The closest text is near line ${best + 1}; the file may differ from what you expect. It reads:\n" +
                    numbered(lines, best + 1 - CONTEXT, best + 1 + maxOf(CONTEXT, wanted.size))
            }
        }
        return "$hint Nothing similar either; the file may have changed. Read it again with read_file and copy the exact text."
    }

    private fun tokens(s: String): Set<String> = WORD.findAll(s).map { it.value }.toSet()

    private fun similarity(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

    private const val CONTEXT = 3
    private const val MAX_SNIPPET = 30
    const val MAX_LINE = 2000
    private val WORD = Regex("[A-Za-z0-9_]+|[^\\sA-Za-z0-9_]")
    private val LINE_NUMBER_PREFIX = Regex("^\\s*\\d+\\t")
}
