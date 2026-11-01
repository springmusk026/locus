package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * The v15 → v16 migration: chats get `toolDiscovery`, the core-set version they froze when created.
 * Existing chats get none, so they keep declaring every tool and their cached prefix stays the same.
 */
class ToolDiscoveryMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(15).createAll())
    }

    /** What Room runs to go from 15 to 16 (see the generated LocusDatabase_AutoMigration_15_16_Impl). */
    private fun migrate() = db.run("ALTER TABLE `conversations` ADD COLUMN `toolDiscovery` TEXT DEFAULT NULL;")

    @Test
    fun `existing chats keep their data and every tool`() {
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, mcpFirst) " +
                "VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2, 'github');",
        )
        val columns = "SELECT id, title, providerId, systemPrompt, createdAt, updatedAt, mcpFirst FROM conversations;"
        val before = db.run(columns)
        migrate()
        assertEquals(before, db.run(columns))
        assertEquals("NULL", db.run("SELECT quote(toolDiscovery) FROM conversations WHERE id = 'c';").trim())
    }

    @Test
    fun `the schema matches a fresh v16 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(16).createAll()) }
        val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('conversations');"
        assertEquals(fresh.run(shape), db.run(shape))
    }
}
