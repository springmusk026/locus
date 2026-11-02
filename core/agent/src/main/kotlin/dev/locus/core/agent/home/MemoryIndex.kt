package dev.locus.core.agent.home

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** One `memory_search` result: a topic, its index line, and where the query matched (`**` around matches). */
data class MemoryHit(
    val topic: String,
    val summary: String,
    val snippet: String?,
    /** From the user's general memory, found from a project chat that also reads it. */
    val general: Boolean = false,
)

/**
 * A memory search as the model typed it, split the way the index's `unicode61` tokenizer splits text
 * (runs of letters, digits and combining marks, in any script), so nothing reaches the FTS5 query
 * parser as syntax. See docs/design/memory-search.md.
 */
class MemoryQuery private constructor(val words: List<String>) {
    /** For the `unicode61` table: every word as a prefix, any of them; single Latin letters left out. */
    val wordMatch: String? = words.filter { it.codePointCount(0, it.length) >= 2 || !isLatin(it) }
        .takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "\"$it\"*" }

    /** For the `trigram` table: words of three characters or more, matched anywhere in the text. */
    val gramMatch: String? = words.filter { it.codePointCount(0, it.length) >= 3 }
        .takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "\"$it\"" }

    /**
     * Words of one or two characters in scripts that don't put spaces between words (東京, 猫, 서울):
     * too short for trigrams and inside a longer unicode61 token, so they're matched as substrings.
     */
    val shortTerms: List<String> = words.filter { it.codePointCount(0, it.length) < 3 && !isLatin(it) }

    val isEmpty: Boolean get() = wordMatch == null && gramMatch == null && shortTerms.isEmpty()

    /** The words that count as a match when scanning text without the index ([MemoryIndexFallback]). */
    val scanTerms: List<String> get() = words.filter { it.codePointCount(0, it.length) >= 2 || !isLatin(it) }.map(::fold)

    companion object {
        fun parse(raw: String): MemoryQuery =
            MemoryQuery(words(Normalizer.normalize(raw, Normalizer.Form.NFC)).map { it.lowercase(Locale.ROOT) }.distinct().take(MAX_WORDS))

        /** More words than this only dilute the ranking (and grow the query); the first ones are kept. */
        const val MAX_WORDS = 16

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

        /** Whether every letter in [word] is Latin (or it has only digits): such words are separated by spaces. */
        private fun isLatin(word: String): Boolean = word.codePoints().allMatch { cp ->
            !Character.isLetter(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
        }

        private val latinMarks = Regex("[\\u0300-\\u036f]+")

        /**
         * Lower case without Latin accents, as `remove_diacritics` does: only the combining diacritics
         * block is dropped, so Devanagari vowel signs and other scripts' marks stay.
         */
        fun fold(text: String): String =
            Normalizer.normalize(latinMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), ""), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}

/** A topic as the index needs it: [fingerprint] changes whenever its file or index line does. */
internal class MemoryDoc(val topic: String, val fingerprint: String, val load: () -> Pair<String, String>)

/**
 * The full-text index of one memory (global or a project's), in its own SQLite file outside
 * agent-home (it's derived data: not backed up, rebuilt when missing). Two FTS5 tables over the same
 * topics: `words` (unicode61, accents folded) ranked by bm25, and `grams` (trigram) for text without
 * spaces between words (CJK, Thai) and words inside longer ones. Results from both are merged by
 * reciprocal rank fusion. See docs/design/memory-search.md.
 *
 * Every call is synchronised; any SQLite failure drops the file so the next call rebuilds it, and the
 * caller falls back to scanning the notes.
 */
class MemorySearchIndex(private val driver: SQLiteDriver, val file: File) {
    private var connection: SQLiteConnection? = null

    /** Opens failed this many times in a row; past [MAX_FAILURES] the index stays off for this process. */
    private var failures = 0

    /** Brings the index up to date with [docs]: adds and re-reads changed topics, drops removed ones. */
    @Synchronized
    internal fun sync(docs: List<MemoryDoc>): Boolean = guarded {
        val known = fingerprints(it)
        val wanted = docs.associateBy { d -> d.topic }
        val stale = known.keys - wanted.keys
        val changed = docs.filter { d -> known[d.topic] != d.fingerprint }
        if (stale.isNotEmpty() || changed.isNotEmpty()) {
            transaction(it) {
                stale.forEach { topic -> delete(it, topic) }
                changed.forEach { d -> write(it, d) }
            }
        }
        true
    } ?: false

    /** Indexes one topic now (after a save or an edit). */
    @Synchronized
    internal fun put(doc: MemoryDoc) {
        guarded { transaction(it) { write(it, doc) } }
    }

    /** Removes one topic (after a forget). */
    @Synchronized
    fun remove(topic: String) {
        guarded { transaction(it) { delete(it, topic) } }
    }

