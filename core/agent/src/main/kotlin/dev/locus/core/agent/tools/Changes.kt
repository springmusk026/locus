package dev.locus.core.agent.tools

/**
 * Who a workspace change belongs to: the user's chat and the turn it happened in. [turnId] is the id
 * of the turn's first message after the user's (the one the chat screen keys the turn by), so a
 * regenerated turn, which starts a new branch with a new first message, has its own changes. A helper
 * carries its parent's owner, so its edits show under the parent's turn.
 */
data class ChangeOwner(val conversationId: String, val turnId: String)

/**
 * One change an agent tool made to a workspace path. The state before it lives in the workspace
 * trash ([beforeTrashId]); nothing is copied for tracking. [existedBefore] false means the tool
 * created the path.
 */
data class FileChange(
    val owner: ChangeOwner,
    val callId: String,
    val toolName: String,
    /** Relative to the workspace root. */
    val path: String,
    val existedBefore: Boolean,
    val beforeTrashId: String?,
    val isDirectory: Boolean = false,
)

/**
 * Records the changes agent tools make (see docs/design/change-tracking.md). Lives in core/data, which
 * stores them; the tools only report. Recording must never fail a tool: implementations swallow their
 * own errors.
 */
fun interface ChangeRecorder {
    suspend fun record(change: FileChange)
}
