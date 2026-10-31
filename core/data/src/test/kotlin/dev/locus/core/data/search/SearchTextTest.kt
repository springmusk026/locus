package dev.locus.core.data.search

import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.database.SearchMigration
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchTextTest {
    private val pdf = Part.Attachment("report.pdf", "application/pdf", "/data/x", "inbox/report.pdf", 10)

    private val samples: List<Pair<Role, List<Part>>> = listOf(
        Role.USER to listOf(pdf, Part.Text("Summarise this"), Part.Text(ModeNote.wrap(ModeNote.PLAN_MODE))),
        Role.USER to listOf(Part.Text("ran it"), Part.Text(ModeNote.result("$ ls\nsecret.txt"))),
        Role.USER to listOf(Part.Checkpoint("summary of the chat so far", 12)),
        Role.USER to listOf(pdf),
        Role.ASSISTANT to listOf(Part.Reasoning("thinking about zebras", 900), Part.Text("Here is **the** answer,"), Part.Text(" in two parts."), Part.ToolCall("k1", "shell", "{\"cmd\":\"rm -rf zebra\"}")),
        Role.ASSISTANT to listOf(pdf, Part.Text("Assistants don't attach files, so names count only for the user.")),
        Role.ASSISTANT to listOf(Part.Text("  \n ")),
        Role.ASSISTANT to listOf(Part.Text("marker  chars  removed")),
        Role.TOOL to listOf(Part.ToolResult("k1", "shell", "zebra output")),
        Role.USER to listOf(Part.Text("quotes \" and 'apostrophes' and \\ backslashes, ünïcödé, 東京")),
    )

    @Test
    fun `only what a person would search is kept`() {
        val texts = samples.map { (role, parts) -> SearchText.of(role, parts) }
        assertEquals("report.pdf Summarise this", texts[0])
        assertEquals("ran it", texts[1])
        assertNull(texts[2])
        assertEquals("report.pdf", texts[3])
        assertEquals("Here is **the** answer, in two parts.", texts[4])
        assertEquals("Assistants don't attach files, so names count only for the user.", texts[5])
        assertNull(texts[6])
        assertEquals("marker  chars  removed", texts[7])
        assertNull(texts[8])
    }

    /** The migration derives the same words in SQL from the stored JSON as [SearchText] does for new messages. */
    @Test
    fun `the v8 backfill derives the same text`() {
        val ok = runCatching { ProcessBuilder("sqlite3", ":memory:", "SELECT json_valid('[]')").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
        Assume.assumeTrue("sqlite3 is not installed", ok)
        val rows = samples.mapIndexed { i, (role, parts) ->
            with(ConversationRepository) { Message("m$i", "c", role, parts, i.toLong()).toEntity(parentId = null) }
        }
        val script = buildString {
            appendLine("CREATE TABLE messages (seq INTEGER PRIMARY KEY, id TEXT, role TEXT, partsJson TEXT, searchText TEXT);")
            rows.forEach { appendLine("INSERT INTO messages (id, role, partsJson) VALUES ('${it.id}', '${it.role}', '${it.partsJson.replace("'", "''")}');") }
            appendLine(SearchMigration.BACKFILL_SEARCH_TEXT + ";")
            // One line per row, so newlines inside the text can't split it.
            appendLine("SELECT id || '=' || COALESCE(replace(replace(searchText, char(10), ' '), char(13), ' '), '<null>') FROM messages ORDER BY seq;")
        }
        val p = ProcessBuilder("sqlite3", "-batch", "-bail", ":memory:").start()
        p.outputStream.bufferedWriter().use { it.write(script) }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        assertEquals("", err)
        val fromSql = out.lines().filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=').takeUnless { v -> v == "<null>" } }
        for (row in rows) {
            // Compared as the index sees them: the same words in the same order.
            assertEquals(row.searchText?.let(SearchQuery::words), fromSql[row.id]?.let(SearchQuery::words), "message ${row.id}")
            assertEquals(row.searchText == null, fromSql[row.id] == null, "message ${row.id}")
        }
    }
}
