package dev.locus.core.database

import dev.locus.core.database.Sqlite3.Companion.literal
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The v20 → v21 migration (#316): `audit_log` rebuilt with an INTEGER PRIMARY KEY `seq`, and the trigram index
 * `audit_fts` built over the rows already there. Run the way Room runs it: [ActivitySearchMigration]'s statements,
 * then the FTS sync triggers Room creates once migrations are done. A phone still on v19 takes 19 → 20 → 21.
 */
class ActivitySearchMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    private val v21 = ExportedSchema(21)

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(20).createAll())
    }

    private fun migrate(on: Sqlite3 = db) = on.run(MIGRATE_20_21)

    private fun row(id: String, chat: String, tool: String, args: String, preview: String, at: Long) =
        "INSERT INTO audit_log (id, conversationId, callId, toolName, argumentsJson, decision, resultPreview, isError, startedAt, finishedAt) " +
            "VALUES (${literal(id)}, ${literal(chat)}, ${literal("call-$id")}, ${literal(tool)}, ${literal(args)}, 'AUTO_ALLOWED', ${literal(preview)}, 0, $at, ${at + 5});"

    private val oldRows = listOf(
        // Inserted out of time order: seq should follow startedAt, not the order rows were written in.
        row("late", "c", "read_file", """{"path":"notes/Kotlin.md"}""", "Read 12 lines", 300),
        row("early", "c", "web_search", """{"query":"vuelos a Málaga"}""", "Encontré 3 resultados", 100),
        row("zh", "d", "shell", """{"command":"打开文件管理器"}""", "找到三个文件", 200),
    ).joinToString("")

    private fun match(phrase: String, on: Sqlite3 = db) =
        on.rows("SELECT a.id FROM audit_fts JOIN audit_log AS a ON a.seq = audit_fts.rowid WHERE audit_fts MATCH ${literal(phrase)} ORDER BY a.id;")

    private val columns = "SELECT id, conversationId, callId, toolName, argumentsJson, decision, resultPreview, isError, startedAt, finishedAt FROM audit_log ORDER BY id;"

    @Test
    fun `every row is kept, numbered in time order, and indexed`() {
        db.run(oldRows)
        val before = db.run(columns)

        migrate()

        assertEquals(before, db.run(columns))
        assertEquals(listOf("early|1", "zh|2", "late|3"), db.rows("SELECT id, seq FROM audit_log ORDER BY seq;"))
        // The backfill: rows from before the migration are found through the index, in every script.
        assertEquals(listOf("late"), match("\"kotlin\""))
        assertEquals(listOf("early"), match("\"málaga\""))
        assertEquals(listOf("zh"), match("\"文件管\""))
        assertEquals(listOf("zh"), match("\"三个文件\""))
        db.run("INSERT INTO audit_fts(audit_fts, rank) VALUES('integrity-check', 1);")
    }

    @Test
    fun `the schema matches a fresh v21 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(v21.createAll()) }
        val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('audit_log'); " +
            "SELECT name, \"unique\" FROM pragma_index_list('audit_log') ORDER BY name; " +
            "$OBJECTS"
        assertEquals(fresh.run(shape), db.run(shape))
    }

    @Test
    fun `ids stay unique, as they were as the primary key`() {
        db.run(oldRows)
        migrate()
        assertFailsWith<IllegalStateException> { db.run(row("late", "c", "read_file", "{}", "", 400)) }
    }

    @Test
    fun `the index follows new calls and deleted chats`() {
        db.run(oldRows)
        migrate()
        db.run(row("new", "d", "write_file", """{"path":"src/Main.kt","content":"fun main() = println(\"Hola\")"}""", "Wrote 1 line", 500))
        assertEquals(listOf("new"), match("\"println\""))
        // A temporary chat's calls are forgotten when it is deleted (AuditDao.deleteForConversation).
        db.run("DELETE FROM audit_log WHERE conversationId = 'd';")
        assertTrue(match("\"文件管\"").isEmpty())
        assertTrue(match("\"println\"").isEmpty())
        assertEquals(listOf("late"), match("\"kotlin\""))
        db.run("INSERT INTO audit_fts(audit_fts, rank) VALUES('integrity-check', 1);")
    }

    @Test
    fun `a backup snapshot keeps the index pointing at the right rows`() {
        // DatabaseBackup copies with VACUUM INTO; the index's rowids are audit_log.seq, which VACUUM keeps.
        db.run(oldRows)
        migrate()
        db.run("DELETE FROM audit_log WHERE id = 'early';")
        val copy = tmp.root.resolve("snapshot.db")
        db.run("VACUUM INTO ${literal(copy.absolutePath)};")
        val restored = Sqlite3(copy)
        restored.run("INSERT INTO audit_fts(audit_fts, rank) VALUES('integrity-check', 1); PRAGMA quick_check;")
        assertEquals(listOf("late"), match("\"kotlin\"", restored))
        assertEquals(listOf("zh"), match("\"文件管\"", restored))
    }

    @Test
    fun `from v19, chats and calls are kept and every table matches a fresh v21 database`() {
        val old = Sqlite3(tmp.newFile("old.db")).apply {
            run(ExportedSchema(19).createAll())
            run(oldRows)
        }
        old.run(NeedsYouMigrationTest.MIGRATE_19_20 + "\n" + MIGRATE_20_21)
        assertEquals(listOf("late"), match("\"kotlin\"", old))

        val fresh = Sqlite3(tmp.newFile("fresh21.db")).apply { run(v21.createAll()) }
        assertEquals(fresh.rows(OBJECTS), old.rows(OBJECTS))
        for (table in fresh.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%';")) {
            val shape = "SELECT name, type, \"notnull\", CASE WHEN dflt_value = 'NULL' THEN NULL ELSE dflt_value END, pk FROM pragma_table_info('$table');"
            assertEquals(fresh.run(shape), old.run(shape), table)
        }
    }

    companion object {
        /**
         * Every table, index, trigger and virtual table, with its SQL. A rebuilt table's own SQL is left out: SQLite
         * rewrites it on RENAME (`CREATE TABLE "audit_log"`), and Room compares a table's columns, not its text.
         */
        const val OBJECTS = "SELECT type, name, tbl_name, CASE WHEN type = 'table' AND sql NOT LIKE 'CREATE VIRTUAL%' THEN NULL ELSE sql END " +
            "FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name;"

        /** [ActivitySearchMigration], then the sync triggers Room's open delegate creates after migrations. */
        val MIGRATE_20_21 = (ActivitySearchMigration.STATEMENTS + ExportedSchema(21).triggers("audit_fts")).joinToString(";\n", postfix = ";")
    }
}
