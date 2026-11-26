package dev.locus.core.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.locus.core.database.DatabaseBackup
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A backup's snapshot is taken on a connection of its own, so the app's writer isn't held up by it
 * (docs/design/backup.md), on real WAL databases made with the bundled SQLite the app ships.
 */
class SnapshotWhileWritingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val driver = BundledSQLiteDriver()

    private fun SQLiteConnection.count(table: String = "t"): Int =
        prepare("SELECT COUNT(*) FROM $table").use { it.step(); it.getLong(0).toInt() }

    private fun SQLiteConnection.insert(n: Int, from: Int = 0) {
        prepare("INSERT INTO t(v) VALUES (?)").use { s ->
            repeat(n) { i ->
                s.bindText(1, "row ${from + i} " + "x".repeat(900))
                s.step()
                s.reset()
            }
        }
    }

    private fun live(rows: Int): Pair<File, SQLiteConnection> {
        val file = File(tmp.newFolder(), "live.db")
        val writer = driver.open(file.path)
        writer.execSQL("PRAGMA journal_mode = WAL")
        // What Room's connection does: the WAL isn't folded into the database file behind the copy's back.
        writer.execSQL("PRAGMA wal_autocheckpoint = 0")
        writer.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
        writer.execSQL("PRAGMA user_version = 20")
        writer.execSQL("BEGIN")
        writer.insert(rows)
        writer.execSQL("COMMIT")
        return file to writer
    }

    @Test
    fun `the snapshot is taken while the writer holds a write transaction open, and holds only what was committed`() {
        val (file, writer) = live(rows = 500)
        try {
            writer.execSQL("BEGIN IMMEDIATE")
            writer.insert(10, from = 1_000)
            val dest = File(tmp.newFolder(), "snapshot.db")

            // The writer's lock is taken, as it is while the agent saves a message: a snapshot that needed
            // the writer (or the database exclusively) would wait here or fail as busy.
            DatabaseBackup.snapshotFile(file, dest)

            val snapshot = driver.open(dest.path)
            try {
                assertEquals(500, snapshot.count(), "committed rows only, WAL content included")
                assertEquals(20L, snapshot.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) })
            } finally {
                snapshot.close()
            }
            // The writer finishes its transaction and goes on.
            writer.execSQL("COMMIT")
            writer.insert(5, from = 2_000)
            assertEquals(515, writer.count())
        } finally {
            writer.close()
        }
    }

    @Test
    fun `writes made while a large snapshot is copied all go through`() {
        val (file, writer) = live(rows = 30_000)
        try {
            val dest = File(tmp.newFolder(), "snapshot.db")
            val failure = AtomicReference<Throwable?>()
            val copier = thread {
                try {
                    DatabaseBackup.snapshotFile(file, dest)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            var written = 0
            while (copier.isAlive) {
                writer.insert(1, from = 100_000 + written)
                written++
            }
            copier.join()
            assertNull(failure.get(), "the snapshot finished")
            assertEquals(30_000 + written, writer.count(), "every write made meanwhile landed")

            val snapshot = driver.open(dest.path)
            try {
                // Whatever moment the copy saw, it is a whole, consistent database that holds the committed 30,000.
                assertTrue(snapshot.count() in 30_000..30_000 + written)
                assertEquals("ok", snapshot.prepare("PRAGMA integrity_check").use { it.step(); it.getText(0) })
            } finally {
                snapshot.close()
            }
        } finally {
            writer.close()
        }
    }

    @Test
    fun `a snapshot never overwrites a file, and never changes the live database`() {
        val (file, writer) = live(rows = 20)
        try {
            val dest = File(tmp.newFolder(), "snapshot.db").apply { writeText("existing") }
            assertFailsWith<Exception> { DatabaseBackup.snapshotFile(file, dest) }
            assertEquals("existing", dest.readText())
            assertEquals(20, writer.count())
        } finally {
            writer.close()
        }
    }
}
