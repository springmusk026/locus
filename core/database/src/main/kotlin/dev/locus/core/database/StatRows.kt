package dev.locus.core.database

/** A message's timing and token usage, without its content. Read-only projection for analytics. */
data class MessageStatRow(
    val conversationId: String,
    val role: String,
    val createdAt: Long,
    val usageJson: String?,
    /** When the message it follows was written (its parent in the branch tree); null for a chat's first. */
    val parentCreatedAt: Long? = null,
)

/** A tool call's outcome and timing, without arguments or results. Read-only projection for analytics. */
data class AuditStatRow(
    val conversationId: String,
    val toolName: String,
    val decision: String,
    val isError: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
)
