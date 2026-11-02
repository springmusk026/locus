package dev.locus.core.linux

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The interactive terminal must be no less isolated than agent commands: the same proot options
 * (the /proc hiding and binder shadows from #78) and the same environment, apart from the shell
 * that runs and the terminal type.
 */
class TerminalParityTest {
    private val paths = ProotArgs.Paths(
        proot = "/app/lib/libproot.so",
        rootfs = "/data/linux/alpine",
        tmp = "/data/linux/tmp",
        workspace = "/data/workspace",
        procFakes = "/data/linux/proc-fakes",
        shadow = "/data/linux/proc-shadow",
    )
    private val guest = GuestLaunch(
        paths,
        listOf("stat", "loadavg", "uptime", "vmstat"),
        hiddenPids = setOf(4321, 4322, 77),
        hiddenDevices = listOf("/dev/binder", "/dev/hwbinder"),
    )

    @Test
    fun `the login shell gets exactly the agent's proot options`() {
        val agent = guest.command("ls", "/workspace")
        val shell = guest.loginShell("/workspace", hasBash = false)
        assertEquals(agent.dropLast(3), shell.dropLast(2))
        assertEquals(listOf("/bin/sh", "-c", "ls"), agent.takeLast(3))
        assertEquals(listOf("/bin/sh", "-l"), shell.takeLast(2))
    }

    @Test
    fun `the proot options are the ones ProotArgs builds`() {
        val expected = ProotArgs.build(paths, "/workspace", listOf("stat", "loadavg", "uptime", "vmstat"), setOf(4321, 4322, 77), listOf("/dev/binder", "/dev/hwbinder"))
        assertEquals(expected, guest.loginShell("/workspace", hasBash = true).dropLast(2))
    }

    @Test
    fun `the shell hides Locus's processes and the binder devices`() {
        val bindings = guest.loginShell("/workspace", hasBash = false).zipWithNext().filter { it.first == "-b" }.map { it.second }
        listOf(77, 4321, 4322).forEach { assertTrue("${paths.shadow}:/proc/$it" in bindings, "/proc/$it is not hidden") }
        assertTrue("${paths.shadow}:/dev/binder" in bindings)
        assertTrue("${paths.shadow}:/dev/hwbinder" in bindings)
        assertTrue("${paths.workspace}:/workspace" in bindings)
        assertTrue("--kill-on-exit" in guest.loginShell("/workspace", hasBash = false))
    }

    @Test
    fun `bash is used when installed`() {
        assertEquals(listOf("/bin/bash", "-l"), guest.loginShell("/workspace", hasBash = true).takeLast(2))
    }

    @Test
    fun `the terminal environment only changes the terminal type`() {
        val base = mapOf("PROOT_LOADER" to "/lib/loader", "HOME" to "/root", "TERM" to "dumb", "PATH" to "/bin")
        val env = GuestLaunch.terminalEnvironment(base)
        assertEquals(base - "TERM", env - "TERM" - "COLORTERM")
        assertEquals("xterm-256color", env["TERM"])
        assertEquals("truecolor", env["COLORTERM"])
    }
}
