package dev.locus.core.database

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import dev.locus.core.model.TASK_BUDGET_ENTRY
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    /** Every conversation, helper (sub-agent) chats included. For analytics; lists use [observeVisible]. */
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    /** The user's chats, without hidden helper chats: home, search and history. */
    @Query("SELECT * FROM conversations WHERE hidden = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeVisible(): Flow<List<ConversationEntity>>

    /** Helper chats started by [parentId], oldest first. Read-only. */
    @Query("SELECT * FROM conversations WHERE parentConversationId = :parentId ORDER BY createdAt")
    suspend fun children(parentId: String): List<ConversationEntity>

    /** The helper chat started by the parent's call [callId]. */
    @Query("SELECT * FROM conversations WHERE parentCallId = :callId LIMIT 1")
    fun observeByParentCall(callId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE parentCallId = :callId LIMIT 1")
    suspend fun byParentCall(callId: String): ConversationEntity?

    /** Helper chats in [status] (e.g. still "running" after the app was killed). */
    @Query("SELECT * FROM conversations WHERE hidden = 1 AND agentStatus = :status")
    suspend fun helpersWithStatus(status: String): List<ConversationEntity>

    @Query("SELECT COUNT(*) FROM conversations WHERE parentConversationId = :parentId AND agentType = :type")
    suspend fun countChildren(parentId: String, type: String): Int

    @Query("UPDATE conversations SET agentStatus = :status WHERE id = :id")
    suspend fun setAgentStatus(id: String, status: String)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE conversations SET providerId = :providerId WHERE id = :id")
    suspend fun setProvider(id: String, providerId: String)

    @Query("UPDATE conversations SET modelOverride = :model WHERE id = :id")
    suspend fun setModelOverride(id: String, model: String?)

    @Query("UPDATE conversations SET effort = :effort WHERE id = :id")
    suspend fun setEffort(id: String, effort: String?)

    @Query("UPDATE conversations SET planMode = :on WHERE id = :id")
    suspend fun setPlanMode(id: String, on: Boolean)

    @Query("UPDATE conversations SET disabledTools = :keys WHERE id = :id")
    suspend fun setDisabledTools(id: String, keys: String)

    /** Shows (and continues) the branch ending at [leafId]. */
    @Query("UPDATE conversations SET activeLeafId = :leafId WHERE id = :id")
    suspend fun setActiveLeaf(id: String, leafId: String?)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    /** Chats with no messages at all, e.g. a "New chat" opened and left. Read-only. */
    @Query("SELECT id FROM conversations WHERE hidden = 0 AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.conversationId = conversations.id)")
    fun observeEmptyIds(): Flow<List<String>>
}

@Dao
interface MessageDao {
    /**
     * Every message of a chat (all branches) in order, each with the chat's active leaf. One query over
     * both tables, so a new message and the leaf moving to it always arrive together.
     */
    @Query(
        "SELECT messages.*, conversations.activeLeafId AS activeLeafId FROM messages " +
            "JOIN conversations ON conversations.id = messages.conversationId WHERE messages.conversationId = :conversationId ORDER BY messages.seq",
    )
    fun observeTree(conversationId: String): Flow<List<TreeRow>>

    @Query(
        "SELECT messages.*, conversations.activeLeafId AS activeLeafId FROM messages " +
            "JOIN conversations ON conversations.id = messages.conversationId WHERE messages.conversationId = :conversationId ORDER BY messages.seq",
    )
    suspend fun tree(conversationId: String): List<TreeRow>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    /** The chat's active leaf if it's one of its messages, else its newest message; null for an empty chat. */
    @Query(
        "SELECT COALESCE((SELECT messages.id FROM messages JOIN conversations ON conversations.activeLeafId = messages.id " +
            "WHERE conversations.id = :conversationId AND messages.conversationId = :conversationId), " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId ORDER BY seq DESC LIMIT 1))",
    )
    suspend fun leaf(conversationId: String): String?

    /**
     * Messages matching the FTS5 query [match], at most one per chat (its newest match), newest first,
     * each with a snippet of [tokens] tokens where matches sit between U+E000 and U+E001. Read-only.
     * See [SEARCH_MESSAGES].
     */
    @Query(SEARCH_MESSAGES)
    suspend fun search(match: String, tokens: Int, limit: Int): List<MessageHitRow>

    /** A chat's message links and active leaf, without content: enough to find its active path. Read-only. */
    @Query(
        "SELECT messages.id AS id, messages.parentId AS parentId, conversations.activeLeafId AS activeLeafId FROM messages " +
            "JOIN conversations ON conversations.id = messages.conversationId WHERE messages.conversationId = :conversationId ORDER BY messages.seq",
    )
    suspend fun links(conversationId: String): List<LinkRow>

    @Query("SELECT parentId FROM messages WHERE id = :id")
    suspend fun parentOf(id: String): String?

    @Query("UPDATE conversations SET activeLeafId = :leafId, updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun moveLeaf(conversationId: String, leafId: String, updatedAt: Long)

    /** Adds [message] and makes it the chat's active leaf, atomically. */
    @Transaction
    suspend fun insertAsLeaf(message: MessageEntity, updatedAt: Long) {
        insert(message)
        moveLeaf(message.conversationId, message.id, updatedAt)
    }

    /**
     * Timing and usage of every message since [since], in order, without content. Read-only, for analytics.
     * Copies made by forking are left out: their requests were counted in the chat they came from.
     */
    @Query(MESSAGE_STATS)
    fun observeStats(since: Long): Flow<List<MessageStatRow>>
}

