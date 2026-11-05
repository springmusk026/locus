package dev.locus.core.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import java.io.File

/**
 * Checks the database from a backup before a restore accepts it (docs/design/backup.md, "Restore").
 * The backup's encryption proves the file is the one that was written, not that the database in it
 * was sound when it was written, and a damaged database would otherwise only show once Room opens it,
 * after the swap, as missing chats or a crash.
 *
 * It runs `PRAGMA quick_check`, which "does not verify UNIQUE constraints and does not verify that
 * index content matches table content", and so runs in O(N) time where integrity_check needs
 * O(N log N) (sqlite.org/pragma.html#pragma_quick_check). Restore already reads every byte of the
 * backup, so one linear pass over the database adds little; the index-to-row mismatches it skips are
 * what `REINDEX` repairs, not lost data. Since SQLite 3.44 both pragmas also call each virtual table's
 * `xIntegrity` (sqlite.org/vtab.html#xintegrity), so the chat search index (`message_fts`, FTS5) is
 * checked too: a damaged index fails with "malformed inverted index for FTS5 table".
 *
 * This catches damage, not attacks: a database crafted to exploit an SQLite bug (CVE-2025-7709, a
 * corrupt FTS5 index, is fixed only in SQLite 3.50.3, and the bundled one is 3.50.1) can be built to
 * pass both checks, and the checks themselves run the code it targets. That fix needs a newer
 * `sqlite-bundled` (docs/research/reliability-security-audit-2026-11.md); a crafted backup also needs
 * its password. It runs on the staged copy with the same bundled SQLite Room uses, never on the live
 * database.
 */
class RestoredDatabaseCheck(private val driver: BundledSQLiteDriver = BundledSQLiteDriver()) {
    /** The problems found in [file], at most [maxRows] from quick_check; empty when it's sound. */
    fun problems(file: File, maxRows: Int = 20): List<String> {
        // Read-write, as Room opens it (a WAL-mode database read-only needs its -shm file to exist already,
        // sqlite.org/wal.html#readonly), but without CREATE: a missing file is a problem, not a new database.
        val connection = try {
            driver.open(file.path, SQLITE_OPEN_READWRITE)
        } catch (e: SQLiteException) {
            return listOf("Can't open the database: ${e.message}")
        }
        return try {
            // The schema comes from the backup: don't let its views or triggers call functions with side
            // effects (sqlite.org/pragma.html#pragma_trusted_schema). Nothing here fires them anyway.
            connection.execSQL("PRAGMA trusted_schema = OFF")
            connection.quickCheck(maxRows)
        } catch (e: SQLiteException) {
            // A header or schema too damaged to read fails the statement rather than returning rows.
            listOf("The database can't be read: ${e.message}")
        } finally {
            connection.close()
            // Opening a WAL database makes -wal and -shm files; closing the last connection removes them,
            // but nothing beside the staged database may be swapped in with it.
            listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }

    private fun SQLiteConnection.quickCheck(maxRows: Int): List<String> =
        prepare("PRAGMA quick_check($maxRows)").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }.filterNot { it.equals("ok", ignoreCase = true) }
}