    /**
     * Topics matching [query], best first, with a snippet where the index can cut one. Null when the
     * index can't be used (the caller scans instead).
     */
    @Synchronized
    fun search(query: MemoryQuery, limit: Int): List<Ranked>? = guarded { c ->
        if (query.isEmpty) return@guarded emptyList()
        val topics = topics(c)
        val lists = mutableListOf<Pair<Double, List<Pair<Long, String?>>>>()
        query.wordMatch?.let { lists += WORDS_WEIGHT to ranked(c, WORDS_SQL, it) }
        query.gramMatch?.let { lists += GRAMS_WEIGHT to ranked(c, GRAMS_SQL, it) }
        if (query.shortTerms.isNotEmpty()) lists += GRAMS_WEIGHT to substringHits(c, query.shortTerms)
        fuse(lists, limit).mapNotNull { (id, snippet) -> topics[id]?.let { Ranked(it, snippet) } }
    }

    /** A topic and the index's snippet for it (null when it matched only as a short substring). */
    data class Ranked(val topic: String, val snippet: String?)

    /** Closes the file and deletes it (a removed project, or tests). */
    @Synchronized
    fun delete() {
        close()
        deleteFiles()
    }

    @Synchronized
    fun close() {
        runCatching { connection?.close() }
        connection = null
    }

    // ---- Internals -------------------------------------------------------------------------------

    private fun <T> guarded(block: (SQLiteConnection) -> T): T? {
        if (failures >= MAX_FAILURES) return null
        return try {
            block(open()).also { failures = 0 }
        } catch (e: Exception) {
            // A damaged or half-written index: drop it; the next call rebuilds it from the notes.
            failures++
            close()
            deleteFiles()
            null
        }
    }

    private fun open(): SQLiteConnection = connection ?: run {
        file.parentFile?.mkdirs()
        val existing = driver.open(file.path)
        val current = try {
            userVersion(existing) == SCHEMA
        } catch (e: Exception) {
            existing.close()
            throw e
        }
        if (current) return@run existing.also { connection = it }
        // Missing (a new, empty file) or from another version of this code: start over; sync refills it.
        existing.close()
        deleteFiles()
        val c = driver.open(file.path)
        try {
            c.execSQL("CREATE TABLE topic(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, fingerprint TEXT NOT NULL)")
            c.execSQL("CREATE VIRTUAL TABLE words USING fts5(name, summary, content, tokenize='unicode61 remove_diacritics 2')")
            c.execSQL("CREATE VIRTUAL TABLE grams USING fts5(name, summary, content, tokenize='trigram remove_diacritics 1')")
            c.execSQL("PRAGMA user_version = $SCHEMA")
        } catch (e: Exception) {
            c.close()
            throw e
        }
        c.also { connection = it }
    }

