package dev.locus.core.data.chatimport

import dev.locus.core.agent.Taint
import dev.locus.core.agent.UntrustedContent
import dev.locus.core.data.history.chatHistoryTools
import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.ImportedChats
import dev.locus.core.model.Role
import dev.locus.core.model.importedFrom
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two passes end to end against the app's repository: the preview's counts and date range, the
 * written archive (tree, leaf, original dates, label, search), importing again, cancelling, and taint
 * when another chat reads an imported one.
 */
class ChatImporterTest {
    private val gpt = ExportFixtures.file("conversations.json", ExportFixtures.bytes("chatgpt-conversations.json"))
    private val claude = ExportFixtures.file("claude.json", ExportFixtures.bytes("claude-conversations.json"))
    private val kyotoId = ImportedChats.chatId(ChatImportSource.CHATGPT, "6789abcd-0000-4000-8000-000000000001")

    @Test
    fun `scan counts what's there and writes nothing, the plan follows the date range`() = runTest {
        val g = TestGraph(backgroundScope)
        val importer = ChatImporter(g.conversations)
        val scan = importer.scan(listOf(gpt, claude))
        assertEquals(4, scan.chats.size)
        assertEquals(3, scan.unreadable)
        assertEquals(2, scan.empty)
        assertEquals(listOf(ChatImportSource.CHATGPT, ChatImportSource.CLAUDE).toSet(), scan.sources.toSet())
        assertEquals(1_682_935_200_000L, scan.oldest) // 2023-05-01
        assertEquals(1_741_075_200_123L, scan.newest) // 2025-03-04
        assertTrue(g.conversations.allOnce().isEmpty())

        val all = scan.plan()
        assertEquals(4, all.new)
        assertEquals(4, all.ids.size)
        // itinerary.pdf + an image in ChatGPT, kitchen.jpg in Claude.
        assertEquals(3, all.notImported)
        // 2024 only: the Kyoto chat (1 Jan 2024) and Claude's flat chat (10 Jan 2024).
        val from = java.time.Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
        val until = java.time.Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()
        val year = scan.plan(from, until)
        assertEquals(2, year.new)
        assertTrue(kyotoId in year.ids)
    }