@Dao
interface UsageEventDao {
    @Insert
    suspend fun insert(event: UsageEventEntity)

    /** Side requests since [since], oldest first. Read-only, for analytics. */
    @Query("SELECT * FROM usage_events WHERE createdAt >= :since ORDER BY seq")
    fun observeSince(since: Long): Flow<List<UsageEventEntity>>
}

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun get(id: String): ScheduledTaskEntity?

    @Upsert
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_log ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntity>>

    @Insert
    suspend fun insert(entry: AuditEntity)

    /** Outcome and timing of every tool call since [since], without arguments or results. Read-only, for analytics. */
    @Query("SELECT conversationId, toolName, decision, isError, startedAt, finishedAt FROM audit_log WHERE startedAt >= :since")
    fun observeStats(since: Long): Flow<List<AuditStatRow>>

    /**
     * A filtered window of the log, newest first, for the Activity screen. Read-only. Each filter is
     * off when its flag says so: [conversationId] null means every chat, [allTools]/[allDecisions]
     * skip the [tools]/[decisions] lists, and a null [pattern] (a LIKE pattern, `\` escapes) skips
     * the text search, which matches arguments, result previews, chat titles, or [textTools] (tool
     * names whose plain-language label matched). [until] ends the time span; [withHelpers] adds the
     * helper chats [conversationId] started (a task receipt's view). The window grows by raising [limit].
     */
    @Query(
        "SELECT * FROM audit_log WHERE startedAt >= :since AND startedAt <= :until " +
            "AND (:conversationId IS NULL OR conversationId = :conversationId " +
            "OR (:withHelpers AND conversationId IN (SELECT id FROM conversations WHERE parentConversationId = :conversationId))) " +
            "AND (:allTools OR toolName IN (:tools)) " +
            // A task's budget entries carry the user's Continue / Stop as a decision, but aren't approvals.
            "AND (:allDecisions OR (decision IN (:decisions) AND toolName != '$TASK_BUDGET_ENTRY')) " +
            "AND (NOT :onlyErrors OR isError = 1) " +
            "AND (:pattern IS NULL OR toolName IN (:textTools) " +
            "OR argumentsJson LIKE :pattern ESCAPE '\\' OR resultPreview LIKE :pattern ESCAPE '\\' " +
            "OR conversationId IN (SELECT id FROM conversations WHERE title LIKE :pattern ESCAPE '\\')) " +
            "ORDER BY startedAt DESC LIMIT :limit",
    )
    fun observeFiltered(
        since: Long,
        conversationId: String?,
        allTools: Boolean,
        tools: List<String>,
        allDecisions: Boolean,
        decisions: List<String>,
        onlyErrors: Boolean,
        pattern: String?,
        textTools: List<String>,
        limit: Int,
        until: Long,
        withHelpers: Boolean,
    ): Flow<List<AuditEntity>>

    /** Every tool name that appears in the log. Read-only; lets filters map categories and labels to names. */
    @Query("SELECT DISTINCT toolName FROM audit_log")
    fun observeToolNames(): Flow<List<String>>

    /**
     * How many calls since [since] (in one chat, or all when null) and how many of them failed. A task's
     * budget entries aren't calls. Read-only.
     */
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN isError = 1 AND decision NOT IN ('USER_DENIED', 'POLICY_DENIED', 'REVIEWER_DENIED') THEN 1 ELSE 0 END), 0) AS failed " +
            "FROM audit_log WHERE startedAt >= :since AND (:conversationId IS NULL OR conversationId = :conversationId) " +
            "AND toolName != '$TASK_BUDGET_ENTRY'",
    )
    fun observeCounts(since: Long, conversationId: String?): Flow<AuditCountRow>
}

/** Totals for a slice of the audit log. Read-only projection. */
data class AuditCountRow(val total: Int, val failed: Int)

/**
 * The full-text search behind [MessageDao.search]. Every branch of a chat is searched; hidden helper
 * chats are not. A copy made by forking is skipped while the message it was copied from still exists,
 * so a forked conversation is found once, where it was written. Per chat only the newest match is
 * kept (SQLite takes the bare columns of a MAX() aggregate from the row that has the maximum).
 */
