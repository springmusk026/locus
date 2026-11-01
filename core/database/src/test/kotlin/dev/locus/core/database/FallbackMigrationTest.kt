package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * The v10 → v11 migration: replies get `fallbackFrom`, the chat's own model a backup stood in for
 * (docs/design/fallbacks.md). Existing replies get none and keep everything else.
 */
class FallbackMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(10).createAll())
    }

    /** What Room runs to go from 10 to 11 (see the generated LocusDatabase_AutoMigration_10_11_Impl). */
    private fun migrate() = db.run("ALTER TABLE `messages` ADD COLUMN `fallbackFrom` TEXT DEFAULT NULL;")

    @Test
    fun `existing replies keep their data and fell back from nothing`() {
        db.run("INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2);")
        db.run(
            "INSERT INTO messages (id, conversationId, role, partsJson, createdAt, searchText, providerId, model, firstTokenMs, totalMs) " +
                "VALUES ('a1', 'c', 'ASSISTANT', '[]', 3, 'hi', 'p', 'm', 10, 20);",
        )
        val columns = "SELECT id, conversationId, role, partsJson, createdAt, providerId, model, firstTokenMs, totalMs FROM messages;"
        val before = db.run(columns)
        migrate()
        assertEquals(before, db.run(columns))
        assertEquals("NULL", db.run("SELECT quote(fallbackFrom) FROM messages WHERE id = 'a1';").trim())
    }

    @Test
    fun `the schema matches a fresh v11 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(11).createAll()) }
        val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('messages');"
        assertEquals(fresh.run(shape), db.run(shape))
    }
}
