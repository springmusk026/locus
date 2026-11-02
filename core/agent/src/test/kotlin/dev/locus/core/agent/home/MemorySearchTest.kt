package dev.locus.core.agent.home

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.MemoryScope
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ranked memory search on the bundled SQLite the app ships (docs/design/memory-search.md). */
class MemorySearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val driver = BundledSQLiteDriver()

    private fun home(dir: File = tmp.newFolder("home"), index: File = File(tmp.root, "index")) =
        AgentHome(dir, sqlite = driver, indexDir = index)

    private fun AgentHome.topics(query: String) = searchMemory(query).map { it.topic }

    @Test
    fun `the bundled SQLite has FTS5 with the trigram tokenizer`() {
        val c = driver.open(":memory:")
        c.prepare("SELECT sqlite_version()").use { s -> s.step(); assertTrue(s.getText(0) >= "3.45", s.getText(0)) }
        c.prepare("CREATE VIRTUAL TABLE t USING fts5(x, tokenize='trigram remove_diacritics 1')").use { it.step() }
        c.close()
    }

    @Test
    fun `results are ranked by bm25 with the topic name counting most`() {
        val home = home()
        home.saveMemory("travel", "Trips", "We once talked about coffee on a train.")
        home.saveMemory("coffee", "How the user takes coffee", "Flat white with oat milk, no sugar. Coffee after 3pm keeps them up.")
        home.saveMemory("garden", "Raised beds", "Tomatoes and basil.")
        assertEquals(listOf("coffee", "travel"), home.topics("coffee"))
        // Several words: any may match; topics with more of them come first.
        assertEquals("coffee", home.topics("oat milk train").first())
        assertTrue("garden" !in home.topics("oat milk train"))
    }

    @Test
    fun `words match as prefixes, and a word inside a longer one still matches`() {
        val home = home()
        home.saveMemory("stack", "Languages", "Prefers Kotlin and KotlinScript for builds.")
        assertEquals(listOf("stack"), home.topics("kotl"))
        assertEquals(listOf("stack"), home.topics("script"))
        assertTrue(home.searchMemory("zebra").isEmpty())
    }

    @Test
    fun `two-letter words count now`() {
        val home = home()
        home.saveMemory("lang", "Languages", "Writes Go at work and JS at home.")
        assertEquals(listOf("lang"), home.topics("go"))
    }

    @Test
    fun `snippets mark where the query matched`() {
        val home = home()
        home.saveMemory("coffee", "How the user takes coffee", "Flat white with oat milk, no sugar.")
        val hit = home.searchMemory("oat").single()
        assertEquals("How the user takes coffee", hit.summary)
        assertTrue(hit.snippet!!.contains("**oat**"), hit.snippet)
    }

    @Test
    fun `accents are folded both ways`() {
        val home = home()
        home.saveMemory("trip", "Brazil", "Stayed at a café in São Paulo near the Pinacoteca.")
        home.saveMemory("resume", "CV", "The user's resume is in Documents.")
        assertEquals(listOf("trip"), home.topics("cafe sao"))
        assertEquals(listOf("trip"), home.topics("CAFÉ"))
        assertEquals(listOf("resume"), home.topics("résumé"))
    }

    @Test
    fun `Nepali in Devanagari is found by whole words, prefixes and inside words`() {
        val home = home()
        home.saveMemory("नेपाली खाना", "मनपर्ने खाना", "मलाई दाल भात र गुन्द्रुक मन पर्छ। म काठमाडौंमा बस्छु।")
        home.saveMemory("coffee", "Coffee", "Flat white.")
        // The topic keeps its own name instead of becoming "note".
        assertEquals(listOf("नेपाली-खाना"), home.topics("दाल भात"))
        // A suffix written onto the word (काठमाडौं + मा, "in Kathmandu"): the stem as a prefix finds it.
        assertEquals(listOf("नेपाली-खाना"), home.topics("काठमाडौं"))
        assertEquals(listOf("नेपाली-खाना"), home.topics("गुन्द्रुक"))
        assertEquals(listOf("नेपाली-खाना"), home.topics("खाना"))
        assertTrue(home.searchMemory("चिया").isEmpty())
        assertEquals("मलाई दाल भात र गुन्द्रुक मन पर्छ। म काठमाडौंमा बस्छु।\n", home.readMemory("नेपाली खाना"))
    }

    @Test
    fun `Chinese, Japanese and Korean match inside runs, even with one or two characters`() {
        val home = home()
        home.saveMemory("tokyo", "Japan", "私は東京に住んでいます。新宿の近くです。")
        home.saveMemory("drinks", "饮料", "我喜欢喝咖啡，不喜欢喝茶。")
        home.saveMemory("seoul", "Korea", "서울에 살아요.")
        assertEquals(listOf("tokyo"), home.topics("東京"))
        assertEquals(listOf("tokyo"), home.topics("新宿の近く"))
        assertEquals(listOf("drinks"), home.topics("咖啡"))
        assertEquals(listOf("drinks"), home.topics("茶"))
        assertEquals(listOf("seoul"), home.topics("서울"))
        assertTrue(home.searchMemory("大阪").isEmpty())
    }

    @Test
    fun `the index follows saves, edits and forgets`() {
        val home = home()
        home.saveMemory("pets", "Pets", "A cat called Momo.")
        assertEquals(listOf("pets"), home.topics("momo"))
        home.saveMemory("pets", "Pets", "A dog called Biscuit.")
        assertTrue(home.searchMemory("momo").isEmpty())
        assertEquals(listOf("pets"), home.topics("biscuit"))
        // An edited summary is searched too.
        home.saveMemory("pets", "Animals at home", "A dog called Biscuit.")
        assertEquals(listOf("pets"), home.topics("animals"))
        assertTrue(home.forgetMemory("pets"))
        assertTrue(home.searchMemory("biscuit").isEmpty())
    }

    @Test
    fun `a missing or damaged index is rebuilt from the notes`() {
        val dir = tmp.newFolder("home")
        val indexDir = File(tmp.root, "index")
        home(dir, indexDir).saveMemory("pets", "Pets", "A cat called Momo.")
        val file = File(indexDir, "memory.db")
        assertTrue(file.exists())
        // A new process (another AgentHome) after the index was deleted.
        file.delete()
        val again = home(dir, indexDir)
        again.openMemoryIndex()
        assertTrue(file.exists())
        assertEquals(listOf("pets"), again.topics("momo"))
        // Garbage instead of a database: the search still answers (by scanning), and the index is rebuilt.
        val broken = File(tmp.root, "index2").apply { mkdirs() }
        File(broken, "memory.db").writeText("not a database at all, just text")
        val third = home(dir, broken)
        assertEquals(listOf("pets"), third.topics("momo"))
        assertEquals(listOf("pets"), third.topics("momo"))
        assertEquals("SQLite format 3", File(broken, "memory.db").readBytes().decodeToString(0, 15))
    }

    @Test
    fun `notes changed outside Locus, such as a restored backup, are picked up`() {
        val home = home()
        home.saveMemory("pets", "Pets", "A cat called Momo.")
        assertEquals(listOf("pets"), home.topics("momo"))
        File(home.memoryDir, "pets.md").apply { writeText("A parrot called Kiwi.\n"); setLastModified(lastModified() + 5_000) }
        File(home.memoryDir, "car.md").writeText("A blue bicycle, no car.\n")
        assertEquals(listOf("pets"), home.topics("kiwi"))
        assertTrue(home.searchMemory("momo").isEmpty())
        assertEquals(listOf("car"), home.topics("bicycle"))
        File(home.memoryDir, "car.md").delete()
        assertTrue(home.searchMemory("bicycle").isEmpty())
    }

    @Test
    fun `projects have their own index, removed with the project`() {
        val home = home()
        val scope = ChatScopeSpec("0f8fad5b-d9cb-469f-a165-70867728950e", "Garden", MemoryScope.PROJECT_AND_GLOBAL)
        home.saveMemory("coffee", "Coffee", "Flat white with oat milk.")
        val view = home.memory(scope)
        view.save("beds", "Raised beds", "Tomatoes by the fence; oat straw as mulch.")
        val hits = view.search("oat")
        assertEquals(listOf("beds" to false, "coffee" to true), hits.map { it.topic to it.general })
        val projectIndex = File(File(tmp.root, "index"), "projects_${scope.projectId}_memory.db")
        assertTrue(projectIndex.exists())
        home.removeProject(scope.projectId)
        assertFalse(projectIndex.exists())
    }

    @Test
    fun `without SQLite the notes are scanned, also in other scripts`() {
        val home = AgentHome(tmp.newFolder("plain"))
        home.saveMemory("food", "Food", "मलाई दाल भात मन पर्छ।")
        home.saveMemory("tokyo", "Japan", "私は東京に住んでいます。")
        home.saveMemory("trip", "Brazil", "A café in São Paulo.")
        assertEquals(listOf("food"), home.topics("दाल"))
        assertEquals(listOf("tokyo"), home.topics("東京"))
        assertEquals(listOf("trip"), home.topics("cafe"))
        assertNotNull(home.searchMemory("दाल").single().snippet)
    }

    @Test
    fun `notes saved under the old ASCII-only names are still found`() {
        val home = home()
        File(home.memoryDir, "caf.md").writeText("Old note.\n")
        assertEquals("Old note.\n", home.readMemory("café"))
        assertTrue(home.forgetMemory("café"))
    }

    @Test
    fun `memory slugs keep every script and match the old ones for ASCII`() {
        assertEquals("coffee-preferences", AgentHome.memorySlug("Coffee preferences!"))
        assertEquals(AgentHome.slug("Home server (Pi 5)"), AgentHome.memorySlug("Home server (Pi 5)"))
        assertEquals("नेपाली-खाना", AgentHome.memorySlug(" नेपाली खाना "))
        assertEquals("東京の店", AgentHome.memorySlug("東京の店"))
        assertEquals("note", AgentHome.memorySlug("!!!"))
        // 60 characters at most, counted in code points: a letter outside the BMP is never cut in half.
        val scriptA = String(Character.toChars(0x1D49C))
        val long = AgentHome.memorySlug(scriptA.repeat(80))
        assertEquals(60, long.codePointCount(0, long.length))
        assertEquals(120, long.length)
    }

    @Test
    fun `fusion adds up ranks across lists`() {
        val fused = MemorySearchIndex.fuse(
            listOf(1.0 to listOf("a" to "A", "b" to null), 0.6 to listOf("b" to "B", "c" to "C")),
            limit = 10,
        )
        // b is in both lists, so it beats a, which is first in only one.
        assertEquals(listOf("b", "a", "c"), fused.map { it.first })
        assertEquals("B", fused.first().second)
    }
}
