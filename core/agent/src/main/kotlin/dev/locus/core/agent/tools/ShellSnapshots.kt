package dev.locus.core.agent.tools

import dev.locus.core.model.FileTrees
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/**
 * Snapshots of a project's folder taken before a shell command, so the files the command changed can be
 * tracked and undone like the file tools' changes (docs/design/shell-snapshots.md).
 *
 * The store lives in Locus's private storage, which Linux can't see:
 *
 * - `blobs/ab/cdef…`: each file's content once, named by its SHA-256 (content-addressed). A file that
 *   didn't change between two snapshots costs nothing the second time.
 * - `snapshots/<project>/<id>.json`: one [Manifest] per snapshot, every file's path, hash, mode and size.
 * - `tmp/`: blobs being written; renamed into `blobs/` once complete.
 *
 * Walks never follow a link: a link is neither entered nor recorded, and every file is opened with
 * `O_NOFOLLOW` ([LinkOption.NOFOLLOW_LINKS]), so a link a program planted can't pull anything from outside
 * the project into the store. Folders named in [EXCLUDED_DIRS] are skipped at any depth.
 *
 * Blocking file IO: callers run it off the main thread. Thread-safe: writes to the store hold one lock.
 */
class ShellSnapshots(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    val limits: Limits = Limits(),
    /** Monotonic time in nanoseconds, for the time budget; tests replace it to simulate a slow phone. */
    private val nanos: () -> Long = System::nanoTime,
) {
    /** The caps a snapshot and the store keep to. The defaults are the documented ones. */
    data class Limits(
        /** Files (after [EXCLUDED_DIRS]) above which a folder isn't snapshotted. */
        val maxFiles: Int = 5_000,
        /** Their total bytes above which a folder isn't snapshotted. */
        val maxBytes: Long = 100_000_000,
        /** How long one walk (listing and hashing) may take before it gives up. */
        val maxMillis: Long = 5_000,
        /** Changed files above which a command's changes aren't recorded (as the per-turn edit cap). */
        val maxChanges: Int = 500,
        /** Turns with snapshots each project keeps. */
        val keepTurns: Int = 20,
        /** Days a snapshot is kept. */
        val keepDays: Int = 14,
        /** Bytes of blobs the whole store keeps; the oldest snapshots go first past it. */
        val budgetBytes: Long = 500_000_000,
    )

    /** One file in a snapshot: its path relative to the project's folder (`/`-separated), SHA-256, POSIX mode bits and size. */
    @Serializable
    data class Entry(val p: String, val h: String, val m: Int, val s: Long)

    @Serializable
    data class Manifest(
        val v: Int = 1,
        val id: String,
        val projectId: String,
        val conversationId: String,
        val turnId: String,
        val callId: String,
        val createdAt: Long,
        val files: List<Entry>,
    )

    /** Why a snapshot (or recording its changes) was skipped, shown to the user under the turn. */
    enum class SkipReason {
        /** More files than [Limits.maxFiles]. */
        TOO_MANY_FILES,

        /** More bytes than [Limits.maxBytes]. */
        TOO_LARGE,

        /** The walk took longer than [Limits.maxMillis]. */
        TOO_SLOW,

        /** The command changed more files than [Limits.maxChanges]. */
        TOO_MANY_CHANGES,

        /** Another tracked command was running in the same project. */
        BUSY,

        /** Reading the folder or writing the store failed. */
        FAILED,
    }

    sealed interface Outcome {
        data class Taken(val manifest: Manifest) : Outcome
        data class Skipped(val reason: SkipReason) : Outcome
    }

    /** What a command did to one file. A rename is a [Kind.DELETED] and an [Kind.ADDED]. */
    data class Change(val path: String, val kind: Kind, val before: Entry?, val after: Entry?) {
        enum class Kind { ADDED, CHANGED, DELETED }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val blobs = File(root, "blobs")
    private val manifests = File(root, "snapshots")
    private val tmp = File(root, "tmp")
    private val lock = Any()

    /** Snapshots taken whose command hasn't finished: never dropped by [retain], their projects [BUSY]. */
    private val inFlight = HashMap<String, String>() // snapshot id → project key

    /**
     * Takes a snapshot of [folder] (a project's folder) for a command about to run: lists it, checks the
     * caps, then hashes each file and stores the blobs it doesn't have yet. Call [release] once the
     * command has finished and its changes are recorded.
     */
    fun take(projectId: String, folder: File, conversationId: String, turnId: String, callId: String): Outcome {
        val key = projectKey(projectId)
        synchronized(lock) {
            if (key in inFlight.values) return Outcome.Skipped(SkipReason.BUSY)
            val id = "${clock()}-${(0..0xffff).random().toString(16)}"
            inFlight[id] = key
            val outcome = try {
                when (val listed = list(folder)) {
                    is Listed.Skip -> Outcome.Skipped(listed.reason)
                    is Listed.Files -> when (val hashed = hash(folder, listed, store = true)) {
                        is Hashed.Skip -> Outcome.Skipped(hashed.reason)
                        is Hashed.Done -> {
                            val manifest = Manifest(1, id, projectId, conversationId, turnId, callId, clock(), hashed.entries)
                            writeManifest(key, manifest)
                            Outcome.Taken(manifest)
                        }
                    }
                }
            } catch (e: IOException) {
                Outcome.Skipped(SkipReason.FAILED)
            } catch (e: RuntimeException) {
                Outcome.Skipped(SkipReason.FAILED)
            }
            if (outcome !is Outcome.Taken) inFlight.remove(id)
            return outcome
        }
    }

    /** [folder]'s files now, hashed but not stored: the state after a command, to compare with its snapshot. */
    fun scan(folder: File): Pair<List<Entry>?, SkipReason?> = try {
        when (val listed = list(folder)) {
            is Listed.Skip -> null to listed.reason
            is Listed.Files -> when (val hashed = hash(folder, listed, store = false)) {
                is Hashed.Skip -> null to hashed.reason
                is Hashed.Done -> hashed.entries to null
            }
        }
    } catch (e: IOException) {
        null to SkipReason.FAILED
    } catch (e: RuntimeException) {
        null to SkipReason.FAILED
    }

    /** The command of snapshot [id] has finished: [retain] may drop it from now on. */
    fun release(id: String) {
        synchronized(lock) { inFlight.remove(id) }
    }

    /** The stored content with SHA-256 [hash], or null when the store no longer has it. Read it, never move it. */
    fun blob(hash: String): File? {
        if (!HASH.matches(hash)) return null
        val file = blobFile(hash)
        return file.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    }

    /**
     * Keeps each project's snapshots of its latest [Limits.keepTurns] turns that are younger than
     * [Limits.keepDays], then drops the oldest anywhere while the blobs are over [Limits.budgetBytes], and
     * deletes every blob no kept snapshot names. Snapshots still in flight always stay.
     */
    fun retain() {
        synchronized(lock) {
            val all = allManifests()
            val now = clock()
            val maxAge = limits.keepDays * 24L * 60 * 60 * 1000
            val drop = mutableSetOf<String>()
            all.groupBy { it.first }.forEach { (_, list) ->
                val newestFirst = list.sortedByDescending { it.second.createdAt }
                val keptTurns = newestFirst.map { it.second.turnId }.distinct().take(limits.keepTurns).toSet()
                newestFirst.forEach { (_, m, _) ->
                    if (m.id in inFlight) return@forEach
                    if (m.turnId !in keptTurns || now - m.createdAt > maxAge) drop += m.id
                }
            }
            var kept = all.filter { it.second.id !in drop }
            gc(kept)
            // Over the budget: the oldest snapshots go first, one at a time, until it fits.
            var total = blobBytes()
            for ((_, m, _) in kept.sortedBy { it.second.createdAt }) {
                if (total <= limits.budgetBytes) break
                if (m.id in inFlight) continue
                drop += m.id
                kept = kept.filter { it.second.id != m.id }
                total = gc(kept)
            }
            all.filter { it.second.id in drop }.forEach { (_, _, file) -> FileTrees.delete(file) }
            gc(kept)
            FileTrees.delete(tmp)
        }
    }

    /** Deletes every snapshot and blob (Settings → Storage → Clear). Snapshots in flight lose their blobs too. Returns the bytes freed. */
    fun clear(): Long = synchronized(lock) {
        val before = sizeBytes()
        FileTrees.delete(blobs)
        FileTrees.delete(manifests)
        FileTrees.delete(tmp)
        (before - sizeBytes()).coerceAtLeast(0)
    }

    /** Bytes the store takes on disk: blobs and manifests. */
    fun sizeBytes(): Long = FileTrees.size(root)

    /** Where the store lives, for Settings → Storage. */
    val folder: File get() = root

    /** Deletes the blobs no snapshot in [kept] names; returns the bytes of the blobs left. */
    private fun gc(kept: List<Triple<String, Manifest, File>>): Long {
        val live = HashSet<String>()
        kept.forEach { (_, m, _) -> m.files.forEach { live += it.h } }
        var total = 0L
        blobs.listFiles().orEmpty().forEach { dir ->
            dir.listFiles().orEmpty().forEach { f ->
                val hash = dir.name + f.name
                if (hash in live) total += f.length() else FileTrees.delete(f)
            }
            if (dir.listFiles().isNullOrEmpty()) FileTrees.delete(dir)
        }
        return total
    }

    private fun blobBytes(): Long = FileTrees.size(blobs)

    /** Every manifest on disk: (project key, manifest, its file). Unreadable ones are deleted. */
    private fun allManifests(): List<Triple<String, Manifest, File>> =
        manifests.listFiles().orEmpty().filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { f ->
                val m = runCatching { json.decodeFromString(Manifest.serializer(), Files.readAllBytes(f.toPath()).decodeToString()) }.getOrNull()
                if (m == null) {
                    FileTrees.delete(f)
                    null
                } else {
                    Triple(dir.name, m, f)
                }
            }
        }

    /** The manifests kept for [projectId], newest first (tests, and the design's checks). */
    fun manifests(projectId: String): List<Manifest> =
        allManifests().filter { it.first == projectKey(projectId) }.map { it.second }.sortedByDescending { it.createdAt }

    private fun writeManifest(key: String, manifest: Manifest) {
        if (!FileTrees.exists(root)) root.mkdirs()
        FileTrees.replaceFile(root, "snapshots/$key/${manifest.id}.json", json.encodeToString(Manifest.serializer(), manifest).toByteArray())
    }

    private sealed interface Listed {
        data class Skip(val reason: SkipReason) : Listed
        class Files(val files: List<Pair<String, Long>>) : Listed
    }

    private sealed interface Hashed {
        data class Skip(val reason: SkipReason) : Hashed
        class Done(val entries: List<Entry>) : Hashed
    }

    private class Stop(val reason: SkipReason) : RuntimeException(null, null, false, false)

    /** Lists [folder]'s regular files with their sizes, skipping links and [EXCLUDED_DIRS], within the caps. */
    private fun list(folder: File): Listed {
        val start = folder.toPath()
        if (Files.isSymbolicLink(start) || !Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            return if (FileTrees.exists(folder)) Listed.Skip(SkipReason.FAILED) else Listed.Files(emptyList())
        }
        val deadline = nanos() + limits.maxMillis * 1_000_000
        val files = ArrayList<Pair<String, Long>>()
        var bytes = 0L
        try {
            // Without FOLLOW_LINKS a link (even to a folder) is reported as a file with its own attributes: never entered.
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != start && dir.fileName.toString() in EXCLUDED_DIRS) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    files += start.relativize(file).joinToString("/") to attrs.size()
                    bytes += attrs.size()
                    when {
                        files.size > limits.maxFiles -> throw Stop(SkipReason.TOO_MANY_FILES)
                        bytes > limits.maxBytes -> throw Stop(SkipReason.TOO_LARGE)
                        nanos() > deadline -> throw Stop(SkipReason.TOO_SLOW)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (s: Stop) {
            return Listed.Skip(s.reason)
        }
        return Listed.Files(files)
    }

    /**
     * Hashes each listed file, streaming, opened with `O_NOFOLLOW`. With [store], a content the store doesn't
     * have yet is copied into it (hashed again as it's copied, so the blob always matches its name even if the
     * file changed in between). A file gone or turned into a link since the listing is left out.
     */
    private fun hash(folder: File, listed: Listed.Files, store: Boolean): Hashed {
        val deadline = nanos() + limits.maxMillis * 1_000_000
        val start = folder.toPath()
        val buffer = ByteArray(BUFFER)
        val entries = ArrayList<Entry>(listed.files.size)
        for ((rel, _) in listed.files) {
            if (nanos() > deadline) return Hashed.Skip(SkipReason.TOO_SLOW)
            val path = start.resolve(rel)
            val mode = modeOf(path) ?: continue
            val entry = try {
                var hash = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { sha256(it, buffer) }
                var size = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()
                if (store && blob(hash) == null) {
                    val copied = copyIn(path, buffer)
                    hash = copied.first
                    size = copied.second
                }
                Entry(rel, hash, mode, size)
            } catch (e: IOException) {
                // Gone, unreadable, or swapped for a link (O_NOFOLLOW refuses it): not part of the snapshot.
                null
            }
            entry?.let { entries += it }
        }
        return Hashed.Done(entries)
    }

    /** Copies [path] into the store, hashing as it goes; returns its hash and size. */
    private fun copyIn(path: Path, buffer: ByteArray): Pair<String, Long> {
        Files.createDirectories(tmp.toPath())
        val temp = File(tmp, "${nanos()}-${(0..0xffffff).random()}").toPath()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        size += n
                    }
                }
            }
            val hash = hex(digest.digest())
            val target = blobFile(hash).toPath()
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(temp)
            } else {
                Files.createDirectories(target.parent)
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            }
            return hash to size
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun blobFile(hash: String) = File(File(blobs, hash.substring(0, 2)), hash.substring(2))

    companion object {
        /**
         * Folders a snapshot skips at any depth: version control, Locus's own `.locus`, dependencies and build
         * output that a command makes again (docs/design/shell-snapshots.md, "What's left out").
         */
        val EXCLUDED_DIRS: Set<String> = setOf(
            ".git", ".hg", ".svn", ".locus",
            "node_modules", ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".tox",
            "build", "dist", "target", ".gradle", ".next", ".cache",
        )

        /** The tool whose calls are snapshotted, as change records name it. */
        const val TOOL = "run_command"

        private const val BUFFER = 64 * 1024
        private val HASH = Regex("[0-9a-f]{64}")
        private val REF = Regex("shell:([0-9a-f]{64}):([0-7]{1,4})")

        /**
         * The "before" reference a change record keeps for a file a command changed or deleted: its blob's hash
         * and mode. Stored where a file tool's change keeps its trash entry id, which can never look like this.
         */
        fun ref(entry: Entry): String = "shell:${entry.h}:${Integer.toOctalString(entry.m and 0xfff)}"

        /** The (hash, mode) in a [ref], or null when [value] isn't one. */
        fun parseRef(value: String?): Pair<String, Int>? {
            val match = REF.matchEntire(value ?: return null) ?: return null
            return match.groupValues[1] to match.groupValues[2].toInt(8)
        }

        /** What changed from [before] to [after], sorted by path. Content or mode differing makes a change. */
        fun diff(before: List<Entry>, after: List<Entry>): List<Change> {
            val old = before.associateBy { it.p }
            val new = after.associateBy { it.p }
            val out = ArrayList<Change>()
            for ((p, b) in old) {
                val a = new[p]
                when {
                    a == null -> out += Change(p, Change.Kind.DELETED, b, null)
                    a.h != b.h || a.m != b.m -> out += Change(p, Change.Kind.CHANGED, b, a)
                }
            }
            for ((p, a) in new) if (p !in old) out += Change(p, Change.Kind.ADDED, null, a)
            return out.sortedBy { it.path }
        }

        /** [path]'s POSIX permission bits, without following a link; null when it can't be read. 0 where POSIX isn't supported. */
        fun modeOf(path: Path): Int? = try {
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).fold(0) { acc, p -> acc or bit(p) }
        } catch (e: UnsupportedOperationException) {
            0
        } catch (e: IOException) {
            null
        }

        /** Sets [path]'s permission bits to [mode]; a failure only loses the mode. */
        fun setMode(path: Path, mode: Int) {
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermission.entries.filter { mode and bit(it) != 0 }.toSet()) }
        }

        private fun bit(p: PosixFilePermission): Int = when (p) {
            PosixFilePermission.OWNER_READ -> 0b100_000_000
            PosixFilePermission.OWNER_WRITE -> 0b010_000_000
            PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
            PosixFilePermission.GROUP_READ -> 0b000_100_000
            PosixFilePermission.GROUP_WRITE -> 0b000_010_000
            PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
            PosixFilePermission.OTHERS_READ -> 0b000_000_100
            PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
            PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
        }

        private fun projectKey(projectId: String): String =
            if (Regex("[A-Za-z0-9_-]{1,100}").matches(projectId)) projectId else hex(MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray()))

        private fun sha256(input: InputStream, buffer: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray): String {
            val chars = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                val v = b.toInt() and 0xff
                chars[i * 2] = HEX[v ushr 4]
                chars[i * 2 + 1] = HEX[v and 0x0f]
            }
            return String(chars)
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
