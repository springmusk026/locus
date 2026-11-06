package dev.locus.core.model

/**
 * Removes characters a model reads but a person can't see from text that came from outside Locus (web
 * pages, mail, MCP replies and tool descriptions, imported skills, files). Hidden instructions written in
 * them ("ASCII smuggling") reach the model while the user, reviewing the same text, sees nothing.
 * See docs/design/taint.md, "Hidden characters".
 *
 * Removed:
 * - **Unicode tag characters** U+E0000–E007F, which mirror ASCII invisibly. Kept only inside the three
 *   RGI subdivision flags (England, Scotland, Wales: U+1F3F4, tag letters, U+E007F CANCEL TAG; UTS #51 ED-14a).
 * - **Bidi controls**: ALM, LRM, RLM, the embeddings and overrides U+202A–202E and the isolates
 *   U+2066–2069 (they reorder what a person sees; CVE-2021-42574 "Trojan Source").
 * - **Other invisible format characters** (General_Category Cf, Default_Ignorable): zero-width space, word
 *   joiner, invisible operators, BOM, soft hyphen, Mongolian vowel separator, interlinear annotation
 *   marks, the deprecated format characters U+206A–206F, Hangul fillers and Khmer inherent vowels.
 * - **Runs of selectors**: a variation selector (or combining grapheme joiner, Mongolian free variation
 *   selector) is kept only directly after a visible character, one at a time. A second one in a row, or
 *   one with nothing visible before it, is how bytes are hidden "in an emoji".
 *
 * Kept: ZWJ and ZWNJ where a script or emoji needs them (UTS #39 §3.1.1.1 contexts A1, A2, B, read
 * loosely, plus emoji ZWJ sequences, UTS #51 ED-16): one joiner between two non-ASCII letters, marks or
 * symbols, or after an Indic virama. A joiner next to ASCII, whitespace, another joiner or the start or
 * end of the text carries nothing a script needs and is removed. Line breaks, tabs and other control
 * characters are left alone, and so are visible format characters (Arabic number signs, Egyptian
 * hieroglyph controls).
 */
object HiddenText {
    /** [text] without its hidden characters, and how many code points were [removed]. */
    data class Cleaned(val text: String, val removed: Int)

