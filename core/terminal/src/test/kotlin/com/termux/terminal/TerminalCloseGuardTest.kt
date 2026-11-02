package com.termux.terminal

import dev.locus.core.terminal.TerminalSessions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Closing a tab whose shell never started must not kill anything. The process starts only once the
 * view has a size, so until then the session's pid is 0, and `kill(0, SIGKILL)` would kill Locus's
 * own process group (the app and a running agent task). In the package of the vendored session so
 * the test can set its pid the way the session does.
 */
class TerminalCloseGuardTest {
    private fun session() = TerminalSession("/bin/sh", "/", arrayOf("-sh"), emptyArray(), 100, null)

    @Test
    fun `a session whose shell never started is not stopped`() {
        val unstarted = session()
        assertEquals(0, unstarted.pid)
        // The vendored isRunning() is true here (it only checks for -1): the trap the guard avoids.
        assertTrue(unstarted.isRunning)
        assertFalse(TerminalSessions.started(unstarted))
        // Closing it (Locus's guard, then the vendored one) is a no-op, not a kill of pid 0.
        TerminalSessions.stop(unstarted)
        unstarted.finishIfRunning()
        assertEquals(0, unstarted.pid)
    }

    @Test
    fun `only a started, still running shell is stopped`() {
        val running = session().apply { mShellPid = 4242 }
        assertTrue(TerminalSessions.started(running))
        val finished = session().apply { mShellPid = -1 }
        assertFalse(TerminalSessions.started(finished))
        assertFalse(finished.isRunning)
    }
}
