package dev.locus

import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.core.data.backup.BackupArchive
import dev.locus.core.data.backup.BackupOptions
import dev.locus.core.data.backup.PendingRestore
import dev.locus.testing.KeepDataAfter
import dev.locus.testing.KeepDataBefore
import dev.locus.testing.LocusTest
import dev.locus.testing.awaitText
import dev.locus.testing.shows
import dev.locus.testing.tap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Restoring a backup across a real restart (docs/design/backup.md): a confirmed restore is swapped in
 * at the next start, before anything opens Locus's files; a swap that fails part-way is rolled back
 * and the user is told once.
 *
 * Each pair is two processes (see ProcessDeathTest): the first test stages the restore as the
 * Restore button does, minus its restart; the orchestrator's next process is the restart, and the
 * test application applies the stage in attachBaseContext as LocusApplication does. Run the class whole.
 */
@HiltAndroidTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RestoreTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    private val backupFile get() = File(context.cacheDir, "restore-test.locusbackup")
    private val password get() = "instrumentation-test".toCharArray()

    /** Writes a backup of what's there now, including the workspace. */
    private fun backUp() = runBlocking {
        backupFile.delete()
        backup.get().backup(Uri.fromFile(backupFile), password, BackupOptions(includeWorkspace = true)) { _, _ -> }
    }

    /** Verifies the backup and confirms it, as Settings → Backup & restore → Restore does before restarting. */
    private fun stageRestore() = runBlocking {
        backup.get().verify(Uri.fromFile(backupFile), password) { _, _ -> }
        backup.get().confirmRestore()
    }

    private fun titles() = runBlocking { conversations.allOnce().map { it.title } }

    private fun writeWorkspace(path: String, text: String) = workspace.resolve(path).apply { parentFile?.mkdirs() }.writeText(text)

    @Test
    @KeepDataAfter
    fun a_confirmedRestoreIsStaged() {
        newChat(IN_BACKUP)
        writeWorkspace("restored.txt", "from the backup\n")
        backUp()
        // Changes after the backup, which the restore must undo.
        newChat(AFTER_BACKUP)
        workspace.resolve("restored.txt").delete()
        writeWorkspace("later.txt", "made after the backup\n")
        stageRestore()
        assertTrue("The restore should wait for the next start", File(PendingRestore.forApp(context).staged.parentFile, "CONFIRMED").exists())
    }

    @Test
    @KeepDataBefore
    fun b_restoreAppliedAtTheNextStart() {
        val titles = titles()
        assertTrue("The backup's chat is back: $titles", IN_BACKUP in titles)
        assertFalse("The chat made after the backup is gone: $titles", AFTER_BACKUP in titles)
        assertEquals("from the backup\n", workspace.resolve("restored.txt").readText())
        assertFalse(workspace.resolve("later.txt").exists())
        assertNull(runBlocking { backup.get().restoreFailure() })
        assertFalse(PendingRestore.forApp(context).staged.parentFile!!.exists())

        launchHome()
        compose.awaitText("Locus")
        compose.waitForIdle()
        assertFalse(compose.shows("Restore didn't finish"))
    }

    /**
     * Stages a restore whose swap fails part-way at the next start, after the current data was moved
     * aside: the stage's folder can't be renamed out of (read-only) and one workspace file in it can't
     * be read, so neither the move nor the copy fallback can put the workspace in place.
     */
    @Test
    @KeepDataAfter
    fun c_restoreThatWillFailIsStaged() {
        newChat(IN_BACKUP)
        writeWorkspace("mine.txt", "v1\n")
        backUp()
        newChat(KEPT)
        writeWorkspace("mine.txt", "v2\n")
        stageRestore()

        val staged = PendingRestore.forApp(context).staged
        val unreadable = File(staged, "${BackupArchive.WORKSPACE}/mine.txt")
        assertTrue("The backup's workspace should be staged", unreadable.isFile)
        assertTrue(unreadable.setReadable(false, false))
        assertTrue(staged.setWritable(false, false))
        // If the device ignores file modes (a root shell, say), this test can't force the failure.
        assertFalse("Couldn't make the staged file unreadable on this device", unreadable.canRead())
        assertFalse("Couldn't make the stage read-only on this device", staged.canWrite())
    }

    @Test
    @KeepDataBefore
    fun d_failedRestoreIsRolledBackAndTheUserIsTold() {
        val failure = runBlocking { backup.get().restoreFailure() }
        assertNotNull("The restore should have failed at start", failure)
        assertTrue("It should have been rolled back: ${failure!!.reason}", failure.rolledBack)
        // Everything is as it was before the attempt.
        val titles = titles()
        assertTrue("The chat made after the backup is still there: $titles", KEPT in titles)
        assertEquals("v2\n", workspace.resolve("mine.txt").readText())

        launchHome()
        compose.awaitText("Restore didn't finish")
        compose.tap("OK")
        waitUntil("the notice to be dismissed") { runBlocking { backup.get().restoreFailure() } == null }
        compose.waitForIdle()
        assertFalse(compose.shows("Restore didn't finish"))
    }

    private companion object {
        const val IN_BACKUP = "In the backup"
        const val AFTER_BACKUP = "Made after the backup"
        const val KEPT = "Kept after a failed restore"
    }
}
