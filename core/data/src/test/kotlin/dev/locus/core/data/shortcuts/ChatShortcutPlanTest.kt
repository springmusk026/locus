package dev.locus.core.data.shortcuts

import dev.locus.core.model.Conversation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which chats become launcher and Direct Share shortcuts (docs/design/android-bridge.md). */
class ChatShortcutPlanTest {
    private fun chat(id: String, updated: Long, pinned: Boolean = false, title: String = "Chat $id", parent: String? = null, hidden: Boolean = false) =
        Conversation(id, title, null, "s", 0, updated, pinned = pinned, parentConversationId = parent, hidden = hidden)

    @Test
    fun `pinned chats come first, then the most recent`() {
        val plan = ChatShortcutPlan.plan(
            listOf(chat("a", 10), chat("b", 50), chat("p1", 1, pinned = true), chat("c", 30), chat("p2", 5, pinned = true), chat("d", 40)),
            max = 4,
        )
        assertEquals(listOf("p2", "p1", "b", "d"), plan.map { it.conversationId })
        assertEquals(listOf(0, 1, 2, 3), plan.map { it.rank })
        assertEquals(listOf(true, true, false, false), plan.map { it.pinned })
        assertEquals("chat:p2", plan.first().id)
    }

    @Test
    fun `helpers, hidden chats and scheduled runs never become shortcuts`() {
        val plan = ChatShortcutPlan.plan(
            listOf(chat("h", 99, parent = "a"), chat("x", 98, hidden = true), chat("s", 97, title = "Scheduled: digest · 1/1"), chat("a", 1)),
        )
        assertEquals(listOf("a"), plan.map { it.conversationId })
    }

    @Test
    fun `labels are bounded and never empty, and no room means no shortcuts`() {
        val plan = ChatShortcutPlan.plan(listOf(chat("a", 1, title = ""), chat("b", 2, title = "x".repeat(100))))
        assertEquals("x".repeat(40), plan[0].title)
        assertEquals("Chat", plan[1].title)
        assertTrue(ChatShortcutPlan.plan(listOf(chat("a", 1)), max = 0).isEmpty())
    }

    @Test
    fun `deleted or dropped chats' shortcuts are removed, static ones are left alone`() {
        val now = ChatShortcutPlan.plan(listOf(chat("a", 2), chat("b", 1)))
        assertEquals(setOf("chat:gone"), ChatShortcutPlan.removed(setOf("chat:a", "chat:gone", "new_chat"), now))
    }

    @Test
    fun `a Direct Share shortcut id names its chat`() {
        assertEquals("abc", ChatShortcutPlan.chatOf(ChatShortcutPlan.idOf("abc")))
        assertNull(ChatShortcutPlan.chatOf("new_chat"))
        assertNull(ChatShortcutPlan.chatOf("chat:"))
        assertNull(ChatShortcutPlan.chatOf(null))
    }
}
