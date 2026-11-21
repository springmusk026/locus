package dev.locus.core.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import dev.locus.core.database.RETIRE_RESTORED_PROPOSALS
import java.io.File

/**
 * Before a restored database is swapped in: its proposals waiting in Needs you become STALE, so they can be read but
 * never run (docs/design/needs-you.md, "Backups"). A backup is a snapshot: a proposal it holds as waiting may have been
 * approved and run after the backup was made, and running it again would send that email twice. A backup from before
 * Needs you has no such table and is left alone. It runs on the staged copy, with the bundled SQLite Room uses.
 */
class RestoredProposals(private val driver: BundledSQLiteDriver = BundledSQLiteDriver()) {
    /** Retires [file]'s waiting proposals; returns how many. Nothing is written when there are none. */
    fun retire(file: File): Int {
        val connection = driver.open(file.path, SQLITE_OPEN_READWRITE)
        return try {
            connection.execSQL("PRAGMA trusted_schema = OFF")
            val hasTable = connection.prepare("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'proposals'").use { it.step(); it.getLong(0) > 0 }
            if (!hasTable) return 0
            val waiting = connection.prepare("SELECT COUNT(*) FROM proposals WHERE status IN ('PENDING', 'RUNNING')").use { it.step(); it.getLong(0).toInt() }
            if (waiting > 0) connection.execSQL(RETIRE_RESTORED_PROPOSALS)
            waiting
        } finally {
            connection.close()
            // As in RestoredDatabaseCheck: nothing beside the staged database may be swapped in with it.
            listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
