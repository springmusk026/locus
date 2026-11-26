package dev.locus.core.agent

import dev.locus.core.agent.tools.RemoteJobs
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the generated snippets through the local /bin/sh with a scratch HOME, as an SSH server would. */
class RemoteJobsTest {
    private val home: File = Files.createTempDirectory("locus-jobs").toFile()

    private fun sh(script: String, path: String? = null): String {
        val p = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).apply {
            environment()["HOME"] = home.path
            if (path != null) environment()["PATH"] = path
        }.start()
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
     * Waits for process [pid] to end, without polling: [ProcessHandle.onExit] completes when it does.
     * A process that's already gone counts as ended. The 20 s are a bound for a broken run, not a wait.
     */
    private fun awaitExit(pid: Long, what: String) {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        try {
            handle.onExit().get(20, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError("timed out waiting for $what (pid $pid) to end")
        }
    }

    private fun runnerPid(id: String): Long = File(home, ".locus-jobs/$id.pid").readText().trim().toLong()

    /**
     * [id]'s status once it has finished. The runner writes the exit code and only then exits, so once
     * its process has ended the status is final.
     */
    private fun finished(id: String): String {
        awaitExit(runnerPid(id), "job $id's runner")
        return sh(RemoteJobs.status(id, 20))
    }

    /**
     * The pid a job's grandchild wrote to [fifo] as it started. Opening a FIFO for reading blocks until
     * the writer opens it, so this returns exactly when the grandchild is up. Bounded like [awaitExit].
     */
    private fun pidFrom(fifo: File): Long {
        val read = CompletableFuture.supplyAsync { fifo.readText().trim().toLong() }
        return try {
            read.get(20, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw AssertionError("timed out waiting for the grandchild to start")
        }
    }

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
        val status = finished("j1")
        assertTrue(status.startsWith("state: finished, exit code 3"), status)
        assertTrue(status.contains("it's here\ntwo"), status)
    }

    @Test
    fun runningJobCanBeListedAndStopped() {
        sh(RemoteJobs.start("slow", "sleep 30"))
        // start forks the runner before it prints, so the job is running as soon as start returns.
        assertTrue(sh(RemoteJobs.status("slow", 5)).startsWith("state: running"))
        assertTrue(sh(RemoteJobs.list()).contains("slow: running"))
        assertEquals("stopped", sh(RemoteJobs.stop("slow")))
        assertTrue(sh(RemoteJobs.status("slow", 5)).startsWith("state: finished, exit code 143"))
    }

    @Test
    fun stopKillsGrandchildren() {
        // The grandchild says it's up by writing its pid to a FIFO, then becomes the sleep (same pid).
        val fifo = File(home, "grandchild.fifo")
        assertEquals("", sh("mkfifo '${fifo.path}'"))
        sh(RemoteJobs.start("tree", "sh -c 'echo \$\$ > ${fifo.path}; exec sleep $marker'; echo after"))
        assertTrue(sh(RemoteJobs.status("tree", 5)).startsWith("state: running"))
        val grandchild = pidFrom(fifo)
        assertTrue(ProcessHandle.of(grandchild).map { it.isAlive }.orElse(false), "the grandchild is running")
        assertEquals("stopped", sh(RemoteJobs.stop("tree")))
        awaitExit(grandchild, "the grandchild")
        assertEquals("", sh("pgrep -f 'slee[p] $marker'"))
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

    /**
     * A job that ends between a check's "has it an exit code?" and "is its runner alive?" is finished,
     * never lost. The runner writes the exit code and then exits, so a check that reads the exit file
     * first and the pid second can find neither while the job ends in between (CI hit this as a
     * dropped-connection run reported "still running" with the job's whole output). Made certain here:
     * a `cat` on PATH that, when the pid is read, lets the job finish and its runner die first.
     */
    @Test
    fun aJobEndingMidCheckIsFinishedNotLost() {
        val shim = File(home, "shim").apply { mkdirs() }
        File(shim, "cat").apply {
            writeText(
                """
                #!/bin/sh
                case "${'$'}1" in
                  *.pid)
                    f="${'$'}{1%.pid}"; p="${'$'}(/bin/cat "${'$'}1")"
                    echo 0 > "${'$'}f.exit.tmp"; mv "${'$'}f.exit.tmp" "${'$'}f.exit"
                    kill "${'$'}p"; while kill -0 "${'$'}p" 2>/dev/null; do sleep 0.01; done ;;
                esac
                exec /bin/cat "${'$'}@"
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val path = shim.path + ":" + System.getenv("PATH")
        fun runningJob(id: String) {
            val dir = File(home, ".locus-jobs").apply { mkdirs() }
            File(dir, "$id.sh").writeText("sleep $marker\n")
            File(dir, "$id.start").writeText("0\n")
            File(dir, "$id.exit").delete()
            // A runner the shell leaves to init, as nohup's is, so it's reaped as soon as it dies.
            File(dir, "$id.pid").writeText(sh("sleep $marker > /dev/null 2>&1 & echo ${'$'}!") + "\n")
        }

        runningJob("mid")
        val probe = RemoteJobs.Probe.parse(sh(RemoteJobs.probe("mid"), path))
        assertEquals("done", probe?.state, "probe")
        assertEquals(0, probe?.exitCode)
        runningJob("mid")
        assertTrue(sh(RemoteJobs.status("mid", 5), path).startsWith("state: finished, exit code 0"), "status")
        runningJob("mid")
        assertEquals("mid: finished (exit 0)", sh(RemoteJobs.list(), path), "list")
        runningJob("mid")
        assertEquals("already finished", sh(RemoteJobs.stop("mid"), path), "stop")
    }
}
