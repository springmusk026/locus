package dev.locus.core.database

import androidx.room3.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the live database for a backup without closing it.
 *
 * `VACUUM INTO` (SQLite 3.27+, the bundled SQLite is 3.50) writes "a consistent snapshot of the
 * original database" to a new file, including what is still in the WAL, which a plain file copy
 * would miss ("If a database file is separated from its WAL file, then transactions that were
 * previously committed to the database might be lost", sqlite.org/wal.html). It runs outside any
 * transaction, since VACUUM refuses to run inside one, on a connection of its own: read-only, opened
 * for the snapshot and closed after it. For `VACUUM INTO` SQLite takes a read transaction on the source,
 * and in WAL mode a reader doesn't block the writer, so the chat keeps saving while a large database is
 * copied. (On Room's writer connection, as before, every write waited for the whole copy.) The snapshot
 * keeps the header's `user_version`, which is Room's schema version. See docs/design/backup.md.
 */
@Singleton
class DatabaseBackup @Inject constructor(private val db: LocusDatabase) {
    /** Writes a snapshot to [dest], which must not exist yet. */
    suspend fun snapshotTo(dest: File) {
        require(!dest.exists()) { "Snapshot target already exists" }
        dest.parentFile?.mkdirs()
        val source = databaseFile()
        // Blocking work for as long as the copy takes: off the caller's thread.
        withContext(Dispatchers.IO) { snapshotFile(source, dest) }
    }

    /** The file Room has open, as SQLite reports it. */
    private suspend fun databaseFile(): File = db.useReaderConnection { connection ->
        connection.usePrepared("PRAGMA database_list") { statement ->
            var path: String? = null
            while (path == null && statement.step()) {
                if (statement.getText(1) == "main") path = statement.getText(2)
            }
            File(checkNotNull(path?.takeIf { it.isNotEmpty() }) { "The database has no file to snapshot" })
        }
    }

    /** The schema version of the open database, after Room has run its migrations. */
    suspend fun schemaVersion(): Int = db.useReaderConnection { connection ->
        connection.usePrepared("PRAGMA user_version") { statement ->
            statement.step()
            statement.getLong(0).toInt()
        }
    }

    companion object {
        /**
         * Writes a snapshot of the database at [source] to [dest] on a new read-only connection, which it closes.
         * Other connections to [source] go on reading and writing meanwhile (the database is in WAL mode).
         */
        fun snapshotFile(source: File, dest: File) {
            val connection = BundledSQLiteDriver().open(source.absolutePath, SQLITE_OPEN_READONLY)
            try {
                // A reader waits for a checkpoint or WAL recovery in progress rather than failing.
                connection.prepare("PRAGMA busy_timeout = $BUSY_MS").use { it.step() }
                connection.prepare("VACUUM INTO ?").use { statement ->
                    statement.bindText(1, dest.absolutePath)
                    statement.step()
                }
            } finally {
                connection.close()
            }
        }

        private const val BUSY_MS = 10_000

        /** The file name Room opens (`DatabaseModule`); restore replaces this file and its -wal/-shm. */
        const val NAME = "locus.db"
    }
}
