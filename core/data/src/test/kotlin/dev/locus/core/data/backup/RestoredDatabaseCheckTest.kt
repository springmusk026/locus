package dev.locus.core.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The check a restore runs on the backup's database before offering it (docs/design/backup.md,
 * "Restore"), on real databases made with the bundled SQLite the app ships. The damaged ones are made
 * here, from a sound one, rather than kept as files.
 */
class RestoredDatabaseCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private val driver = BundledSQLiteDriver()
    private val check = RestoredDatabaseCheck(driver)

    /**
     * A database shaped like Locus's (a table with an index, and an external-content FTS5 index over it
     * like `message_fts`), in WAL mode like Room's, copied out with `VACUUM INTO` as a backup does.
     * [damage] runs on the live database before the copy.
     */
    private fun database(name: String = "locus.db", userVersion: Int = 8, damage: SQLiteConnection.() -> Unit = {}): File {
        val live = File(tmp.newFolder(), "live.db")
        val c = driver.open(live.path)
        try {
            c.execSQL("PRAGMA journal_mode = WAL")
            c.execSQL("CREATE TABLE messages (seq INTEGER PRIMARY KEY, chat TEXT NOT NULL, searchText TEXT)")
            c.execSQL("CREATE INDEX index_messages_chat ON messages(chat)")
            c.execSQL("CREATE VIRTUAL TABLE message_fts USING fts5(searchText, content=messages, content_rowid=seq, tokenize='unicode61 remove_diacritics 2')")
            c.execSQL("BEGIN")
            c.prepare("INSERT INTO messages(chat, searchText) VALUES (?, ?)").use { s ->
                repeat(3000) { i ->
                    s.bindText(1, "chat-${i % 40}")
                    s.bindText(2, "message $i about kotlin coroutines and sqlite page number ${i * 7}")
                    s.step()
                    s.reset()
                }
            }
            c.execSQL("COMMIT")
            c.execSQL("INSERT INTO message_fts(message_fts) VALUES('rebuild')")
            c.execSQL("PRAGMA user_version = $userVersion")
            c.damage()
            val snapshot = File(tmp.newFolder(), name)
            c.prepare("VACUUM INTO ?").use { it.bindText(1, snapshot.path); it.step() }
            return snapshot
        } finally {
            c.close()
        }
    }

    /** Overwrites one whole page past the header page with junk, as a bad block or a torn write would. */
    private fun smashPage(file: File, page: Int) {
        val pageSize = RandomAccessFile(file, "r").use { f -> f.seek(16); f.readUnsignedShort().let { if (it == 1) 65536 else it } }
        assertTrue(file.length() > page.toLong() * pageSize, "the database has page $page")
        RandomAccessFile(file, "rw").use { f ->
            f.seek((page - 1).toLong() * pageSize)
            f.write(ByteArray(pageSize) { (it * 31 + 7).toByte() })
        }
    }

    @Test
    fun `a sound database passes and is left exactly as it was`() {
        val db = database()
        val before = db.readBytes()
        assertEquals(emptyList(), check.problems(db))
        assertContentEquals(before, db.readBytes())
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
    }

    @Test
    fun `a damaged table page fails quick_check`() {
        val db = database()
        smashPage(db, 3)
        val problems = check.problems(db)
        assertTrue(problems.isNotEmpty())
        assertFalse(File(db.path + "-wal").exists())
    }

    @Test
    fun `a damaged full-text index fails`() {
        // Only the index's own records (its %_data shadow table) are overwritten; every b-tree stays
        // valid. quick_check still finds it, through FTS5's xIntegrity (SQLite 3.44+).
        val db = database { execSQL("UPDATE message_fts_data SET block = zeroblob(length(block)) WHERE id > 10") }
        val problems = check.problems(db)
        assertTrue(problems.any { "FTS5" in it && "message_fts" in it }, problems.toString())
    }

    @Test
    fun `a file that only looks like SQLite fails`() {
        val db = File(tmp.newFolder(), "locus.db")
        db.writeBytes("SQLite format 3\u0000".encodeToByteArray() + ByteArray(8192) { (it % 251).toByte() })
        assertTrue(check.problems(db).isNotEmpty())
    }

    @Test
    fun `a missing file fails rather than being created`() {
        val db = File(tmp.root, "absent.db")
        assertTrue(check.problems(db).isNotEmpty())
        assertFalse(db.exists())
    }

    /** A phone with Locus's restore layout, holding a sound database of its own. */
    private class Phone(val root: File) {
        val db = File(root, "databases/locus.db")
        val settings = File(root, "files/datastore/settings.json")
        val agentHome = File(root, "files/agent-home")
        val workspace = File(root, "files/workspace")
        val vault = File(root, "files/datastore/secrets.enc")
        val restore = PendingRestore(File(root, "no_backup/restore"), db, settings, agentHome, workspace, vault)
    }

    private fun backupOf(snapshot: File, schema: Int = 8): ByteArray {
        val home = tmp.newFolder().apply { File(this, "memory").mkdirs(); File(this, "memory/MEMORY.md").writeText("- kotlin\n") }
        val plan = BackupArchive.plan(BackupSources(snapshot, settingsFile = null, agentHome = home, workspace = null, vault = null))
        val manifest = BackupManifest(appVersion = "0.1.0", appVersionCode = 1, schemaVersion = schema, createdAt = 1, sections = plan.sections)
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, PASSWORD, iterations = 10_000).use { BackupArchive.write(it, plan, manifest) }
        return out.toByteArray()
    }

    private fun verify(bytes: ByteArray, phone: Phone): ExtractedBackup {
        val opened = BackupCrypto.open(ByteArrayInputStream(bytes))
        return BackupArchive.extract(BackupCrypto.decrypt(opened, BackupCrypto.unlock(opened, PASSWORD)), phone.restore.staged, 8, check::problems)
    }

    @Test
    fun `a backup with a damaged database is refused and nothing on the phone changes`() {
        val phone = Phone(tmp.newFolder("phone"))
        database().copyTo(phone.db)
        val current = phone.db.readBytes()
        val damaged = database().also { smashPage(it, 3) }

        val e = assertFailsWith<BackupException> { verify(backupOf(damaged), phone) }
        assertEquals(BackupError.DatabaseDamaged, e.error)
        // What BackupManager.verify does with any failure: the stage goes, and nothing is confirmed.
        phone.restore.discard()
        assertFalse(phone.restore.staged.exists())
        assertFalse(phone.restore.apply())
        assertContentEquals(current, phone.db.readBytes())
        assertEquals(emptyList(), check.problems(phone.db))
    }

    @Test
    fun `a backup with a sound database is offered and restores it byte for byte`() {
        val phone = Phone(tmp.newFolder("phone"))
        database().copyTo(phone.db)
        val sound = database(userVersion = 7)

        val extracted = verify(backupOf(sound, schema = 7), phone)
        assertEquals(7, extracted.manifest.schemaVersion)
        phone.restore.markReady()
        assertTrue(phone.restore.apply())
        assertContentEquals(sound.readBytes(), phone.db.readBytes())
    }

    private companion object {
        val PASSWORD = "correct horse battery staple".toCharArray()
    }
}
