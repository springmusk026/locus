package dev.locus.core.data.repository

import dev.locus.core.agent.tools.ChangeRecorder
import dev.locus.core.agent.tools.FileChange
import dev.locus.core.agent.tools.FileReads
import dev.locus.core.agent.tools.LineDiff
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.database.FileChangeDao
import dev.locus.core.database.FileChangeEntity
import dev.locus.core.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** One path a turn changed, as the changes card shows it. */
data class TrackedChange(
    /** The row's id, for [ChangeRepository.undo]. */
    val id: Long,
    val turnId: String,
    val path: String,
    val kind: Kind,
    val added: Int,
    val removed: Int,
    /** Unified diff; null for folders, binary files and diffs too large to keep. */
    val diff: String?,
    val binary: Boolean,
    val isDirectory: Boolean,
    val undone: Boolean,
    /** Whether the state before the turn can still be put back (its trash copy is still there). */
    val canUndo: Boolean,
) {
    enum class Kind { CREATED, MODIFIED, DELETED }
}

/** What undoing a change would do, asked before the user confirms. */
data class UndoCheck(
    /** The file is no longer as the agent left it (the user, a command or a later turn changed it). */
    val changedSince: Boolean,
    /** Why it can't be undone, or null when it can. */
    val blocked: String? = null,
)

/**
 * Tracks the workspace changes agent tools make, per chat and per turn, and undoes them. The state
 * before a change is the workspace trash's own copy (every changing tool already keeps one), so only
 * the diff text is stored here. See docs/design/change-tracking.md.
 *
 * Storage is capped: each chat keeps its latest [MAX_TURNS_PER_CHAT] turns with changes, and all chats
 * together at most [MAX_DIFF_CHARS] characters of diff text; older turns are dropped first. Dropping a
 * record leaves the trash copies alone: they're the user's, in Workspace → Trash.
 */
