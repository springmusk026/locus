package dev.locus.core.agent.home

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Which SQLite the app ships. Room and the memory index both open their databases with
 * `BundledSQLiteDriver` (`androidx.sqlite:sqlite-bundled`, one version in libs.versions.toml), never
 * Android's own SQLite, so this engine runs on every Android version. These tests load its desktop
 * build, which is compiled from the same SQLite source (the same `sqlite_source_id()` as the Android
 * `.so`; docs/design/search.md). See docs/research/reliability-security-audit-2026-11.md for the CVEs
 * this version still has.
 */
class BundledSqliteVersionTest {
    private fun query(sql: String): String = BundledSQLiteDriver().open(":memory:").use { c ->
        c.prepare(sql).use { s -> s.step(); s.getText(0) }
    }

    private fun parts(version: String) = version.split('.').map { it.toInt() }

    private fun atLeast(version: String, floor: String): Boolean {
        val v = parts(version)
        val f = parts(floor)
        for (i in 0 until maxOf(v.size, f.size)) {
            val a = v.getOrElse(i) { 0 }
            val b = f.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    @Test
    fun `the bundled SQLite never goes back past the version we checked`() {
        // A dependency change that pulled in an older sqlite-bundled would bring back fixed CVEs.
        // Raise FLOOR whenever sqlite-bundled moves up, after checking its SQLite version.
        val version = query("SELECT sqlite_version()")
        assertTrue(atLeast(version, FLOOR), "Bundled SQLite $version is older than $FLOOR")
    }

    @Test
    fun `the version comparison is numeric`() {
        assertTrue(atLeast("3.50.10", "3.50.9"))
        assertTrue(atLeast("3.100.0", "3.50.1"))
        assertTrue(!atLeast("3.49.2", "3.50.1"))
        assertTrue(atLeast("3.50.1", "3.50.1"))
    }

    @Test
    fun `it has the features the app relies on`() {
        val options = BundledSQLiteDriver().open(":memory:").use { c ->
            c.prepare("SELECT compile_options FROM pragma_compile_options").use { s ->
                buildList { while (s.step()) add(s.getText(0)) }
            }
        }
        assertTrue("ENABLE_FTS5" in options, options.toString())
        // VACUUM INTO (backups) came in 3.27.0.
        assertTrue(atLeast(query("SELECT sqlite_version()"), "3.27.0"))
    }

    private companion object {
        /** `androidx.sqlite:sqlite-bundled` 2.7.1 bundles SQLite 3.50.1 (source id 2025-06-06 … b77dc5e0). */
        const val FLOOR = "3.50.1"
    }
}