const val SEARCH_MESSAGES = "SELECT m.id AS messageId, m.conversationId AS conversationId, m.role AS role, m.createdAt AS createdAt, " +
    "snippet(message_fts, 0, char(57344), char(57345), '…', :tokens) AS snippet " +
    "FROM message_fts JOIN messages AS m ON m.seq = message_fts.rowid " +
    "WHERE message_fts MATCH :match AND m.seq IN (" +
    "SELECT MAX(h.seq) FROM message_fts AS f JOIN messages AS h ON h.seq = f.rowid " +
    "JOIN conversations AS c ON c.id = h.conversationId " +
    "WHERE f.message_fts MATCH :match AND c.hidden = 0 " +
    "AND (h.copiedFrom IS NULL OR NOT EXISTS (SELECT 1 FROM messages AS s WHERE s.id = h.copiedFrom)) " +
    "GROUP BY h.conversationId) " +
    "ORDER BY m.createdAt DESC, m.seq DESC LIMIT :limit"

/**
 * The query behind [MessageDao.observeStats]: each message's timing, usage and (since v9) the model that
 * answered, with its parent's time for older replies' speed. Fork copies are left out.
 */
const val MESSAGE_STATS = "SELECT m.conversationId AS conversationId, m.role AS role, m.createdAt AS createdAt, m.usageJson AS usageJson, " +
    "p.createdAt AS parentCreatedAt, m.providerId AS providerId, m.model AS model, m.firstTokenMs AS firstTokenMs, m.totalMs AS totalMs " +
    "FROM messages AS m LEFT JOIN messages AS p ON p.id = m.parentId WHERE m.createdAt >= :since AND m.copiedFrom IS NULL ORDER BY m.seq"

/** A message found by [MessageDao.search]. Read-only projection. */
data class MessageHitRow(val messageId: String, val conversationId: String, val role: String, val createdAt: Long, val snippet: String)

/** A message's place in its chat's tree (see [MessageDao.links]). Read-only projection. */
data class LinkRow(val id: String, val parentId: String?, val activeLeafId: String?)

/** A message with its chat's active leaf (see [MessageDao.observeTree]). Read-only projection. */
data class TreeRow(@Embedded val message: MessageEntity, val activeLeafId: String?)

/** Task records for receipts (v14). See [TaskRunEntity]. */
@Dao
interface TaskRunDao {
    @Insert
    suspend fun insert(run: TaskRunEntity)

    @Query("UPDATE task_runs SET endedAt = :endedAt, outcome = :outcome, detail = :detail WHERE id = :id")
    suspend fun finish(id: String, endedAt: Long, outcome: String, detail: String?)

    /** A chat's tasks, oldest first. */
    @Query("SELECT * FROM task_runs WHERE conversationId = :conversationId ORDER BY startedAt")
    fun observe(conversationId: String): Flow<List<TaskRunEntity>>

    @Query("SELECT * FROM task_runs WHERE conversationId = :conversationId ORDER BY startedAt")
    suspend fun all(conversationId: String): List<TaskRunEntity>

    /** Tasks never finished: running now, or cut off when the app was killed. */
    @Query("SELECT * FROM task_runs WHERE endedAt IS NULL")
    suspend fun open(): List<TaskRunEntity>
}

/** A helper chat's model request, without content, for its parent's task receipts. Read-only projection. */
data class HelperUsageRow(
    val conversationId: String,
    val parentCallId: String?,
    val agentType: String?,
    val createdAt: Long,
    val usageJson: String?,
    val providerId: String?,
    val model: String?,
)

/** What a chat's task receipts read besides its messages: helpers' requests, side requests and the audit log. Read-only. */
@Dao
interface ReceiptDao {
    /** Every assistant turn of the helper chats [parentId] started, with the call that started each. */
    @Query(
        "SELECT m.conversationId AS conversationId, c.parentCallId AS parentCallId, c.agentType AS agentType, m.createdAt AS createdAt, " +
            "m.usageJson AS usageJson, m.providerId AS providerId, m.model AS model " +
            "FROM messages AS m JOIN conversations AS c ON c.id = m.conversationId " +
            "WHERE c.parentConversationId = :parentId AND m.role = 'ASSISTANT' ORDER BY m.seq",
    )
    fun observeHelperUsage(parentId: String): Flow<List<HelperUsageRow>>

    /** Side requests (compaction, the safety reviewer…) of one chat, oldest first. */
    @Query("SELECT * FROM usage_events WHERE conversationId = :conversationId ORDER BY seq")
    fun observeSideUsage(conversationId: String): Flow<List<UsageEventEntity>>

    /** The audit entries of a chat and of the helpers it started, oldest first. */
    @Query(
        "SELECT * FROM audit_log WHERE conversationId = :conversationId " +
            "OR conversationId IN (SELECT id FROM conversations WHERE parentConversationId = :conversationId) ORDER BY startedAt",
    )
    fun observeAudit(conversationId: String): Flow<List<AuditEntity>>
}
