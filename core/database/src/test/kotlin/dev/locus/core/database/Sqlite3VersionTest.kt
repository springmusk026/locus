package dev.locus.core.database

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The gate that skips the system-sqlite3 tests on a host whose SQLite is too old for their SQL. */
class Sqlite3VersionTest {
    private fun reason(version: String?, fts5: Boolean = true) = Sqlite3.unavailableReason(version) { fts5 }

    @Test
    fun `a sqlite older than 3_44 is reported, not run into a syntax error`() {
        val old = reason("3.40.1 2022-12-28 14:03:47 df5c253c0b3dd24916e4ec7cf77d3db5294cc9fd45ae7b9c5e82ad8197f38a24 (64-bit)")
        assertTrue(old != null && "3.44.0 or newer" in old && "found 3.40.1" in old, old)
        assertTrue(reason("3.43.2 2023-10-10")!!.contains("found 3.43.2"))
    }

    @Test
    fun `3_44 and newer pass, as do newer major-minor versions`() {
        assertNull(reason("3.44.0 2023-11-01"))
        assertNull(reason("3.50.1 2025-06-06"))
        assertNull(reason("3.54.0 2026-04-09 8fa8248e (64-bit)"))
        assertNull(reason("4.0.0 2030-01-01"))
    }

    @Test
    fun `a missing or unreadable sqlite3 and a build without FTS5 say so`() {
        assertEquals("sqlite3 is not installed", reason(null))
        assertTrue(reason("garbage")!!.contains("found garbage"))
        assertEquals("sqlite3 was built without FTS5 or JSON", reason("3.50.1 2025-06-06", fts5 = false))
    }

    @Test
    fun `versions are read from the first word`() {
        assertEquals(listOf(3, 40, 1), Sqlite3.parseVersion("3.40.1 2022-12-28"))
        assertEquals(listOf(3, 9), Sqlite3.parseVersion("3.9 2015"))
        assertNull(Sqlite3.parseVersion("not-a-version"))
    }
}
