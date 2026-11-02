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
 * on the JVM. Tests using it are skipped where `sqlite3` (with FTS5 and JSON) isn't installed.
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
         * Skips the test where `sqlite3` (with FTS5 and JSON) is missing, as on a bare dev machine. On CI
         * (the `CI` variable GitHub Actions sets) a missing `sqlite3` fails instead, so the migration
         * tests can't quietly stop running there.
         */
        fun assumeAvailable() {
            val ok = isAvailable()
            if (System.getenv("CI") == "true") check(ok) { "sqlite3 with FTS5 and JSON is required on CI but isn't installed" }
            Assume.assumeTrue("sqlite3 with FTS5 is not installed", ok)
        }

        private fun isAvailable(): Boolean = runCatching {
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
