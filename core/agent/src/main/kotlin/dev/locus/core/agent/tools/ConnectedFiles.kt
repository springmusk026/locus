package dev.locus.core.agent.tools

import dev.locus.core.agent.ChatScope
import dev.locus.core.agent.ToolException
import dev.locus.core.model.ConnectedFolder
import dev.locus.core.model.ConnectedFolders
import dev.locus.core.model.FileTrees
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** One file or folder in a connected folder's document tree, as its provider describes it. */
data class DocEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    /** FLAG_SUPPORTS_WRITE: its contents may be replaced (files). */
    val canWrite: Boolean = true,
    val canDelete: Boolean = true,
    val canRename: Boolean = true,
    /** FLAG_DIR_SUPPORTS_CREATE: files and folders may be made in it (folders). */
    val canCreate: Boolean = true,
)

/** A provider refused or failed: [problem] says how, so the file layer can say what to do. */
class DocTreeException(val problem: Problem, message: String) : IOException(message) {
    enum class Problem {
        /** The grant is gone: the user revoked it, cleared Locus's data, or the folder moved. */
        PERMISSION_LOST,

        /** The granted folder itself is gone (deleted, or its SD card removed). */
        GONE,

        /** A file or folder inside it isn't there (any more). */
        NOT_FOUND,

        /** The provider doesn't support this (a read-only provider, no rename). */
        UNSUPPORTED,

        /** The provider refused the name or the operation. */
        REJECTED,

        FAILED,
    }
}

/**
 * One connected folder's document tree, by path segments below its top ([] is the top). The Android
 * implementation (core/data's SafDocTree) speaks `DocumentsContract`; tests use a plain folder. It
 * never reaches outside the granted tree: every document is found by listing its parent's children.
 * Throws [DocTreeException].
 */
interface DocTree {
    fun root(): DocEntry

    /** The children of the folder at [path]. */
    fun list(path: List<String>): List<DocEntry>

    fun open(path: List<String>): InputStream

    /** Replaces the contents of the existing file at [path]. */
    fun write(path: List<String>, bytes: ByteArray)

    /** Makes an empty file [name] in [parent] and returns the name the provider gave it (it may add " (1)"). */
    fun createFile(parent: List<String>, name: String): String

    /** Makes a folder [name] in [parent]; returns the name it got. */
    fun createDirectory(parent: List<String>, name: String): String

    /** Deletes the file or folder (with everything in it) at [path]. */
    fun delete(path: List<String>)

    /** Renames [path] within its folder; returns the name it got. */
    fun rename(path: List<String>, newName: String): String

    /** A `content://` address other apps can open [path] by (Open with…, Share), or null where there's none. */
    fun uriOf(path: List<String>): String? = null
}

/** The user's connected folders, from the settings, and a tree for each. */
interface ConnectedFolderSource {
    /** All of them, now (a snapshot; cheap). */
    fun folders(): List<ConnectedFolder>

    fun tree(folder: ConnectedFolder): DocTree
}

/** What is at a stored path now, for change tracking ([Workspace.storedItem]). */
interface StoredItem {
    val exists: Boolean
    val isDirectory: Boolean
    val isFile: Boolean get() = exists && !isDirectory
    val length: Long

    fun open(): InputStream

    /** Moves it to the trash (undoing a file a change created). */
    fun trash(reason: TrashEntry.Reason): TrashEntry

    /** Every chat that had read it must read it again ([FileReads.invalidate]). */
    fun invalidate(reads: FileReads)
}

internal class FileItem(private val ws: Workspace, val file: File) : StoredItem {
    override val exists get() = file.exists()
    override val isDirectory get() = file.isDirectory
    override val length get() = file.length()
    override fun open(): InputStream = file.inputStream()
    override fun trash(reason: TrashEntry.Reason) = ws.trash(file, reason)
    override fun invalidate(reads: FileReads) = reads.invalidate(file)
}

