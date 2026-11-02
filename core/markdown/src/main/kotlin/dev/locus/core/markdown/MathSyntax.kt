package dev.locus.core.markdown

/** One formula found in a reply: its LaTeX, whether it was display maths, and the text it came from. */
data class MathFormula(val latex: String, val display: Boolean, val source: String)

/**
 * Markdown with its maths taken out, ready for the Markdown renderer.
 *
 * - Inline formulas are each replaced by one private-use character ([placeholderFor]); the renderer's
 *   annotator swaps that character for the typeset formula, or for [MathFormula.source] if the
 *   formula can't be typeset.
 * - Display formulas that sit on lines of their own become ```` ```math ```` fences, so they render as
 *   blocks. Display formulas inside a sentence stay inline.
 *
 * Code (fenced blocks and backtick spans) is never touched.
 */
data class PreparedMarkdown(val markdown: String, val formulas: List<MathFormula>) {
    /** The formula a placeholder character stands for, or null for any other character. */
    fun formulaAt(c: Char): MathFormula? {
        val index = c.code - PLACEHOLDER_BASE
        return if (index in formulas.indices) formulas[index] else null
    }
}

/** The private-use character that stands for formula [index] in [PreparedMarkdown.markdown]. */
fun placeholderFor(index: Int): Char = (PLACEHOLDER_BASE + index).toChar()

internal const val PLACEHOLDER_BASE = 0xE000

/** Private use area U+E000–U+F8FF: at most this many inline formulas per message are typeset. */
internal const val MAX_INLINE_FORMULAS = 0xF8FF - 0xE000 + 1

/** Longest inline formula we accept; longer `$` pairs are almost certainly not maths. */
private const val MAX_INLINE_LENGTH = 1_000

/** Longest display formula we accept. */
private const val MAX_DISPLAY_LENGTH = 8_000

/**
 * Finds the maths in [text] and returns it prepared for rendering (see [PreparedMarkdown]).
 *
 * Delimiters: `$…$` and `\(…\)` inline, `$$…$$` and `\[…\]` display. A backslash before `$` makes it a
 * literal dollar. To keep prices from turning into maths, single dollars follow Pandoc's rules: the
 * opening `$` must have a non-space character right after it, the closing `$` a non-space character
 * right before it and no digit right after it. Beyond Pandoc, the opening `$` must not follow a letter
 * or digit ("US$5", "Ca$h"), and the first unescaped `$` after an opener must be its closer; if it
 * can't be, the opener is plain text. So "$5 and $10" and "$5-$10" stay text.
 *
 * If the text already contains private-use characters, it is returned unchanged: the placeholders
 * would be ambiguous.
 */
fun prepareMath(text: String): PreparedMarkdown {
    if (text.none { it == '$' || it == '\\' }) return PreparedMarkdown(text, emptyList())
    if (text.any { it.code in PLACEHOLDER_BASE..0xF8FF }) return PreparedMarkdown(text, emptyList())
    val formulas = ArrayList<MathFormula>()
    val out = StringBuilder(text.length)
    var proseStart = 0
    for (region in codeFenceRegions(text)) {
        rewriteProse(text, proseStart, region.first, out, formulas)
        out.append(text, region.first, region.last + 1)
        proseStart = region.last + 1
    }
    rewriteProse(text, proseStart, text.length, out, formulas)
    return PreparedMarkdown(out.toString(), formulas)
}

/**
 * Index ranges (inclusive, each covering whole lines) of fenced code blocks. An unclosed fence runs to
 * the end, as in CommonMark.
 */
internal fun codeFenceRegions(text: String): List<IntRange> {
    val regions = ArrayList<IntRange>()
    var lineStart = 0
    var openStart = -1
    var openChar = ' '
    var openLength = 0
    while (lineStart < text.length) {
        val newline = text.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) text.length else newline + 1
        val line = text.substring(lineStart, if (newline < 0) text.length else newline)
        val trimmed = line.trimStart(' ', '\t', '>')
        if (openStart < 0) {
            val run = fenceRun(trimmed)
            if (run != null) {
                // A backtick fence's info string may not contain backticks.
                val info = trimmed.substring(run.second)
                if (run.first != '`' || '`' !in info) {
                    openStart = lineStart
                    openChar = run.first
                    openLength = run.second
                }
            }
        } else {
            val run = fenceRun(trimmed)
            if (run != null && run.first == openChar && run.second >= openLength && trimmed.substring(run.second).isBlank()) {
                regions += openStart until lineEnd
                openStart = -1
            }
        }
        lineStart = lineEnd
    }
    if (openStart >= 0) regions += openStart until text.length
    return regions
}

