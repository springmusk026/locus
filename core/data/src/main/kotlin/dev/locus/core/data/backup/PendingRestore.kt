package dev.locus.core.data.backup

import android.content.Context
import dev.locus.core.database.DatabaseBackup
import dev.locus.core.datastore.DataStoreFiles
import java.io.File
import java.io.IOException

/**
 * Swaps a verified, staged backup into place while nothing has the files open: at the very start of
 * the next process, before Hilt builds Room, DataStore or the workspace (call it from
 * `Application.attachBaseContext`, which runs before any ContentProvider).
 *
 * The swap is all-or-nothing (docs/design/backup.md, "Applying a restore"). The staging folder
 * ([root]) holds `staged/` (from [BackupArchive.extract]) and these markers, read in this order:
 *
 * - `COMMITTED`: every staged part is in place. Only the old copies are left to delete.
 * - `JOURNAL`: targets may have moved. Without `COMMITTED` it's rolled back: the old copies go back,
 *   and anything already moved in is removed. It lists each target and whether it existed, so a
 *   target that's present now but wasn't before is known to be from the backup.
 * - `STARTED` (renamed from `CONFIRMED` as the first step): an attempt began but nothing was touched;
 *   reported as failed, not tried again.
 * - `CONFIRMED`: the user confirmed; the swap runs.
 * - None of them: a cancelled or interrupted preview, deleted.
 *
 * A failed or interrupted swap is never tried again by itself. It's rolled back, the stage is
 * deleted, and [failure] records it for the app to tell the user once.
 *
 * 0.1.2 and 0.1.3 wrote a `READY` marker instead, but could never apply it ([forApp] threw in
 * `attachBaseContext`). Such a stage may be weeks old by now, so it's discarded, not applied over
 * what the user has done since.
 */
