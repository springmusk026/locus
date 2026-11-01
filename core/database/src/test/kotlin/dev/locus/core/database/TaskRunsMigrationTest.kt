package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The v13 → v14 migration: a new `task_runs` table holding when each task ran and how it ended, for
 * task receipts (docs/design/task-receipt.md). Nothing existing changes; the table starts empty and
 * goes with its chat.
 */
class TaskRunsMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(13).createAll())
    }

    /** What Room runs to go from 13 to 14 (see the generated LocusDatabase_AutoMigration_13_14_Impl): the new table and its index. */
    private fun migrate() {
        db.run(
            ExportedSchema(14).createSql("task_runs") + ";\n" +
                "CREATE INDEX IF NOT EXISTS `index_task_runs_conversationId` ON `task_runs` (`conversationId`);",
        )
    }

    private val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('task_runs');"
    private val indices = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'task_runs' AND name NOT LIKE 'sqlite_%' ORDER BY name;"
    private val everythingElse = "SELECT * FROM conversations; SELECT seq, id, conversationId, role, partsJson, createdAt, usageJson FROM messages; " +
        "SELECT * FROM audit_log; SELECT * FROM usage_events;"

    @Test
    fun `existing chats, messages, activity and usage are untouched`() {
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2);" +
                "INSERT INTO messages (id, conversationId, role, partsJson, createdAt, usageJson) VALUES ('m', 'c', 'ASSISTANT', '[]', 3, '{\"input\":10,\"output\":2}');" +
                "INSERT INTO audit_log (id, conversationId, callId, toolName, argumentsJson, decision, resultPreview, isError, startedAt, finishedAt) " +
                "VALUES ('a', 'c', 'call', 'write_file', '{}', 'USER_APPROVED', 'ok', 0, 3, 4);" +
                "INSERT INTO usage_events (conversationId, source, createdAt, providerId, model, usageJson, totalMs) VALUES ('c', 'reviewer', 3, 'p', 'm', NULL, 5);",
        )
        val before = db.run(everythingElse)
        migrate()
        assertEquals(before, db.run(everythingElse))
        assertEquals("0", db.run("SELECT COUNT(*) FROM task_runs;"))
    }

    @Test
    fun `the schema matches a fresh v14 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(14).createAll()) }
        assertEquals(fresh.run(shape), db.run(shape))
        assertEquals(fresh.run(indices), db.run(indices))
    }

    @Test
    fun `a task opens without an outcome, closes with one, and goes with its chat`() {
        migrate()
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES ('c', 'Chat', 'p', 'prompt', 1, 2);" +
                "INSERT INTO task_runs (id, conversationId, startedAt) VALUES ('t', 'c', 10);",
        )
        assertEquals("t|c|10|||", db.run("SELECT * FROM task_runs;"))
        db.run("UPDATE task_runs SET endedAt = 20, outcome = 'failed', detail = 'quota' WHERE id = 't';")
        assertEquals("t|c|10|20|failed|quota", db.run("SELECT * FROM task_runs;"))
        // A task belongs to a chat that exists.
        assertFailsWith<IllegalStateException> { db.run("INSERT INTO task_runs (id, conversationId, startedAt) VALUES ('u', 'missing', 1);") }
        db.run("DELETE FROM conversations WHERE id = 'c';")
        assertEquals("0", db.run("SELECT COUNT(*) FROM task_runs;"))
    }
}
