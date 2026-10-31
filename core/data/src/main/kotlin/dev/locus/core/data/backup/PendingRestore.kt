package dev.locus.core.data.backup

import android.content.Context
import androidx.datastore.dataStoreFile
import dev.locus.core.database.DatabaseBackup
import java.io.File

/**
 * Swaps a verified, staged backup into place while nothing has the files open: at the very start of
 * the next process, before Hilt builds Room, DataStore or the workspace (call it from
 * `Application.attachBaseContext`, which runs before any ContentProvider).
 *
 * The staging folder holds `staged/` (from [BackupArchive.extract]) and, once the user confirmed,
 * a `READY` marker. Without the marker the stage is a cancelled or interrupted preview and is
 * deleted. Each step renames within the app's own storage and can be repeated, so a crash part-way
 * finishes on the next start.
 */
class PendingRestore(
    private val root: File,
    private val database: File,
    private val settings: File,
    private val agentHome: File,
    private val workspace: File,
) {
    val staged: File get() = File(root, "staged")
    private val ready: File get() = File(root, READY)

    /** Marks the stage as confirmed; it's applied on the next start. */
    fun markReady() {
        ready.writeText(System.currentTimeMillis().toString())
    }

    /** Throws away a stage that wasn't confirmed. */
    fun discard() {
        root.deleteRecursively()
    }

    /** Applies a confirmed stage. Returns true if it restored something. */
    fun apply(): Boolean {
        if (!root.exists()) return false
        if (!ready.exists()) {
            discard()
            return false
        }
        File(staged, BackupArchive.DATABASE_ENTRY).takeIf { it.isFile }?.let { db ->
            database.parentFile?.mkdirs()
            // The old database's WAL and shared memory belong to it; left behind, SQLite would replay them into the new one.
            listOf("-wal", "-shm", "-journal").forEach { File(database.path + it).delete() }
            replace(db, database)
        }
        File(staged, BackupArchive.SETTINGS_ENTRY).takeIf { it.isFile }?.let { replace(it, settings) }
        File(staged, BackupArchive.AGENT_HOME).takeIf { it.isDirectory }?.let { replace(it, agentHome) }
        File(staged, BackupArchive.WORKSPACE).takeIf { it.isDirectory }?.let { replace(it, workspace) }
        discard()
        return true
    }

    /** Moves [from] over [to]: the old one aside first, so a crash never leaves neither. */
    private fun replace(from: File, to: File) {
        val old = File(to.path + ".restore-old")
        old.deleteRecursively()
        to.parentFile?.mkdirs()
        if (to.exists() && !to.renameTo(old)) error("Couldn't move ${to.name} aside")
        if (!from.renameTo(to)) {
            // Same filesystem in practice; copy as a fallback.
            if (!from.copyRecursively(to, overwrite = true)) error("Couldn't restore ${to.name}")
            from.deleteRecursively()
        }
        old.deleteRecursively()
    }

    companion object {
        private const val READY = "READY"

        /** The real locations, matching `DatabaseModule`, `DataStoreModule` and `DataModule`. */
        fun forApp(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "restore"),
            database = context.getDatabasePath(DatabaseBackup.NAME),
            settings = context.dataStoreFile(SETTINGS_FILE),
            agentHome = File(context.filesDir, AGENT_HOME_DIR),
            workspace = File(context.filesDir, WORKSPACE_DIR),
        )

        const val SETTINGS_FILE = "settings.json"
        const val AGENT_HOME_DIR = "agent-home"
        const val WORKSPACE_DIR = "workspace"
    }
}
