package dev.locus.core.agent.home

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the memory search index does when SQLite says the file is busy (docs/design/memory-search.md). */
class MemoryIndexBusyTest {
    @get:Rule val tmp = TemporaryFolder()

    private val driver = BundledSQLiteDriver()

    private fun doc(topic: String, text: String) = MemoryDoc(topic, "$topic:${text.length}") { "Summary of $topic" to text }

    private fun topics(index: MemorySearchIndex, query: String) = index.search(MemoryQuery.parse(query), 8)?.map { it.topic }

    @Test
    fun `a busy index keeps its files and its notes, however often it is busy`() {
        val file = File(tmp.root, "index/memory.db")
        val index = MemorySearchIndex(driver, file, busyTimeoutMs = 20)
        index.put(doc("pets", "A cat called Momo."))
        assertEquals(listOf("pets"), topics(index, "momo"))

        // Another connection holds the write lock, as a second save racing the first would.
        val other = driver.open(file.path)
        other.execSQL("BEGIN IMMEDIATE")
        // More often than the index tolerates failures in a row.
        repeat(6) {
            index.put(doc("dogs", "A dog called Rex."))
            assertTrue(file.exists(), "the index file was deleted by a busy error")
            // A busy file isn't a reason to switch the index off, nor to forget what it holds.
            assertEquals(false, index.sync(listOf(doc("pets", "A cat called Momo."), doc("dogs", "A dog called Rex."))))
        }
        assertEquals(listOf("pets"), topics(index, "momo"), "reading is still allowed while another connection writes")

        other.execSQL("ROLLBACK")
        other.close()
        // Not rebuilt, still on: the same index takes the write now.
        index.put(doc("dogs", "A dog called Rex."))
        assertEquals(listOf("dogs"), topics(index, "rex"))
        assertEquals(listOf("pets"), topics(index, "momo"))
        assertTrue(index.sync(listOf(doc("pets", "A cat called Momo."), doc("dogs", "A dog called Rex."))))
    }

    @Test
    fun `a damaged index is still deleted and rebuilt`() {
        val file = File(tmp.root, "index/memory.db").apply { parentFile.mkdirs(); writeText("not a database at all, just text") }
        val index = MemorySearchIndex(driver, file, busyTimeoutMs = 20)
        assertFalse(index.sync(listOf(doc("pets", "A cat called Momo."))), "the damaged file is dropped on the call that finds it")
        assertTrue(index.sync(listOf(doc("pets", "A cat called Momo."))))
        assertEquals(listOf("pets"), topics(index, "momo"))
        assertNotNull(file.takeIf { it.exists() })
        assertEquals("SQLite format 3", file.readBytes().decodeToString(0, 15))
    }
}
