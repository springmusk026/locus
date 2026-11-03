package dev.locus.core.data.linux

import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxEnvironment.ServiceState
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a service's end reads before and after Locus restarts (docs/design/linux-health.md, "Services"):
 * a restart that ended it is neutral, a crash stays a crash, and "Start when Locus opens" brings it
 * back. Services run on the host shell here, as in [dev.locus.core.data.agent.LinuxKillReasonTest];
 * a second [LinuxEnvironment] over the same files is the app after a restart.
 */
class ServiceRestartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph = TestGraph(scope)
    private val started = mutableListOf<LinuxEnvironment>()

    private fun linux(): LinuxEnvironment = LinuxEnvironment(graph.context).apply {
        runOnHost(LinuxEnvironment.CommandHost { command, _ -> ProcessBuilder("/bin/sh", "-c", command) })
        inBackground = { false }
        started += this
    }

    @After
    fun cleanup() = runBlocking {
        started.forEach { env -> env.serviceStatuses().forEach { env.stopService(it.name) } }
        scope.cancel()
    }

    private fun LinuxEnvironment.status(name: String) = serviceStatuses().first { it.name == name }

    /** Waits for [name] to stop and for its exit to be written to its record. */
    private suspend fun LinuxEnvironment.ended(name: String): LinuxEnvironment.ServiceStatus = withTimeout(10_000) {
        while (status(name).running || LinuxEnvironment(graph.context).serviceStatuses().first { it.name == name }.exitCode == null) {
            kotlinx.coroutines.delay(50)
        }
        status(name)
    }

    @Test
    fun `a service a restart ended reads as stopped by restart, with no health check`() = runBlocking {
        val before = linux()
        before.startService("web", "sleep 30")
        assertEquals(ServiceState.Running, before.status("web").state)

        // The app restarts: the old process's services are gone, only the record is left.
        val after = linux()
        val web = after.status("web")
        assertEquals(ServiceState.StoppedByRestart, web.state)
        assertTrue(web.lostOnRestart)
        assertNull(LinuxHealth.serviceCheck(web), "a restart ending a service isn't a problem")
    }

    @Test
    fun `a crash is a problem, and still reads as a crash after a restart`() = runBlocking {
        val before = linux()
        before.startService("api", "exit 3")
        val crashed = before.ended("api")
        assertEquals(ServiceState.Crashed, crashed.state)
        assertEquals(3, crashed.exitCode)
        assertEquals(dev.locus.core.linux.HealthLevel.Problem, LinuxHealth.serviceCheck(crashed)?.level)

        val after = linux().status("api")
        assertEquals(ServiceState.Crashed, after.state)
        assertFalse(after.lostOnRestart)
        assertEquals(3, after.exitCode)

        // A clean exit is neither.
        before.startService("once", "true")
        assertEquals(ServiceState.Finished, before.ended("once").state)
        assertNull(LinuxHealth.serviceCheck(linux().status("once")))
    }

    @Test
    fun `start when Locus opens brings a service back after a restart, only for what the user approved`() = runBlocking {
        val before = linux()
        before.startService("web", "sleep 30")
        assertTrue(before.setServiceAutostart("web", true))
        assertFalse(before.setServiceAutostart("nope", true))
        assertTrue(before.status("web").autostart)

        // After a restart it's set to start but isn't running yet: a warning with Start.
        val after = linux()
        val due = after.status("web")
        assertEquals(ServiceState.StoppedByRestart, due.state)
        assertEquals(dev.locus.core.linux.LinuxFix.StartService, LinuxHealth.serviceCheck(due)?.fix)

        assertEquals(emptyMap(), after.startAutostartServices())
        assertEquals(ServiceState.Running, after.status("web").state)
        assertTrue(after.status("web").autostart, "starting it keeps the setting")
        assertTrue(after.startAutostartServices().isEmpty(), "a running one isn't started twice")

        // The agent starting a different command under the same name needs the user's say again.
        after.startService("web", "sleep 31")
        assertFalse(after.status("web").autostart)
    }

    @Test
    fun `autostart waits for Locus to be on screen and for Linux`() = runBlocking {
        val before = linux()
        before.startService("web", "sleep 30")
        before.setServiceAutostart("web", true)

        val after = LinuxEnvironment(graph.context).also { started += it }
        assertEquals(LinuxStatus.NotInstalled, after.status.value)
        val visible = MutableStateFlow(false)
        LinuxAutostart(after, scope) { visible }.start()

        kotlinx.coroutines.delay(300)
        assertFalse(after.status("web").running, "not while Locus is in the background")
        visible.value = true
        kotlinx.coroutines.delay(300)
        assertFalse(after.status("web").running, "not before Linux is ready")
        after.runOnHost(LinuxEnvironment.CommandHost { command, _ -> ProcessBuilder("/bin/sh", "-c", command) })
        withTimeout(10_000) { while (!after.status("web").running) kotlinx.coroutines.delay(50) }
    }
}
