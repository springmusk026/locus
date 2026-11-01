package dev.locus.core.data.backup

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PendingRestore.apply] is all-or-nothing: a failure or a process death at any step leaves exactly
 * the files from before, or (once committed) exactly the restored ones. docs/design/backup.md.
 */
class RestoreSwapTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Thrown by [FaultFs] to stand for the process dying: [PendingRestore] doesn't catch Errors. */
    private class SimulatedCrash : Error("process died")

    /**
     * Counts each rename, write and delete as a step. Steps in [failAt] throw an IOException, or
     * [SimulatedCrash] when [crash]. A copy fails if the rename just before it was made to fail, so a
     * failed move in really fails (the copy fallback is tested on its own).
     */
    private class FaultFs(private val failAt: Set<Int> = emptySet(), private val crash: Boolean = false) : RestoreFs() {
        var steps = 0
        var commitStep = -1
        private var renameFailed = false

        private fun step() {
            steps++
            renameFailed = false
            if (steps in failAt) {
                if (crash) throw SimulatedCrash()
                throw IOException("injected failure at step $steps")
            }
        }

        override fun rename(from: File, to: File) {
            try {
                step()
            } catch (e: IOException) {
                renameFailed = true
                throw e
            }
            super.rename(from, to)
            if (to.name == "COMMITTED") commitStep = steps
        }

        override fun copy(from: File, to: File) {
            if (renameFailed) throw IOException("copy failed too")
            super.copy(from, to)
        }

        override fun delete(file: File) {
            step()
            super.delete(file)
        }
    }

    private class World(val root: File) {
        val db = File(root, "databases/locus.db")
        val settings = File(root, "files/datastore/settings.json")
        val agentHome = File(root, "files/agent-home")
        val workspace = File(root, "files/workspace")
        fun restore(fs: RestoreFs = RestoreFs()) =
            PendingRestore(File(root, "no_backup/restore"), db, settings, agentHome, workspace, fs = fs)

        /** Every file and folder outside the restore's own storage, with its contents. */
        fun snapshot(): Map<String, String> = root.walkTopDown()
            .filter { it != root && !it.relativeTo(root).path.startsWith("no_backup") }
            .associate { it.relativeTo(root).path to if (it.isDirectory) "<dir>" else it.readText() }
    }

    /** What the phone has: everything present, the database with its WAL and shared memory. */
    private fun fullWorld(): World = World(tmp.newFolder()).apply {
        db.write("old db")
        File(db.path + "-wal").write("old wal")
        File(db.path + "-shm").write("old shm")
        settings.write("old settings")
        File(agentHome, "memory/old.md").write("old memory")
        File(workspace, "project/main.py").write("print('mine')")
    }

    /** A phone without settings or a workspace yet (and no WAL): targets the backup adds rather than replaces. */
    private fun sparseWorld(): World = World(tmp.newFolder()).apply {
        db.write("old db")
        // DataStore's folder is there before its first file; a rollback leaves folders the swap made.
        settings.parentFile.mkdirs()
        File(agentHome, "memory/old.md").write("old memory")
    }

    /** A verified, confirmed stage with every part, the workspace included. */
    private fun World.stageBackup() {
        val r = restore()
        File(r.staged, BackupArchive.DATABASE_ENTRY).write("new db")
        File(r.staged, BackupArchive.SETTINGS_ENTRY).write("new settings")
        File(r.staged, "${BackupArchive.AGENT_HOME}/memory/new.md").write("new memory")
        File(r.staged, "${BackupArchive.WORKSPACE}/restored.txt").write("from the backup")
        r.markReady()
    }

    private val restored = mapOf(
        "databases" to "<dir>",
        "databases/locus.db" to "new db",
        "files" to "<dir>",
        "files/datastore" to "<dir>",
        "files/datastore/settings.json" to "new settings",
        "files/agent-home" to "<dir>",
        "files/agent-home/memory" to "<dir>",
        "files/agent-home/memory/new.md" to "new memory",
        "files/workspace" to "<dir>",
        "files/workspace/restored.txt" to "from the backup",
    )

    private fun File.write(text: String) {
        parentFile.mkdirs()
        writeText(text)
    }

    /** The number of steps a clean swap takes, and the one that writes COMMITTED. */
    private fun measure(world: () -> World): Pair<Int, Int> {
        val w = world().apply { stageBackup() }
        val fs = FaultFs()
        assertTrue(w.restore(fs).apply())
        assertEquals(restored, w.snapshot())
        assertTrue(fs.commitStep > 0)
        return fs.steps to fs.commitStep
    }

    @Test
    fun `a successful swap replaces everything and cleans up`() {
        for (make in listOf(::fullWorld, ::sparseWorld)) {
            val w = make().apply { stageBackup() }
            assertTrue(w.restore().apply())
            assertEquals(restored, w.snapshot())
            assertFalse(File(w.root, "no_backup/restore").exists())
            assertNull(w.restore().failure())
            // Nothing is applied twice.
            assertFalse(w.restore().apply())
            assertEquals(restored, w.snapshot())
        }
    }

    @Test
    fun `a failure at any step before commit rolls back to the exact previous files`() {
        for (make in listOf(::fullWorld, ::sparseWorld)) {
            val (steps, commit) = measure(make)
            for (k in 1..steps) {
                val w = make()
                val before = w.snapshot()
                w.stageBackup()

                val applied = w.restore(FaultFs(setOf(k))).apply()

                if (k <= commit) {
                    assertFalse(applied, "step $k of $steps")
                    assertEquals(before, w.snapshot(), "step $k of $steps")
                    // The stage and CONFIRMED are gone: no retry at the next start.
                    assertFalse(File(w.root, "no_backup/restore").exists(), "step $k")
                    val failure = assertNotNull(w.restore().failure(), "step $k")
                    assertTrue(failure.rolledBack)
                    assertTrue(failure.reason.startsWith("IOException"), failure.reason)
                    assertFalse(w.restore().apply())
                    assertEquals(before, w.snapshot(), "step $k, next start")
                } else {
                    // Committed: only clean-up failed, and the next start finishes it.
                    assertTrue(applied, "step $k of $steps")
                    assertNull(w.restore().failure())
                    w.restore().apply()
                    assertEquals(restored, w.snapshot(), "step $k of $steps")
                    assertFalse(File(w.root, "no_backup/restore").exists(), "step $k")
                }
            }
        }
    }

    @Test
    fun `a crash at any step recovers at the next start`() {
        for (make in listOf(::fullWorld, ::sparseWorld)) {
            val (steps, commit) = measure(make)
            for (k in 1..steps) {
                val w = make()
                val before = w.snapshot()
                w.stageBackup()

                assertFailsWith<SimulatedCrash> { w.restore(FaultFs(setOf(k), crash = true)).apply() }

                // The next process. A crash before step 1 (CONFIRMED → STARTED) is a crash before the
                // attempt began, so the restore runs then; after it, it's never attempted twice.
                val applied = w.restore().apply()
                val rolledBack = k in 2..commit
                if (rolledBack) {
                    assertFalse(applied, "crash at step $k of $steps")
                    assertEquals(before, w.snapshot(), "crash at step $k of $steps")
                    assertTrue(assertNotNull(w.restore().failure()).rolledBack)
                } else {
                    assertTrue(applied, "crash at step $k of $steps")
                    assertEquals(restored, w.snapshot(), "crash at step $k of $steps")
                    assertNull(w.restore().failure())
                }
                assertFalse(File(w.root, "no_backup/restore").exists(), "crash at step $k")
                // And the one after that changes nothing.
                assertFalse(w.restore().apply())
                assertEquals(if (rolledBack) before else restored, w.snapshot())
            }
        }
    }

    @Test
    fun `a crash during the rollback is rolled back again`() {
        val (_, commit) = measure(::fullWorld)
        // Crash just before COMMITTED, when every part has moved; then crash at each step of the rollback.
        for (m in 1..12) {
            val w = fullWorld()
            val before = w.snapshot()
            w.stageBackup()
            assertFailsWith<SimulatedCrash> { w.restore(FaultFs(setOf(commit), crash = true)).apply() }
            val recovery = FaultFs(setOf(m), crash = true)
            val crashed = runCatching { w.restore(recovery).apply() }.exceptionOrNull()
            if (crashed == null) break // the rollback has fewer than m steps
            assertTrue(crashed is SimulatedCrash)

            assertFalse(w.restore().apply())
            assertEquals(before, w.snapshot(), "crash at rollback step $m")
            assertFalse(File(w.root, "no_backup/restore").exists())
        }
    }

    @Test
    fun `a rollback that can't finish is retried at the next start, never the restore`() {
        val (_, commit) = measure(::fullWorld)
        val w = fullWorld()
        val before = w.snapshot()
        w.stageBackup()

        // Fail the commit, then the first step of the rollback too.
        assertFalse(w.restore(FaultFs(setOf(commit, commit + 1))).apply())
        val partial = assertNotNull(w.restore().failure())
        assertFalse(partial.rolledBack)
        // Only the journal is left: the stage and CONFIRMED are gone.
        assertEquals(listOf("JOURNAL"), File(w.root, "no_backup/restore").list()?.toList())

        // The app ran meanwhile and created an empty database where the restored one had been.
        w.db.takeIf { !it.exists() }?.write("")

        assertFalse(w.restore().apply())
        assertEquals(before, w.snapshot())
        assertTrue(assertNotNull(w.restore().failure()).rolledBack)
        assertFalse(File(w.root, "no_backup/restore").exists())
    }

    @Test
    fun `the copy fallback is used when a move in fails`() {
        val w = fullWorld().apply { stageBackup() }
        val fs = object : RestoreFs() {
            override fun rename(from: File, to: File) {
                if ("staged" in from.path) throw IOException("cross-device")
                super.rename(from, to)
            }
        }
        assertTrue(w.restore(fs).apply())
        assertEquals(restored, w.snapshot())
    }

    @Test
    fun `the notice is set by a failure, cleared when seen, and cleared by a later successful restore`() {
        val (_, commit) = measure(::fullWorld)
        val w = fullWorld()
        w.stageBackup()
        assertFalse(w.restore(FaultFs(setOf(commit))).apply())
        assertNotNull(w.restore().failure())

        w.restore().clearFailure()
        assertNull(w.restore().failure())

        // Another failure, then the user restores again and it works.
        w.stageBackup()
        assertFalse(w.restore(FaultFs(setOf(commit))).apply())
        assertNotNull(w.restore().failure())
        w.stageBackup()
        assertTrue(w.restore().apply())
        assertNull(w.restore().failure())
    }
}
