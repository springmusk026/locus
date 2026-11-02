package dev.locus.core.data.agent

import dev.locus.core.linux.AndroidKill
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Linux process that dies from signal 9 while Locus is in the background is reported as killed by
 * Android (phantom process limit), with the fix; in front, or stopped by Locus itself, it isn't.
 * Commands run on the host shell here (as in the benchmark harness), which reports a signal death
 * as 128 + the signal, like Android's java.lang.Process.
 */
class LinuxKillReasonTest {
    private val graph = TestGraph(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    private val linux = graph.linux.apply {
        runOnHost(LinuxEnvironment.CommandHost { command, _ -> ProcessBuilder("/bin/sh", "-c", command) })
    }

    @Test
    fun `a command killed by signal 9 in the background says Android killed it`() = runBlocking {
        linux.inBackground = { true }
        val killed = linux.run("kill -9 $$")
        assertEquals(AndroidKill.SIGKILL_EXIT, killed.exitCode)
        assertTrue(killed.killedByAndroid)

        // On screen it's a plain exit code: whatever killed it, it wasn't the background limit.
        linux.inBackground = { false }
        assertFalse(linux.run("kill -9 $$").killedByAndroid)
        // Another failure in the background isn't put down to Android either.
        linux.inBackground = { true }
        assertFalse(linux.run("exit 1").killedByAndroid)
    }

    @Test
    fun `a service killed in the background is reported so, one Locus stopped is not`() = runBlocking {
        linux.inBackground = { true }
        linux.startService("web", "sleep 0.3; kill -9 $$")
        val status = waitForStop("web")
        assertTrue(status.killedByAndroid, "$status")

        // Stopping it is Locus's doing, even though the last resort is a SIGKILL.
        val api = linux.startService("api", "sleep 30")
        assertTrue(linux.stopService("api"))
        api.process.waitFor()
        Thread.sleep(100) // the exit watcher runs on its own thread
        assertFalse(api.killedByAndroid)
    }

    private fun waitForStop(name: String): LinuxEnvironment.ServiceStatus {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val s = linux.serviceStatuses().first { it.name == name }
            if (!s.running && s.killedByAndroid) return s
            Thread.sleep(50)
        }
        return linux.serviceStatuses().first { it.name == name }
    }
}
