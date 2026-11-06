package dev.locus

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A long task on a real device: 64 tool steps, each a real file write in the workspace, run to the
 * end with no step cap (a chat the user watches has none), while the UI stays responsive.
 */
@HiltAndroidTest
class LongTaskTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    @Test
    fun sixtyFourStepsRunToTheEndAndTheUiKeepsUp() {
        val total = 64
        // The UI is probed at fixed points inside the run: the model's reply after 16, 32 and 48 steps
        // is held until that probe is done. Counting probes over wall time instead tied the test to the
        // emulator's speed: a slow one fit only two probes into the run.
        val checkpoints = listOf(16, 32, 48).associateWith { Gate() }
        val chat = newChat()
        FakeModel.script(FakeModel.steps(total, gates = checkpoints))
        launchChat(chat)
        compose.send("Write $total files, one per step.")

        // Mid-run, with the steps so far on screen, Compose reaches idle (no runaway recomposition from
        // run state) and the main thread answers within a frame budget's reach; and again for the main
        // thread right after each checkpoint opens, while the next steps stream in.
        for ((step, gate) in checkpoints) {
            gate.awaitReached()
            assertTrue("The run should still be going at step $step", state(chat).running)
            val idleStart = SystemClock.uptimeMillis()
            compose.waitForIdle()
            val idleMs = SystemClock.uptimeMillis() - idleStart
            assertTrue("Compose took ${idleMs}ms to reach idle at step $step", idleMs < 5_000)
            val pausedMs = mainThreadLatencyMs()
            assertTrue("The main thread took ${pausedMs}ms to answer at step $step", pausedMs < 1_000)
            gate.open()
            val streamingMs = mainThreadLatencyMs()
            assertTrue("The main thread took ${streamingMs}ms to answer after step $step", streamingMs < 1_000)
        }

        compose.awaitText("All $total steps done.")
        awaitRunEnd(chat)
        val s = state(chat)
        assertNull("Run ended with an error: ${s.error}", s.error)
        // No step cap: no "Stopped after N steps" notice.
        assertNull(s.notice)

        val history = runBlocking { conversations.history(chat) }
        assertEquals(1, history.count { it.role == Role.USER })
        assertEquals(total, history.sumOf { it.toolResults.size })
        assertEquals("Each step ran once", total, history.flatMap { it.toolCalls }.map { it.id }.distinct().size)
        assertEquals(total + 1, FakeModel.requests.count { !it.isSideQuestion })
        val files = File(workspace.resolve("steps").path).listFiles().orEmpty()
        assertEquals(total, files.size)
        assertEquals("step $total\n", workspace.resolve("steps/step-$total.txt").readText())
    }

    /** How long a message posted to the main thread waits to run. */
    private fun mainThreadLatencyMs(): Long {
        val posted = SystemClock.uptimeMillis()
        val ran = CountDownLatch(1)
        var at = 0L
        Handler(Looper.getMainLooper()).post { at = SystemClock.uptimeMillis(); ran.countDown() }
        check(ran.await(10, TimeUnit.SECONDS)) { "The main thread didn't answer within 10 s" }
        return at - posted
    }
}
