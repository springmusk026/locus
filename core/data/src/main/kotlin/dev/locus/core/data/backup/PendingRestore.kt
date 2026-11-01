package dev.locus.core.data.backup

import android.content.Context
import dev.locus.core.database.DatabaseBackup
import dev.locus.core.datastore.DataStoreFiles
import java.io.File

/**
 * Swaps a verified, staged backup into place while nothing has the files open: at the very start of
 * the next process, before Hilt builds Room, DataStore or the workspace (call it from
 * `Application.attachBaseContext`, which runs before any ContentProvider).
 *
 * The staging folder holds `staged/` (from [BackupArchive.extract]) and, once the user confirmed,
 * a `CONFIRMED` marker. Without the marker the stage is a cancelled or interrupted preview and is
 * deleted. Each step renames within the app's own storage and can be repeated, so a crash part-way
 * finishes on the next start.
 *
 * 0.1.2 and 0.1.3 wrote a `READY` marker instead, but could never apply it ([forApp] threw in
 * `attachBaseContext`). Such a stage may be weeks old by now, so it's discarded, not applied over
 * what the user has done since.
 */
class PendingRestore(
    internal val root: File,
    internal val database: File,
    internal val settings: File,
    internal val agentHome: File,
    internal val workspace: File,
) {
    val staged: File get() = File(root, "staged")
    private val confirmed: File get() = File(root, CONFIRMED)

    /** Marks the stage as confirmed; it's applied on the next start. */
    fun markReady() {
        confirmed.writeText(System.currentTimeMillis().toString())
    }

    /** Throws away a stage that wasn't confirmed. */
    fun discard() {
        root.deleteRecursively()
    }

    /** Applies a confirmed stage. Returns true if it restored something. */
    fun apply(): Boolean {
        if (!root.exists()) return false
        // A legacy `READY` stage has no CONFIRMED marker, so it's discarded here too.
        if (!confirmed.exists()) {
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
        private const val CONFIRMED = "CONFIRMED"

        /**
         * The real locations, matching `DatabaseModule`, `DataStoreModule` and `DataModule`. Called
         * from `Application.attachBaseContext`, so it may only use the base context's own directories:
         * `applicationContext` is still null there.
         */
        fun forApp(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "restore"),
            database = context.getDatabasePath(DatabaseBackup.NAME),
            settings = DataStoreFiles.file(context, DataStoreFiles.SETTINGS),
            agentHome = File(context.filesDir, AGENT_HOME_DIR),
            workspace = File(context.filesDir, WORKSPACE_DIR),
        )

        const val AGENT_HOME_DIR = "agent-home"
        const val WORKSPACE_DIR = "workspace"
    }
}
