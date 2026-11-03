package dev.locus.core.data.repository

import dev.locus.core.model.FileTrees
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * One change to one file within a turn, in the order they happened: an edit by a file tool with its
 * own diff, or a change made outside the file tools between two of them (a command, or the user).
 */
@Serializable
data class FileEdit(
    val path: String,
    /** The tool call that made it; empty for an [outside] change. */
    val callId: String,
    /** "write_file", "edit_file", "multi_edit", "delete_path"…; "command" for an [outside] change. */
    val toolName: String,
    val at: Long,
    val added: Int = 0,
    val removed: Int = 0,
    /** This edit's own unified diff; null when it was too large to keep, binary, a folder, or [outside]. */
    val diff: String? = null,
    val binary: Boolean = false,
    /**
     * Found, not recorded: the file wasn't as the previous tool edit left it when the next one began, so
     * something else (a command, the user) changed it in between. There's no diff of its own: what it
     * changed is in the turn's net diff.
     */
    val outside: Boolean = false,
    /** SHA-256 of the file after this edit (null when it was removed), to spot an [outside] change next time. */
    val afterSha256: String? = null,
)

/**
 * Per-edit history for the changes card (docs/design/change-tracking.md, "Edit history"): one small JSON
 * file per turn, beside the `file_changes` table rather than in it, so the database schema doesn't
 * change. It lives in the app's no-backup storage: after a restore the cards come back with their net
 * diffs, without per-edit detail (the sheet says so).
 *
 * Capped: [MAX_EDIT_CHARS] of diff per edit, [MAX_TURN_CHARS] per turn (later edits keep only counts),
 * [MAX_EDITS_PER_TURN] edits per turn, and [MAX_TOTAL_BYTES] for all turns, oldest files going first.
 * Files are written through [FileTrees.writeReplacing] and never read through a link.
 *
 * Not thread-safe: [ChangeRepository] calls it under its lock.
 */
class ChangeHistory(private val dir: File?) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FileEdit.serializer())

    /** [turnId]'s edits, oldest first; empty when none were kept. */
    fun forTurn(turnId: String): List<FileEdit> {
        val file = fileFor(turnId) ?: return emptyList()
        if (!file.isFile || FileTrees.isLink(file)) return emptyList()
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
    }

    /** Adds [edit] to [turnId]'s history, keeping the caps. */
    fun append(turnId: String, edit: FileEdit) {
        val edits = forTurn(turnId)
        if (edits.size >= MAX_EDITS_PER_TURN) return
        val used = edits.sumOf { it.diff?.length ?: 0 }
        val kept = edit.diff?.takeIf { it.length <= MAX_EDIT_CHARS && used + it.length <= MAX_TURN_CHARS }
        write(turnId, edits + edit.copy(diff = kept))
    }

    /** Forgets [path]'s edits in [turnId] (its change was undone and starts afresh, or no longer stands). */
    fun clearPath(turnId: String, path: String) {
        val edits = forTurn(turnId)
        if (edits.none { it.path == path }) return
        write(turnId, edits.filterNot { it.path == path })
    }

    /** Deletes these turns' histories. */
    fun deleteTurns(turnIds: Collection<String>) {
        turnIds.forEach { id -> fileFor(id)?.let { runCatching { FileTrees.delete(it) } } }
    }

    /** Deletes every history but [turnIds]' (their records were pruned or their chat deleted), then the oldest past [MAX_TOTAL_BYTES]. */
    fun retain(turnIds: Set<String>) {
        val root = dir ?: return
        if (FileTrees.isLink(root)) return
        val keep = turnIds.mapNotNull { fileFor(it)?.name }.toSet()
        val files = root.listFiles().orEmpty().filter { it.name.endsWith(SUFFIX) }
        files.filter { it.name !in keep }.forEach { runCatching { FileTrees.delete(it) } }
        var total = files.filter { it.name in keep }.sumOf { it.length() }
        for (f in files.filter { it.name in keep }.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= f.length()
            runCatching { FileTrees.delete(f) }
        }
    }

    private fun write(turnId: String, edits: List<FileEdit>) {
        val root = dir ?: return
        val file = fileFor(turnId) ?: return
        runCatching {
            if (edits.isEmpty()) {
                FileTrees.delete(file)
            } else {
                if (!FileTrees.exists(root)) root.mkdirs()
                FileTrees.writeReplacing(root, file, json.encodeToString(serializer, edits))
            }
        }
    }

    /** A turn id is a message id (a UUID); anything else is hashed into a safe name. */
    private fun fileFor(turnId: String): File? {
        val root = dir ?: return null
        val name = if (SAFE.matches(turnId)) turnId else sha256(turnId)
        return File(root, name + SUFFIX)
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_EDIT_CHARS = 50_000
        const val MAX_TURN_CHARS = 1_000_000
        const val MAX_EDITS_PER_TURN = 500
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
        private const val SUFFIX = ".json"
        private val SAFE = Regex("[A-Za-z0-9_-]{1,100}")
    }
}
