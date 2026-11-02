package dev.locus.core.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

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

/** The project folder on the machine, as listed over SFTP; null [files] when the folder doesn't exist yet. */
data class RemoteScan(val files: Map<String, RemoteStat>?)

/** One change sync-back made on the phone, for change tracking. */
data class PulledChange(val path: String, val kind: Kind) {
    enum class Kind { CREATED, CHANGED, DELETED }
}

/** What one sync did, for the line in the tool result. */
data class SyncReport(val uploaded: Int = 0, val removedRemote: Int = 0, val pulled: List<PulledChange> = emptyList(), val skippedSecrets: Int = 0, val conflicts: List<String> = emptyList())

/**
 * Incremental two-way sync of one project folder over SFTP (docs/design/remote-execution.md):
 * - **up**, before each command: files changed on the phone since the last agreed state are uploaded,
 *   and files deleted on the phone are deleted on the machine (only ones Locus had copied there).
 * - **down**, after it: files the command created, changed or deleted on the machine come back.
 *
 * A file changed on both sides (rare: the phone's side is synced right before each command) keeps the
 * machine's version on the phone, and the phone's goes to the workspace trash first, so nothing is lost.
 */
class RemoteSync(
    private val fs: RemoteFs,
    private val localRoot: File,
    private val remoteRoot: String,
    private val rules: SyncRules,
    private val secrets: Collection<String>,
    /** Where downloads wait before they're checked and put in place: Locus's private storage, outside the workspace. */
    private val tmpDir: File,
) {
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
        walk(localRoot, "")
        return LocalScan(files, secret, stays, large)
    }

    suspend fun scanRemote(): RemoteScan {
        if (fs.list(remoteRoot) == null) return RemoteScan(null)
        val files = linkedMapOf<String, RemoteStat>()
        suspend fun walk(dir: String, prefix: String) {
            val entries = fs.list(dir) ?: return
            for (e in entries.sortedBy { it.name }) {
                val path = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
                when (e.kind) {
                    RemoteEntry.Kind.DIR -> if (rules.skip(path, true) == null) walk("$dir/${e.name}", path)
                    RemoteEntry.Kind.FILE -> if (rules.skip(path, false) == null && e.stat.size <= MAX_FILE) files[path] = e.stat
                    RemoteEntry.Kind.OTHER -> Unit
                }
            }
        }
        walk(remoteRoot, "")
        return RemoteScan(files)
    }

    /** Uploads what changed on the phone since [state]; returns the new state and what was done. */
    suspend fun up(state: SyncState, scan: LocalScan = scanLocal(), onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Pair<SyncState, SyncReport> {
        val files = state.files.toMutableMap()
        val pending = scan.files.mapNotNull { (path, local) ->
            val known = files[path]
            val hash = if (known != null && known.size == local.size && known.mtime == local.mtime) known.hash else sha256(local.file)
            if (known != null && known.hash == hash) {
                // Same content, only touched: remember the new stamp so it isn't hashed again.
                if (known.mtime != local.mtime) files[path] = known.copy(mtime = local.mtime, size = local.size)
                null
            } else {
                Triple(path, local, hash)
            }
        }
        var uploaded = 0
        var skipped = 0
        val madeDirs = HashSet<String>()
        pending.forEachIndexed { i, (path, local, hash) ->
            onProgress(i, pending.size)
            val bytes = local.file.readBytes()
            if (SyncRules.containsSecret(bytes, secrets)) {
                skipped++
                return@forEachIndexed
            }
            val target = "$remoteRoot/$path"
            val parent = target.substringBeforeLast('/')
            if (madeDirs.add(parent)) fs.mkdirs(parent)
            val stat = fs.upload(target, local.file, local.file.canExecute())
            files[path] = SyncState.Synced(hash, local.size, local.mtime, stat.size, stat.mtime)
            uploaded++
        }
        onProgress(pending.size, pending.size)
        // Deleted on the phone: delete the copy, unless it changed on the machine since (then it comes back down).
        var removed = 0
        for (path in files.keys.toList()) {
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
        return state.copy(files = files) to SyncReport(uploaded = uploaded, removedRemote = removed, skippedSecrets = skipped + scan.secrets.size)
    }

    /**
     * Brings back what changed on the machine since [state]. [apply] makes each change on the phone
     * (through the workspace, so the old version goes to the trash and the change is tracked); it gets
     * the downloaded copy, or null for a delete. Files over [MAX_FILE] stay on the machine.
     */
    suspend fun down(state: SyncState, apply: (path: String, downloaded: File?, kind: PulledChange.Kind) -> Unit): Pair<SyncState, SyncReport> {
        val remote = scanRemote().files ?: return state.copy(files = emptyMap()) to SyncReport()
        val files = state.files.toMutableMap()
        val pulled = mutableListOf<PulledChange>()
        val conflicts = mutableListOf<String>()
        tmpDir.mkdirs()
        try {
            for ((path, stat) in remote) {
                val known = files[path]
                if (known != null && known.remoteSize == stat.size && known.remoteMtime == stat.mtime) continue
                val tmp = File.createTempFile("pull", ".part", tmpDir)
                tmp.outputStream().buffered().use { out -> fs.download("$remoteRoot/$path", out, MAX_FILE) }
                val hash = sha256(tmp)
                val local = File(localRoot, path)
                if (known != null && known.hash == hash) {
                    files[path] = known.copy(remoteSize = stat.size, remoteMtime = stat.mtime)
                    tmp.delete()
                    continue
                }
                if (local.isFile && sha256(local) == hash) {
                    files[path] = SyncState.Synced(hash, local.length(), local.lastModified(), stat.size, stat.mtime)
                    tmp.delete()
                    continue
                }
                if (known != null && local.isFile && sha256(local) != known.hash) conflicts += path
                val kind = if (local.exists()) PulledChange.Kind.CHANGED else PulledChange.Kind.CREATED
                apply(path, tmp, kind)
                tmp.delete()
                if (stat.executable && local.isFile) local.setExecutable(true, false)
                files[path] = SyncState.Synced(hash, local.length(), local.lastModified(), stat.size, stat.mtime)
                pulled += PulledChange(path, kind)
            }
            // Deleted on the machine: delete on the phone too, unless the phone's copy changed since.
            for ((path, known) in files.toMap()) {
                if (path in remote) continue
                val local = File(localRoot, path)
                if (local.isFile && sha256(local) == known.hash) {
                    apply(path, null, PulledChange.Kind.DELETED)
                    pulled += PulledChange(path, PulledChange.Kind.DELETED)
                }
                files.remove(path)
            }
        } finally {
            tmpDir.listFiles()?.forEach { it.delete() }
        }
        return state.copy(files = files) to SyncReport(pulled = pulled, conflicts = conflicts)
    }

    companion object {
        /** Files larger than this aren't synced (as sftp_upload and sftp_download, 100 MB). */
        const val MAX_FILE = 100L * 1024 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
