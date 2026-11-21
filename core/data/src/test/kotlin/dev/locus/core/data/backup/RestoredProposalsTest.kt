package dev.locus.core.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A restore never brings back a proposal that can run (docs/design/needs-you.md, "Backups"): a backup is a
 * snapshot, and what it holds as waiting may have been approved and run since. Waiting and running proposals
 * in the staged database become STALE; decided ones stay as they were; a backup from before Needs you is left
 * byte for byte as it was. On real databases made with the bundled SQLite the app ships.
 */
class RestoredProposalsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val driver = BundledSQLiteDriver()

    private fun database(withProposals: Boolean): File {
        val file = File(tmp.newFolder(), "locus.db")
        val c = driver.open(file.path)
        try {
            c.execSQL("PRAGMA journal_mode = WAL")
            c.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY NOT NULL)")
            c.execSQL("INSERT INTO conversations VALUES ('c')")
            if (withProposals) {
                c.execSQL("CREATE TABLE proposals (id TEXT PRIMARY KEY NOT NULL, conversationId TEXT NOT NULL, status TEXT NOT NULL)")
                c.execSQL("INSERT INTO proposals VALUES ('wait', 'c', 'PENDING'), ('run', 'c', 'RUNNING'), ('done', 'c', 'DONE'), ('no', 'c', 'DECLINED')")
            }
        } finally {
            c.close()
        }
        return file
    }

    private fun statuses(file: File): String {
        val c = driver.open(file.path)
        try {
            return c.prepare("SELECT id, status FROM proposals ORDER BY id").use { s ->
                buildList { while (s.step()) add("${s.getText(0)}=${s.getText(1)}") }.joinToString()
            }
        } finally {
            c.close()
        }
    }

    @Test
    fun `waiting and running proposals from a backup are retired, decided ones kept`() {
        val db = database(withProposals = true)
        assertEquals(2, RestoredProposals(driver).retire(db))
        assertEquals("done=DONE, no=DECLINED, run=STALE, wait=STALE", statuses(db))
        // Nothing beside the staged database is left to be swapped in with it.
        listOf("-wal", "-shm", "-journal").forEach { assertEquals(false, File(db.path + it).exists(), it) }
        // A second look finds nothing left to retire.
        assertEquals(0, RestoredProposals(driver).retire(db))
    }

    @Test
    fun `a backup from before Needs you is left as it was`() {
        val db = database(withProposals = false)
        val before = db.readBytes()
        assertEquals(0, RestoredProposals(driver).retire(db))
        assertContentEquals(before, db.readBytes())
    }
}