/**
 * The file layer for connected folders (docs/design/connected-folders.md). The file tools reach a
 * folder the user connected as `folders/<name>/…`:
 *
 * - **Paths** are checked here and nowhere else: `..`, backslashes and NULs are refused, and a project
 *   chat sees only the folders linked to its project. A tree is walked from its top by name, so nothing
 *   outside the grant can be named.
 * - **Rules:** a read-only folder refuses every change, and so do hidden files and folders (`.obsidian`,
 *   `.git`, `.trash`) unless the user allowed them. Names SAF or FAT storage would refuse are refused
 *   up front.
 * - **Speed:** SAF answers one IPC per folder, so listings are kept for [LISTING_TTL_MS] (another app or
 *   Syncthing may change files, so never longer) and dropped on every change Locus makes; walks for
 *   find and search stop at [MAX_WALK].
 * - **Undo:** before-copies go to the workspace trash ([Workspace.keep]), so per-file undo and the
 *   changes card work as they do for workspace files.
 */
class ConnectedFiles internal constructor(
    private val source: ConnectedFolderSource,
    private val ws: Workspace,
    private val clock: () -> Long,
) {
    /** A path inside a connected folder: [segments] below its top. */
    data class Target(val folder: ConnectedFolder, val segments: List<String>) {
        /** The path as the agent writes it. */
        val path: String get() = (listOf(ConnectedFolders.PREFIX, folder.name) + segments).joinToString("/")
        val name: String get() = segments.lastOrNull() ?: folder.name
        val parent: Target? get() = if (segments.isEmpty()) null else Target(folder, segments.dropLast(1))
        fun child(name: String) = Target(folder, segments + name)

        /** The key [FileReads] knows it by: stable while the folder stays connected. */
        val readKey: String get() = "connected:${folder.id}/${segments.joinToString("/")}"
    }

    /** Every connected folder, whatever the chat. */
    fun all(): List<ConnectedFolder> = runCatching { source.folders() }.getOrDefault(emptyList())

    /** The folders the running chat sees: all outside projects, the linked ones in a project chat. */
    fun visible(): List<ConnectedFolder> = all().filter { ConnectedFolders.visibleIn(it, ChatScope.current()?.projectId) }

    private fun parts(path: String): List<String> =
        path.trim().removePrefix("/").split('/').filter { it.isNotEmpty() && it != "." }

    /** Whether [path] names a connected folder or something in one (any folder, any chat). */
    fun claims(path: String): Boolean {
        val p = parts(path)
        return p.size >= 2 && p[0] == ConnectedFolders.PREFIX && all().any { it.name == p[1] }
    }

    /** Whether [path] is `folders` itself, where the connected folders are listed. */
    fun isTop(path: String): Boolean = parts(path) == listOf(ConnectedFolders.PREFIX) && all().isNotEmpty()

    /** The connected folders this chat sees whose path (`folders/<name>`) appears in [text]. */
    fun mentionedIn(text: String): List<String> =
        visible().map { it.name }.filter { name -> Regex("(^|[^A-Za-z0-9._-])${ConnectedFolders.PREFIX}/${Regex.escape(name)}(?![A-Za-z0-9._-])").containsMatchIn(text) }

    /** What another tool says when it's given a connected folder's path. */
    fun notReachable(path: String): String =
        "'$path' is in a connected folder (a folder on the phone the user granted through Android's storage access), not a file on disk. " +
            "Only the file tools (list_files, read_file, find_files, search_files, write_file, edit_file, multi_edit, make_directory, copy_path, " +
            "move_path, delete_path) reach it. Linux and run_command can't see it: copy files into the workspace with copy_path to use them there."

    /**
     * The connected path [path] names, or null when it isn't one (a workspace path). Throws for a path
     * that climbs out with `..`, has a backslash or NUL, or names a folder this project chat doesn't see.
     */
    fun target(path: String): Target? {
        val p = parts(path)
        if (p.size < 2 || p[0] != ConnectedFolders.PREFIX) return null
        val folder = all().firstOrNull { it.name == p[1] } ?: return null
        if (!ConnectedFolders.visibleIn(folder, ChatScope.current()?.projectId)) {
            throw ToolException("'${ConnectedFolders.pathOf(folder)}' isn't linked to this project. The user can link it in Workspace → Connected folders.")
        }
        return Target(folder, checkSegments(path, p.drop(2)))
    }

    /** [target] for a stored path (change records, trash), whatever the chat; never throws for visibility. */
    fun storedTarget(path: String): Target? {
        val p = parts(path)
        if (p.size < 2 || p[0] != ConnectedFolders.PREFIX) return null
        val folder = all().firstOrNull { it.name == p[1] } ?: return null
        return Target(folder, runCatching { checkSegments(path, p.drop(2)) }.getOrNull() ?: return null)
    }

    private fun checkSegments(path: String, segments: List<String>): List<String> {
        if (segments.any { it == ".." }) throw ToolException("'$path' climbs out of the connected folder with '..'. Locus never goes outside a folder the user granted.")
        if (segments.any { '\\' in it || '\u0000' in it }) throw ToolException("'$path' has a backslash or a NUL in it, which no folder name can.")
        return segments
    }

    // ---- Rules

    /** Why the agent may not change [t], or null. */
    fun writeProblem(t: Target): String? {
        if (t.folder.readOnly) {
            return "${ConnectedFolders.pathOf(t.folder)} is read-only for Locus. The user can let Locus change it in Workspace → Connected folders."
        }
        val hidden = t.segments.firstOrNull { it.startsWith(".") }
        if (hidden != null && !t.folder.hiddenWritable) {
            return "'${t.path}' is in a hidden file or folder ($hidden), which is read-only: apps keep their settings and history there (.obsidian, .git, .trash). " +
                "The user can allow changes there in Workspace → Connected folders."
        }
        return null
    }

    fun requireWritable(t: Target) {
        writeProblem(t)?.let { throw ToolException(it) }
    }

    // ---- Reading

    private class Listing(val at: Long, val entries: List<DocEntry>)

    private val listings = object : LinkedHashMap<String, Listing>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Listing>) = size > MAX_LISTINGS
    }

    private fun key(t: Target) = t.folder.id + "/" + t.segments.joinToString("/")

    /** Forgets cached listings of [t] and everything below it (after a change Locus made). */
    fun forget(t: Target) = synchronized(listings) {
        val k = key(t)
        listings.keys.removeAll { it == k || it.startsWith("$k/") }
    }

    /** The children of folder [t], from a listing at most [LISTING_TTL_MS] old unless [fresh]. */
    fun list(t: Target, fresh: Boolean = false): List<DocEntry> {
        val k = key(t)
        if (!fresh) synchronized(listings) { listings[k]?.takeIf { clock() - it.at < LISTING_TTL_MS }?.let { return it.entries } }
        val entries = provider(t) { tree(t).list(t.segments) }
        synchronized(listings) { listings[k] = Listing(clock(), entries) }
        return entries
    }

    /** What is at [t], or null when nothing is. */
    fun stat(t: Target, fresh: Boolean = false): DocEntry? {
        val parent = t.parent ?: return provider(t) { tree(t).root() }
        val siblings = try {
            list(parent, fresh)
        } catch (e: ToolException) {
            // A missing folder on the way means a missing file, not an error.
            if (stat(parent, fresh) == null) return null else throw e
        }
        return siblings.firstOrNull { it.name == t.name }
    }

    fun existing(t: Target): DocEntry = stat(t) ?: throw ToolException("'${t.path}' does not exist.")

    fun open(t: Target): InputStream = provider(t) { tree(t).open(t.segments) }

    /** [DocTree.uriOf] for [t]. */
    fun uriOf(t: Target): String? = runCatching { provider(t) { tree(t).uriOf(t.segments) } }.getOrNull()

    /** [t]'s bytes, refusing a file over [max]. */
    fun readBytes(t: Target, max: Long = MAX_FILE_BYTES): ByteArray = open(t).use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw ToolException("'${t.path}' is over ${max / 1024 / 1024} MB, too large for the file tools in a connected folder.")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    /** Whether the file at [t] looks like text (no NUL in its first 4 KB). */
    fun isText(t: Target): Boolean = runCatching {
        open(t).use { input ->
            val head = ByteArray(4096)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            (0 until read).none { head[it] == 0.toByte() }
        }
    }.getOrDefault(false)

    /** Records that [chat] has seen [t] with [bytes] (just read or written). */
    fun recordRead(reads: FileReads, chat: String, t: Target, bytes: ByteArray) {
        val e = stat(t, fresh = true) ?: return
        reads.record(chat, t.readKey, e.modified, bytes.size.toLong(), sha256(bytes))
    }

    /** [FileReads.state] for [t], from a fresh look, so a change another app just made is seen. */
    fun readState(reads: FileReads, chat: String, t: Target): FileReads.State {
        val e = stat(t, fresh = true) ?: return FileReads.State.NEW_FILE
        if (e.isDirectory) return FileReads.State.NEW_FILE
        return reads.state(chat, t.readKey, e.modified, e.size) { sha256(readBytes(t)) }
    }

    /**
     * Files below [t] (or [t] itself for a file), breadth first, skipping hidden folders and the
     * dependency folders the workspace walk skips unless [includeIgnored]; at most [max] are listed.
     */
    fun walk(t: Target, includeIgnored: Boolean, max: Int = MAX_WALK): Walk {
        val top = existing(t)
        if (!top.isDirectory) return Walk(listOf(t to top), capped = false)
        val found = mutableListOf<Pair<Target, DocEntry>>()
        val queue = ArrayDeque(listOf(t))
        var visited = 0
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (child in list(dir).sortedBy { it.name }) {
                if (++visited > max) return Walk(found, capped = true)
                val at = dir.child(child.name)
                if (child.isDirectory) {
                    if (includeIgnored || (!child.name.startsWith(".") && child.name !in CodeFiles.IGNORED_DIRS)) queue += at
                } else {
                    found += at to child
                }
            }
        }
        return Walk(found, capped = false)
    }

    class Walk(val files: List<Pair<Target, DocEntry>>, val capped: Boolean)

    // ---- Changing

    /** Makes folder [t] and any missing parents. */
    fun mkdirs(t: Target) {
        var at = Target(t.folder, emptyList())
        for (segment in t.segments) {
            val next = at.child(segment)
            val e = stat(next, fresh = true)
            when {
                e == null -> {
                    checkName(next)
                    val got = provider(next) { tree(next).createDirectory(at.segments, segment) }
                    forget(at)
                    if (got != segment) {
                        runCatching { tree(next).delete(at.segments + got) }
                        throw ToolException("The storage app wouldn't make the folder '${next.path}' (it made '$got' instead, so a name that differs only in case may exist). Pick another name.")
                    }
                }
                !e.isDirectory -> throw ToolException("'${next.path}' is a file, not a folder.")
            }
            at = next
        }
    }

    /** Writes [bytes] to file [t], making it and its folders if missing. Returns whether it existed. */
    fun writeFile(t: Target, bytes: ByteArray): Boolean {
        val parent = t.parent ?: throw ToolException("'${t.path}' is the connected folder itself.")
        mkdirs(parent)
        val e = stat(t, fresh = true)
        if (e?.isDirectory == true) throw ToolException("'${t.path}' is a folder.")
        if (e == null) {
            checkName(t)
            val got = provider(t) { tree(t).createFile(parent.segments, t.name) }
            if (got != t.name) {
                runCatching { tree(t).delete(parent.segments + got) }
                forget(parent)
                throw ToolException(
                    "The storage app wouldn't create '${t.path}' under that name (it made '$got'), so a file whose name differs only in case " +
                        "probably exists. Read the folder with list_files and use that file's exact name.",
                )
            }
        } else if (!e.canWrite) {
            throw ToolException("The storage app that holds ${ConnectedFolders.pathOf(t.folder)} doesn't let apps change '${t.path}'.")
        }
        try {
            provider(t) { tree(t).write(t.segments, bytes) }
        } finally {
            forget(parent)
        }
        return e != null
    }

    /** Copies what's at [t] into the trash and deletes it. A folder is refused when it's too large to keep. */
    fun trash(t: Target, reason: TrashEntry.Reason): TrashEntry {
        if (t.segments.isEmpty()) throw ToolException("A connected folder itself can't be deleted from Locus; the user can disconnect it in Workspace → Connected folders.")
        val e = existing(t)
        if (!e.canDelete) throw ToolException("The storage app that holds ${ConnectedFolders.pathOf(t.folder)} doesn't let apps delete '${t.path}'.")
        val entry = keep(t, e, reason)
        try {
            provider(t) { tree(t).delete(t.segments) }
        } catch (ex: Exception) {
            ws.purgeTrash(entry.id)
            throw ex
        } finally {
            forget(t.parent!!)
            forget(t)
        }
        return entry
    }

    /** Keeps a copy of file [t] before it is overwritten. */
    fun backup(t: Target, reason: TrashEntry.Reason): TrashEntry = keep(t, existing(t), reason)

    private fun keep(t: Target, e: DocEntry, reason: TrashEntry.Reason): TrashEntry =
        ws.keep(t.path, t.folder.id, reason, e.isDirectory) { payload ->
            if (e.isDirectory) {
                val budget = longArrayOf(MAX_KEPT_FILES.toLong(), MAX_KEPT_BYTES)
                copyOut(t, payload, budget)
            } else {
                payload.parentFile?.mkdirs()
                open(t).use { input -> payload.outputStream().use { input.copyTo(it) } }
            }
        }

    /**
     * Copies [t] (a file or folder) to [dest] on disk. [budget] is files then bytes left; past it the
     * copy stops with an error, so a huge folder is never copied by accident.
     */
    fun copyOut(t: Target, dest: File, budget: LongArray = longArrayOf(MAX_KEPT_FILES.toLong(), MAX_KEPT_BYTES)) {
        val e = existing(t)
        if (e.isDirectory) {
            dest.mkdirs()
            for (child in list(t)) copyOut(t.child(child.name), File(dest, child.name), budget)
        } else {
            budget[0]--
            budget[1] -= e.size
            if (budget[0] < 0 || budget[1] < 0) {
                throw ToolException("'${t.path}' is too large to copy (over $MAX_KEPT_FILES files or ${MAX_KEPT_BYTES / 1024 / 1024} MB). Ask the user to do this in their Files app.")
            }
            dest.parentFile?.mkdirs()
            open(t).use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }

    /** Copies [src] (a workspace file or folder, never through a link) to [t], which must not exist yet. */
    fun copyIn(src: File, t: Target, budget: LongArray = longArrayOf(MAX_KEPT_FILES.toLong(), MAX_KEPT_BYTES)) {
        if (FileTrees.isLink(src)) return
        if (src.isDirectory) {
            mkdirs(t)
            for (child in src.listFiles().orEmpty().sortedBy { it.name }) copyIn(child, t.child(child.name), budget)
        } else {
            budget[0]--
            budget[1] -= src.length()
            if (budget[0] < 0 || budget[1] < 0) {
                throw ToolException("'${src.name}' is too large to copy into a connected folder (over $MAX_KEPT_FILES files or ${MAX_KEPT_BYTES / 1024 / 1024} MB).")
            }
            if (src.length() > MAX_FILE_BYTES) throw ToolException("'${src.name}' is over ${MAX_FILE_BYTES / 1024 / 1024} MB, too large to copy into a connected folder.")
            writeFile(t, src.readBytes())
        }
    }

    /** Copies within connected folders: [from] to [to], which must not exist. */
    fun copyWithin(from: Target, to: Target, budget: LongArray = longArrayOf(MAX_KEPT_FILES.toLong(), MAX_KEPT_BYTES)) {
        val e = existing(from)
        if (e.isDirectory) {
            mkdirs(to)
            for (child in list(from)) copyWithin(from.child(child.name), to.child(child.name), budget)
        } else {
            budget[0]--
            budget[1] -= e.size
            if (budget[0] < 0 || budget[1] < 0) throw ToolException("'${from.path}' is too large to copy (over $MAX_KEPT_FILES files or ${MAX_KEPT_BYTES / 1024 / 1024} MB).")
            writeFile(to, readBytes(from))
        }
    }

    /** Renames [t] within its folder to [name] (the provider's own rename: no copy). */
    fun rename(t: Target, name: String): Target {
        val to = t.parent!!.child(name)
        checkName(to)
        val got = provider(t) { tree(t).rename(t.segments, name) }
        forget(t.parent!!)
        forget(t)
        if (got != name) throw ToolException("The storage app renamed '${t.path}' to '$got' instead of '$name'.")
        return to
    }

    /** Puts trash entry [entry] (kept by [keep]) back from [payload]; what is there now goes to the trash first. */
    internal fun restore(entry: TrashEntry, payload: File) {
        val folder = all().firstOrNull { it.id == entry.folderId }
            ?: throw ToolException("${entry.originalPath} was in a folder that is no longer connected. Connect it again to restore this.")
        val p = parts(entry.originalPath)
        if (p.size < 3 || p[1] != folder.name) throw ToolException("${entry.originalPath} was in a folder that is now connected under another name.")
        val t = Target(folder, checkSegments(entry.originalPath, p.drop(2)))
        if (stat(t, fresh = true) != null) trash(t, TrashEntry.Reason.MOVED_OVER)
        if (payload.isDirectory) {
            mkdirs(t)
            payload.listFiles().orEmpty().forEach { copyIn(it, t.child(it.name)) }
        } else {
            writeFile(t, payload.readBytes())
        }
    }

    /** [StoredItem] for [t], for change tracking. */
    fun item(t: Target): StoredItem = object : StoredItem {
        private val e by lazy { runCatching { stat(t, fresh = true) }.getOrNull() }
        override val exists get() = e != null
        override val isDirectory get() = e?.isDirectory == true
        override val length get() = e?.size ?: 0
        override fun open(): InputStream = this@ConnectedFiles.open(t)
        override fun trash(reason: TrashEntry.Reason) = this@ConnectedFiles.trash(t, reason)
        override fun invalidate(reads: FileReads) = reads.invalidate(t.readKey)
    }

    // ---- Names and errors

    /** Refuses a name SAF or FAT storage (SD cards) would reject or change. */
    fun checkName(t: Target) {
        nameProblem(t.name)?.let { throw ToolException("Can't name '${t.path}': $it") }
    }

    private fun tree(t: Target): DocTree = source.tree(t.folder)

    /** Runs [block] against [t]'s provider, turning its failures into what the agent should do. */
    private inline fun <T> provider(t: Target, block: () -> T): T = try {
        block()
    } catch (e: DocTreeException) {
        throw ToolException(describe(t, e))
    } catch (e: SecurityException) {
        throw ToolException(describe(t, DocTreeException(DocTreeException.Problem.PERMISSION_LOST, e.message.orEmpty())))
    }

    private fun describe(t: Target, e: DocTreeException): String {
        val top = ConnectedFolders.pathOf(t.folder)
        return when (e.problem) {
            DocTreeException.Problem.PERMISSION_LOST ->
                "Locus no longer has access to $top (the permission was removed, or the folder was moved). Ask the user to reconnect it in Workspace → Connected folders."
            DocTreeException.Problem.GONE ->
                "$top is gone: the folder was deleted, or the storage it was on (an SD card) was removed. Ask the user to reconnect it in Workspace → Connected folders."
            DocTreeException.Problem.NOT_FOUND -> "'${t.path}' does not exist (any more). Another app may have moved or deleted it."
            DocTreeException.Problem.UNSUPPORTED -> "The storage app that holds $top doesn't support this for '${t.path}'${e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}"
            DocTreeException.Problem.REJECTED -> "The storage app refused '${t.path}'${e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}"
            DocTreeException.Problem.FAILED -> "Couldn't reach '${t.path}': ${e.message ?: "the storage app failed"}."
        }
    }

    companion object {
        /** How long a folder listing is trusted: other apps (Syncthing, Obsidian) change files too. */
        const val LISTING_TTL_MS = 3_000L
        const val MAX_LISTINGS = 256

        /** Entries a find or search walks before it stops and says so. */
        const val MAX_WALK = 5_000

        /** The largest file the tools read or write in a connected folder. */
        const val MAX_FILE_BYTES = 10L * 1024 * 1024

        /** What a deleted folder's trash copy (and a copy between places) may hold. */
        const val MAX_KEPT_FILES = 2_000
        const val MAX_KEPT_BYTES = 200L * 1024 * 1024

        private val FAT_FORBIDDEN = "\"*/:<>?\\|"

        /**
         * Why [name] can't be a file or folder name in a connected folder, or null. Covers what SAF and
         * FAT/exFAT (SD cards) refuse: `" * / : < > ? \ |`, control characters, `.` and `..`, a trailing
         * dot or space, and more than 255 bytes.
         */
        fun nameProblem(name: String): String? = when {
            name.isEmpty() || name == "." || name == ".." -> "that isn't a name."
            name.any { it.code < 0x20 || it.code == 0x7f } -> "it has control characters."
            name.any { it in FAT_FORBIDDEN } -> "storage like SD cards refuses the characters \" * / : < > ? \\ |."
            name.endsWith(".") || name.endsWith(" ") -> "a name can't end with a dot or a space there."
            name.toByteArray().size > 255 -> "it's longer than 255 bytes."
            else -> null
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
