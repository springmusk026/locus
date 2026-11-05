package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * The v17 → v18 migration: chats get `chatOnly`, set for a chat that starts on a model that can't use
 * tools. Existing chats get 0, so they keep declaring the tools they always had.
 */
class ChatOnlyMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(17).createAll())
    }

    /** What Room runs to go from 17 to 18 (see the generated LocusDatabase_AutoMigration_17_18_Impl). */
    private fun migrate() = db.run(MIGRATE_17_18)

    @Test
    fun `existing chats keep their data and their tools`() {
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, toolDiscovery) " +
                "VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2, 'v1');",
        )
        val columns = "SELECT id, title, providerId, systemPrompt, createdAt, updatedAt, toolDiscovery FROM conversations;"
        val before = db.run(columns)
        migrate()
        assertEquals(before, db.run(columns))
        assertEquals("0", db.run("SELECT chatOnly FROM conversations WHERE id = 'c';").trim())
    }

    @Test
    fun `the schema matches a fresh v18 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(18).createAll()) }
        val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('conversations');"
        assertEquals(fresh.run(shape), db.run(shape))
    }

    companion object {
        const val MIGRATE_17_18 = "ALTER TABLE `conversations` ADD COLUMN `chatOnly` INTEGER NOT NULL DEFAULT 0;"
    }
}
