package dev.locus.core.agent.tools

import dev.locus.core.agent.ToolException

/**
 * Text replacement as `edit_file` and `multi_edit` do it: the text must be found, and found once
 * unless every occurrence is wanted. A miss says why and shows the file's real text nearby, so the
 * model can copy it rather than guess again (the most common way coding agents stall).
 *
 * When old_text isn't in the file exactly, three more lenient tiers are tried in turn, as Codex's
 * `seek_sequence` and opencode's edit tool do: typographic look-alikes (curly quotes, dashes, odd
 * spaces) read as their plain forms, then whole lines compared without trailing whitespace, then
 * whole lines whose indentation differs by the same amount on every line (new_text is re-indented by
 * that amount). A lenient tier applies only when it finds exactly one place, and the result says
 * which tier matched; otherwise the miss is reported with the nearest real text.
 */
internal object TextEdits {
    /** [note] is set when old_text only matched after ignoring a difference, and says which. */
    data class Applied(val content: String, val replacements: Int, val note: String? = null)

    /**
     * Replaces [oldText] with [newText] in [content]. [lenient] allows the tiers described above; it is
     * off when the file changed since the chat read it, so then only an exact match is trusted.
     */
    fun apply(content: String, oldText: String, newText: String, replaceAll: Boolean, lenient: Boolean = true): Applied {
        if (oldText.isEmpty()) throw ToolException("old_text is empty. To create a file or replace all of it, use write_file.")
        if (oldText == newText) throw ToolException("old_text and new_text are the same, so there is nothing to change.")
        // Files with Windows line endings: the model writes \n, the file has \r\n.
        val crlf = "\r\n" in content && '\r' !in oldText
        val old = if (crlf) oldText.replace("\n", "\r\n") else oldText
        val new = if (crlf) newText.replace("\n", "\r\n") else newText
        val offsets = occurrences(content, old)
        when {
            offsets.isEmpty() -> return (if (lenient) lenientApply(content, old, oldText, new, newText, crlf) else null)
                ?: throw ToolException(notFound(content, oldText))
            offsets.size > 1 && !replaceAll -> throw ToolException(
                "old_text appears ${offsets.size} times (at lines ${offsets.take(10).joinToString { lineOf(content, it).toString() }}${if (offsets.size > 10) ", …" else ""}). " +
                    "Include more surrounding lines so it matches once, or set replace_all to true to change every occurrence.",
            )
        }
        return Applied(splice(content, offsets, old.length, new), offsets.size)
    }

    private fun splice(content: String, offsets: List<Int>, oldLength: Int, new: String): String {
        val out = StringBuilder(content.length + (new.length - oldLength) * offsets.size)
        var last = 0
        for (i in offsets) {
            out.append(content, last, i).append(new)
            last = i + oldLength
        }
        out.append(content, last, content.length)
        return out.toString()
    }

    /** The lenient tiers, or null when none of them finds exactly one place. */
    private fun lenientApply(content: String, old: String, oldText: String, new: String, newText: String, crlf: Boolean): Applied? {
        // Tier 1: typographic look-alikes. The mapping is one character to one, so offsets carry over.
        val typographic = occurrences(plain(content), plain(old))
        if (typographic.size == 1) {
            return Applied(
                splice(content, typographic, old.length, new), 1,
                "old_text matched at line ${lineOf(content, typographic.single())} only after reading curly quotes, dashes or special spaces as plain ones, so the edit was made there.",
            )
        }
        if (typographic.size > 1) return null

        val lines = content.split('\n')
        val wanted = oldText.split('\n')
        val wholeLines = wanted.size > 1 && wanted.last().isEmpty()
        val oldLines = if (wholeLines) wanted.dropLast(1) else wanted
        if (oldLines.all { it.isBlank() }) return null
        fun body(line: String) = plain(line.removeSuffix("\r"))

        // Tier 2: whole lines, ignoring trailing whitespace.
        val trailing = candidates(lines, oldLines) { file, want -> body(file).trimEnd() == plain(want).trimEnd() }
        if (trailing.size == 1) {
            val at = trailing.single()
            return Applied(
                replaceLines(lines, at, oldLines.size, replacementLines(newText, wholeLines), crlf), 1,
                "old_text matched lines ${at + 1}–${at + oldLines.size} only after ignoring trailing whitespace, so the edit was made there.",
            )
        }
        if (trailing.size > 1) return null

        // Tier 3: whole lines whose indentation differs by the same amount on every line.
        val indented = candidates(lines, oldLines) { file, want -> body(file).trim() == plain(want).trim() }
            .mapNotNull { at -> indentShift(lines.subList(at, at + oldLines.size).map(::body), oldLines.map(::plain))?.let { at to it } }
        if (indented.size != 1) return null
        val (at, shift) = indented.single()
        return Applied(
            replaceLines(lines, at, oldLines.size, replacementLines(newText, wholeLines).map(shift::applyTo), crlf), 1,
            "old_text matched lines ${at + 1}–${at + oldLines.size} only with different indentation (the file has ${shift.describe()}), " +
                "so the edit was made there with new_text re-indented to match. Check the lines below.",
        )
    }

