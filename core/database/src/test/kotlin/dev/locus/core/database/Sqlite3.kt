package dev.locus.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs SQL through the system `sqlite3`, so the migration and search SQL are tested on a real SQLite
 * on the JVM. Tests using it are skipped where `sqlite3` (with FTS5 and JSON, 3.44 or newer) isn't installed.
 * The app ships its own SQLite (the bundled driver, 3.50 with FTS5); this one is whatever the OS has.
 */
class Sqlite3(val file: File) {
    /** Runs [sql] (one or more statements) and returns what it printed, one row per line, `|` between columns. */
    fun run(sql: String): String {
        val process = ProcessBuilder(BINARY, "-batch", "-bail", file.absolutePath).redirectErrorStream(false).start()
        process.outputStream.bufferedWriter().use { it.write("PRAGMA foreign_keys = ON;\n$sql\n") }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "sqlite3 timed out" }
        check(process.exitValue() == 0 && err.isBlank()) { "sqlite3 failed: $err\n--- SQL ---\n${sql.take(2_000)}" }
        return out.trimEnd('\n')
    }

    fun rows(sql: String): List<String> = run(sql).lines().filter { it.isNotEmpty() }

    companion object {
        const val BINARY = "sqlite3"

        /**
         * The oldest SQLite these tests' SQL runs on: `group_concat(x, sep ORDER BY y)` (the search backfill in
         * [LocusDatabase]) arrived in 3.44. Debian 12 and Amazon Linux 2023 ship 3.40, where it fails with
         * `near "ORDER": syntax error`. The app itself uses the bundled SQLite (3.50), so it isn't affected.
         */
        val MIN_VERSION = listOf(3, 44, 0)

        /**
         * Skips the test where `sqlite3` (with FTS5 and JSON, 3.44 or newer) is missing or too old, as on a bare dev
         * machine, saying which. On CI (the `CI` variable GitHub Actions sets) that fails instead, so the migration
         * tests can't quietly stop running there.
         */
        fun assumeAvailable() {
            val problem = unavailableReason()
            if (System.getenv("CI") == "true") check(problem == null) { "$problem (required on CI)" }
            Assume.assumeTrue(problem, problem == null)
        }

        /** Why the system `sqlite3` can't run these tests, or null when it can. */
        internal fun unavailableReason(version: String? = versionOutput(), fts5: () -> Boolean = ::hasFts5AndJson): String? {
            if (version == null) return "sqlite3 is not installed"
            val found = parseVersion(version)
            if (found == null || compare(found, MIN_VERSION) < 0) {
                return "sqlite3 ${MIN_VERSION.joinToString(".")} or newer is needed (group_concat ... ORDER BY), found ${found?.joinToString(".") ?: version.trim()}"
            }
            return if (fts5()) null else "sqlite3 was built without FTS5 or JSON"
        }

        /** `3.40.1` from `sqlite3 --version`'s first word ("3.40.1 2022-12-28 ..."), or null when it isn't a version. */
        internal fun parseVersion(output: String): List<Int>? =
            output.trim().substringBefore(' ').split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.size >= 2 }

        private fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        private fun versionOutput(): String? = runCatching {
            val p = ProcessBuilder(BINARY, "--version").redirectErrorStream(true).start()
            try {
                val out = p.inputStream.bufferedReader().readText()
                out.takeIf { p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 }
            } finally {
                p.destroy()
            }
        }.getOrNull()

        private fun hasFts5AndJson(): Boolean = runCatching {
            val p = ProcessBuilder(BINARY, ":memory:", "CREATE VIRTUAL TABLE t USING fts5(x); SELECT json_valid('[]');")
                .redirectErrorStream(true).start()
            try {
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            } finally {
                p.destroy()
            }
        }.getOrDefault(false)

        /** A string as an SQL literal. */
        fun literal(s: String) = "'" + s.replace("'", "''") + "'"
    }
}

/** An exported Room schema (core/database/schemas), as the SQL Room runs to create it. */
class ExportedSchema(version: Int) {
    private val database: JsonObject = Json.parseToJsonElement(
        File("schemas/dev.locus.core.database.LocusDatabase/$version.json").readText(),
    ).jsonObject.getValue("database").jsonObject

    private val entities = database.getValue("entities").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    fun entity(table: String): JsonObject = entities.first { it.string("tableName") == table }

    fun createSql(table: String): String = entity(table).string("createSql").replace("\${TABLE_NAME}", table)

    /** FTS content-sync triggers, which Room creates after migrations run. */
    fun triggers(table: String): List<String> = entity(table)["contentSyncTriggers"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /** Everything Room's createAllTables runs for a new database. */
    fun createAll(): String = buildString {
        for (e in entities) {
            val table = e.string("tableName")
            appendLine(createSql(table) + ";")
            e["indices"]?.jsonArray?.forEach { appendLine(it.jsonObject.string("createSql").replace("\${TABLE_NAME}", table) + ";") }
            triggers(table).forEach { appendLine("$it;") }
        }
        database.getValue("setupQueries").jsonArray.forEach { appendLine(it.jsonPrimitive.content + ";") }
    }
}