/** The fence character and run length if [s] starts with three or more backticks or tildes. */
private fun fenceRun(s: String): Pair<Char, Int>? {
    val c = s.firstOrNull() ?: return null
    if (c != '`' && c != '~') return null
    var n = 0
    while (n < s.length && s[n] == c) n++
    return if (n >= 3) c to n else null
}

private fun rewriteProse(text: String, from: Int, to: Int, out: StringBuilder, formulas: MutableList<MathFormula>) {
    var i = from
    var copied = from
    fun emitInline(start: Int, end: Int, latex: String, display: Boolean) {
        if (formulas.size >= MAX_INLINE_FORMULAS) return
        out.append(text, copied, start)
        val source = text.substring(start, end)
        formulas += MathFormula(latex.trim(), display, source)
        out.append(placeholderFor(formulas.size - 1))
        copied = end
    }
    while (i < to) {
        val c = text[i]
        when {
            c == '`' -> {
                // Skip a code span: a run of n backticks up to the next run of exactly n.
                val n = runLength(text, i, to, '`')
                val close = findBacktickRun(text, i + n, to, n)
                i = if (close >= 0) close + n else i + n
            }
            c == '\\' && i + 1 < to -> {
                val next = text[i + 1]
                if (next == '(' || next == '[') {
                    val display = next == '['
                    val closer = if (display) "\\]" else "\\)"
                    val end = findCloser(text, i + 2, to, closer, if (display) MAX_DISPLAY_LENGTH else MAX_INLINE_LENGTH)
                    val latex = if (end >= 0) text.substring(i + 2, end) else ""
                    if (end >= 0 && latex.isNotBlank()) {
                        val stop = end + 2
                        if (display && emitDisplayBlock(text, i, stop, latex, from, to, out, copied)) {
                            copied = lineEndAfter(text, stop, to)
                            i = copied
                        } else {
                            emitInline(i, stop, latex, display)
                            i = stop
                        }
                        continue
                    }
                }
                i += 2 // An escaped character, including \$ and \\, is never a delimiter.
            }
            c == '$' && i + 1 < to && text[i + 1] == '$' -> {
                val end = findCloser(text, i + 2, to, "$$", MAX_DISPLAY_LENGTH)
                val latex = if (end >= 0) text.substring(i + 2, end) else ""
                if (end >= 0 && latex.isNotBlank()) {
                    val stop = end + 2
                    if (emitDisplayBlock(text, i, stop, latex, from, to, out, copied)) {
                        copied = lineEndAfter(text, stop, to)
                        i = copied
                    } else {
                        emitInline(i, stop, latex, true)
                        i = stop
                    }
                } else {
                    i += 2
                }
            }
            c == '$' -> {
                val end = inlineDollarEnd(text, i, to)
                if (end >= 0) {
                    emitInline(i, end + 1, text.substring(i + 1, end), false)
                    i = end + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }
    out.append(text, copied, to)
}

/**
 * The index of the `$` that closes the inline formula opened at [open], or -1 if that `$` isn't an
 * opener or has no valid closer (see [prepareMath] for the rules).
 */
internal fun inlineDollarEnd(text: String, open: Int, to: Int): Int {
    if (open + 1 >= to) return -1
    val after = text[open + 1]
    if (after.isWhitespace() || after == '$') return -1
    if (open > 0 && text[open - 1].isLetterOrDigit()) return -1
    var j = open + 1
    while (j < to && j - open <= MAX_INLINE_LENGTH) {
        val c = text[j]
        when {
            c == '\\' -> j += 2
            c == '\n' && isBlankLineAhead(text, j + 1, to) -> return -1
            c == '$' -> {
                val before = text[j - 1]
                val next = if (j + 1 < to) text[j + 1] else ' '
                return if (!before.isWhitespace() && !next.isDigit() && next != '$') j else -1
            }
            else -> j++
        }
    }
    return -1
}

/** The start of [closer] after [from], not crossing a blank line, within [maxLength]; else -1. */
private fun findCloser(text: String, from: Int, to: Int, closer: String, maxLength: Int): Int {
    var j = from
    while (j < to && j - from <= maxLength) {
        val c = text[j]
        if (text.startsWith(closer, j)) return j
        if (c == '\n' && isBlankLineAhead(text, j + 1, to)) return -1
        // In $$…$$ an escaped character can't close; \] and \) are themselves backslash pairs.
        j += if (c == '\\' && closer[0] == '$') 2 else 1
    }
    return -1
}

private fun isBlankLineAhead(text: String, from: Int, to: Int): Boolean {
    var k = from
    while (k < to && (text[k] == ' ' || text[k] == '\t' || text[k] == '>')) k++
    return k >= to || text[k] == '\n' || text[k] == '\r'
}

private fun runLength(text: String, from: Int, to: Int, c: Char): Int {
    var n = 0
    while (from + n < to && text[from + n] == c) n++
    return n
}

private fun findBacktickRun(text: String, from: Int, to: Int, n: Int): Int {
    var j = from
    while (j < to) {
        if (text[j] == '`') {
            val run = runLength(text, j, to, '`')
            if (run == n) return j
            j += run
        } else {
            j++
        }
    }
    return -1
}

private fun lineEndAfter(text: String, from: Int, to: Int): Int {
    val newline = text.indexOf('\n', from)
    return if (newline < 0 || newline >= to) to else newline + 1
}

/**
 * Writes a display formula that occupies whole lines as a ```` ```math ```` fence and returns true.
 * Returns false (the caller keeps it inline) when other text shares its first or last line, or the
 * line starts with something we can't repeat on every line of a fence.
 */
private fun emitDisplayBlock(
    text: String,
    start: Int,
    stop: Int,
    latex: String,
    from: Int,
    to: Int,
    out: StringBuilder,
    copied: Int,
): Boolean {
    val lineStart = maxOf(from, text.lastIndexOf('\n', start - 1) + 1)
    if (lineStart < copied) return false
    val prefix = text.substring(lineStart, start)
    val lineEnd = lineEndAfter(text, stop, to)
    if (text.substring(stop, lineEnd).isNotBlank()) return false
    val indent = fenceIndent(prefix) ?: return false
    out.append(text, copied, lineStart)
    val body = latex.lines().map { stripQuotePrefix(it, prefix) }.joinToString("\n").trim()
    if (body.isBlank()) return false
    val ticks = "`".repeat(maxOf(3, longestBacktickRun(body) + 1))
    out.append(prefix).append(ticks).append("math\n")
    body.lines().forEach { out.append(indent.continuation).append(it).append('\n') }
    out.append(indent.continuation).append(ticks).append('\n')
    return true
}

private class FenceIndent(val continuation: String)

/**
 * How the lines after the first are indented, given the text before the opening delimiter: plain
 * indentation repeats; blockquote markers repeat; after a list marker, spaces of the same width.
 * Anything else (text before the delimiter) returns null.
 */
private fun fenceIndent(prefix: String): FenceIndent? {
    if (prefix.isBlank()) return FenceIndent(prefix)
    if (prefix.all { it == ' ' || it == '\t' || it == '>' }) return FenceIndent(prefix)
    val listMarker = Regex("""^[ \t>]*(?:[-*+]|\d{1,9}[.)])[ \t]+$""")
    if (listMarker.matches(prefix) && '>' !in prefix) return FenceIndent(" ".repeat(prefix.length))
    return null
}

/** Inside a blockquote, the formula's own lines carry "> " markers that the fence re-adds. */
private fun stripQuotePrefix(line: String, prefix: String): String {
    if ('>' !in prefix) return line
    return line.replace(Regex("""^[ \t]*(?:>[ \t]?)+"""), "")
}

private fun longestBacktickRun(s: String): Int {
    var best = 0
    var run = 0
    for (c in s) {
        run = if (c == '`') run + 1 else 0
        if (run > best) best = run
    }
    return best
}