    private fun deleteFiles() {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun userVersion(c: SQLiteConnection): Int = c.prepare("PRAGMA user_version").use { s -> if (s.step()) s.getLong(0).toInt() else 0 }

    private fun fingerprints(c: SQLiteConnection): Map<String, String> =
        c.prepare("SELECT name, fingerprint FROM topic").use { s -> buildMap { while (s.step()) put(s.getText(0), s.getText(1)) } }

    private fun topics(c: SQLiteConnection): Map<Long, String> =
        c.prepare("SELECT id, name FROM topic").use { s -> buildMap { while (s.step()) put(s.getLong(0), s.getText(1)) } }

    private fun transaction(c: SQLiteConnection, block: () -> Unit) {
        c.execSQL("BEGIN IMMEDIATE")
        try {
            block()
            c.execSQL("COMMIT")
        } catch (e: Exception) {
            runCatching { c.execSQL("ROLLBACK") }
            throw e
        }
    }

    private fun idOf(c: SQLiteConnection, topic: String): Long? =
        c.prepare("SELECT id FROM topic WHERE name = ?").use { s -> s.bindText(1, topic); if (s.step()) s.getLong(0) else null }

    private fun delete(c: SQLiteConnection, topic: String) {
        val id = idOf(c, topic) ?: return
        listOf("DELETE FROM words WHERE rowid = ?", "DELETE FROM grams WHERE rowid = ?", "DELETE FROM topic WHERE id = ?").forEach { sql ->
            c.prepare(sql).use { s -> s.bindLong(1, id); s.step() }
        }
    }

    private fun write(c: SQLiteConnection, doc: MemoryDoc) {
        delete(c, doc.topic)
        val (summary, content) = doc.load()
        c.prepare("INSERT INTO topic(name, fingerprint) VALUES (?, ?)").use { s -> s.bindText(1, doc.topic); s.bindText(2, doc.fingerprint); s.step() }
        val id = c.prepare("SELECT last_insert_rowid()").use { s -> s.step(); s.getLong(0) }
        // Topic names are slugs: hyphens become spaces so "coffee-preferences" reads as two words in both tables.
        val name = doc.topic.replace('-', ' ')
        listOf("words", "grams").forEach { table ->
            c.prepare("INSERT INTO $table(rowid, name, summary, content) VALUES (?, ?, ?, ?)").use { s ->
                s.bindLong(1, id); s.bindText(2, name); s.bindText(3, summary); s.bindText(4, content); s.step()
            }
        }
    }

    private fun ranked(c: SQLiteConnection, sql: String, match: String): List<Pair<Long, String?>> =
        c.prepare(sql).use { s ->
            s.bindText(1, match)
            buildList { while (s.step()) add(s.getLong(0) to (if (s.isNull(1)) null else s.getText(1))) }
        }

    /**
     * Topics containing short CJK-like terms anywhere, ordered by how many of the terms they contain.
     * `LIKE` on a trigram table scans for patterns under three characters, which is fine at this size;
     * the terms are letters and marks only, so they hold no `%` or `_`.
     */
    private fun substringHits(c: SQLiteConnection, terms: List<String>): List<Pair<Long, String?>> {
        val counts = LinkedHashMap<Long, Int>()
        terms.forEach { term ->
            c.prepare("SELECT rowid FROM grams WHERE name LIKE ?1 OR summary LIKE ?1 OR content LIKE ?1").use { s ->
                s.bindText(1, "%$term%")
                while (s.step()) counts.merge(s.getLong(0), 1, Int::plus)
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key to null }
    }

    companion object {
        /** Bump to rebuild every index from the notes (changed tables or tokenizers). */
        const val SCHEMA = 1

        private const val MAX_FAILURES = 3

        /** Reciprocal rank fusion's constant (Cormack, Clarke and Büttcher, SIGIR 2009). */
        private const val RRF_K = 60.0

        /** Whole-word (prefix) matches count more than substring matches, which are noisier for spaced scripts. */
        private const val WORDS_WEIGHT = 1.0
        private const val GRAMS_WEIGHT = 0.6

        /** The topic name counts most, then its index line, then the note: bm25 column weights. */
        private const val WORDS_SQL =
            "SELECT rowid, snippet(words, -1, '**', '**', '…', 12) FROM words WHERE words MATCH ? ORDER BY bm25(words, 10.0, 4.0, 1.0) LIMIT 50"
        private const val GRAMS_SQL =
            "SELECT rowid, snippet(grams, -1, '**', '**', '…', 24) FROM grams WHERE grams MATCH ? ORDER BY bm25(grams, 10.0, 4.0, 1.0) LIMIT 50"

        /**
         * Merges ranked lists: each topic scores the sum of weight / (k + rank) over the lists it's in.
         * The snippet comes from the first list that has one.
         */
        internal fun <K> fuse(lists: List<Pair<Double, List<Pair<K, String?>>>>, limit: Int): List<Pair<K, String?>> {
            val scores = LinkedHashMap<K, Double>()
            val snippets = HashMap<K, String>()
            lists.forEach { (weight, list) ->
                list.forEachIndexed { rank, (key, snippet) ->
                    scores.merge(key, weight / (RRF_K + rank + 1), Double::plus)
                    if (snippet != null && key !in snippets) snippets[key] = snippet
                }
            }
            return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key to snippets[it.key] }
        }
    }
}

/** Search without the index: a scan of every note with the same word rules, for when SQLite isn't there. */
internal object MemoryIndexFallback {
    fun search(query: MemoryQuery, topics: List<AgentHome.MemoryTopic>, limit: Int): List<MemorySearchIndex.Ranked> {
        val terms = query.scanTerms
        if (terms.isEmpty()) return emptyList()
        return topics
            .map { t ->
                val name = MemoryQuery.fold(t.name.replace('-', ' '))
                val summary = MemoryQuery.fold(t.summary)
                val content = MemoryQuery.fold(t.content)
                t to terms.sumOf { w -> 10 * count(name, w) + 4 * count(summary, w) + count(content, w) }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (t, _) -> MemorySearchIndex.Ranked(t.name, null) }
    }

    private fun count(hay: String, needle: String): Int {
        var n = 0
        var i = hay.indexOf(needle)
        while (i >= 0) {
            n++
            i = hay.indexOf(needle, i + needle.length)
        }
        return n
    }
}

/** A line of [content] around the first match of [query], for results the index gave no snippet for. */
internal fun memorySnippet(content: String, query: MemoryQuery): String? {
    val terms = query.scanTerms.ifEmpty { return null }
    val line = content.lineSequence().map { it.trim() }.firstOrNull { l -> l.isNotEmpty() && terms.any { it in MemoryQuery.fold(l) } } ?: return null
    return if (line.length <= 160) line else line.take(160) + "…"
}