class PendingRestore internal constructor(
    internal val root: File,
    internal val database: File,
    internal val settings: File,
    internal val agentHome: File,
    internal val workspace: File,
    /** Where a failed restore is recorded; outside [root], so deleting the stage keeps it. */
    internal val failureFile: File = File(root.path + "-failed"),
    internal val fs: RestoreFs = RestoreFs(),
) {
    val staged: File get() = File(root, "staged")
    private val confirmed: File get() = File(root, CONFIRMED)
    private val started: File get() = File(root, STARTED)
    private val journal: File get() = File(root, JOURNAL)
    private val committed: File get() = File(root, COMMITTED)

    /** Every file or folder a restore may replace, by the name the journal uses. */
    private val targets: Map<String, File>
        get() = linkedMapOf(
            DB to database,
            // The old database's WAL, shared memory and rollback journal belong to it: left beside the
            // restored one, SQLite would replay them into it. They're moved aside with it, and put
            // back with it on a rollback.
            "$DB-wal" to File(database.path + "-wal"),
            "$DB-shm" to File(database.path + "-shm"),
            "$DB-journal" to File(database.path + "-journal"),
            SETTINGS to settings,
            AGENT_HOME to agentHome,
            WORKSPACE to workspace,
        )

    /** Marks the stage as confirmed; it's applied on the next start. */
    fun markReady() {
        confirmed.writeText(System.currentTimeMillis().toString())
    }

    /** Throws away a stage that wasn't confirmed. */
    fun discard() {
        root.deleteRecursively()
    }

    /**
     * Applies a confirmed stage, or finishes or rolls back a swap an earlier process left half-done.
     * Returns true if a backup is now in place. A failure is rolled back and recorded in [failure];
     * it doesn't throw for that.
     */
    fun apply(): Boolean {
        if (!root.exists()) return false
        return when {
            committed.exists() -> {
                finish()
                true
            }
            journal.exists() || started.exists() -> {
                // A process died mid-swap. Rolling back is the one unambiguous direction: a part may
                // have been half-copied, and the stage may be part-moved. Without a journal nothing
                // was touched yet; the attempt is still reported, not repeated.
                fail("Locus stopped while the backup was being put in place.")
                false
            }
            confirmed.exists() -> swap()
            else -> {
                // A cancelled preview, or a legacy `READY` stage.
                discard()
                false
            }
        }
    }

    /** The last restore that didn't finish, until [clearFailure]. */
    fun failure(): RestoreFailure? {
        if (!failureFile.isFile) return null
        val lines = runCatching { failureFile.readLines() }.getOrNull() ?: return null
        return RestoreFailure(
            at = lines.getOrNull(0)?.toLongOrNull() ?: 0,
            rolledBack = lines.getOrNull(1) != "partial",
            reason = lines.drop(2).joinToString("\n"),
        )
    }

    /** The user has seen the notice. */
    fun clearFailure() {
        failureFile.delete()
    }

    private fun swap(): Boolean {
        val moves = buildList {
            File(staged, BackupArchive.DATABASE_ENTRY).takeIf { it.isFile }?.let { add(it to DB) }
            File(staged, BackupArchive.SETTINGS_ENTRY).takeIf { it.isFile }?.let { add(it to SETTINGS) }
            File(staged, BackupArchive.AGENT_HOME).takeIf { it.isDirectory }?.let { add(it to AGENT_HOME) }
            File(staged, BackupArchive.WORKSPACE).takeIf { it.isDirectory }?.let { add(it to WORKSPACE) }
        }
        // The database's sidecar files go aside with it.
        val aside = moves.flatMap { (_, key) -> if (key == DB) targets.keys.filter { it.startsWith(DB) } else listOf(key) }
        try {
            // 1. One attempt only: if this process dies before the journal exists, the next start
            // finds STARTED, knows nothing was touched, and reports it instead of trying again.
            fs.rename(confirmed, started)
            // Leftovers of an older version's swap, never of ours: with no journal there's nothing to roll back.
            aside.forEach { key ->
                val target = targets.getValue(key)
                val old = old(target)
                if (old.exists()) {
                    if (target.exists()) fs.delete(old) else fs.rename(old, target)
                }
            }
            // 2. Record what exists now, before touching anything. From here on a crash rolls back.
            fs.write(journal, aside.joinToString("\n") { "$it ${if (targets.getValue(it).exists()) 1 else 0}" })
            // 3. Every target aside.
            aside.forEach { key ->
                val target = targets.getValue(key)
                if (target.exists()) fs.rename(target, old(target))
            }
            // 4. Every staged part in.
            moves.forEach { (from, key) ->
                val target = targets.getValue(key)
                target.parentFile?.mkdirs()
                try {
                    fs.rename(from, target)
                } catch (_: IOException) {
                    // Same filesystem in practice; copy as a fallback. A partial copy is removed by the rollback.
                    fs.copy(from, target)
                }
            }
            // 5. The point of no return.
            fs.write(committed, System.currentTimeMillis().toString())
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            return false
        }
        finish()
        return true
    }

    /** After `COMMITTED`: deletes the old copies and the stage. Repeated on the next start if it stops. */
    private fun finish() {
        clearFailure()
        // Only exceptions are caught here and below: an Error is treated like the process dying.
        try {
            targets.values.forEach { old(it).takeIf(File::exists)?.let(fs::delete) }
            // The journal goes before the stage, so a stop part-way never leaves a journal without COMMITTED.
            fs.delete(journal)
            fs.delete(root)
        } catch (_: Exception) {
            // The restore is in place; the next start deletes what's left.
        }
    }

    /**
     * Puts every target back as the journal recorded it, deletes the stage, and records the failure.
     * If something can't be put back, the journal is kept (and nothing else marks the stage as
     * confirmed), so the next start tries the rollback again rather than the restore.
     */
    private fun fail(reason: String) {
        val restored = try {
            rollBack()
        } catch (_: Exception) {
            false
        }
        if (restored) {
            discard()
        } else {
            // Keep only the journal; the stage and CONFIRMED go, so the restore itself is never retried.
            root.listFiles()?.filter { it.name != JOURNAL }?.forEach { it.deleteRecursively() }
        }
        try {
            failureFile.parentFile?.mkdirs()
            failureFile.writeText("${System.currentTimeMillis()}\n${if (restored) "rolled-back" else "partial"}\n$reason")
        } catch (_: IOException) {
            // Nothing to tell the user with, but the data itself is handled.
        }
    }

    /** Returns true if every target is as the journal found it. */
    private fun rollBack(): Boolean {
        // No journal yet: nothing was touched.
        if (!journal.exists()) return true
        val entries = journal.readLines().mapNotNull { line ->
            val (key, existed) = line.split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
            targets[key]?.let { it to (existed == "1") }
        }
        var ok = true
        entries.forEach { (target, existed) ->
            try {
                val old = old(target)
                when {
                    // Moved aside: whatever is at the target now came from the backup, perhaps half of it.
                    old.exists() -> {
                        if (target.exists()) fs.delete(target)
                        fs.rename(old, target)
                    }
                    // Wasn't there before: anything there now came from the backup.
                    !existed -> if (target.exists()) fs.delete(target)
                    // Existed and never moved aside: still the user's own.
                    else -> Unit
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        if (ok) fs.delete(journal)
        return ok
    }

    private fun old(target: File) = File(target.path + OLD_SUFFIX)

    companion object {
        private const val CONFIRMED = "CONFIRMED"
        private const val STARTED = "STARTED"
        private const val JOURNAL = "JOURNAL"
        private const val COMMITTED = "COMMITTED"
        internal const val OLD_SUFFIX = ".restore-old"

        private const val DB = "database"
        private const val SETTINGS = "settings"
        private const val AGENT_HOME = "agent-home"
        private const val WORKSPACE = "workspace"

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

/** A restore that didn't finish. [rolledBack] is false if some of the old data couldn't be put back yet. */
data class RestoreFailure(val at: Long, val rolledBack: Boolean, val reason: String)

/** The file operations a swap makes, each throwing on failure; tests replace them to fail or stop at any step. */
internal open class RestoreFs {
    open fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("Couldn't move ${from.name} to ${to.path}")
    }

    open fun copy(from: File, to: File) {
        if (!from.copyRecursively(to, overwrite = true)) throw IOException("Couldn't copy ${from.name}")
    }

    open fun delete(file: File) {
        if (!file.deleteRecursively()) throw IOException("Couldn't delete ${file.path}")
    }

    /** Writes [text] to a temporary file, then renames it over [file], so [file] is whole or absent. */
    open fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        rename(tmp, file)
    }
}
