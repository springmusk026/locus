package dev.locus.core.database

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The v10 → v11 migration: a new `file_changes` table for change tracking (docs/design/change-tracking.md).
 * Nothing existing changes; the table starts empty and goes with its chat.
 */
class FileChangesMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(10).createAll())
    }

    /** What Room runs to go from 10 to 11 (see the generated LocusDatabase_AutoMigration_10_11_Impl): the new table and its indices. */
    private fun migrate() {
        val v11 = ExportedSchema(11)
        db.run(
            v11.createSql("file_changes") + ";\n" +
                "CREATE INDEX IF NOT EXISTS `index_file_changes_conversationId` ON `file_changes` (`conversationId`);\n" +
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_file_changes_turnId_path` ON `file_changes` (`turnId`, `path`);",
        )
    }

    private val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('file_changes');"
    private val indices = "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'file_changes' AND name NOT LIKE 'sqlite_%' ORDER BY name;"

    @Test
    fun `existing chats and messages are untouched`() {
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES ('c', 'Old chat', 'p', 'prompt', 1, 2);" +
                "INSERT INTO messages (id, conversationId, role, partsJson, createdAt) VALUES ('m', 'c', 'USER', '[]', 3);",
        )
        val before = db.run("SELECT * FROM conversations; SELECT seq, id, conversationId, role, partsJson, createdAt FROM messages;")
        migrate()
        assertEquals(before, db.run("SELECT * FROM conversations; SELECT seq, id, conversationId, role, partsJson, createdAt FROM messages;"))
        assertEquals("0", db.run("SELECT COUNT(*) FROM file_changes;"))
    }

    @Test
    fun `the schema matches a fresh v11 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(ExportedSchema(11).createAll()) }
        assertEquals(fresh.run(shape), db.run(shape))
        assertEquals(fresh.run(indices), db.run(indices))
    }

    @Test
    fun `changes go with their chat, and a turn holds one row per path`() {
        migrate()
        db.run(
            "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES ('c', 'Chat', 'p', 'prompt', 1, 2);" +
                "INSERT INTO file_changes (conversationId, turnId, path, callId, toolName, existedBefore, beforeTrashId, isDirectory, afterSha256, afterExists, added, removed, diff, binary, createdAt, updatedAt) " +
                "VALUES ('c', 't1', 'a.txt', 'call', 'edit_file', 1, 'trash-1', 0, 'abc', 1, 1, 1, '--- a/a.txt', 0, 5, 5);",
        )
        assertFailsWith<IllegalStateException> {
            db.run(
                "INSERT INTO file_changes (conversationId, turnId, path, callId, toolName, existedBefore, beforeTrashId, isDirectory, afterSha256, afterExists, added, removed, diff, binary, createdAt, updatedAt) " +
                    "VALUES ('c', 't1', 'a.txt', 'call2', 'edit_file', 1, 'trash-2', 0, 'def', 1, 1, 1, NULL, 0, 6, 6);",
            )
        }
        db.run("DELETE FROM conversations WHERE id = 'c';")
        assertEquals("0", db.run("SELECT COUNT(*) FROM file_changes;"))
    }
}
