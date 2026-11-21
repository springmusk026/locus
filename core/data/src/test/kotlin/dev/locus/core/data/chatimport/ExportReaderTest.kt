package dev.locus.core.data.chatimport

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finding the conversations in what the user picked (a zip, shards, a zip inside a zip, a bare JSON
 * file), refusing what isn't an export, keeping what came before damage, and reading as a stream.
 */
class ExportReaderTest {
    private val gpt = ExportFixtures.bytes("chatgpt-conversations.json")
    private val claude = ExportFixtures.bytes("claude-conversations.json")

    private fun chats(entries: List<ParsedEntry>) = entries.filterIsInstance<ParsedEntry.Chat>().map { it.chat }

    @Test
    fun `a chatgpt zip, its shards, and claude's per-category zip are all found`() = runTest {
        val single = ExportFixtures.zip("chat.html" to "<html/>".toByteArray(), "file-abc.dat" to ByteArray(5000), "conversations.json" to gpt, "user.json" to "{}".toByteArray())
        assertEquals(2, chats(ExportFixtures.readAll(ExportFixtures.file("export.zip", single)).first).size)

        // 2026 shards, with a manifest that isn't conversations.
        val shard0 = "[${ExportFixtures.chatGpt("s0", 1_700_000_000)}]".toByteArray()
        val shard1 = "[${ExportFixtures.chatGpt("s1", 1_700_100_000)},${ExportFixtures.chatGpt("s2", 1_700_200_000)}]".toByteArray()
        val sharded = ExportFixtures.zip("export_manifest.json" to "{}".toByteArray(), "conversations-000.json" to shard0, "conversations-001.json" to shard1)
        val (entries, summary) = ExportFixtures.readAll(ExportFixtures.file("sharded.zip", sharded))
        assertEquals(listOf("s0", "s1", "s2"), chats(entries).map { it.sourceId })
        assertEquals(2, summary.files)

        // A zip in a zip (privacy portal; Claude's conversations-000.zip inside a download folder), and Claude's projects skipped.
        val inner = ExportFixtures.zip("conversations.json" to claude, "users.json" to "[]".toByteArray())
        val outer = ExportFixtures.zip("projects.json" to "[]".toByteArray(), "Conversations__abc.zip" to inner)
        assertEquals(2, chats(ExportFixtures.readAll(ExportFixtures.file("portal.zip", outer)).first).size)

        // Both at once, and a bare JSON file.
        val (both, _) = ExportFixtures.readAll(ExportFixtures.file("conversations.json", gpt), ExportFixtures.file("claude.zip", inner))
        assertEquals(4, chats(both).size)
    }

    @Test
    fun `files that aren't exports say why`() = runTest {
        suspend fun problem(bytes: ByteArray) = assertFailsWith<ExportException> { ExportFixtures.readAll(ExportFixtures.file("x", bytes)) }.problem
        assertEquals(ExportProblem.NotAnExport, problem("hello, world".toByteArray()))
        assertEquals(ExportProblem.NotAnExport, problem("""{"version":1}""".toByteArray()))
        assertEquals(ExportProblem.NotAnExport, problem(ByteArray(0)))
        assertEquals(ExportProblem.NoConversations, problem(ExportFixtures.zip("projects.json" to "[]".toByteArray(), "memories.json" to "{}".toByteArray())))
        assertEquals(ExportProblem.Unreadable, problem("[{\"mapping\": ".toByteArray()))
        // An empty export: read, nothing in it.
        val (entries, _) = ExportFixtures.readAll(ExportFixtures.file("conversations.json", "[]".toByteArray()))
        assertTrue(entries.isEmpty())
        // A file that can't be opened.
        val broken = ExportFile("gone.zip", null) { throw java.io.FileNotFoundException("gone") }
        assertEquals(ExportProblem.Unreadable, assertFailsWith<ExportException> { ExportReader().read(listOf(broken)) {} }.problem)
    }

    @Test
    fun `a file cut off part-way keeps the conversations before the cut`() = runTest {
        val full = "[${ExportFixtures.chatGpt("a", 1_700_000_000)},${ExportFixtures.chatGpt("b", 1_700_000_100)},${ExportFixtures.chatGpt("c", 1_700_000_200)}]".toByteArray()
        val cut = full.copyOf(full.size - 40)
        val (entries, summary) = ExportFixtures.readAll(ExportFixtures.file("conversations.json", cut))
        assertEquals(listOf("a", "b"), chats(entries).map { it.sourceId })
        assertTrue(summary.truncated)
        val (whole, wholeSummary) = ExportFixtures.readAll(ExportFixtures.file("conversations.json", full))
        assertEquals(3, chats(whole).size)
        assertFalse(wholeSummary.truncated)
    }

    @Test
    fun `a large export is read as a stream, never whole`() = runTest {
        // About 60 MB of conversations, produced as they're read: holding it would need far more than the
        // test's working set, and the first conversation arrives after a few kilobytes.
        val count = 3_000
        val source = ExportFixtures.GeneratedExport(count, textPerChat = 10_000)
        var firstAt = -1L
        var seen = 0
        val summary = ExportReader().read(listOf(ExportFile("conversations.json", null) { source })) { entry ->
            if (firstAt < 0) firstAt = source.produced
            if (entry is ParsedEntry.Chat) seen++
        }
        assertEquals(count, seen)
        assertEquals(1, summary.files)
        assertTrue(source.produced > 50_000_000, "${source.produced}")
        assertTrue(firstAt in 1..1_000_000, "first conversation after $firstAt bytes")
    }
}
