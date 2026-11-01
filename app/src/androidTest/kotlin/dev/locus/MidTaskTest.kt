package dev.locus

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.core.model.Role
import dev.locus.testing.FakeModel
import dev.locus.testing.Gate
import dev.locus.testing.LocusTest
import dev.locus.testing.SendButton
import dev.locus.testing.awaitText
import dev.locus.testing.send
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Talking to Locus while it works: a mid-task note (#55) and a side question, `/btw` (#52). */
@HiltAndroidTest
class MidTaskTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    /** A note sent mid-task waits for the step boundary, joins the same task once, and the model reads it. */
    @Test
    fun midTaskNoteJoinsTheRunningTask() {
        val total = 8
        val gate = Gate()
        val chat = newChat()
        FakeModel.script(FakeModel.steps(total, gates = mapOf(3 to gate)))
        launchChat(chat)
        compose.send("Write $total files.")
        gate.awaitReached()

        compose.send("Also keep the drafts.", SendButton.TELL)
        waitUntil("the note to be queued") { state(chat).queued.any { it.text == "Also keep the drafts." } }
        assertTrue(state(chat).running)
        gate.open()

        compose.awaitText("All $total steps done.")
        awaitRunEnd(chat)
        val history = runBlocking { conversations.history(chat) }
        val users = history.filter { it.role == Role.USER }
        assertEquals(2, users.size)
        assertEquals("Delivered once", 1, users.count { it.userText == "Also keep the drafts." })
        // Joined inside the task (after a step's results), not as a new turn after the answer.
        val note = history.indexOfFirst { it.userText == "Also keep the drafts." }
        assertEquals(Role.TOOL, history[note - 1].role)
        assertTrue(history.drop(note + 1).any { it.toolResults.isNotEmpty() })
        assertEquals(total, history.sumOf { it.toolResults.size })
        // The model saw it in a later request; the queue is empty.
        assertTrue(FakeModel.requests.last().toString().contains("Also keep the drafts."))
        assertTrue(state(chat).queued.isEmpty())
        compose.awaitText("Told while working")
    }

    /** `/btw` mid-task: answered apart, never added to the chat, and the task carries on untouched. */
    @Test
    fun btwIsAnsweredApartWhileTheTaskRuns() {
        val total = 6
        val gate = Gate()
        val chat = newChat()
        FakeModel.script(FakeModel.steps(total, gates = mapOf(2 to gate), sideAnswer = "You're on step three."))
        launchChat(chat)
        compose.send("Write $total files.")
        gate.awaitReached()

        compose.send("/btw ${FakeModel.SIDE_MARK} which step are you on?", SendButton.SIDE)
        compose.awaitText("You're on step three.")
        assertTrue("The task keeps running", state(chat).running)
        gate.open()

        compose.awaitText("All $total steps done.")
        awaitRunEnd(chat)
        val history = runBlocking { conversations.history(chat) }
        assertTrue("The side question isn't in the chat", history.none { FakeModel.SIDE_MARK in it.toString() })
        assertEquals(1, history.count { it.role == Role.USER })
        assertEquals(total, history.sumOf { it.toolResults.size })
        assertEquals(1, FakeModel.requests.count { it.isSideQuestion })
        // The task's own requests never carried the question.
        assertTrue(FakeModel.requests.filterNot { it.isSideQuestion }.none { FakeModel.SIDE_MARK in it.toString() })
    }
}
