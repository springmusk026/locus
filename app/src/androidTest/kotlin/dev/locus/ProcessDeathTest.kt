package dev.locus

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.core.model.Role
import dev.locus.testing.FakeModel
import dev.locus.testing.Gate
import dev.locus.testing.KeepDataAfter
import dev.locus.testing.KeepDataBefore
import dev.locus.testing.LocusTest
import dev.locus.testing.awaitText
import dev.locus.testing.send
import dev.locus.testing.tap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * The process dies mid-task, then Locus starts again: the chat offers Continue
 * (AgentRunner.recoverInterrupted), and continuing finishes the task without repeating a step.
 *
 * Two tests, two processes. The orchestrator runs each test in its own process, in this class's
 * name order, and ends a test's process when the test ends: [a_taskIsCutOffMidStep] leaves the run
 * waiting on the model, so its process dies mid-task, exactly as when Android kills Locus.
 * [b_relaunchOffersContinueAndNothingRepeats] is the next start. Run them together (the whole class).
 */
@HiltAndroidTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProcessDeathTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    @Test
    @KeepDataAfter
    fun a_taskIsCutOffMidStep() {
        val chat = newChat(TITLE)
        val cut = Gate()
        // Never opened: the request after step CUT_AT waits until the process dies.
        FakeModel.script(FakeModel.steps(TOTAL, gates = mapOf(CUT_AT to cut)))
        launchChat(chat)
        compose.send("Write $TOTAL files.")
        cut.awaitReached()
        val history = runBlocking { conversations.history(chat) }
        assertEquals(CUT_AT, history.sumOf { it.toolResults.size })
        assertTrue(state(chat).running)
        // The test ends here, and with it the process, mid-task.
    }

    @Test
    @KeepDataBefore
    fun b_relaunchOffersContinueAndNothingRepeats() {
        val chat = runBlocking { conversations.allOnce() }.firstOrNull { it.title == TITLE }?.id
        assertNotNull("No chat from a_taskIsCutOffMidStep: run this class whole, with the orchestrator", chat)
        chat!!
        val before = runBlocking { conversations.history(chat) }
        assertEquals(CUT_AT, before.sumOf { it.toolResults.size })

        FakeModel.script(FakeModel.steps(TOTAL))
        launchChat(chat)
        compose.awaitText("This task stopped when Locus was closed")
        assertTrue(state(chat).interrupted)
        compose.tap("Continue")
        compose.awaitText("All $TOTAL steps done.")
        awaitRunEnd(chat)

        val history = runBlocking { conversations.history(chat) }
        assertEquals("The user's message is there once", 1, history.count { it.role == Role.USER })
        assertEquals(TOTAL, history.sumOf { it.toolResults.size })
        val calls = history.flatMap { it.toolCalls }.map { it.id }
        assertEquals("No step ran twice", calls.distinct(), calls)
        assertEquals(TOTAL, calls.size)
        // The model was asked to go on from step CUT_AT, never from the start.
        assertEquals(CUT_AT, FakeModel.requests.first().toolResults)
        assertEquals(TOTAL - CUT_AT + 1, FakeModel.requests.size)
        assertEquals(TOTAL, workspace.resolve("steps").listFiles().orEmpty().size)
    }

    private companion object {
        const val TITLE = "Cut off by process death"
        const val TOTAL = 12
        const val CUT_AT = 5
    }
}
