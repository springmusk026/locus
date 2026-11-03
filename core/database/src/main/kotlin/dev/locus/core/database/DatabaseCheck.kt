package dev.locus.core.database

import androidx.room3.useReaderConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The self-test's integrity check of the open database. `PRAGMA quick_check` "does most of the
 * checking of the full integrity_check pragma but runs much faster" (it skips matching index content
 * to table rows), returns a single row "ok" when no problem is found, and otherwise one row per
 * problem, up to N rows for `quick_check(N)` (sqlite.org/pragma.html#pragma_quick_check). It only
 * reads, so it runs on a reader connection and never blocks a write for long.
 */
@Singleton
class DatabaseCheck @Inject constructor(private val db: LocusDatabase) {
    /** The problems `quick_check` found, at most [maxRows]; empty when the database is fine. */
    suspend fun quickCheck(maxRows: Int = 20): List<String> = db.useReaderConnection { connection ->
        connection.usePrepared("PRAGMA quick_check($maxRows)") { statement ->
            buildList {
                while (statement.step()) add(statement.getText(0))
            }
        }
    }.filterNot { it.equals("ok", ignoreCase = true) }
}
