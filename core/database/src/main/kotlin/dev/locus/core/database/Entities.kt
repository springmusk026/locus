package dev.locus.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "conversations", indices = [Index("parentConversationId"), Index("parentCallId")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String?,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Pinned chats stay at the top of the home list. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Model id used instead of the provider's default for this chat (v5). */
    @ColumnInfo(defaultValue = "NULL") val modelOverride: String? = null,
    /** Effort level name (Effort enum) for this chat; null follows the provider (v5). */
    @ColumnInfo(defaultValue = "NULL") val effort: String? = null,
    /** Plan mode on for this chat (v5). */
    @ColumnInfo(defaultValue = "0") val planMode: Boolean = false,
    /** Tool groups and connectors switched off for this chat, newline-separated keys (v5). */
    @ColumnInfo(defaultValue = "") val disabledTools: String = "",
    /** A helper (sub-agent) chat: the chat that started it (v6). */
    @ColumnInfo(defaultValue = "NULL") val parentConversationId: String? = null,
    /** A helper chat: the parent's agent_spawn call (v6). */
    @ColumnInfo(defaultValue = "NULL") val parentCallId: String? = null,
    /** A helper chat: its profile, e.g. "researcher" (v6). */
    @ColumnInfo(defaultValue = "NULL") val agentType: String? = null,
    /** A helper chat: SubagentStatus wire name (v6). */
    @ColumnInfo(defaultValue = "NULL") val agentStatus: String? = null,
    /** Left out of the chat list, search and history (helper chats) (v6). */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

/** Append-only. [seq] gives a total order that survives equal timestamps. */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index("id", unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    val conversationId: String,
    val role: String,
    val partsJson: String,
    val createdAt: Long,
    val format: String?,
    val rawPayload: String?,
    /** JSON TokenUsage for assistant turns. */
    @ColumnInfo(defaultValue = "NULL") val usageJson: String? = null,
)

/** A prompt the agent runs on a schedule, read-only, notifying the user with the result. */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val prompt: String,
    val intervalMinutes: Long,
    val enabled: Boolean,
    val createdAt: Long,
    val lastRunAt: Long?,
    val lastConversationId: String?,
    val lastSummary: String?,
)

@Entity(
    tableName = "audit_log",
    indices = [Index("conversationId"), Index("startedAt")],
)
data class AuditEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val decision: String,
    val resultPreview: String,
    val isError: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
)
