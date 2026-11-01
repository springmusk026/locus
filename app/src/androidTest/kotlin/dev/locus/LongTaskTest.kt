package dev.locus

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.core.model.Role
import dev.locus.testing.FakeModel
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
        val chat = newChat()
        // ~40 ms of "thinking" per step: the run takes a few seconds, long enough to probe the UI during it.
        FakeModel.script(FakeModel.steps(total, stepDelayMs = 40))
        launchChat(chat)
        compose.send("Write $total files, one per step.")

        // While it runs, Compose keeps reaching idle (no runaway recomposition from streamed state) and
        // the main thread answers within a frame budget's reach.
        var probes = 0
        var worstIdleMs = 0L
        var worstMainMs = 0L
        waitUntil("the run to start") { state(chat).running }
        while (state(chat).running) {
            val idleStart = SystemClock.uptimeMillis()
            compose.waitForIdle()
            worstIdleMs = maxOf(worstIdleMs, SystemClock.uptimeMillis() - idleStart)
            worstMainMs = maxOf(worstMainMs, mainThreadLatencyMs())
            probes++
            Thread.sleep(100)
        }
        assertTrue("Probed the UI only $probes times during the run", probes >= 3)
        assertTrue("Compose took ${worstIdleMs}ms to reach idle mid-run", worstIdleMs < 5_000)
        assertTrue("The main thread took ${worstMainMs}ms to answer mid-run", worstMainMs < 1_000)

        compose.awaitText("All $total steps done.")
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
