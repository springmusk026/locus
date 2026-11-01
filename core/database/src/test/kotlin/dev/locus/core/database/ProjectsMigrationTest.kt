package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * The v14 → v15 migration: the `projects` table, and `projectId` / `projectMemory` on chats. Existing
 * chats stay exactly as they were, outside any project, and their messages survive (the table is
 * altered in place, never recreated, so the messages' cascade can't fire).
 */
class ProjectsMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(14).createAll())
    }

    private val v15 = ExportedSchema(15)

    /** What Room runs to go from 14 to 15 (see the generated LocusDatabase_AutoMigration_14_15_Impl). */
    private fun migrate() = db.run(
        listOf(
            "ALTER TABLE `conversations` ADD COLUMN `projectId` TEXT DEFAULT NULL",
            "ALTER TABLE `conversations` ADD COLUMN `projectMemory` TEXT DEFAULT NULL",
            v15.createSql("projects"),
        ).plus(v15.triggers("message_fts")).joinToString(";\n", postfix = ";"),
    )

    @Test
    fun `existing chats and their messages are kept, outside any project`() {
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, disabledTools, mcpFirst) " +
                "VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2, 'group:email', 'github');" +
                "INSERT INTO messages (id, conversationId, role, partsJson, createdAt) VALUES ('m', 'c', 'USER', '[]', 3);",
        )
        val columns = "id, title, providerId, systemPrompt, createdAt, updatedAt, disabledTools, mcpFirst"
        val before = db.run("SELECT $columns FROM conversations;")
        migrate()
        assertEquals(before, db.run("SELECT $columns FROM conversations;"))
        assertEquals("NULL|NULL", db.run("SELECT quote(projectId), quote(projectMemory) FROM conversations WHERE id = 'c';"))
        assertEquals("1", db.run("SELECT COUNT(*) FROM messages WHERE conversationId = 'c';"))
        assertEquals("0", db.run("SELECT COUNT(*) FROM projects;"))
    }

    @Test
    fun `the schema matches a fresh v15 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(v15.createAll()) }
        for (table in listOf("conversations", "projects")) {
            val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('$table');"
            assertEquals(fresh.run(shape), db.run(shape), table)
        }
        val indexes = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'conversations' ORDER BY name;"
        assertEquals(fresh.run(indexes), db.run(indexes))
    }

    @Test
    fun `a chat can join a project after the migration`() {
        migrate()
        db.run(
            "INSERT INTO projects (id, name, folder, providerId, model, helperProviderId, helperModel, memoryScope, createdAt, updatedAt) " +
                "VALUES ('p1', 'Garden', 'projects/garden', NULL, NULL, NULL, NULL, 'PROJECT_ONLY', 1, 1);" +
                "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, projectId, projectMemory) " +
                "VALUES ('c2', 'New', NULL, 'prompt', 1, 1, 'p1', 'PROJECT_ONLY');",
        )
        assertEquals("c2", db.run("SELECT id FROM conversations WHERE projectId = 'p1';"))
    }
}
