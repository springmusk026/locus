package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * v15 → v17 in one go, the path a phone on v15 takes: the scheduled tasks' run conditions (16) and then
 * chats' `toolDiscovery` (17). Old tasks keep no conditions, old chats keep every tool, and every table
 * ends up shaped like a fresh v17 database.
 */
class CombinedMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(15).createAll())
    }

    /** What Room runs, in order (see the generated LocusDatabase_AutoMigration_15_16_Impl and _16_17_Impl). */
    private fun migrate() = db.run(
        listOf(
            "ALTER TABLE `scheduled_tasks` ADD COLUMN `requiresCharging` INTEGER NOT NULL DEFAULT 0;",
            "ALTER TABLE `scheduled_tasks` ADD COLUMN `unmeteredOnly` INTEGER NOT NULL DEFAULT 0;",
            "ALTER TABLE `scheduled_tasks` ADD COLUMN `batteryNotLow` INTEGER NOT NULL DEFAULT 0;",
            ToolDiscoveryMigrationTest.MIGRATE_16_17,
        ).joinToString("\n"),
    )

    @Test
    fun `old chats, their messages and old tasks are kept as they were`() {
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, mcpFirst, projectId) " +
                "VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2, 'github', NULL);" +
                "INSERT INTO messages (id, conversationId, role, partsJson, createdAt) VALUES ('m', 'c', 'USER', '[]', 3);" +
                "INSERT INTO scheduled_tasks (id, title, prompt, intervalMinutes, enabled, createdAt) " +
                "VALUES ('t', 'Digest', 'Summarise', 60, 1, 4);",
        )
        val chat = "SELECT id, title, providerId, systemPrompt, createdAt, updatedAt, mcpFirst, quote(projectId) FROM conversations;"
        val task = "SELECT id, title, prompt, intervalMinutes, enabled, createdAt FROM scheduled_tasks;"
        val chatBefore = db.run(chat)
        val taskBefore = db.run(task)
        migrate()
        assertEquals(chatBefore, db.run(chat))
        assertEquals(taskBefore, db.run(task))
        assertEquals("NULL", db.run("SELECT quote(toolDiscovery) FROM conversations WHERE id = 'c';").trim())
        assertEquals("0|0|0", db.run("SELECT requiresCharging, unmeteredOnly, batteryNotLow FROM scheduled_tasks WHERE id = 't';").trim())
        assertEquals("1", db.run("SELECT COUNT(*) FROM messages WHERE conversationId = 'c';").trim())
    }

    @Test
    fun `every table matches a fresh v17 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(17).createAll()) }
        val tables = "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name;"
        assertEquals(fresh.rows(tables), db.rows(tables))
        for (table in fresh.rows(tables)) {
            val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('$table');"
            assertEquals(fresh.run(shape), db.run(shape), table)
        }
    }
}
