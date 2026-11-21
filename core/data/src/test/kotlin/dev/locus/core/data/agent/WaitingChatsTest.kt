package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/** The "questions waiting" banner's source (docs/design/approvals-waiting.md). */
class WaitingChatsTest {
    private fun approval(chat: String) = ApprovalRequest(chat, "call-$chat", "run_command", ToolRisk.EXECUTE, "Run ls", "{}")
    private fun question(chat: String) = UserQuestion(chat, "q-$chat", "Which branch?", emptyList(), false, true)
    private val pause = BudgetPause(BudgetLimit.TOKENS, "Paused")

    @Test
    fun `a chat waits on an approval first, then a question, then a budget pause`() {
        val kinds = waitingKinds(
            mapOf(
                "a" to RunState(running = true, approvals = listOf(approval("a")), questions = listOf(question("a")), budgetPause = pause),
                "b" to RunState(questions = listOf(question("b")), budgetPause = pause),
                "c" to RunState(budgetPause = pause),
                "idle" to RunState(),
                "working" to RunState(running = true),
            ),
        )
        assertEquals(mapOf("a" to WaitingKind.APPROVAL, "b" to WaitingKind.QUESTION, "c" to WaitingKind.BUDGET), kinds)
    }

    @Test
    fun `scheduled runs never count as waiting`() {
        assertEquals(emptyMap(), waitingKinds(mapOf("s" to RunState(scheduled = true, budgetPause = pause, approvals = listOf(approval("s"))))))
    }

    @Test
    fun `a chat keeps the time it started waiting, new ones get now, and finished ones leave`() {
        val first = trackWaiting(emptyList(), mapOf("b" to WaitingKind.QUESTION), at = 100)
        assertEquals(listOf(WaitingChat("b", WaitingKind.QUESTION, 100)), first)
        // b now waits on an approval instead: still since 100. a starts waiting later.
        val second = trackWaiting(first, mapOf("a" to WaitingKind.APPROVAL, "b" to WaitingKind.APPROVAL), at = 200)
        assertEquals(listOf(WaitingChat("b", WaitingKind.APPROVAL, 100), WaitingChat("a", WaitingKind.APPROVAL, 200)), second)
        // b is answered: only a is left, still since 200.
        assertEquals(listOf(WaitingChat("a", WaitingKind.APPROVAL, 200)), trackWaiting(second, mapOf("a" to WaitingKind.APPROVAL), at = 300))
        // Ties are ordered by chat id, so the order never flickers.
        assertEquals(listOf("x", "y"), trackWaiting(emptyList(), mapOf("y" to WaitingKind.BUDGET, "x" to WaitingKind.BUDGET), at = 5).map { it.chatId })
    }

    @Test
    fun `the flow lists waiting chats oldest first as the runner's states change`() = runTest {
        val states = MutableStateFlow<Map<String, RunState>>(emptyMap())
        var now = 1_000L
        val waiting = WaitingChats.forTest(states, backgroundScope) { now }
        runCurrent()
        assertEquals(emptyList(), waiting.chats.value)

        states.value = mapOf("late" to RunState(), "early" to RunState(questions = listOf(question("early"))))
        runCurrent()
        now = 2_000L
        states.value = states.value + ("late" to RunState(approvals = listOf(approval("late"))))
        runCurrent()
        assertEquals(listOf("early" to 1_000L, "late" to 2_000L), waiting.chats.value.map { it.chatId to it.since })

        // Streamed text and other changes that don't change who waits keep the list as it is.
        now = 3_000L
        states.value = states.value + ("other" to RunState(running = true, streamingText = "Hel"))
        runCurrent()
        assertEquals(listOf("early", "late"), waiting.chats.value.map { it.chatId })

        states.value = states.value - "early"
        runCurrent()
        assertEquals(listOf(WaitingChat("late", WaitingKind.APPROVAL, 2_000L)), waiting.chats.value)
    }
}
