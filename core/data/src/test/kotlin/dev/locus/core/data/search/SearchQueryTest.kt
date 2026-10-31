package dev.locus.core.data.search

import org.junit.Assume
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchQueryTest {
    private fun match(raw: String) = SearchQuery.parse(raw).match

    @Test
    fun `words match as prefixes, every one of them`() {
        assertEquals("\"kotl\"*", match("kotl"))
        assertEquals("\"Kotlin\"* \"flow\"*", match("  Kotlin,   flow! "))
    }

    @Test
    fun `quoted phrases match exactly and in order`() {
        assertEquals("\"room migration\" \"kotlin\"*", match("kotlin \"room migration\""))
        // Still typing the closing quote: the words stay loose so results don't vanish meanwhile.
        assertEquals("\"room\"* \"migr\"*", match("\"room migr"))
        assertNull(match("\"\""))
    }

    @Test
    fun `one letter searches titles only`() {
        val q = SearchQuery.parse("k")
        assertNull(q.match)
        assertFalse(q.isEmpty)
        assertTrue(q.matchesTitle("Kotlin notes"))
        // Next to a longer word, a lone letter is dropped rather than matching everything.
        assertEquals("\"kotlin\"*", match("kotlin c"))
    }

    @Test
    fun `fts syntax in the input is just separators or words`() {
        assertEquals("\"a1\"* \"OR\"*", match("a1 OR b"))
        assertEquals("\"NEAR\"* \"title\"* \"x2\"*", match("NEAR(title: x2)"))
        assertEquals("\"and\"* \"not\"*", match("and -not"))
        assertTrue(SearchQuery.parse("\"*^:()-+'").isEmpty)
        assertTrue(SearchQuery.parse("   ").isEmpty)
        assertTrue(SearchQuery.parse("🙂🙂").isEmpty)
    }

    @Test
    fun `words split like the unicode61 tokenizer`() {
        assertEquals(listOf("C", "snake", "case", "éte", "東京タワー", "3", "14"), SearchQuery.words("C++ snake_case éte 東京タワー 3.14"))
        assertEquals("cafe", SearchQuery.fold("Café"))
    }

    @Test
    fun `titles match ignoring case and accents`() {
        assertTrue(SearchQuery.parse("cafe kotl").matchesTitle("Kotlin at the Café"))
        assertTrue(SearchQuery.parse("\"the cafe\"").matchesTitle("Kotlin at the Café"))
        assertFalse(SearchQuery.parse("\"cafe the\"").matchesTitle("Kotlin at the Café"))
        assertFalse(SearchQuery.parse("otlin").matchesTitle("Kotlin"))
    }

    /** Every query the parser builds is valid FTS5, whatever was typed. Runs on the system sqlite3. */
    @Test
    fun `no input makes the index throw`() {
        val inputs = listOf(
            "\"", "\"\"", "\"\"\"", "*", "**", "kotlin*", "\"kotlin", "kotlin\"", "a\"b\"c", "NEAR", "NEAR(a b, 2)", "AND", "OR OR",
            "NOT kotlin", "title:kotlin", "^kotlin", "{a b}:c", "(a", "a)", "-", "+", "'", "';DROP TABLE t;--", "\\\"", "\u0000",
            "kotlin", "ünïcödé", "東京", "x".repeat(500), "🙂 kotlin",
        )
        val script = buildString {
            appendLine("CREATE VIRTUAL TABLE t USING fts5(x, tokenize = 'unicode61 remove_diacritics 2');")
            appendLine("INSERT INTO t VALUES ('Kotlin and the NEAR title, naïve café 東京タワー');")
            for (input in inputs) {
                val m = SearchQuery.parse(input).match ?: continue
                appendLine("SELECT count(*) FROM t WHERE t MATCH '${m.replace("'", "''")}';")
            }
        }
        val ok = runCatching { ProcessBuilder("sqlite3", ":memory:", "SELECT 1").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        Assume.assumeTrue("sqlite3 is not installed", ok)
        val p = ProcessBuilder("sqlite3", "-batch", "-bail", ":memory:").start()
        p.outputStream.bufferedWriter().use { it.write(script) }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        assertEquals("", err)
        assertTrue(out.lines().filter { it.isNotBlank() }.all { it.toInt() >= 0 })
    }
}
