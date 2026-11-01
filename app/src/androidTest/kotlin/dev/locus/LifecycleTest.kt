package dev.locus

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.core.model.Role
import dev.locus.testing.FakeModel
import dev.locus.testing.Gate
import dev.locus.testing.LocusTest
import dev.locus.testing.awaitText
import dev.locus.testing.send
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A task keeps going through the app going to the background, rotation and recreation. */
@HiltAndroidTest
class LifecycleTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    /**
     * Home mid-task: the activity stops, AgentService keeps the run going as a foreground service with
     * its status notification, and the run finishes while Locus is still in the background.
     */
    @Test
    fun taskContinuesInTheForegroundServiceAfterHome() {
        val total = 20
        val halfway = Gate()
        val chat = newChat()
        FakeModel.script(FakeModel.steps(total, gates = mapOf(10 to halfway)))
        launchChat(chat)
        compose.send("Write $total files.")
        halfway.awaitReached()
        assertTrue("AgentService should run while the task does", agentServiceInForeground())

        device.pressHome()
        waitUntil("Locus to go to the background") { !appVisible() }
        assertTrue("AgentService should stay in the foreground in the background", agentServiceInForeground())
        assertTrue("The status notification should show", activeNotifications().any { it.id == STATUS_NOTIFICATION })

        halfway.open()
        awaitRunEnd(chat)
        assertFalse("Locus should still be in the background", appVisible())
        assertNull(state(chat).error)
        assertEquals(total, runBlocking { conversations.history(chat) }.sumOf { it.toolResults.size })
        // The service stops itself once nothing runs.
        waitUntil("AgentService to stop") { !agentServiceInForeground() }

        reopen()
        compose.awaitText("All $total steps done.")
    }

    /** Rotation, then a recreation, mid-task: the run carries on, the chat shows it, nothing runs twice. */
    @Test
    fun rotationAndRecreationMidTask() {
        val total = 16
        val first = Gate()
        val second = Gate()
        val chat = newChat()
        FakeModel.script(FakeModel.steps(total, gates = mapOf(5 to first, 10 to second)))
        val scenario = launchChat(chat)
        compose.send("Write $total files.")

        first.awaitReached()
        try {
            device.setOrientationLeft()
            compose.waitForIdle()
            assertTrue(state(chat).running)
            first.open()
            second.awaitReached()
            device.setOrientationNatural()
            compose.waitForIdle()
        } finally {
            device.unfreezeRotation()
        }
        // A configuration change of another kind (or Android recreating the activity): same result.
        scenario.recreate()
        assertTrue(state(chat).running)
        second.open()

        compose.awaitText("All $total steps done.")
        awaitRunEnd(chat)
        val history = runBlocking { conversations.history(chat) }
        assertEquals(1, history.count { it.role == Role.USER })
        assertEquals(total, history.sumOf { it.toolResults.size })
        assertEquals(total + 1, FakeModel.requests.size)
    }

    private companion object {
        /** AgentService's foreground notification id. */
        const val STATUS_NOTIFICATION = 1
    }
}
