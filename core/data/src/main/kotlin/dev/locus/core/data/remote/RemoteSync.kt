package dev.locus.core.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What both sides agreed on after the last sync, per file: the content hash, the phone's size and
 * modification time, and the machine's. A file whose stamps still match wasn't changed on that side;
 * the hash tells a real change from a touch. This is the "last agreed state" a three-way sync compares
 * against (Mutagen's term), kept on the phone only.
 */
@Serializable
data class SyncState(
    val hostId: String = "",
    val folder: String = "",
    val files: Map<String, Synced> = emptyMap(),
) {
    @Serializable
    data class Synced(val hash: String, val size: Long, val mtime: Long, val remoteSize: Long, val remoteMtime: Long)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(file: File): SyncState? = runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()

        fun write(file: File, state: SyncState) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), state))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

/** A file in the project folder on the phone. */
data class LocalFile(val file: File, val size: Long, val mtime: Long)

/** The project folder on the phone: what would be synced, and what is left out and why. */
data class LocalScan(
    val files: Map<String, LocalFile>,
    /** Secret files and folders, never copied. */
    val secrets: List<String>,
    /** Folders that stay on the phone (git, dependencies, builds, `.locusignore`). */
    val stays: List<String>,
    /** Files over [RemoteSync.MAX_FILE] bytes, left out. */
    val tooLarge: List<String>,
)

/**
 * The project folder on the machine, as listed over SFTP; null [files] when the folder doesn't exist yet.
 * [refused] are entries whose names can't be a file in a folder (`..`, a `/`, a NUL), never copied.
 */
data class RemoteScan(val files: Map<String, RemoteStat>?, val refused: List<String> = emptyList())

/** One change sync-back made on the phone, for change tracking. */
data class PulledChange(val path: String, val kind: Kind) {
    enum class Kind { CREATED, CHANGED, DELETED }
}

/**
 * What one sync did, for the line in the tool result. [refused] are files left alone because their
 * name or place on the phone isn't safe: a `..` or absolute name, or a folder on the way that is a link.
 */
data class SyncReport(
    val uploaded: Int = 0,
    val removedRemote: Int = 0,
    val pulled: List<PulledChange> = emptyList(),
    val skippedSecrets: Int = 0,
    val conflicts: List<String> = emptyList(),
    val refused: List<String> = emptyList(),
)

/**
 * Incremental two-way sync of one project folder over SFTP (docs/design/remote-execution.md):
 * - **up**, before each command: files changed on the phone since the last agreed state are uploaded,
 *   and files deleted on the phone are deleted on the machine (only ones Locus had copied there).
 * - **down**, after it: files the command created, changed or deleted on the machine come back.
 *
 * A file changed on both sides (rare: the phone's side is synced right before each command) keeps the
 * machine's version on the phone, and the phone's goes to the workspace trash first, so nothing is lost.
 *
 * Nothing is read or written outside the project folder on the phone ([local]): names from the machine
 * are checked before any file is hashed, written, backed up or deleted, and links are never followed,
 * neither a link to a file nor a folder on the way that is one.
 */
