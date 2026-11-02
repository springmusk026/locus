package dev.locus.feature.chat

import dev.locus.core.model.ProposedPlan
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TranscriptRowsTest {
    private fun note(key: String, text: String = key) = ChatItem.Note(key, text)
    private fun call(key: String) = ChatItem.ToolCall(key, "read_file", "{}", null)

    @Test
    fun `steps share a card and rows say what they show`() {
        val rows = groupSteps(listOf(ChatItem.User("u", "hi"), note("n"), call("c"), ChatItem.Assistant("a", "done")))
        assertEquals(listOf("u", "steps:n", "a"), rows.map { it.key })
        assertEquals(listOf<Any>(ChatItem.User::class, TranscriptRow.Steps::class, ChatItem.Assistant::class), rows.map { it.contentType })
        assertTrue(rows[1].containsKey("c"))
    }

    @Test
    fun `equal elements come back as the previous instances, and an unchanged list as the same list`() {
        val keeper = InstanceKeeper<ChatItem> { it.key }
        val first = keeper.keep(listOf(ChatItem.User("u", "hi"), ChatItem.Assistant("a", "hello")))
        // Derived again: equal but new objects.
        val again = keeper.keep(listOf(ChatItem.User("u", "hi"), ChatItem.Assistant("a", "hello")))
        assertSame(first, again)

        // One changed and one added: the unchanged one is kept, the others are the new ones.
        val changed = ChatItem.Assistant("a", "hello, edited")
        val next = keeper.keep(listOf(ChatItem.User("u", "hi"), changed, note("n")))
        assertNotSame(first, next)
        assertSame(first[0], next[0])
        assertSame(changed, next[1])
    }

    @Test
    fun `a plan stops waiting while a run goes, without copying anything else`() {
        val plan = ChatItem.Plan("p", ProposedPlan("t", listOf("s")), PlanStatus.Waiting)
        val rows = groupSteps(listOf(ChatItem.User("u", "hi"), plan))
        assertSame(rows, rows.markWaitingPlanRows(waiting = true))
        val running = rows.markWaitingPlanRows(waiting = false)
        assertSame(rows[0], running[0])
        assertEquals(PlanStatus.Answered, ((running[1] as TranscriptRow.Single).item as ChatItem.Plan).status)
        val noPlans = groupSteps(listOf(ChatItem.User("u", "hi")))
        assertSame(noPlans, noPlans.markWaitingPlanRows(waiting = false))
    }
}
