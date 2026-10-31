package dev.locus.core.data.search

import dev.locus.core.database.MessageHitRow
import dev.locus.core.model.Conversation
import dev.locus.core.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The search layer over in-memory data: what the index returns, merged with title matches. */
class ChatSearchTest {
    private fun chat(id: String, title: String, updatedAt: Long) = Conversation(id, title, null, "", 0, updatedAt)

    private class Fake(
        val chats: List<Conversation>,
        val rows: List<MessageHitRow>,
        val shown: Map<String, Set<String>> = emptyMap(),
    ) : SearchSource {
        val queries = mutableListOf<String>()
        override suspend fun chats() = chats
        override suspend fun messages(match: String, tokens: Int, limit: Int): List<MessageHitRow> {
            queries += match
            return rows.take(limit)
        }
        override suspend fun shownBranch(conversationId: String) = shown[conversationId].orEmpty()
    }

    private val kotlinChat = chat("k", "Kotlin coroutines", updatedAt = 50)
    private val deploy = chat("d", "Deploy the site", updatedAt = 300)
    private val other = chat("o", "Groceries", updatedAt = 900)

    @Test
    fun `message matches and title matches merge, one per chat, newest first`() = runTest {
        val source = Fake(
            listOf(kotlinChat, deploy, other),
            listOf(
                MessageHitRow("m2", "d", "ASSISTANT", 200, "built with Kotlin 2.4"),
                MessageHitRow("m1", "k", "USER", 40, "why are kotlin flows cold"),
                // A helper chat's or deleted chat's row never reaches the user.
                MessageHitRow("m9", "gone", "USER", 999, "kotlin"),
            ),
            shown = mapOf("d" to setOf("m2")),
        )
        val hits = ChatSearch(source).search("kotlin")
        assertEquals(listOf("\"kotlin\"*"), source.queries)
        assertEquals(listOf("d", "k"), hits.map { it.conversation.id })
        val first = hits.first().message!!
        assertEquals(Role.ASSISTANT, first.role)
        assertEquals("built with Kotlin 2.4", first.snippet.text)
        assertTrue(first.onShownBranch)
        // m1 is on a branch the chat isn't showing; the title matched too, but the message wins.
        assertEquals(false, hits[1].message!!.onShownBranch)
    }

    @Test
    fun `a title match without matching messages is listed by the chat's last activity`() = runTest {
        val hits = ChatSearch(Fake(listOf(kotlinChat, deploy, other), listOf(MessageHitRow("m", "k", "USER", 10, "site")))).search("site")
        assertEquals(listOf("d", "k"), hits.map { it.conversation.id })
        assertNull(hits.first().message)
        assertEquals(300, hits.first().at)
    }

    @Test
    fun `one letter looks at titles without asking the index`() = runTest {
        val source = Fake(listOf(kotlinChat, deploy, other), emptyList())
        assertEquals(listOf("o"), ChatSearch(source).search("g").map { it.conversation.id })
        assertTrue(source.queries.isEmpty())
        assertTrue(ChatSearch(source).search("  \"* ").isEmpty())
    }

    @Test
    fun `the limit applies to the merged list`() = runTest {
        val chats = (1..10).map { chat("c$it", "Kotlin $it", updatedAt = it.toLong()) }
        assertEquals(listOf("c10", "c9", "c8"), ChatSearch(Fake(chats, emptyList())).search("kotlin", limit = 3).map { it.conversation.id })
    }
}