class RemoteSync(
    private val fs: RemoteFs,
    localRoot: File,
    private val remoteRoot: String,
    private val rules: SyncRules,
    private val secrets: Collection<String>,
    /**
     * Locus's private storage, outside the workspace, where each [down] makes its own folder for downloads
     * to wait in before they're checked and put in place.
     */
    private val tmpParent: File,
) {
    /** The project folder, links resolved: what every file must stay inside. */
    private val root: File = localRoot.canonicalFile

    /**
     * [path] (relative, `/`-separated) in the project folder on the phone, or null when it isn't safe to
     * read or write there: empty, absolute, with a NUL, a `.` or `..` part, a part that is a link (the sync
     * never follows links, as [scanLocal] doesn't), or a part that is a file where a folder should be.
     * Checked right before each operation; the file itself may not exist yet.
     */
    fun local(path: String): File? {
        if (!isSafeRelative(path)) return null
        var at = root
        for (part in path.split('/')) {
            at = File(at, part)
            val p = at.toPath()
            if (Files.isSymbolicLink(p)) return null
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) break
            if (at.path != File(root, path).path && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return null
        }
        val file = File(root, path)
        // Belt and braces: whatever exists of it must resolve inside the project.
        if (!file.canonicalPath.startsWith(root.path + File.separator)) return null
        return file
    }

    /** Opens [file] for reading without following a link in its place (O_NOFOLLOW). */
    private fun open(file: File): InputStream = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    fun scanLocal(): LocalScan {
        val files = linkedMapOf<String, LocalFile>()
        val secret = mutableListOf<String>()
        val stays = mutableListOf<String>()
        val large = mutableListOf<String>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                // Links aren't followed: one could point outside the project folder.
                if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
                val isDir = child.isDirectory
                when (rules.skip(path, isDir)) {
                    SyncRules.Skip.SECRET -> { secret += path; continue }
                    SyncRules.Skip.STAYS -> { stays += path; continue }
                    null -> Unit
                }
                if (isDir) {
                    walk(child, path)
                } else if (child.isFile) {
                    if (child.length() > MAX_FILE) large += path
                    else files[path] = LocalFile(child, child.length(), child.lastModified())
                }
            }
        }
        walk(root, "")
        return LocalScan(files, secret, stays, large)
    }

    /**
     * Lists the project folder on the machine level by level, several folders at once: a deep tree costs
     * a round trip per level rather than per folder.
     */
    suspend fun scanRemote(): RemoteScan {
        val top = fs.list(remoteRoot) ?: return RemoteScan(null)
        val files = linkedMapOf<String, RemoteStat>()
        val refused = mutableListOf<String>()
        // (folder on the machine, its path in the project with a trailing '/', its entries)
        var level = listOf(Triple(remoteRoot, "", top))
        while (level.isNotEmpty()) {
            val folders = mutableListOf<Pair<String, String>>()
            for ((dir, prefix, entries) in level) {
                for (e in entries.sortedBy { it.name }) {
                    // A name is one path part: a server sending "..", "a/b" or a NUL is refused, not followed.
                    if (!isSafeName(e.name)) {
                        refused += prefix + e.name
                        continue
                    }
                    // An upload's temporary file or backup (SftpFs.upload), not the project's.
                    if (e.name.startsWith(SftpFs.PART_PREFIX) || e.name.startsWith(SftpFs.OLD_PREFIX)) continue
                    val path = prefix + e.name
                    when (e.kind) {
                        RemoteEntry.Kind.DIR -> if (rules.skip(path, true) == null) folders += "$dir/${e.name}" to "$path/"
                        RemoteEntry.Kind.FILE -> if (rules.skip(path, false) == null && e.stat.size <= MAX_FILE) files[path] = e.stat
                        RemoteEntry.Kind.OTHER -> Unit
                    }
                }
            }
            val listed = java.util.concurrent.ConcurrentHashMap<String, List<RemoteEntry>>()
            SftpTransfer.forEachConcurrent(folders, SftpTransfer.PARALLEL_REQUESTS) { (dir, _) ->
                fs.list(dir)?.let { listed[dir] = it }
            }
            level = folders.mapNotNull { (dir, prefix) -> listed[dir]?.let { Triple(dir, prefix, it) } }
        }
        return RemoteScan(files, refused)
    }

    /** Uploads what changed on the phone since [state]; returns the new state and what was done. */
    suspend fun up(state: SyncState, scan: LocalScan = scanLocal(), onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Pair<SyncState, SyncReport> {
        val files = state.files.toMutableMap()
        val refused = mutableListOf<String>()
        val pending = scan.files.mapNotNull { (path, local) ->
            val known = files[path]
            if (local(path) == null) {
                refused += path
                return@mapNotNull null
            }
            val hash = if (known != null && known.size == local.size && known.mtime == local.mtime) known.hash else open(local.file).use(::sha256)
            if (known != null && known.hash == hash) {
                // Same content, only touched: remember the new stamp so it isn't hashed again.
                if (known.mtime != local.mtime) files[path] = known.copy(mtime = local.mtime, size = local.size)
                null
            } else {
                Triple(path, local, hash)
            }
        }
        var skipped = 0
        // Checked again right before it's read: a file swapped for a link since the scan isn't followed.
        val sends = pending.mapNotNull { (path, local, hash) ->
            val file = local(path)
            when {
                file == null -> { refused += path; null }
                open(file).use { SyncRules.containsSecret(it, secrets) } -> { skipped++; null }
                else -> Upload(path, local, hash, file)
            }
        }
        val folders = sends.map { "$remoteRoot/${it.path}".substringBeforeLast('/') }.distinct()
        SftpTransfer.forEachConcurrent(folders, SftpTransfer.PARALLEL_REQUESTS) { fs.mkdirs(it) }
        // Several files at once over the one connection: a project of small files is bound by round
        // trips (open, write, close, setstat, rename, stat each), not by bandwidth.
        val done = java.util.concurrent.ConcurrentHashMap<String, SyncState.Synced>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        onProgress(0, pending.size)
        SftpTransfer.forEachConcurrent(sends, PARALLEL_FILES) { u ->
            val stat = fs.upload("$remoteRoot/${u.path}", { open(u.file) }, u.file.canExecute())
            done[u.path] = SyncState.Synced(u.hash, u.local.size, u.local.mtime, stat.size, stat.mtime)
            onProgress(count.incrementAndGet(), pending.size)
        }
        files.putAll(done)
        val uploaded = done.size
        onProgress(pending.size, pending.size)
        // Deleted on the phone: delete the copy, unless it changed on the machine since (then it comes back down).
        var removed = 0
        for (path in files.keys.toList()) {
            // A name an older version agreed on without checking: forget it rather than delete "$remoteRoot/../…".
            if (!isSafeRelative(path)) {
                files.remove(path)
                continue
            }
            if (path in scan.files || rules.skip(path, false) != null) continue
            if (scan.tooLarge.contains(path)) continue
            val known = files.getValue(path)
            val remote = fs.stat("$remoteRoot/$path")
            if (remote == null || (remote.size == known.remoteSize && remote.mtime == known.remoteMtime)) {
                if (remote != null) fs.remove("$remoteRoot/$path")
                files.remove(path)
                removed++
            }
        }
        return state.copy(files = files) to SyncReport(uploaded = uploaded, removedRemote = removed, skippedSecrets = skipped + scan.secrets.size, refused = refused)
    }

    /**
     * Brings back what changed on the machine since [state]. [apply] makes each change on the phone
     * (through the workspace, so the old version goes to the trash and the change is tracked); it gets
     * the file on the phone, already checked to be inside the project ([local]), and the downloaded copy,
     * or null for a delete. Files over [MAX_FILE] stay on the machine; files whose names or places aren't
     * safe are left alone and named in [SyncReport.refused].
     */
    suspend fun down(state: SyncState, apply: (path: String, target: File, downloaded: File?, kind: PulledChange.Kind) -> Unit): Pair<SyncState, SyncReport> {
        val scan = scanRemote()
        val remote = scan.files ?: return state.copy(files = emptyMap()) to SyncReport()
        val files = state.files.toMutableMap()
        val pulled = mutableListOf<PulledChange>()
        val conflicts = mutableListOf<String>()
        val refused = scan.refused.toMutableList()
        // This sync's own folder: another project syncing at the same time has its own.
        val session = newSession()
        try {
            val changed = remote.filter { (path, stat) ->
                val known = files[path]
                if (known != null && known.remoteSize == stat.size && known.remoteMtime == stat.mtime) return@filter false
                if (local(path) == null) {
                    refused += path
                    return@filter false
                }
                true
            }
            // Downloaded several at once, into this sync's own folder; then checked and applied one by one.
            val fetched = java.util.concurrent.ConcurrentHashMap<String, File>()
            SftpTransfer.forEachConcurrent(changed.keys.toList(), PARALLEL_FILES) { path ->
                val tmp = File.createTempFile("pull", ".part", session)
                tmp.outputStream().buffered(256 * 1024).use { out -> fs.download("$remoteRoot/$path", out, MAX_FILE) }
                fetched[path] = tmp
            }
            for ((path, stat) in changed) {
                val known = files[path]
                val tmp = fetched.getValue(path)
                val hash = sha256(tmp)
                if (known != null && known.hash == hash) {
                    files[path] = known.copy(remoteSize = stat.size, remoteMtime = stat.mtime)
                    tmp.delete()
                    continue
                }
                // Checked again after the download, right before the phone's copy is read or replaced.
                val local = local(path)
                if (local == null) {
                    refused += path
                    tmp.delete()
                    continue
                }
                val localHash = if (local.isFile) open(local).use(::sha256) else null
                if (localHash == hash) {
                    files[path] = SyncState.Synced(hash, local.length(), local.lastModified(), stat.size, stat.mtime)
                    tmp.delete()
                    continue
                }
                if (known != null && localHash != null && localHash != known.hash) conflicts += path
                val kind = if (local.exists()) PulledChange.Kind.CHANGED else PulledChange.Kind.CREATED
                apply(path, local, tmp, kind)
                tmp.delete()
                if (stat.executable && local.isFile) local.setExecutable(true, false)
                files[path] = SyncState.Synced(hash, local.length(), local.lastModified(), stat.size, stat.mtime)
                pulled += PulledChange(path, kind)
            }
            // Deleted on the machine: delete on the phone too, unless the phone's copy changed since.
            for ((path, known) in files.toMap()) {
                if (path in remote) continue
                val local = local(path)
                if (local == null) {
                    refused += path
                } else if (local.isFile && open(local).use(::sha256) == known.hash) {
                    apply(path, local, null, PulledChange.Kind.DELETED)
                    pulled += PulledChange(path, PulledChange.Kind.DELETED)
                }
                files.remove(path)
            }
        } finally {
            session.deleteRecursively()
        }
        return state.copy(files = files) to SyncReport(pulled = pulled, conflicts = conflicts, refused = refused.distinct())
    }

    /**
     * A new folder for one sync's downloads under [tmpParent]. Folders left by a sync that never finished
     * (the process died) are removed once they're older than [STALE_SESSION], and so is the single
     * shared folder older versions used.
     */
    private fun newSession(): File {
        tmpParent.mkdirs()
        val cutoff = System.currentTimeMillis() - STALE_SESSION
        tmpParent.listFiles()?.forEach { f ->
            val ours = f.isDirectory && (f.name == LEGACY_PULL || f.name.startsWith(SESSION_PREFIX))
            if (ours && (f.name == LEGACY_PULL || f.lastModified() < cutoff)) f.deleteRecursively()
        }
        return Files.createTempDirectory(tmpParent.toPath(), SESSION_PREFIX).toFile()
    }

    private class Upload(val path: String, val local: LocalFile, val hash: String, val file: File)

    companion object {
        /** Files synced at once over the one connection, each with its own requests in flight. */
        const val PARALLEL_FILES = 4

        /** Files larger than this aren't synced (as sftp_upload and sftp_download, 100 MB). */
        const val MAX_FILE = 100L * 1024 * 1024

        private const val SESSION_PREFIX = "pull-"
        private const val LEGACY_PULL = "pull"

        /** A download folder older than this was left by a sync that died: none takes that long. */
        private val STALE_SESSION = TimeUnit.HOURS.toMillis(6)

        /** A relative `/`-separated path whose every part is a plain name ([isSafeName]). */
        fun isSafeRelative(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') && path.split('/').all(::isSafeName)

        /** One path part: not empty, `.` or `..`, and without `/` or NUL. */
        fun isSafeName(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

        fun sha256(file: File): String = file.inputStream().use(::sha256)

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
