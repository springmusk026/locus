package dev.locus.core.data.search

import java.text.Normalizer
import java.util.Locale

/**
 * A search as the user typed it: words, which match as prefixes ("kotl" finds "Kotlin"), and
 * "quoted phrases", which match those words in that order. Every term must match.
 *
 * Words are split the way the index's `unicode61` tokenizer splits text: letters, digits and combining
 * marks make words, everything else (spaces, punctuation, symbols, emoji) separates them. So nothing
 * the user types reaches the FTS5 query parser as syntax: each word becomes a quoted string, and
 * operators (`AND`, `NEAR`, `*`, `:`, `^`, a stray `"`) are either separators or plain words.
 */
class SearchQuery private constructor(
    /** Terms that search message text; empty when the query is too short for that. */
    val terms: List<Term>,
    /** Terms that search titles: [terms], or the single letters typed so far when there are no others. */
    private val titleTerms: List<Term>,
) {
    /** A word ([prefix]: matched as the start of a word) or a quoted phrase (several words in a row). */
    data class Term(val words: List<String>, val prefix: Boolean)

    val isEmpty: Boolean get() = titleTerms.isEmpty()

    /** The FTS5 MATCH expression, or null when only titles should be searched. */
    val match: String? get() = terms.takeIf { it.isNotEmpty() }?.joinToString(" ") { term ->
        "\"" + term.words.joinToString(" ").replace("\"", "\"\"") + "\"" + if (term.prefix) "*" else ""
    }

    /** Whether [title] contains every term, ignoring case and accents, words matched as prefixes. */
    fun matchesTitle(title: String): Boolean {
        if (titleTerms.isEmpty()) return false
        val words = words(title).map(::fold)
        return titleTerms.all { term ->
            val wanted = term.words.map(::fold)
            (0..words.size - wanted.size).any { start ->
                wanted.indices.all { i ->
                    val word = words[start + i]
                    if (term.prefix && i == wanted.lastIndex) word.startsWith(wanted[i]) else word == wanted[i]
                }
            }
        }
    }

    companion object {
        /** Words shorter than this are left out of message search: one letter would match nearly everything. */
        const val MIN_WORD = 2

        fun parse(raw: String): SearchQuery {
            val phrases = mutableListOf<Term>()
            val loose = StringBuilder()
            var rest = raw
            // Closed "…" pairs are phrases; an unclosed quote (still typing) leaves its words loose.
            while (true) {
                val open = rest.indexOf('"')
                val close = if (open < 0) -1 else rest.indexOf('"', open + 1)
                if (close < 0) {
                    loose.append(' ').append(rest)
                    break
                }
                loose.append(' ').append(rest, 0, open)
                words(rest.substring(open + 1, close)).takeIf { it.isNotEmpty() }?.let { phrases += Term(it, prefix = false) }
                rest = rest.substring(close + 1)
            }
            val all = words(loose.toString())
            val long = all.filter { it.length >= MIN_WORD }.map { Term(listOf(it), prefix = true) }
            val terms = phrases + long
            val titleTerms = terms.ifEmpty { all.map { Term(listOf(it), prefix = true) } }
            return SearchQuery(terms.distinct(), titleTerms.distinct())
        }

        /** Splits [text] into words as unicode61 does: runs of letters, digits and marks. */
        fun words(text: String): List<String> {
            val out = mutableListOf<String>()
            val word = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (isWordChar(cp)) {
                    word.appendCodePoint(cp)
                } else if (word.isNotEmpty()) {
                    out += word.toString()
                    word.clear()
                }
                i += Character.charCount(cp)
            }
            if (word.isNotEmpty()) out += word.toString()
            return out
        }

        private fun isWordChar(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
            Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            -> true
            else -> false
        }

        private val marks = Regex("\\p{M}+")

        /** Lower case without accents, roughly what unicode61 with remove_diacritics does. */
        fun fold(word: String): String = marks.replace(Normalizer.normalize(word, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}
