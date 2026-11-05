package dev.locus.core.data.diagnostics

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.database.DatabaseBackup
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.model.FileTrees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/** A part of Locus's storage the Storage screen lists, in the order shown. */
enum class StorageArea(val title: String, val note: String) {
    Database("Chats and history", "The database: chats, messages, activity and usage."),
    Workspace("Workspace", "Files you and the agent keep."),
    Trash("Workspace trash", "Deleted and overwritten files, kept so changes can be undone."),
    Linux("Linux", "Alpine Linux, its packages and its caches."),
    Models("On-device models", "LiteRT and GGUF models, and unfinished downloads."),
    Browser("Browser data", "The agent browser's cookies, sign-ins and page cache."),
    Caches("Caches", "Temporary files Locus makes again when needed."),
    Logs("Logs", "Diagnostics entries on this phone."),
    AgentHome("Memory, skills and settings", "What the agent learned, your skills and connectors, settings and saved keys."),
}

/** One area's size; [bytes] is null when it couldn't be measured. */
data class StorageItem(val area: StorageArea, val bytes: Long?)

/** What [StorageUsage.measure] found. [freeBytes]/[totalBytes] are the phone storage Locus's files live on, 0 when unknown. */
data class StorageReport(val items: List<StorageItem>, val freeBytes: Long, val totalBytes: Long, val takenAt: Long) {
    val locusBytes: Long get() = items.sumOf { it.bytes ?: 0 }
    fun bytes(area: StorageArea): Long? = items.firstOrNull { it.area == area }?.bytes
}

/**
 * Settings → About & privacy → Diagnostics → Storage: how much space each part of Locus uses, and the
 * safe clean-ups (docs/design/diagnostics.md, "Storage"). Sizes are measured off the main thread with
 * [FileTrees.size], which never follows a link: the workspace and the Linux rootfs are written by
 * programs Locus doesn't control (docs/design/linux-isolation.md).
 */
@Singleton
class StorageUsage internal constructor(
    private val areas: () -> Map<StorageArea, List<File>>,
    private val cacheDir: File,
    private val workspace: Workspace,
    private val phone: () -> Pair<Long, Long>?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context, workspace: Workspace, linux: LinuxEnvironment) : this(
        areas = { StorageMeter.areas(context, workspace, linux) },
        cacheDir = context.cacheDir,
        workspace = workspace,
        phone = { runCatching { StatFs(context.filesDir.path).let { it.availableBytes to it.totalBytes } }.getOrNull() },
    )

    /** Every area's size. Cancelling the caller stops the walk between files. */
    suspend fun measure(): StorageReport = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val items = areas().map { (area, roots) -> StorageItem(area, StorageMeter.size(roots, context)) }
        val (free, total) = phone() ?: (0L to 0L)
        StorageReport(items, free, total, clock())
    }

    /** Clears the caches the user confirmed; returns the bytes freed. See [StorageMeter.clearCaches]. */
    suspend fun clearCaches(): Long = withContext(Dispatchers.IO) { StorageMeter.clearCaches(cacheDir, clock()) }

    /** Empties the workspace trash the user confirmed; returns how many entries went. */
    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) { workspace.emptyTrash() }
}

/** Where each area lives and how it's measured and cleaned; kept apart from Android's APIs so it's tested on temp folders. */
internal object StorageMeter {
    /** Chromium's own folders in the cache: counted as browser data and left to the WebView. */
    private val BROWSER_CACHES = setOf("WebView", "org.chromium.android_webview")

    /** A cache entry touched this recently may be in use (a backup's scratch copy, a photo being taken): kept. */
    const val IN_USE_MS = 10 * 60_000L

    fun areas(context: Context, workspace: Workspace, linux: LinuxEnvironment): Map<StorageArea, List<File>> {
        val db = context.getDatabasePath(DatabaseBackup.NAME)
        val cache = context.cacheDir
        return linkedMapOf(
            StorageArea.Database to listOf(db, File(db.path + "-wal"), File(db.path + "-shm")),
            StorageArea.Workspace to listOf(workspace.base),
            StorageArea.Trash to listOf(workspace.trashFolder),
            StorageArea.Linux to listOf(linux.storageDir),
            StorageArea.Models to modelRoots(context.filesDir),
            StorageArea.Browser to listOf(File(context.dataDir, "app_webview")) + BROWSER_CACHES.map { File(cache, it) },
            StorageArea.Caches to cacheEntries(cache),
            StorageArea.Logs to listOf(File(context.noBackupFilesDir, "diagnostics")),
            StorageArea.AgentHome to listOf(
                File(context.filesDir, "agent-home"),
                File(context.noBackupFilesDir, "memory-index"),
                File(context.filesDir, "datastore"),
            ),
        )
    }

    /**
     * Where on-device models live, for both engines: LiteRT-LM's `.litertlm` files (OnDeviceModels),
     * GGUF files with their index (GgufModels) and unfinished GGUF downloads (GgufDownloads).
     */
    fun modelRoots(filesDir: File): List<File> = listOf(File(filesDir, "models"), File(filesDir, "gguf"), File(filesDir, "gguf-downloads"))

    /** What "Caches" counts: everything in the cache folder but the browser's own. */
    fun cacheEntries(cache: File): List<File> = cache.listFiles().orEmpty().filter { it.name !in BROWSER_CACHES }

    /** The size of [roots] together, never following a link; null when a walk failed. */
    fun size(roots: List<File>, context: CoroutineContext? = null): Long? = runCatching {
        roots.sumOf { root -> FileTrees.size(root) { context?.ensureActive() } }
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }

    /**
     * Deletes what's in [cache] except the browser's folders and anything touched in the last
     * [IN_USE_MS] (it may be in use). Never follows a link. Returns the bytes freed.
     */
    fun clearCaches(cache: File, now: Long): Long = cacheEntries(cache)
        .filter { now - it.lastModified() > IN_USE_MS }
        .sumOf { entry ->
            val before = FileTrees.size(entry)
            FileTrees.delete(entry)
            // A file that couldn't be deleted is still there: only what went counts.
            (before - FileTrees.size(entry)).coerceAtLeast(0)
        }
}
