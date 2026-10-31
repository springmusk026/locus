package dev.locus.core.database

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the live database for a backup without closing it.
 *
 * `VACUUM INTO` (SQLite 3.27+, the bundled SQLite is 3.50) writes "a consistent snapshot of the
 * original database" to a new file, including what is still in the WAL, which a plain file copy
 * would miss ("If a database file is separated from its WAL file, then transactions that were
 * previously committed to the database might be lost", sqlite.org/wal.html). It runs on the
 * writer connection outside any transaction, since VACUUM refuses to run inside one, so no write
 * lands half-way through. The snapshot keeps the header's `user_version`, which is Room's schema
 * version. See docs/design/backup.md.
 */
@Singleton
class DatabaseBackup @Inject constructor(private val db: LocusDatabase) {
    /** Writes a snapshot to [dest], which must not exist yet. */
    suspend fun snapshotTo(dest: File) {
        require(!dest.exists()) { "Snapshot target already exists" }
        dest.parentFile?.mkdirs()
        db.useWriterConnection { connection ->
            connection.usePrepared("VACUUM INTO ?") { statement ->
                statement.bindText(1, dest.absolutePath)
                statement.step()
            }
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
        /** The file name Room opens (`DatabaseModule`); restore replaces this file and its -wal/-shm. */
        const val NAME = "locus.db"
    }
}
