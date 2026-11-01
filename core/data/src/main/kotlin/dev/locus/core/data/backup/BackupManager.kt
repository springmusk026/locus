package dev.locus.core.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.database.DatabaseBackup
import dev.locus.core.datastore.DataStoreFiles
import dev.locus.core.datastore.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** What the user chose to put in a backup. The vault is off unless they turn it on. */
data class BackupOptions(val includeWorkspace: Boolean = false, val includeVault: Boolean = false)

/** Sizes for the backup screen, before compression. */
data class BackupEstimate(val baseBytes: Long, val workspaceBytes: Long)

/**
 * Encrypted backup and restore of everything Locus keeps (docs/design/backup.md). Nothing leaves the
 * phone except through the file the user picks with the system picker, and only ciphertext is
 * written there. Restoring verifies the whole file first, then stages it in private storage, and
 * swaps it in on the next start ([PendingRestore]).
 */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: DatabaseBackup,
    private val secrets: SecretStore,
    private val runner: Provider<AgentRunner>,
    private val tasks: Provider<TaskRepository>,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val integrity: dev.locus.core.data.integrity.PolicyIntegrity,
    private val projects: dev.locus.core.database.ProjectDao,
) {
    private val pending = PendingRestore.forApp(context)
    private val agentHomeDir get() = File(context.filesDir, PendingRestore.AGENT_HOME_DIR)
    private val workspaceDir get() = File(context.filesDir, PendingRestore.WORKSPACE_DIR)
    private val settingsFile get() = DataStoreFiles.file(context, DataStoreFiles.SETTINGS)

    /** A verified backup waiting for the user to confirm; the vault stays in memory only. */
    private var verified: ExtractedBackup? = null

    suspend fun estimate(): BackupEstimate = withContext(Dispatchers.IO) {
        val db = context.getDatabasePath(DatabaseBackup.NAME)
        val dbBytes = listOf(db, File(db.path + "-wal")).sumOf { it.length() }
        BackupEstimate(
            baseBytes = dbBytes + settingsFile.length() + BackupArchive.sizeOf(agentHomeDir),
            workspaceBytes = BackupArchive.sizeOf(workspaceDir),
        )
    }

    /** Whether a run is active, updated as runs start and stop; for the restore screen. */
    val runActiveFlow: Flow<Boolean> get() = runner.get().states.map { runActive() }.distinctUntilChanged()

    /** True while a chat, helper or scheduled task is running; restoring must wait. */
    fun runActive(): Boolean {
        val r = runner.get()
        return r.activeCount > 0 || r.states.value.values.any { it.running }
    }

    /**
     * Writes an encrypted backup to [target]. [onProgress] gets bytes done and the total, both
     * before compression. Returns the manifest that was written.
     */
    suspend fun backup(target: Uri, password: CharArray, options: BackupOptions, onProgress: (Long, Long) -> Unit): BackupManifest =
        withContext(Dispatchers.IO) {
            val scratch = File(context.cacheDir, "backup-${System.nanoTime()}").apply { mkdirs() }
            try {
                val snapshot = File(scratch, DatabaseBackup.NAME)
                database.snapshotTo(snapshot)
                val sources = BackupSources(
                    databaseSnapshot = snapshot,
                    settingsFile = settingsFile.takeIf { it.isFile },
                    agentHome = agentHomeDir,
                    workspace = workspaceDir.takeIf { options.includeWorkspace },
                    vault = if (options.includeVault) secrets.exportAll() else null,
                )
                val plan = BackupArchive.plan(sources)
                val (versionName, versionCode) = appVersion()
                val manifest = BackupManifest(
                    appVersion = versionName,
                    appVersionCode = versionCode,
                    schemaVersion = BackupArchive.sqliteUserVersion(snapshot) ?: error("Snapshot isn't an SQLite file"),
                    createdAt = System.currentTimeMillis(),
                    sections = plan.sections,
                    contentVersion = BackupArchive.CONTENT_VERSION,
                    projects = projects.all().size,
                )
                val total = plan.totalBytes
                onProgress(0, total)
                val context = currentCoroutineContext()
                // "wt" truncates a file being overwritten (leftover bytes would fail authentication);
                // some providers only offer "w", which is fine for the new file the save dialog creates.
                val resolver = this@BackupManager.context.contentResolver
                val out = (runCatching { resolver.openOutputStream(target, "wt") }.getOrNull() ?: resolver.openOutputStream(target, "w"))
                    ?: throw FileNotFoundException("Can't write to the chosen file")
                out.buffered(256 * 1024).use { raw ->
                    BackupCrypto.encrypt(raw, password).use { encrypted ->
                        BackupArchive.write(encrypted, plan, manifest) { done ->
                            context.ensureActive()
                            onProgress(done, total)
                        }
                    }
                }
                onProgress(total, total)
                manifest
            } finally {
                scratch.deleteRecursively()
            }
        }

    /** Reads only the unencrypted header, to reject a file that isn't a backup before asking for a password. */
    suspend fun inspect(source: Uri) = withContext(Dispatchers.IO) {
        open(source).use { input -> BackupCrypto.open(input).header }
    }

    /**
     * Decrypts and checks the whole file, unpacking it into private staging. Nothing current is
     * touched. Throws [BackupException] (wrong password, damaged, newer). [onProgress] gets bytes
     * unpacked so far and the total from the manifest once it is known.
     */
    suspend fun verify(source: Uri, password: CharArray, onProgress: (Long, Long) -> Unit): BackupManifest = withContext(Dispatchers.IO) {
        discardVerified()
        val maxSchema = database.schemaVersion()
        val context = currentCoroutineContext()
        try {
            val extracted = open(source).use { input ->
                val opened = BackupCrypto.open(input.buffered(256 * 1024))
                val key = BackupCrypto.unlock(opened, password)
                BackupArchive.extract(BackupCrypto.decrypt(opened, key), pending.staged, maxSchema) { done, total ->
                    context.ensureActive()
                    onProgress(done, total)
                }
            }
            verified = extracted
            extracted.manifest
        } catch (e: Throwable) {
            pending.discard()
            throw e
        }
    }

    /** Forgets a verified backup the user didn't restore. */
    fun discardVerified() {
        verified = null
        pending.discard()
    }

    /**
     * Restores the verified backup on the next start, all of it or none of it, then restarts the app.
     * The vault, if the backup has it, is staged encrypted and swapped in with the rest (otherwise
     * this phone's keys are kept); nothing current changes before the restart. Refuses while a run is
     * active. Doesn't return on success.
     */
    suspend fun restoreAndRestart() {
        confirmRestore()
        restart()
    }

    /**
     * Everything [restoreAndRestart] does except the restart: the verified backup is confirmed and
     * applies at the next start of the process. Instrumentation tests call it directly, since a
     * restart would end the test; the orchestrator's next test is that next start (app/src/androidTest).
     */
    suspend fun confirmRestore() {
        val extracted = verified ?: error("Nothing verified to restore")
        if (runActive()) throw BackupException(BackupError.RunActive)
        try {
            extracted.vault?.let { secrets.writeVaultFile(it, pending.stagedVault) }
            withContext(Dispatchers.IO) {
                // The backup's permissions and connector approvals were sealed with the key of the
                // phone it came from; seal them with this phone's, or they'd be reset at the next start.
                integrity.sealRestored(File(pending.staged, BackupArchive.SETTINGS_ENTRY), File(pending.staged, BackupArchive.AGENT_HOME))
                // Scheduled tasks must not start while files are swapped. They're scheduled again at the
                // next start from whichever database is then in place, restored or (after a rollback) the old one.
                pending.owesReschedule()
                tasks.get().cancelAllScheduled()
                pending.markReady()
            }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { pending.stagedVault.delete() } }
            if (pending.rescheduleOwed()) rescheduleTasks()
            throw e
        }
    }

    /**
     * Called at startup when a restore cancelled scheduled tasks and they aren't back yet (whether the
     * restore was applied or rolled back): schedules the tasks in the database now in place. A failure
     * is recorded for [rescheduleFailures] and tried again at the next start.
     */
    fun rescheduleTasks() {
        scope.launch(Dispatchers.IO) {
            try {
                tasks.get().rescheduleAll()
                pending.rescheduled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
                runCatching { pending.rescheduleFailed(reason) }
                rescheduleFailure.value = reason
            }
        }
    }

    private val rescheduleFailure = MutableStateFlow<String?>(null)

    /**
     * Why scheduled tasks couldn't be set up again after a restore, for a notice: the recorded reason
     * from an earlier start, then any failure in this one. Null when there's nothing to say.
     */
    fun rescheduleFailures(): Flow<String?> = flow {
        emit(pending.rescheduleFailure())
        emitAll(rescheduleFailure.filterNotNull())
    }.flowOn(Dispatchers.IO)

    /** The user has seen the reschedule notice. The tasks are still tried again at the next start. */
    suspend fun dismissRescheduleFailure() = withContext(Dispatchers.IO) {
        rescheduleFailure.value = null
        pending.clearRescheduleFailure()
    }

    /** A restore that didn't finish on an earlier start (rolled back), for a one-time notice; null if none. */
    suspend fun restoreFailure(): RestoreFailure? = withContext(Dispatchers.IO) { pending.failure() }

    /** The user has seen the notice about a restore that didn't finish. */
    suspend fun dismissRestoreFailure() = withContext(Dispatchers.IO) { pending.clearFailure() }

    private fun open(uri: Uri) = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("Can't read the chosen file")

    private fun appVersion(): Pair<String, Long> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return (info.versionName ?: "?") to code
    }

    /**
     * Starts the launcher activity in a fresh task and ends this process; the new process applies
     * the staged restore before anything opens the files.
     */
    private fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val component = launch?.component
        if (component != null) context.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }
}
