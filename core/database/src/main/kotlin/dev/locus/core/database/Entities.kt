package dev.locus.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts5
import androidx.room3.FtsOptions
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
    /** The last message of the branch shown and continued; null means the newest message (v7). */
    @ColumnInfo(defaultValue = "NULL") val activeLeafId: String? = null,
    /** Built-in integrations this chat reaches through their MCP servers, newline-separated ids, fixed at creation (v10). */
    @ColumnInfo(defaultValue = "") val mcpFirst: String = "",
)

/**
 * Append-only. [seq] gives a total order that survives equal timestamps. Messages form a tree through
 * [parentId] (branches from regenerating or editing); the chat shows one path through it.
 */
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
    /** The message this one follows; null for a chat's first message (v7, backfilled linearly). */
    @ColumnInfo(defaultValue = "NULL") val parentId: String? = null,
    /** For a copy made by forking a chat: the source message's id, so its usage isn't counted twice (v7). */
    @ColumnInfo(defaultValue = "NULL") val copiedFrom: String? = null,
    /**
     * What a person would search for in this message, as plain text: the user's typed text and the
     * names of files they attached, or the assistant's reply. Null for tool results and for messages
     * with nothing to find. Derived from the parts when the row is written; indexed by [MessageFtsEntity] (v8).
     */
    @ColumnInfo(defaultValue = "NULL") val searchText: String? = null,
    /** Assistant turns: the provider config that answered (v9). Null for other roles and older turns. */
    @ColumnInfo(defaultValue = "NULL") val providerId: String? = null,
    /** Assistant turns: the model id that answered (v9). */
    @ColumnInfo(defaultValue = "NULL") val model: String? = null,
    /** Assistant turns: ms from sending the request to the first streamed output (v9). */
    @ColumnInfo(defaultValue = "NULL") val firstTokenMs: Long? = null,
    /** Assistant turns: ms from sending the request to the finished turn (v9). */
    @ColumnInfo(defaultValue = "NULL") val totalMs: Long? = null,
    /** Assistant turns answered by a backup model: the chat's own model it stood in for (v11). */
    @ColumnInfo(defaultValue = "NULL") val fallbackFrom: String? = null,
)

/**
 * The full-text index over [MessageEntity.searchText] (v8). An FTS5 table with external content: it
 * stores only the index, reads the text from `messages`, and its rowid is the message's [MessageEntity.seq].
 * Room creates the triggers that keep it in step with `messages`.
 */
@Fts5(
    contentEntity = MessageEntity::class,
    contentRowId = "seq",
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
)
@Entity(tableName = "message_fts")
data class MessageFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val searchText: String?,
)

/**
 * A model request that isn't stored as a message (v9): a chat title, follow-up suggestions, a
 * compaction summary, a safety review, a reflection, or another helper-model job. Counts and timing
 * only, never content. Deleted with its chat; [conversationId] is null for requests outside a chat
 * (e.g. onboarding).
 */
@Entity(
    tableName = "usage_events",
    foreignKeys = [ForeignKey(ConversationEntity::class, ["id"], ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index("createdAt")],
)
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val conversationId: String?,
    /** `UsageSource.wire`: "title", "suggestions", "compaction", "reviewer", "reflection", "side_question" or "other". */
    val source: String,
    val createdAt: Long,
    val providerId: String?,
    val model: String?,
    /** JSON TokenUsage, as on messages; null when the provider reported none. */
    val usageJson: String?,
    val totalMs: Long?,
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
    /** [dev.locus.core.model.TaskRunOutcome] name of the last run; null before the first. */
    val lastOutcome: String? = null,
    /**
     * The per-run idempotency marker: set just before a run sends its prompt, cleared when the worker
     * returns. A worker that finds it set for its own work id is a retry of a run that already sent
     * the prompt (Android stopped it, or the process died), and never sends it again.
     */
    val runWorkId: String? = null,
    /** The chat the marked run writes to. */
    val runConversationId: String? = null,
    /** When the marked run started (wall clock); a marker older than the run limit is stale. */
    val runStartedAt: Long? = null,
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