    @Test
    fun `imported chats are read-only archives with their tree, leaf, dates and search`() = runTest {
        val g = TestGraph(backgroundScope)
        val importer = ChatImporter(g.conversations)
        val scan = importer.scan(listOf(gpt, claude))
        val result = importer.import(listOf(gpt, claude), scan.plan().ids, ImportTexts(notImported = { names, images -> "NOT: ${names.joinToString()} +$images" }))
        assertEquals(ImportResult(imported = 4, updated = 0, failed = 0), result)

        val chat = assertNotNull(g.conversations.get(kyotoId))
        assertEquals(ChatImportSource.CHATGPT, chat.importedFrom)
        assertEquals("Trip to Kyoto 🍵", chat.title)
        assertNull(chat.providerId)
        assertEquals("", chat.systemPrompt)
        assertEquals(1_704_067_200_123L, chat.createdAt)
        // The shown branch is current_node's; the other is one switch away.
        val thread = g.conversations.thread(kyotoId)
        val shown = thread.messages.map { it.text }
        assertEquals(4, shown.size)
        assertTrue(shown[0].startsWith("Plan 3 days in Kyoto"), shown[0])
        assertTrue(shown[1].startsWith("Day 1: Uji"), shown[1])
        assertEquals(listOf("What about day 3?", "Day 3: Arashiyama."), shown.drop(2))
        assertTrue(thread.messages.first().text.endsWith("NOT: itinerary.pdf +1"), thread.messages.first().text)
        assertEquals(2, thread.branches.values.single().count)
        assertTrue(g.conversations.switchBranch(kyotoId, ImportedChats.messageId(kyotoId, "u2b")))
        assertEquals("Sure: add Nara on day 4.", g.conversations.history(kyotoId).last().text)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), g.conversations.history(kyotoId).takeLast(2).map { it.role })

        // Named by its first question when the export has no title.
        assertTrue(g.conversations.allOnce().any { it.title == "Héllo without a title" })
        // Searchable like any chat.
        assertEquals(kyotoId, g.conversations.search("Arashiyama").single().conversation.id)
        // Imported messages are kept out of Insights' counts, like a fork's copies.
        assertTrue(g.database.messageRows.filter { it.conversationId == kyotoId }.all { it.copiedFrom?.startsWith("chatgpt:") == true })
    }

    @Test
    fun `importing the same export again adds nothing, a newer copy replaces the old one`() = runTest {
        val g = TestGraph(backgroundScope)
        val importer = ChatImporter(g.conversations)
        importer.import(listOf(gpt, claude), importer.scan(listOf(gpt, claude)).plan().ids)
        val rows = g.database.messageRows.size
        g.conversations.setPinned(kyotoId, true)

        val again = importer.scan(listOf(gpt, claude))
        val plan = again.plan()
        assertEquals(0, plan.new)
        assertEquals(4, plan.unchanged)
        assertTrue(plan.ids.isEmpty())
        // Even when asked to, an unchanged chat isn't written twice.
        assertEquals(ImportResult(0, 0, 0), importer.import(listOf(gpt, claude), again.chats.map { it.id }.toSet()))
        assertEquals(rows, g.database.messageRows.size)
        assertEquals(4, g.conversations.allOnce().size)

        // The conversation went on in ChatGPT and was exported again: replaced, pin kept.
        val newer = ExportFixtures.file(
            "conversations.json",
            "[${ExportFixtures.chatGpt("6789abcd-0000-4000-8000-000000000001", 1_704_067_200, turns = 3, title = "Trip to Kyoto 🍵", updatedSeconds = 1_800_000_000)}]".toByteArray(),
        )
        val update = importer.scan(listOf(newer)).plan()
        assertEquals(1, update.updated)
        assertEquals(ImportResult(0, 1, 0), importer.import(listOf(newer), update.ids))
        assertEquals(6, g.conversations.history(kyotoId).size)
        assertTrue(g.conversations.get(kyotoId)!!.pinned)
        assertEquals(4, g.conversations.allOnce().size)
    }

    @Test
    fun `cancelling mid-import leaves whole chats only`() = runTest {
        val g = TestGraph(backgroundScope)
        val importer = ChatImporter(g.conversations)
        val many = ExportFixtures.file("conversations.json", ("[" + (0 until 40).joinToString(",") { ExportFixtures.chatGpt("c$it", 1_700_000_000L + it, turns = 3) } + "]").toByteArray())
        val ids = importer.scan(listOf(many)).plan().ids
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            var task: kotlinx.coroutines.Job? = null
            task = coroutineContext[kotlinx.coroutines.Job]
            importer.import(listOf(many), ids) { p -> if (p.chats >= 5) task?.cancel() }
        }
        assertFailsWith<CancellationException> { job.await() }
        val written = g.conversations.allOnce()
        assertTrue(written.size in 5 until 40, "${written.size}")
        // Every chat that's there has all six messages.
        written.forEach { assertEquals(6, g.database.messageRows.count { m -> m.conversationId == it.id }, it.id) }
    }

    @Test
    fun `reading or listing an imported chat from another chat taints the run, and nothing runs in it`() = runTest {
        val g = TestGraph(backgroundScope)
        val importer = ChatImporter(g.conversations)
        importer.import(listOf(gpt), importer.scan(listOf(gpt)).plan().ids)
        val own = g.conversations.create(null).id
        g.conversations.appendUserMessage(own, "my own chat about Arashiyama")
        val (search, read) = chatHistoryTools(g.conversations)

        val readKyoto = read.execute(buildJsonObject { put("id", kyotoId) }).content
        assertTrue(UntrustedContent.OPEN in readKyoto && "Arashiyama" in readKyoto.substringAfter(UntrustedContent.OPEN), readKyoto)
        assertTrue(Taint.isSource("chat_history_read", readKyoto))
        // A Locus chat reads as before: not outside content.
        assertFalse(Taint.isSource("chat_history_read", read.execute(buildJsonObject { put("id", own) }).content))

        // Search: the user's own chat stays unmarked, the imported one is inside the markers.
        val found = search.execute(buildJsonObject { put("query", "Arashiyama") }).content
        assertTrue(Taint.isSource("chat_history_search", found))
        assertTrue(own in found.substringBefore(UntrustedContent.OPEN), found)
        assertTrue(kyotoId in found.substringAfter(UntrustedContent.OPEN), found)
        val ownOnly = search.execute(buildJsonObject { put("query", "own chat") }).content
        assertFalse(Taint.isSource("chat_history_search", ownOnly), ownOnly)

        // A new chat's frozen prompt doesn't list imported titles among recent chats.
        assertFalse("Trip to Kyoto" in g.conversations.create(null).systemPrompt)

        // Nothing is ever sent from an archive.
        g.runner.send(kyotoId, "continue please")
        assertEquals(null, g.runner.states.value[kyotoId]?.running?.takeIf { it })
        assertTrue(g.requests.isEmpty())
        assertEquals(4, g.conversations.history(kyotoId).size)
    }
}
