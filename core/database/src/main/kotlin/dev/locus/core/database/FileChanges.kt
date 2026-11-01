package dev.locus.core.database

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

/**
 * One workspace path an agent changed in one turn of a chat (v11), for the changes card and undo. Several
 * changes to the same path in a turn share a row: [beforeTrashId] is the state before the first, and the
 * after-state, [diff] and counts follow the latest. The before-state is the workspace trash's own copy,
 * so tracking stores no file contents beyond the diff text. See docs/design/change-tracking.md.
 */
@Entity(
    tableName = "file_changes",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index(value = ["turnId", "path"], unique = true)],
)
data class FileChangeEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val conversationId: String,
    /** The turn's first message after the user's (how the chat screen keys a turn). */
    val turnId: String,
    /** Relative to the workspace root. */
    val path: String,
    /** The latest tool call that changed it, and that tool. */
    val callId: String,
    val toolName: String,
    /** False: the turn created the path, so undoing removes it. */
    val existedBefore: Boolean,
    /** The trash entry holding the path as it was before the turn's first change to it; null when it didn't exist. */
    val beforeTrashId: String?,
    val isDirectory: Boolean,
    /** SHA-256 of the file right after the latest change; null when the change removed it, or for a folder. */
    val afterSha256: String?,
    /** Whether the path existed right after the latest change. */
    val afterExists: Boolean,
    val added: Int,
    val removed: Int,
    /** Unified diff from before the turn to after its latest change; null for folders, binary files or one too large to keep. */
    val diff: String?,
    val binary: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** When the user undid it; null while it stands. */
    val undoneAt: Long? = null,
)

/** How much diff text one turn's changes keep. */
data class TurnSize(val turnId: String, val chars: Long)

@Dao
interface FileChangeDao {
    /** A chat's tracked changes, oldest first. */
    @Query("SELECT * FROM file_changes WHERE conversationId = :conversationId ORDER BY seq")
    fun observe(conversationId: String): Flow<List<FileChangeEntity>>

    /** The live (not undone) row for [path] in [turnId], if the turn changed it already. */
    @Query("SELECT * FROM file_changes WHERE turnId = :turnId AND path = :path")
    suspend fun get(turnId: String, path: String): FileChangeEntity?

    @Query("SELECT * FROM file_changes WHERE seq = :seq")
    suspend fun bySeq(seq: Long): FileChangeEntity?

    @Query("SELECT * FROM file_changes WHERE conversationId = :conversationId AND turnId = :turnId ORDER BY seq")
    suspend fun turn(conversationId: String, turnId: String): List<FileChangeEntity>

    @Insert
    suspend fun insert(change: FileChangeEntity): Long

    @Update
    suspend fun update(change: FileChangeEntity)

    @Query("DELETE FROM file_changes WHERE seq = :seq")
    suspend fun delete(seq: Long)

    @Query("UPDATE file_changes SET undoneAt = :at WHERE seq = :seq")
    suspend fun markUndone(seq: Long, at: Long)

    /** A chat's turns that have changes, newest first. */
    @Query("SELECT turnId FROM file_changes WHERE conversationId = :conversationId GROUP BY turnId ORDER BY MAX(seq) DESC")
    suspend fun turnsNewestFirst(conversationId: String): List<String>

    /** Every turn with changes and the diff text it keeps, oldest first, across chats. */
    @Query("SELECT turnId, SUM(COALESCE(LENGTH(diff), 0)) AS chars FROM file_changes GROUP BY turnId ORDER BY MAX(seq)")
    suspend fun turnSizesOldestFirst(): List<TurnSize>

    @Query("DELETE FROM file_changes WHERE turnId IN (:turnIds)")
    suspend fun deleteTurns(turnIds: List<String>)
}
