package dev.locus.core.data.linux

import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A command that leaves a program in the background holding its output (`npm run dev &`) still
 * returns: within its timeout plus a short grace when it runs too long, and soon after it ends
 * otherwise (#312). Commands run on the host shell, as in [ServiceRestartTest].
 */
class CommandBackgroundOutputTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val linux = TestGraph(scope).linux.apply {
        runOnHost(LinuxEnvironment.CommandHost { command, _ -> ProcessBuilder("/bin/sh", "-c", command) })
        inBackground = { false }
    }

    @After
    fun cleanup() {
        // The background sleeps hold nothing of the test's; end them anyway.
        runCatching { ProcessBuilder("pkill", "-f", "sleep 41").start().waitFor() }
        scope.cancel()
    }

    @Test
    fun `a timed-out command with a background child holding its output returns`() = runBlocking {
        val started = System.nanoTime()
        val result = linux.run("echo started; sleep 41 & sleep 41", timeoutMs = 500)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.timedOut)
        assertTrue("started" in result.output, result.output)
        assertTrue(tookMs < 500 + LinuxEnvironment.OUTPUT_GRACE_MS + 3_000, "took $tookMs ms")
    }

    @Test
    fun `a command that ends with a background child holding its output returns`() = runBlocking {
        val started = System.nanoTime()
        val result = linux.run("sleep 41 & echo done", timeoutMs = 60_000)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, result.exitCode)
        assertTrue("done" in result.output, result.output)
        assertTrue(tookMs < LinuxEnvironment.OUTPUT_GRACE_MS + 3_000, "took $tookMs ms")
    }
}
