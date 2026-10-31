package dev.locus.core.database

import dev.locus.core.database.Sqlite3.Companion.literal
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The v8 → v9 migration, run on a database created from the exported v8 schema, the way Room runs it:
 * the generated AutoMigration's statements, then the FTS sync triggers Room (re)creates once
 * migrations are done. v9 adds the answering model and latency to messages, and `usage_events`.
 */
class ReplyInfoMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(8).createAll())
    }

    private val v9 = ExportedSchema(9)

    /** What Room runs to go from 8 to 9 (see the generated LocusDatabase_AutoMigration_8_9_Impl). */
    private fun migrate() = db.run(
        listOf(
            "ALTER TABLE `messages` ADD COLUMN `providerId` TEXT DEFAULT NULL",
            "ALTER TABLE `messages` ADD COLUMN `model` TEXT DEFAULT NULL",
            "ALTER TABLE `messages` ADD COLUMN `firstTokenMs` INTEGER DEFAULT NULL",
            "ALTER TABLE `messages` ADD COLUMN `totalMs` INTEGER DEFAULT NULL",
            v9.createSql("usage_events"),
            "CREATE INDEX IF NOT EXISTS `index_usage_events_conversationId` ON `usage_events` (`conversationId`)",
            "CREATE INDEX IF NOT EXISTS `index_usage_events_createdAt` ON `usage_events` (`createdAt`)",
        ).plus(v9.triggers("message_fts")).joinToString(";\n", postfix = ";"),
    )

    private fun chat(id: String) = db.run(
        "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt) VALUES (${literal(id)}, ${literal(id)}, 'p', 'prompt', 0, 0);",
    )

    private var clock = 0L

    private fun message(id: String, chat: String, role: String, text: String, parent: String? = null, usage: String? = null, copiedFrom: String? = null) = db.run(
        "INSERT INTO messages (id, conversationId, role, partsJson, createdAt, usageJson, parentId, copiedFrom, searchText) VALUES " +
            "(${literal(id)}, ${literal(chat)}, ${literal(role)}, '[]', ${clock++ * 1_000}, ${usage?.let(::literal) ?: "NULL"}, " +
            "${parent?.let(::literal) ?: "NULL"}, ${copiedFrom?.let(::literal) ?: "NULL"}, ${literal(text)});",
    )

    private fun stats(since: Long = 0) = db.rows(MESSAGE_STATS.replace(":since", "$since") + ";")

    @Test
    fun `existing rows keep their data and get null model and latency`() {
        chat("c")
        message("u1", "c", "USER", "hello")
        message("a1", "c", "ASSISTANT", "hi", parent = "u1", usage = """{"input":10,"output":2}""")
        val before = db.run("SELECT seq, id, conversationId, role, partsJson, createdAt, format, rawPayload, usageJson, parentId, copiedFrom, searchText FROM messages ORDER BY seq;")

        migrate()

        assertEquals(before, db.run("SELECT seq, id, conversationId, role, partsJson, createdAt, format, rawPayload, usageJson, parentId, copiedFrom, searchText FROM messages ORDER BY seq;"))
        assertEquals("NULL|NULL|NULL|NULL", db.run("SELECT quote(providerId), quote(model), quote(firstTokenMs), quote(totalMs) FROM messages WHERE id = 'a1';"))
        // The analytics query reads old rows with nulls, so Insights falls back to inferring them.
        assertEquals(listOf("c|USER|0||||||", "c|ASSISTANT|1000|{\"input\":10,\"output\":2}|0||||"), stats())
    }

    @Test
    fun `the schema matches a fresh v9 database`() {
        migrate()
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(v9.createAll()) }
        val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('messages'); " +
            "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('usage_events'); " +
            "SELECT * FROM pragma_foreign_key_list('usage_events'); " +
            "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name;"
        assertEquals(fresh.run(shape), db.run(shape))
    }

    @Test
    fun `new rows store the model and latency, and search still works`() {
        chat("c")
        message("u1", "c", "USER", "kotlin flows")
        migrate()
        db.run(
            "INSERT INTO messages (id, conversationId, role, partsJson, createdAt, parentId, searchText, providerId, model, firstTokenMs, totalMs) " +
                "VALUES ('a1', 'c', 'ASSISTANT', '[]', 5000, 'u1', 'kotlin answer', 'p', 'claude-x', 420, 3100);",
        )
        assertEquals("c|ASSISTANT|5000||0|p|claude-x|420|3100", stats().last())
        // The FTS triggers still fire after ALTER TABLE: both rows are found, and the index is consistent.
        assertEquals(2, db.rows("SELECT rowid FROM message_fts WHERE message_fts MATCH '\"kotlin\"';").size)
        db.run("INSERT INTO message_fts(message_fts, rank) VALUES('integrity-check', 1);")
    }

    @Test
    fun `fork copies keep their fields but stay out of stats`() {
        chat("a")
        chat("b")
        migrate()
        db.run(
            "INSERT INTO messages (id, conversationId, role, partsJson, createdAt, providerId, model, totalMs) VALUES ('a1', 'a', 'ASSISTANT', '[]', 1, 'p', 'm', 900);" +
                "INSERT INTO messages (id, conversationId, role, partsJson, createdAt, providerId, model, totalMs, copiedFrom) VALUES ('b1', 'b', 'ASSISTANT', '[]', 2, 'p', 'm', 900, 'a1');",
        )
        assertEquals(listOf("a"), stats().map { it.substringBefore('|') })
        assertEquals("p|m|900", db.run("SELECT providerId, model, totalMs FROM messages WHERE id = 'b1';"))
    }

    @Test
    fun `usage events go with their chat, and may have none`() {
        chat("c")
        chat("d")
        migrate()
        db.run(
            "INSERT INTO usage_events (conversationId, source, createdAt, providerId, model, usageJson, totalMs) VALUES " +
                "('c', 'title', 10, 'p', 'small', '{\"input\":50,\"output\":5}', 300), " +
                "('d', 'compaction', 20, 'p', 'big', NULL, NULL), " +
                "(NULL, 'other', 30, 'p', 'small', NULL, NULL);",
        )
        db.run("DELETE FROM conversations WHERE id = 'c';")
        assertEquals(listOf("compaction|20", "other|30"), db.rows("SELECT source, createdAt FROM usage_events ORDER BY seq;"))
        // An event for a chat that doesn't exist is refused (the repository drops it quietly).
        assertFailsWith<IllegalStateException> {
            db.run("INSERT INTO usage_events (conversationId, source, createdAt) VALUES ('gone', 'title', 40);")
        }
    }
}
