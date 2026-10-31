package dev.locus.core.agent

import dev.locus.core.agent.tools.RemoteJobs
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the generated snippets through the local /bin/sh with a scratch HOME, as an SSH server would. */
class RemoteJobsTest {
    private val home: File = Files.createTempDirectory("locus-jobs").toFile()

    private fun sh(script: String): String {
        val p = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).apply { environment()["HOME"] = home.path }.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    @AfterTest
    fun cleanup() {
        // Stop anything a failed test left running, then drop the scratch HOME.
        sh(RemoteJobs.list()).lines().mapNotNull { it.substringBefore(": running", "").ifEmpty { null } }.forEach { sh(RemoteJobs.stop(it)) }
        home.deleteRecursively()
    }

    /**
     * Polls until [check] holds. Real processes take real time, so this waits on a deadline rather
     * than for a fixed time: fast machines don't wait, slow CI runners get up to 20 s.
     */
    private fun <T> eventually(what: String, read: () -> T, check: (T) -> Boolean): T {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            val value = read()
            if (check(value)) return value
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what; last: $value")
            Thread.sleep(25)
        }
    }

    private fun waitFor(id: String, state: String): String = eventually("$id to be $state", { sh(RemoteJobs.status(id, 20)) }) { it.contains(state) }

    /** A sleep only this run starts, so pgrep never matches another run's (or another checkout's) process. */
    private val marker = "31.${(1000..9999).random()}"

    @Test
    fun idsAreSlugged() {
        assertEquals("build-site-abc123", RemoteJobs.newId("Build site!", { "ABC123xyz" }))
        assertEquals("job-x", RemoteJobs.newId(null, { "x" }))
        assertFalse(RemoteJobs.isValidId("../etc"))
        assertFalse(RemoteJobs.isValidId("a b"))
    }

    @Test
    fun runsDetachedAndReportsExitAndOutput() {
        assertEquals("started j1", sh(RemoteJobs.start("j1", "echo \"it's here\"; echo two; exit 3")))
        val status = waitFor("j1", "finished")
        assertTrue(status.startsWith("state: finished, exit code 3"), status)
        assertTrue(status.contains("it's here\ntwo"), status)
    }

    @Test
    fun runningJobCanBeListedAndStopped() {
        sh(RemoteJobs.start("slow", "sleep 30"))
        assertTrue(waitFor("slow", "running").startsWith("state: running"))
        assertTrue(sh(RemoteJobs.list()).contains("slow: running"))
        assertEquals("stopped", sh(RemoteJobs.stop("slow")))
        assertTrue(sh(RemoteJobs.status("slow", 5)).startsWith("state: finished, exit code 143"))
    }

    @Test
    fun stopKillsGrandchildren() {
        sh(RemoteJobs.start("tree", "sh -c 'sleep $marker'; echo after"))
        assertTrue(waitFor("tree", "running").startsWith("state: running"))
        val grandchild = "pgrep -f 'slee[p] $marker'"
        eventually("the grandchild to start", { sh(grandchild) }) { it.isNotBlank() }
        assertEquals("stopped", sh(RemoteJobs.stop("tree")))
        eventually("the grandchild to be killed", { sh(grandchild) }) { it.isEmpty() }
        assertFalse(sh(RemoteJobs.status("tree", 5)).contains("after"))
    }

    @Test
    fun scriptsSurviveFishAndCshStyleLoginShells() {
        // Only the outer pipeline is parsed by the login shell; it has no sh-only syntax.
        val outer = RemoteJobs.start("x", "echo 'a\nb'")
        assertTrue(Regex("^echo [A-Za-z0-9+/=]+ \\| base64 -d \\| sh$").matches(outer), outer)
    }

    @Test
    fun unknownJob() {
        assertEquals("state: no such job", sh(RemoteJobs.status("nope", 5)))
        assertEquals("no jobs", sh(RemoteJobs.list()))
    }
}