    /** Start indexes (0-based) where every line of [want] matches the file's lines by [same]. */
    private fun candidates(lines: List<String>, want: List<String>, same: (String, String) -> Boolean): List<Int> =
        (0..lines.size - want.size).filter { i -> want.indices.all { same(lines[i + it], want[it]) } }

    /** new_text as lines; with [wholeLines], a trailing newline closes the last line rather than adding one. */
    private fun replacementLines(newText: String, wholeLines: Boolean): List<String> = when {
        newText.isEmpty() -> if (wholeLines) emptyList() else listOf("")
        wholeLines && newText.endsWith("\n") -> newText.removeSuffix("\n").split('\n')
        else -> newText.split('\n')
    }

    private fun replaceLines(lines: List<String>, at: Int, count: Int, replacement: List<String>, crlf: Boolean): String {
        // In a CRLF file each line keeps its \r, except a last line that had none.
        val lastHadCr = lines[at + count - 1].endsWith("\r")
        val fixed = replacement.mapIndexed { i, line ->
            val bare = line.removeSuffix("\r")
            if (crlf && (i < replacement.lastIndex || lastHadCr)) "$bare\r" else bare
        }
        return (lines.subList(0, at) + fixed + lines.subList(at + count, lines.size)).joinToString("\n")
    }

    /** Indentation to add to (or remove from) each of the model's lines to get the file's. */
    private class Shift(val add: Boolean, val by: String) {
        fun applyTo(line: String): String = when {
            line.isBlank() -> line
            add -> by + line
            else -> line.removePrefix(by)
        }

        fun describe(): String {
            val tabs = by.count { it == '\t' }
            val spaces = by.length - tabs
            val amount = listOfNotNull(
                tabs.takeIf { it > 0 }?.let { "$it ${if (it == 1) "tab" else "tabs"}" },
                spaces.takeIf { it > 0 }?.let { "$it ${if (it == 1) "space" else "spaces"}" },
            ).joinToString(" and ")
            return "$amount ${if (add) "more" else "less"} on each line"
        }
    }

    /** The one indentation change that turns every non-blank line of [want] into [file]'s, or null. */
    private fun indentShift(file: List<String>, want: List<String>): Shift? {
        var shift: Shift? = null
        for (i in file.indices) {
            if (file[i].isBlank() && want[i].isBlank()) continue
            val have = file[i].takeWhile { it == ' ' || it == '\t' }
            val asked = want[i].takeWhile { it == ' ' || it == '\t' }
            val here = when {
                have.startsWith(asked) -> Shift(add = true, by = have.drop(asked.length))
                asked.startsWith(have) -> Shift(add = false, by = asked.drop(have.length))
                else -> return null
            }
            if (shift == null) shift = here else if (shift.add != here.add || shift.by != here.by) return null
        }
        return shift?.takeIf { it.by.isNotEmpty() }
    }

    /** Curly quotes, dashes and special spaces as their plain forms, one character for one (like opencode's normalizeForMatch). */
    private fun plain(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) {
            out[i] = when (val c = s[i]) {
                '‘', '’', '‚', '‛' -> '\''
                '“', '”', '„', '‟' -> '"'
                '‐', '‑', '‒', '–', '—', '―', '−' -> '-'
                ' ', ' ', ' ', '　', in ' '..' ' -> ' '
                else -> c
            }
        }
        return String(out)
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
        // Same lines apart from whitespace (inside lines, or indentation that isn't a uniform shift), or in more than one place.
        val squashed = wanted.map { squash(it) }
        if (squashed.any { it.isNotEmpty() }) {
            val places = (0..lines.size - wanted.size).filter { i -> wanted.indices.all { squash(lines[i + it]) == squashed[it] } }
            if (places.isNotEmpty()) {
                val i = places.first()
                val where = if (places.size > 1) " It matches ${places.size} places that way (lines ${places.take(10).joinToString { (it + 1).toString() }}), so include more lines to pick one." else ""
                return "$hint Lines ${i + 1}–${i + wanted.size} match it if whitespace is ignored, so the indentation or spacing differs.$where " +
                    "Copy the text exactly as it is:\n${numbered(lines, i + 1, i + wanted.size)}"
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

    /** A line with all whitespace runs collapsed and typographic look-alikes made plain, for the miss hint. */
    private fun squash(s: String): String = plain(s).trim().replace(WHITESPACE, " ")

    private fun tokens(s: String): Set<String> = WORD.findAll(s).map { it.value }.toSet()

    private fun similarity(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

    private const val CONTEXT = 3
    private const val MAX_SNIPPET = 30
    const val MAX_LINE = 2000
    private val WORD = Regex("[A-Za-z0-9_]+|[^\\sA-Za-z0-9_]")
    private val WHITESPACE = Regex("\\s+")
    private val LINE_NUMBER_PREFIX = Regex("^\\s*\\d+\\t")
}