    fun clean(text: String): Cleaned {
        if (!mayHide(text)) return Cleaned(text, 0)
        val out = StringBuilder(text.length)
        var removed = 0
        var i = 0
        // The last code point kept, for the joiner and selector rules.
        var last = -1
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                cp == BLACK_FLAG -> {
                    val end = subdivisionFlagEnd(text, i + n)
                    if (end > 0) {
                        out.append(text, i, end)
                        last = TAG_CANCEL
                        i = end
                        continue
                    }
                    out.appendCodePoint(cp)
                    last = cp
                }
                cp in TAGS -> removed++
                isJoiner(cp) -> {
                    val next = if (i + n < text.length) text.codePointAt(i + n) else -1
                    if (keepJoiner(cp, last, out, next)) {
                        out.appendCodePoint(cp)
                        last = cp
                    } else {
                        removed++
                    }
                }
                isSelector(cp) -> {
                    // One selector after a visible character; never two in a row, never after nothing.
                    if (last >= 0 && !isSelector(last) && !isJoiner(last) && !Character.isWhitespace(last)) {
                        out.appendCodePoint(cp)
                        last = cp
                    } else {
                        removed++
                    }
                }
                isHidden(cp) -> removed++
                else -> {
                    out.appendCodePoint(cp)
                    last = cp
                }
            }
            i += n
        }
        return if (removed == 0) Cleaned(text, 0) else Cleaned(out.toString(), removed)
    }

    fun strip(text: String): String = clean(text).text

    /** How many hidden characters [text] has. */
    fun count(text: String): Int = clean(text).removed

    /** The line a tool result gets when [removed] characters were taken out of it; null when none were. */
    fun note(removed: Int, what: String = "the text above"): String? =
        if (removed <= 0) null
        else "(Locus removed $removed hidden ${if (removed == 1) "character" else "characters"} from $what: text a person can't see.)"

    /** Cheap pre-check: whether [text] has any character that could be removed. */
    private fun mayHide(text: String): Boolean {
        for (c in text) {
            val code = c.code
            if (code < 0xAD) continue
            if (c.isSurrogate()) return true
            if (isHidden(code) || isJoiner(code) || isSelector(code)) return true
        }
        return false
    }

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private const val BLACK_FLAG = 0x1F3F4
    private const val TAG_CANCEL = 0xE007F
    private val TAGS = 0xE0000..0xE007F

    /** The tag letters of the RGI subdivision flags (UTS #51 Annex C): England, Scotland, Wales. */
    private val RGI_SUBDIVISIONS = setOf("gbeng", "gbsct", "gbwls")

    /** Where an RGI subdivision flag's tags starting at [from] end (after CANCEL TAG), or -1. */
    private fun subdivisionFlagEnd(text: String, from: Int): Int {
        val spec = StringBuilder()
        var i = from
        while (i < text.length && spec.length <= 6) {
            val cp = text.codePointAt(i)
            if (cp == TAG_CANCEL) return if (spec.toString() in RGI_SUBDIVISIONS) i + Character.charCount(cp) else -1
            if (cp !in 0xE0020..0xE007E) return -1
            spec.append((cp - 0xE0000).toChar())
            i += Character.charCount(cp)
        }
        return -1
    }

    private fun isJoiner(cp: Int) = cp == ZWJ || cp == ZWNJ

    private fun isSelector(cp: Int) =
        cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp == 0x034F || cp in 0x180B..0x180D || cp == 0x180F

    /** Removed wherever it appears. */
    private fun isHidden(cp: Int): Boolean = when (cp) {
        0x00AD, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180E, 0x3164, 0xFEFF, 0xFFA0 -> true
        in 0x200B..0x200F -> cp != ZWNJ && cp != ZWJ
        in 0x202A..0x202E, in 0x2060..0x206F, in 0xFFF9..0xFFFB -> true
        else -> Character.getType(cp) == Character.FORMAT.toInt() && !isJoiner(cp) && cp !in TAGS && !isVisibleFormat(cp)
    }

    /** Format characters that render (Arabic number signs and the like) or that a script needs. */
    private fun isVisibleFormat(cp: Int) =
        cp in 0x0600..0x0605 || cp == 0x06DD || cp == 0x070F || cp in 0x0890..0x0891 || cp == 0x08E2 ||
            cp == 0x110BD || cp == 0x110CD || cp in 0x13430..0x1345F

    /**
     * A joiner is kept between two characters a script or emoji joins: non-ASCII letters, marks and
     * symbols (variation selectors and skin-tone modifiers before it count as part of the emoji). After
     * an Indic virama ZWJ may also end a cluster (UTS #39 context B), so the next character isn't checked.
     */
    private fun keepJoiner(cp: Int, last: Int, out: CharSequence, next: Int): Boolean {
        if (last < 0 || isJoiner(last)) return false
        val before = if (isSelector(last)) codePointBefore(out, out.length - Character.charCount(last)) else last
        if (before < 0 || !joinable(before)) return false
        if (cp == ZWJ && isVirama(before)) return next < 0 || !isJoiner(next)
        return next >= 0 && !isJoiner(next) && joinable(next)
    }

    private fun codePointBefore(text: CharSequence, end: Int): Int =
        if (end <= 0) -1 else Character.codePointBefore(text, end)

    private fun joinable(cp: Int): Boolean {
        if (cp < 0x80) return false
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
            Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.OTHER_SYMBOL, Character.MODIFIER_SYMBOL,
            -> true
            // Emoji blocks the JDK's tables may not know yet (newer emoji are unassigned there).
            else -> cp in 0x1F000..0x1FAFF
        }
    }

    /** The viramas of the Indic scripts (Devanagari to Sinhala, Myanmar, Khmer): where ZWJ forms half forms. */
    private fun isVirama(cp: Int): Boolean =
        cp in VIRAMAS || (cp in 0x0900..0x0DFF && (cp and 0x7F) == 0x4D) || cp == 0x0DCA

    private val VIRAMAS = setOf(0x1039, 0x103A, 0x17D2, 0x1B44, 0xA8C4, 0xA9C0, 0x11046, 0x110B9)
}
