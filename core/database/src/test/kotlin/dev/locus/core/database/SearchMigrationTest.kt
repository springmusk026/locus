package dev.locus.core.database

import dev.locus.core.database.Sqlite3.Companion.literal
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v7 → v8 migration, run on a database created from the exported v7 schema, the way Room runs it:
 * the generated AutoMigration's statements, then [SearchMigration], then the FTS sync triggers Room
 * creates once migrations are done.
 */
class SearchMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: Sqlite3

    @Before
    fun setUp() {
        Sqlite3.assumeAvailable()
        db = Sqlite3(tmp.newFile("locus.db"))
        db.run(ExportedSchema(7).createAll())
    }

    private val v8 = ExportedSchema(8)

    /** What Room runs to go from 7 to 8 (see the generated LocusDatabase_AutoMigration_7_8_Impl). */
    private fun migrate() = db.run(
        listOf(
            "ALTER TABLE `messages` ADD COLUMN `searchText` TEXT DEFAULT NULL",
            v8.createSql("message_fts"),
            SearchMigration.BACKFILL_SEARCH_TEXT,
            SearchMigration.REBUILD_INDEX,
        ).plus(v8.triggers("message_fts")).joinToString(";\n", postfix = ";"),
    )

    private fun chat(id: String, hidden: Boolean = false, title: String = id) = db.run(
        "INSERT INTO conversations (id, title, providerId, systemPrompt, createdAt, updatedAt, hidden) VALUES (${literal(id)}, ${literal(title)}, NULL, 'prompt', 0, 0, ${if (hidden) 1 else 0});",
    )

    private var clock = 0L

    private fun message(id: String, chat: String, role: String, partsJson: String, parent: String? = null, copiedFrom: String? = null) = db.run(
        "INSERT INTO messages (id, conversationId, role, partsJson, createdAt, parentId, copiedFrom) VALUES " +
            "(${literal(id)}, ${literal(chat)}, ${literal(role)}, ${literal(partsJson)}, ${++clock}, ${parent?.let(::literal) ?: "NULL"}, ${copiedFrom?.let(::literal) ?: "NULL"});",
    )

    private fun searchText(id: String): String? = db.run("SELECT quote(searchText) FROM messages WHERE id = ${literal(id)};")
        .let { if (it == "NULL") null else it.removeSurrounding("'").replace("''", "'") }

    private fun text(s: String) = """{"kind":"text","text":${jsonString(s)}}"""
    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun search(match: String, limit: Int = 50) =
        db.rows(SEARCH_MESSAGES.replace(":match", literal(match)).replace(":tokens", "8").replace(":limit", "$limit") + ";")

    @Test
    fun `existing messages get their plain text, and nothing else`() {
        chat("c")
        message("u1", "c", "USER", """[{"kind":"attachment","name":"report.pdf","mimeType":"application/pdf","path":"/x","workspacePath":"inbox/report.pdf","sizeBytes":1},${text("Summarise the Kotlin report")},${text("\n\n<locus_mode>Plan mode is on.</locus_mode>")}]""")
        message("a1", "c", "ASSISTANT", """[{"kind":"reasoning","content":"secret thoughts about zebras","durationMs":5},${text("Let me read it.")},{"kind":"tool_call","id":"k1","name":"read_file","argumentsJson":"{\"path\":\"inbox/report.pdf\"}"}]""", parent = "u1")
        message("t1", "c", "TOOL", """[{"kind":"tool_result","callId":"k1","toolName":"read_file","content":"zebra payload"}]""", parent = "a1")
        message("a2", "c", "ASSISTANT", """[${text("It is about **coroutines**.")}]""", parent = "t1")
        message("u2", "c", "USER", """[${text("ok")},${text("\n\n<locus_result>ran ls: zebra.txt</locus_result>")}]""", parent = "a2")
        message("cp", "c", "USER", """[{"kind":"checkpoint","summary":"zebras were discussed","replacedMessages":4}]""", parent = "u2")
        message("bad", "c", "USER", "not json", parent = "cp")

        migrate()

        assertEquals("report.pdf Summarise the Kotlin report", searchText("u1"))
        assertEquals("Let me read it.", searchText("a1"))
        assertEquals(null, searchText("t1"))
        assertEquals("It is about **coroutines**.", searchText("a2"))
        assertEquals("ok", searchText("u2"))
        assertEquals(null, searchText("cp"))
        assertEquals(null, searchText("bad"))
        // Nothing from reasoning, tool payloads, notes for the model or checkpoints is findable.
        assertTrue(search("\"zebra\" *").isEmpty())
        assertTrue(search("\"locus_mode\"").isEmpty())
        assertEquals(listOf("u1"), search("\"report\"").map { it.substringBefore('|') }.filter { it == "u1" })
    }

    @Test
    fun `data survives and the schema matches a fresh v8 database`() {
        chat("c", title = "Café")
        message("u1", "c", "USER", "[${text("hello")}]")
        message("a1", "c", "ASSISTANT", "[${text("hi")}]", parent = "u1")
        val before = db.run("SELECT seq, id, conversationId, role, partsJson, createdAt, parentId FROM messages ORDER BY seq; SELECT * FROM conversations;")

        migrate()

        assertEquals(before, db.run("SELECT seq, id, conversationId, role, partsJson, createdAt, parentId FROM messages ORDER BY seq; SELECT * FROM conversations;"))
        val fresh = Sqlite3(tmp.newFile("fresh.db")).apply { run(v8.createAll()) }
        val shape = "SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info('messages'); " +
            "SELECT type, name, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name;"
        assertEquals(fresh.run(shape), db.run(shape))
        assertEquals(
            fresh.run("SELECT sql FROM sqlite_master WHERE name = 'message_fts';"),
            db.run("SELECT sql FROM sqlite_master WHERE name = 'message_fts';"),
        )
    }

    @Test
    fun `the index follows inserts and deletes after the migration`() {
        chat("c")
        chat("d")
        message("u1", "c", "USER", "[${text("Kotlin flows")}]")
        migrate()
        // New rows arrive with searchText already set (the repository derives it); the trigger indexes them.
        db.run("INSERT INTO messages (id, conversationId, role, partsJson, createdAt, searchText) VALUES ('u2', 'd', 'USER', '[]', 99, 'Kotlin coroutines in São Paulo');")
        assertEquals(setOf("u1", "u2"), search("\"kotl\" *").map { it.substringBefore('|') }.toSet())
        assertEquals(listOf("u2"), search("\"sao\" \"paulo\"").map { it.substringBefore('|') })
        // Deleting a chat cascades to its messages, and the triggers keep the index consistent with them.
        db.run("DELETE FROM conversations WHERE id = 'c';")
        db.run("INSERT INTO message_fts(message_fts, rank) VALUES('integrity-check', 1);")
        assertEquals(listOf("u2"), search("\"kotlin\"").map { it.substringBefore('|') })
    }

    @Test
    fun `search finds each chat once, newest match first, without helpers or fork copies`() {
        chat("a")
        chat("b")
        chat("helper", hidden = true)
        chat("fork")
        message("a1", "a", "USER", "[${text("deploy the kotlin site")}]")
        message("a2", "a", "ASSISTANT", "[${text("The kotlin site is live.")}]", parent = "a1")
        message("h1", "helper", "ASSISTANT", "[${text("kotlin research notes")}]")
        message("b1", "b", "USER", "[${text("kotlin again")}]")
        // An older branch of b also matches; b still shows once, with its newest match.
        message("b0", "b", "USER", "[${text("kotlin first try")}]")
        message("f1", "fork", "USER", "[${text("deploy the kotlin site")}]", copiedFrom = "a1")
        migrate()

        val hits = search("\"kotlin\"")
        assertEquals(listOf("b0", "a2"), hits.map { it.substringBefore('|') })
        assertEquals("b0|b|USER|${clock - 1}|kotlin first try", hits.first())

        // Once the source chat is gone, the copy is the only place left to find it.
        db.run("DELETE FROM conversations WHERE id = 'a';")
        assertEquals(listOf("f1", "b0"), search("\"deploy\"").map { it.substringBefore('|') } + search("\"first\"").map { it.substringBefore('|') })
        assertEquals(1, search("\"kotlin\"", limit = 1).size)
    }

    @Test
    fun `query strings never break the index`() {
        chat("c")
        message("u1", "c", "USER", "[${text("say \"hi\" to C++ and naïve café")}]")
        migrate()
        assertEquals(1, search("\"say \"\"hi\"\"\"").size)
        assertEquals(1, search("\"cafe\" \"naive\"").size)
        assertEquals(0, search("\"...\" *").size)
    }

    @Test
    fun `backfill time for a few thousand messages`() {
        chat("c")
        val words = "kotlin compose room sqlite search index branch chat model token reply agent tool linux phone".split(" ")
        val count = 5_000
        val inserts = buildString {
            appendLine("BEGIN;")
            for (i in 0 until count) {
                val body = (0 until 60).joinToString(" ") { words[(i * 7 + it * 3) % words.size] }
                val role = if (i % 3 == 2) "TOOL" else if (i % 2 == 0) "USER" else "ASSISTANT"
                val parts = if (role == "TOOL") """[{"kind":"tool_result","callId":"k$i","toolName":"shell","content":${jsonString(body)}}]""" else "[${text(body)}]"
                appendLine("INSERT INTO messages (id, conversationId, role, partsJson, createdAt) VALUES ('m$i', 'c', '$role', ${literal(parts)}, $i);")
            }
            appendLine("COMMIT;")
        }
        db.run(inserts)
        val ms = measureTimeMillis { migrate() }
        println("v7 → v8 migration of $count messages (~60 words each) took $ms ms with the system sqlite3, process start included")
        assertEquals("${count - count / 3}", db.run("SELECT count(*) FROM messages WHERE searchText IS NOT NULL;"))
        db.run("INSERT INTO message_fts(message_fts, rank) VALUES('integrity-check', 1);")
        assertTrue(ms < 20_000)
    }
}
