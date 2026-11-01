package dev.locus.core.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.dataStoreFile
import dev.locus.core.database.DatabaseBackup
import dev.locus.core.datastore.DataStoreFiles
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [PendingRestore.forApp] as `LocusApplication.attachBaseContext` calls it, and [PendingRestore.apply] on those paths. */
class PendingRestoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * A context that answers for its own directories like the base context the system hands to
     * `attachBaseContext`. [app] is what `applicationContext` returns: null before the Application
     * is attached, the Application itself (whose `applicationContext` is itself) afterwards.
     */
    private class DirsContext(private val root: File, private val attached: Boolean) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name")
        override fun getApplicationContext(): Context? = if (attached) this else null
    }

    @Test
    fun `forApp works before applicationContext exists`() {
        val root = tmp.newFolder()
        val base = DirsContext(root, attached = false)
        // The bug: androidx's dataStoreFile goes through applicationContext, which is null here.
        assertFailsWith<NullPointerException> { base.dataStoreFile(DataStoreFiles.SETTINGS) }

        val restore = PendingRestore.forApp(base)

        // The same file DataStoreModule opens once the app is up: dataStoreFile on the application context.
        val app = DirsContext(root, attached = true)
        assertEquals(app.dataStoreFile("settings.json"), restore.settings)
        assertEquals(File(root, "files/datastore/settings.json"), restore.settings)
        assertEquals(app.getDatabasePath(DatabaseBackup.NAME), restore.database)
        assertEquals(File(root, "files/agent-home"), restore.agentHome)
        assertEquals(File(root, "files/workspace"), restore.workspace)
        assertEquals(File(root, "no_backup/restore"), restore.root)
    }

    @Test
    fun `a confirmed stage is swapped in at the real paths`() {
        val root = tmp.newFolder()
        val app = DirsContext(root, attached = true)
        // What the app has now.
        val db = app.getDatabasePath(DatabaseBackup.NAME).apply { parentFile.mkdirs(); writeText("old db") }
        File(db.path + "-wal").writeText("old wal")
        File(db.path + "-shm").writeText("old shm")
        app.dataStoreFile("settings.json").apply { parentFile.mkdirs(); writeText("old settings") }
        File(app.filesDir, "agent-home/memory").mkdirs()
        File(app.filesDir, "agent-home/memory/old.md").writeText("old")
        File(app.filesDir, "workspace").mkdirs()
        File(app.filesDir, "workspace/keep.txt").writeText("mine")

        // A verified backup, staged and confirmed by the previous process (no workspace in it).
        val before = PendingRestore.forApp(app)
        stage(before.staged)
        before.markReady()

        // The next process: applied from attachBaseContext.
        assertTrue(PendingRestore.forApp(DirsContext(root, attached = false)).apply())

        assertEquals("new db", db.readText())
        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
        assertEquals("new settings", app.dataStoreFile("settings.json").readText())
        assertEquals("new", File(app.filesDir, "agent-home/memory/new.md").readText())
        assertFalse(File(app.filesDir, "agent-home/memory/old.md").exists())
        assertEquals("mine", File(app.filesDir, "workspace/keep.txt").readText())
        assertFalse(File(root, "no_backup/restore").exists())
        assertTrue(root.walkTopDown().none { it.name.endsWith(".restore-old") })
        // Nothing left to apply on the start after that.
        assertFalse(PendingRestore.forApp(DirsContext(root, attached = false)).apply())
    }

    @Test
    fun `a READY stage from 0_1_2 or 0_1_3 is discarded, not applied`() {
        val root = tmp.newFolder()
        val app = DirsContext(root, attached = true)
        val settings = app.dataStoreFile("settings.json").apply { parentFile.mkdirs(); writeText("current") }
        val restore = PendingRestore.forApp(app)
        stage(restore.staged)
        File(restore.root, "READY").writeText("1761900000000")

        assertFalse(PendingRestore.forApp(DirsContext(root, attached = false)).apply())
        assertEquals("current", settings.readText())
        assertFalse(restore.root.exists())
    }

    private fun stage(staged: File) {
        File(staged, BackupArchive.DATABASE_ENTRY).apply { parentFile.mkdirs(); writeText("new db") }
        File(staged, BackupArchive.SETTINGS_ENTRY).apply { parentFile.mkdirs(); writeText("new settings") }
        File(staged, "${BackupArchive.AGENT_HOME}/memory").mkdirs()
        File(staged, "${BackupArchive.AGENT_HOME}/memory/new.md").writeText("new")
    }
}