@Singleton
class ChangeRepository @Inject constructor(
    private val dao: FileChangeDao,
    private val workspace: Workspace,
    private val reads: FileReads,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ChangeRecorder {
    internal var clock: () -> Long = System::currentTimeMillis
    private val lock = Mutex()

    /** The chat's tracked changes, oldest first. */
    fun observe(conversationId: String): Flow<List<TrackedChange>> =
        dao.observe(conversationId).map { rows -> rows.map { it.toTracked() } }.flowOn(io)

    override suspend fun record(change: FileChange) = withContext(io) {
        lock.withLock {
            val turnId = change.owner.turnId
            val existing = dao.get(turnId, change.path)
            // Changed again after the user undid it in this turn: track afresh from this change.
            val base = existing?.takeIf { it.undoneAt == null }
            if (existing != null && base == null) dao.delete(existing.seq)
            val existedBefore = base?.existedBefore ?: change.existedBefore
            val beforeTrashId = if (base != null) base.beforeTrashId else change.beforeTrashId
            val file = workspace.resolve(change.path)
            val afterExists = file.exists()
            val isDirectory = change.isDirectory || file.isDirectory || base?.isDirectory == true
            if (!existedBefore && !afterExists) {
                // Created and removed within the turn: nothing to show or undo.
                base?.let { dao.delete(it.seq) }
                return@withLock
            }
            val diff = if (isDirectory) null else diffOf(change.path, existedBefore, beforeTrashId, file.takeIf { afterExists })
            val now = clock()
            val row = FileChangeEntity(
                seq = base?.seq ?: 0,
                conversationId = change.owner.conversationId,
                turnId = turnId,
                path = change.path,
                callId = change.callId,
                toolName = change.toolName,
                existedBefore = existedBefore,
                beforeTrashId = beforeTrashId,
                isDirectory = isDirectory,
                afterSha256 = if (afterExists && file.isFile) sha256(file) else null,
                afterExists = afterExists,
                added = diff?.added ?: 0,
                removed = diff?.removed ?: 0,
                diff = diff?.unified,
                binary = diff?.binary == true,
                createdAt = base?.createdAt ?: now,
                updatedAt = now,
            )
            if (base == null) dao.insert(row) else dao.update(row)
            prune(change.owner.conversationId)
        }
    }

    /** Whether change [id] can be undone, and whether the file changed since the agent left it. */
    suspend fun check(id: Long): UndoCheck = withContext(io) {
        val row = dao.bySeq(id) ?: return@withContext UndoCheck(false, "This change is no longer tracked.")
        check(row)
    }

    /** [check] for every change of a turn that still stands: changed since if any file changed. */
    suspend fun checkTurn(conversationId: String, turnId: String): UndoCheck = withContext(io) {
        val rows = dao.turn(conversationId, turnId).filter { it.undoneAt == null }
        if (rows.isEmpty()) return@withContext UndoCheck(false, "Nothing left to undo in this turn.")
        val checks = rows.map { check(it) }
        UndoCheck(checks.any { it.changedSince }, checks.firstNotNullOfOrNull { it.blocked }.takeIf { checks.all { c -> c.blocked != null } })
    }

    private fun check(row: FileChangeEntity): UndoCheck {
        if (row.undoneAt != null) return UndoCheck(false, "Already undone.")
        if (row.existedBefore && row.beforeTrashId?.let(workspace::trashPayload) == null) {
            return UndoCheck(false, "The earlier version is no longer in the workspace trash (it was restored or emptied).")
        }
        return UndoCheck(changedSince(row))
    }

    /**
     * Undoes the changes [ids]: each path goes back to how it was before its turn changed it. What's
     * there now goes to the trash first, so the undo can be undone from Workspace → Trash. Later
     * changes are undone first. Returns the paths put back; the chats that had read them must read
     * them again ([FileReads.invalidate]).
     */
    suspend fun undo(ids: List<Long>): List<String> = withContext(io) {
        lock.withLock {
            val rows = ids.mapNotNull { dao.bySeq(it) }.filter { it.undoneAt == null }.sortedByDescending { it.seq }
            rows.mapNotNull { row ->
                if (check(row).blocked != null) return@mapNotNull null
                val target = workspace.resolve(row.path)
                if (row.existedBefore) {
                    workspace.restore(row.beforeTrashId!!)
                } else if (target.exists()) {
                    workspace.trash(target, TrashEntry.Reason.DELETED)
                }
                dao.markUndone(row.seq, clock())
                reads.invalidate(target)
                row.path
            }.distinct()
        }
    }

    /** Undoes every change of [turnId] that still stands; see [undo]. */
    suspend fun undoTurn(conversationId: String, turnId: String): List<String> =
        undo(dao.turn(conversationId, turnId).filter { it.undoneAt == null }.map { it.seq })

    private fun changedSince(row: FileChangeEntity): Boolean {
        val file = workspace.resolve(row.path)
        if (!row.afterExists) return file.exists()
        if (!file.exists()) return true
        if (row.isDirectory) return !file.isDirectory
        return !file.isFile || sha256(file) != row.afterSha256
    }

    private fun diffOf(path: String, existedBefore: Boolean, beforeTrashId: String?, after: File?): LineDiff.Result? {
        val before = if (existedBefore) {
            val payload = beforeTrashId?.let(workspace::trashPayload)?.takeIf { it.isFile } ?: return null
            textOf(payload) ?: return LineDiff.Result(0, 0, null, binary = true)
        } else {
            null
        }
        val afterText = after?.let { textOf(it) ?: return LineDiff.Result(0, 0, null, binary = true) }
        return LineDiff.diff(path, before, afterText, maxChars = MAX_DIFF_CHARS_PER_FILE)
    }

    /** A file's text for diffing, or null when it's too large to diff or isn't text. */
    private fun textOf(file: File): String? {
        if (!file.isFile || file.length() > MAX_DIFF_FILE_BYTES) return null
        val text = file.readText()
        return text.takeUnless(LineDiff::isBinary)
    }

    /** Keeps the latest [MAX_TURNS_PER_CHAT] turns of [conversationId], and [MAX_DIFF_CHARS] of diff text overall. */
    private suspend fun prune(conversationId: String) {
        val old = dao.turnsNewestFirst(conversationId).drop(MAX_TURNS_PER_CHAT)
        if (old.isNotEmpty()) dao.deleteTurns(old)
        val sizes = dao.turnSizesOldestFirst()
        var total = sizes.sumOf { it.chars }
        val drop = mutableListOf<String>()
        // The newest turn always stays, whatever its size.
        for (turn in sizes.dropLast(1)) {
            if (total <= MAX_DIFF_CHARS) break
            drop += turn.turnId
            total -= turn.chars
        }
        if (drop.isNotEmpty()) dao.deleteTurns(drop)
    }

    private fun FileChangeEntity.toTracked() = TrackedChange(
        id = seq,
        turnId = turnId,
        path = path,
        kind = when {
            !existedBefore -> TrackedChange.Kind.CREATED
            !afterExists -> TrackedChange.Kind.DELETED
            else -> TrackedChange.Kind.MODIFIED
        },
        added = added,
        removed = removed,
        diff = diff,
        binary = binary,
        isDirectory = isDirectory,
        undone = undoneAt != null,
        canUndo = undoneAt == null && (!existedBefore || beforeTrashId?.let(workspace::trashPayload) != null),
    )

    private fun sha256(file: File): String {
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

    companion object {
        /** Turns with changes each chat keeps. */
        const val MAX_TURNS_PER_CHAT = 30

        /** Diff text kept across all chats (about 8 MB of ASCII). */
        const val MAX_DIFF_CHARS = 8_000_000L

        /** A single file's diff text; larger diffs keep only their line counts. */
        const val MAX_DIFF_CHARS_PER_FILE = 200_000

        /** Files larger than this aren't diffed (their change is still tracked and can be undone). */
        const val MAX_DIFF_FILE_BYTES = 2L * 1024 * 1024
    }
}
