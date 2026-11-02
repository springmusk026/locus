package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * The v9 → v10 migration: chats get `mcpFirst`, the built-in integrations they reach through MCP
 * servers. Existing chats get none, so their declared tools stay exactly as they were.
 */
class McpFirstMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(9).createAll())
    }

    /** What Room runs to go from 9 to 10 (see the generated LocusDatabase_AutoMigration_9_10_Impl). */
    private fun migrate() = db.run("ALTER TABLE `conversations` ADD COLUMN `mcpFirst` TEXT NOT NULL DEFAULT '';")

    @Test
    fun `existing chats keep their data and leave nothing out`() {
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, disabledTools) " +
                "VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2, 'group:email');",
        )
        val before = db.run("SELECT id, title, providerId, systemPrompt, createdAt, updatedAt, disabledTools FROM conversations;")
        migrate()
        assertEquals(before, db.run("SELECT id, title, providerId, systemPrompt, createdAt, updatedAt, disabledTools FROM conversations;"))
        assertEquals("''", db.run("SELECT quote(mcpFirst) FROM conversations WHERE id = 'c';").trim())
    }

    @Test
    fun `the schema matches a fresh v10 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(10).createAll()) }
        val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('conversations');"
        assertEquals(fresh.run(shape), db.run(shape))
    }
}
