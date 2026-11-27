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

    /**
     * Temporary chats: hidden like a helper's chat, but the user's own (no parent). See
     * `Conversation.temporary` and docs/design/temporary-chats.md.
     */
    @Query("SELECT * FROM conversations WHERE hidden = 1 AND parentConversationId IS NULL")
    suspend fun temporaryChats(): List<ConversationEntity>

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

    /**
     * Gives [effort] to the chats that follow [providerId]'s own effort and already have messages, so a change
     * to the provider's setting doesn't change their requests. [orphans]: the provider is also the one chats
     * without a provider, or with one removed (not in [known]), fall back to. Helper chats are left out.
     */
    @Query(
        "UPDATE conversations SET effort = :effort WHERE effort IS NULL AND parentConversationId IS NULL " +
            "AND EXISTS (SELECT 1 FROM messages WHERE messages.conversationId = conversations.id) " +
            "AND (providerId = :providerId OR (:orphans AND (providerId IS NULL OR providerId NOT IN (:known))))",
    )
    suspend fun keepEffort(providerId: String, orphans: Boolean, known: List<String>, effort: String): Int

    @Query("UPDATE conversations SET planMode = :on WHERE id = :id")
    suspend fun setPlanMode(id: String, on: Boolean)

    @Query("UPDATE conversations SET chatOnly = :on WHERE id = :id")
    suspend fun setChatOnly(id: String, on: Boolean)

    @Query("UPDATE conversations SET disabledTools = :keys WHERE id = :id")
    suspend fun setDisabledTools(id: String, keys: String)

    /** Puts a chat in a project (or takes it out with nulls). Only for chats with no messages; see ConversationRepository.moveToProject. */
    @Query("UPDATE conversations SET projectId = :projectId, projectMemory = :memory, systemPrompt = :systemPrompt, mcpFirst = :mcpFirst WHERE id = :id")
    suspend fun setProject(id: String, projectId: String?, memory: String?, systemPrompt: String, mcpFirst: String)

    /** Moves a deleted project's chats out of it; their frozen prompts stay as they are. */
    @Query("UPDATE conversations SET projectId = NULL WHERE projectId = :projectId")
    suspend fun releaseProject(projectId: String)

    /** Shows (and continues) the branch ending at [leafId]. */
    @Query("UPDATE conversations SET activeLeafId = :leafId WHERE id = :id")
    suspend fun setActiveLeaf(id: String, leafId: String?)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    /** Renames the chat only while its title is still [expected]; returns the rows changed (0 or 1). */
    @Query("UPDATE conversations SET title = :title WHERE id = :id AND title = :expected")
    suspend fun renameIf(id: String, expected: String, title: String): Int

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Chats imported from a ChatGPT or Claude export (their ids start with `imported-`; see
     * `dev.locus.core.model.ImportedChats`), with when they were last updated, so importing the same
     * export again skips them or replaces older copies. Read-only.
     */
    @Query("SELECT id, updatedAt, pinned FROM conversations WHERE id LIKE 'imported-%'")
    suspend fun importedStamps(): List<ImportedStamp>

    /** Chats with no messages at all, e.g. a "New chat" opened and left. Read-only. */
    @Query("SELECT id FROM conversations WHERE hidden = 0 AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.conversationId = conversations.id)")
    fun observeEmptyIds(): Flow<List<String>>
}

@Dao
interface MessageDao {
    /**
     * How far a chat's transcript has got: its message count, newest [MessageEntity.seq] and active leaf,
     * read in one statement. Room re-runs it on every write to `messages` or `conversations` (it tracks
     * tables, not rows), but it reads a single row through the `conversationId` index, so a screen can
     * watch it cheaply and load messages only when it changes for its own chat. See
     * docs/design/transcript-reads.md.
     */
    @Query(
        "SELECT COUNT(*) AS count, COALESCE(MAX(seq), 0) AS maxSeq, " +
            "(SELECT activeLeafId FROM conversations WHERE id = :conversationId) AS activeLeafId " +
            "FROM messages WHERE conversationId = :conversationId",
    )
    fun observeVersion(conversationId: String): Flow<TranscriptVersion>

    /**
     * A chat's messages with [MessageEntity.seq] in (after, upTo], in order, for display: without
     * `rawPayload` (provider replay data the UI never reads) and `searchText`. Read-only; requests are
     * built from [tree], which keeps every column.
     */
    @Query(
        "SELECT seq, id, conversationId, role, partsJson, createdAt, format, usageJson, parentId, providerId, model, firstTokenMs, totalMs, fallbackFrom " +
            "FROM messages WHERE conversationId = :conversationId AND seq > :after AND seq <= :upTo ORDER BY seq",
    )
    suspend fun viewRows(conversationId: String, after: Long, upTo: Long): List<MessageViewRow>

    /**
     * Every message of a chat (all branches) in order, each with the chat's active leaf, with every
     * column: what model requests, recovery, compaction and forks read.
     */
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

    @Upsert
    suspend fun upsertImportedConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteImportedConversation(id: String)

    /**
     * Writes one imported chat (docs/design/import-chats.md): its row and all its messages, in one
     * transaction, so a cancelled or failed import never leaves half a chat. With [replacing], the older
     * copy of the same chat goes first (its messages with it, by cascade).
     */
    @Transaction
    suspend fun saveImportedChat(conversation: ConversationEntity, messages: List<MessageEntity>, replacing: Boolean) {
        if (replacing) deleteImportedConversation(conversation.id)
        upsertImportedConversation(conversation)
        insertAll(messages)
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

    @Insert
    suspend fun insertAll(events: List<UsageEventEntity>)

    /**
     * Keeps a chat's side requests when the chat is deleted (a temporary chat): their counts and cost stay
     * in Insights, no longer linked to any chat. Without this, deleting the chat would cascade them away.
     */
    @Query("UPDATE usage_events SET conversationId = NULL WHERE conversationId = :conversationId")
    suspend fun detach(conversationId: String)

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

    /** For a new task (or a restore). Changes to an existing one go through the column updates below, so they never overwrite a run's marker. */
    @Upsert
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: String)

    /** Edits what and when the task runs, leaving its run columns alone. Returns the rows changed (0 when it's gone). */
    @Query(
        "UPDATE scheduled_tasks SET title = :title, prompt = :prompt, intervalMinutes = :intervalMinutes, " +
            "requiresCharging = :requiresCharging, unmeteredOnly = :unmeteredOnly, batteryNotLow = :batteryNotLow WHERE id = :id",
    )
    suspend fun setDefinition(
        id: String,
        title: String,
        prompt: String,
        intervalMinutes: Long,
        requiresCharging: Boolean,
        unmeteredOnly: Boolean,
        batteryNotLow: Boolean,
    ): Int

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean): Int

    /** Sets what starts the task (null: its schedule), leaving its run columns alone. A new trigger starts with no state. */
    @Query("UPDATE scheduled_tasks SET triggerJson = :triggerJson, triggerStateJson = NULL WHERE id = :id")
    suspend fun setTrigger(id: String, triggerJson: String?): Int

    /** Records where the trigger's source stands (its cursor, how the last check went). */
    @Query("UPDATE scheduled_tasks SET triggerStateJson = :stateJson WHERE id = :id")
    suspend fun setTriggerState(id: String, stateJson: String?): Int

    /** The user has seen task [id]'s latest result in Needs you (opened or dismissed it). */
    @Query("UPDATE scheduled_tasks SET lastSeenRunAt = lastRunAt WHERE id = :id")
    suspend fun markSeen(id: String): Int

    /** The user opened chat [conversationId]: the latest result of a task whose last run wrote it counts as seen. */
    @Query("UPDATE scheduled_tasks SET lastSeenRunAt = lastRunAt WHERE lastConversationId = :conversationId AND lastRunAt IS NOT NULL")
    suspend fun markSeenForChat(conversationId: String): Int

    /** Every task's latest result counts as seen ("Mark all read"). */
    @Query("UPDATE scheduled_tasks SET lastSeenRunAt = lastRunAt WHERE lastRunAt IS NOT NULL")
    suspend fun markAllSeen(): Int

    /** Records how a run ended, leaving the marker alone. */
    @Query(
        "UPDATE scheduled_tasks SET lastRunAt = :at, lastConversationId = :conversationId, lastSummary = :summary, lastOutcome = :outcome WHERE id = :id",
    )
    suspend fun setLastRun(id: String, at: Long, conversationId: String, summary: String?, outcome: String)

    /** See [CLAIM_RUN]. Returns the rows changed: 1 when [workId] now holds the task, 0 when another run does. */
    @Query(CLAIM_RUN)
    suspend fun claimRunRow(id: String, workId: String, now: Long, staleBefore: Long): Int

    /**
     * Claims task [id] for work [workId] in one transaction (see [CLAIM_RUN]). Returns the row as it was
     * just before the claim, so the caller knows whose stale marker it replaced, or null when the claim
     * failed because another run holds the task (or it's gone).
     */
    @Transaction
    suspend fun claimRun(id: String, workId: String, now: Long, staleBefore: Long): ScheduledTaskEntity? {
        val before = get(id) ?: return null
        return if (claimRunRow(id, workId, now, staleBefore) == 1) before else null
    }

    /** Names the chat the claimed run sends its prompt to; 0 rows when [workId] no longer holds the task. */
    @Query(SET_RUN_CONVERSATION)
    suspend fun setRunConversation(id: String, workId: String, conversationId: String): Int

    /** Clears the marker, only while [workId] holds it (another run may have taken over a stale one). */
    @Query(RELEASE_RUN)
    suspend fun releaseRun(id: String, workId: String): Int
}

/**
 * The single-flight claim behind [ScheduledTaskDao.claimRun]: one statement, so two workers starting the
 * same task at once (the schedule and Run now) can't both take it. It succeeds when no run holds the task,
 * when the holder's marker is stale (started at or before [staleBefore]: no worker lives that long), or when
 * [workId] itself holds it without a chat yet (it claimed, then died before sending anything). A claim
 * has no chat (`runConversationId` NULL) until [ScheduledTaskDao.setRunConversation] names one.
 */
const val CLAIM_RUN = "UPDATE scheduled_tasks SET runWorkId = :workId, runConversationId = NULL, runStartedAt = :now " +
    "WHERE id = :id AND (runWorkId IS NULL OR runStartedAt IS NULL OR runStartedAt <= :staleBefore " +
    "OR (runWorkId = :workId AND runConversationId IS NULL))"

/** Behind [ScheduledTaskDao.setRunConversation]: only the run holding the task names its chat. */
const val SET_RUN_CONVERSATION = "UPDATE scheduled_tasks SET runConversationId = :conversationId WHERE id = :id AND runWorkId = :workId"

/** Behind [ScheduledTaskDao.releaseRun]: only the run holding the task releases it. */
const val RELEASE_RUN = "UPDATE scheduled_tasks SET runWorkId = NULL, runConversationId = NULL, runStartedAt = NULL WHERE id = :id AND runWorkId = :workId"

/**
 * The rest of [AuditDao.observeFiltered] once there is no text to search for: tool, decision, error and time
 * filters, newest first. Behind [AuditDao.observeBrowse] and its two chat variants.
 */
private const val AUDIT_BROWSE_FILTERS = "AND startedAt >= :since AND startedAt <= :until " +
    "AND (:allTools OR toolName IN (:tools)) " +
    // A task's budget entries carry the user's Continue / Stop as a decision, but aren't approvals.
    "AND (:allDecisions OR (decision IN (:decisions) AND toolName != '$TASK_BUDGET_ENTRY')) " +
    "AND (NOT :onlyErrors OR isError = 1) " +
    "ORDER BY startedAt DESC LIMIT :limit"

/** Every chat: no `conversations` table in it, so a chat being written doesn't run it again. */
const val AUDIT_BROWSE_ALL = "SELECT * FROM audit_log WHERE 1 $AUDIT_BROWSE_FILTERS"

/** One chat, found through the `conversationId` index. */
const val AUDIT_BROWSE_CHAT = "SELECT * FROM audit_log WHERE conversationId = :conversationId $AUDIT_BROWSE_FILTERS"

/** One chat and its helpers: the only browse query that reads `conversations`. */
const val AUDIT_BROWSE_CHAT_AND_HELPERS = "SELECT * FROM audit_log WHERE (conversationId = :conversationId " +
    "OR conversationId IN (SELECT id FROM conversations WHERE parentConversationId = :conversationId)) $AUDIT_BROWSE_FILTERS"

/**
 * The filters of an Activity text search, shared by [AUDIT_SEARCH] and [AUDIT_SEARCH_SCAN]. Neither reads
 * `conversations`: the chats to look in ([chats], a chat and its helpers) and the chats whose title matched
 * ([titleChats]) come in as lists, worked out from their own small queries ([AuditDao.observeTitleMatches],
 * [AuditDao.observeHelperChats]), so writing a chat doesn't run the search again. The text test is the `LIKE` the
 * search has always used (`\` escapes): in [AUDIT_SEARCH] it confirms each row the index found, so the results are
 * exactly what the `LIKE` alone returns.
 */
private const val AUDIT_SEARCH_FILTERS = "AND startedAt >= :since AND startedAt <= :until " +
    "AND (:allChats OR conversationId IN (:chats)) " +
    "AND (:allTools OR toolName IN (:tools)) " +
    "AND (:allDecisions OR (decision IN (:decisions) AND toolName != '$TASK_BUDGET_ENTRY')) " +
    "AND (NOT :onlyErrors OR isError = 1) " +
    "AND (toolName IN (:textTools) OR conversationId IN (:titleChats) " +
    "OR argumentsJson LIKE :pattern ESCAPE '\\' OR resultPreview LIKE :pattern ESCAPE '\\') " +
    "ORDER BY startedAt DESC LIMIT :limit"

/**
 * Activity's text search through the `audit_fts` index: the rows whose arguments or result preview contain the
 * phrase [match] (trigram, so three characters or more), whose tool label matched, or whose chat's title did. Only
 * those rows are read and sorted; the log isn't scanned.
 */
const val AUDIT_SEARCH = "SELECT * FROM audit_log WHERE seq IN (" +
    "SELECT rowid FROM audit_fts WHERE audit_fts MATCH :match " +
    "UNION SELECT seq FROM audit_log WHERE toolName IN (:textTools) " +
    "UNION SELECT seq FROM audit_log WHERE conversationId IN (:titleChats)) $AUDIT_SEARCH_FILTERS"

/** [AUDIT_SEARCH] for text too short for the index (under three characters): the log in time order, filtered. */
const val AUDIT_SEARCH_SCAN = "SELECT * FROM audit_log WHERE 1 $AUDIT_SEARCH_FILTERS"

/**
 * The text search as it was before the index (v20 and earlier), behind [AuditDao.observeFiltered]: `LIKE` over every
 * row, with chat titles and helper chats read from `conversations`. Kept for the rare search whose chat lists are too
 * long to bind, and as the reference the indexed search is tested against (AuditSearchSqlTest).
 */
const val AUDIT_SEARCH_LIKE =
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
        "ORDER BY startedAt DESC LIMIT :limit"

@Dao
interface AuditDao {
    /** Forgets a deleted temporary chat's tool calls (docs/design/temporary-chats.md). */
    @Query("DELETE FROM audit_log WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("SELECT * FROM audit_log ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntity>>

    @Insert
    suspend fun insert(entry: AuditEntity)

    /** The entries of [callIds] in one chat: a call gets its entry once it finished (recovery after a restart). */
    @Query("SELECT * FROM audit_log WHERE conversationId = :conversationId AND callId IN (:callIds)")
    suspend fun forCalls(conversationId: String, callIds: List<String>): List<AuditEntity>

    /** Outcome and timing of every tool call since [since], without arguments or results. Read-only, for analytics. */
    @Query("SELECT conversationId, toolName, decision, isError, startedAt, finishedAt FROM audit_log WHERE startedAt >= :since")
    fun observeStats(since: Long): Flow<List<AuditStatRow>>

    /**
     * A filtered window of the log, newest first, for the Activity screen: the general query, used when
     * there is text to search for. Read-only. Each filter is off when its flag says so: [conversationId]
     * null means every chat, [allTools]/[allDecisions] skip the [tools]/[decisions] lists, and a null
     * [pattern] (a LIKE pattern, `\` escapes) skips the text search, which matches arguments, result
     * previews, chat titles, or [textTools] (tool names whose plain-language label matched). [until] ends
     * the time span; [withHelpers] adds the helper chats [conversationId] started (a task receipt's view).
     * The window grows by raising [limit].
     *
     * It reads `conversations` for titles, so Room runs it again on every write to that table, and it scans the log.
     * Browsing without text uses [observeBrowse], [observeBrowseChat] and [observeBrowseChatAndHelpers]; text search
     * uses [observeSearch] (or [observeSearchScan]). This one is left for the rare search whose title matches are
     * too many to pass as a list.
     */
    @Query(AUDIT_SEARCH_LIKE)
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

    /**
     * Activity's text search through the index ([AUDIT_SEARCH]). [match] is an FTS5 phrase of three or more
     * characters and [pattern] the same text as a `LIKE` pattern; [chats] (unless [allChats]) are the chats to look
     * in and [titleChats] the chats whose title matched. Reads `audit_log` alone, so only a recorded tool call runs it again.
     */
    @Query(AUDIT_SEARCH)
    fun observeSearch(
        match: String,
        pattern: String,
        textTools: List<String>,
        titleChats: List<String>,
        allChats: Boolean,
        chats: List<String>,
        since: Long,
        until: Long,
        allTools: Boolean,
        tools: List<String>,
        allDecisions: Boolean,
        decisions: List<String>,
        onlyErrors: Boolean,
        limit: Int,
    ): Flow<List<AuditEntity>>

    /** [observeSearch] for text under three characters, which the trigram index can't look up ([AUDIT_SEARCH_SCAN]). */
    @Query(AUDIT_SEARCH_SCAN)
    fun observeSearchScan(
        pattern: String,
        textTools: List<String>,
        titleChats: List<String>,
        allChats: Boolean,
        chats: List<String>,
        since: Long,
        until: Long,
        allTools: Boolean,
        tools: List<String>,
        allDecisions: Boolean,
        decisions: List<String>,
        onlyErrors: Boolean,
        limit: Int,
    ): Flow<List<AuditEntity>>

    /** The chats whose title matches [pattern] (a `LIKE` pattern, `\` escapes), for [observeSearch]. */
    @Query("SELECT id FROM conversations WHERE title LIKE :pattern ESCAPE '\\' ORDER BY id")
    fun observeTitleMatches(pattern: String): Flow<List<String>>

    /** The helper chats [conversationId] started, for [observeSearch] on a task receipt. */
    @Query("SELECT id FROM conversations WHERE parentConversationId = :conversationId ORDER BY id")
    fun observeHelperChats(conversationId: String): Flow<List<String>>

    /**
     * [observeFiltered] with no text to search for, across every chat. It reads only `audit_log`, in
     * `startedAt` order, so it is run again only when a tool call is recorded, not for every chat write.
     */
    @Query(AUDIT_BROWSE_ALL)
    fun observeBrowse(
        since: Long,
        until: Long,
        allTools: Boolean,
        tools: List<String>,
        allDecisions: Boolean,
        decisions: List<String>,
        onlyErrors: Boolean,
        limit: Int,
    ): Flow<List<AuditEntity>>

    /** [observeBrowse] for one chat, through the `conversationId` index. */
    @Query(AUDIT_BROWSE_CHAT)
    fun observeBrowseChat(
        conversationId: String,
        since: Long,
        until: Long,
        allTools: Boolean,
        tools: List<String>,
        allDecisions: Boolean,
        decisions: List<String>,
        onlyErrors: Boolean,
        limit: Int,
    ): Flow<List<AuditEntity>>

    /** [observeBrowseChat] plus the helper chats [conversationId] started (a task receipt's view). */
    @Query(AUDIT_BROWSE_CHAT_AND_HELPERS)
    fun observeBrowseChatAndHelpers(
        conversationId: String,
        since: Long,
        until: Long,
        allTools: Boolean,
        tools: List<String>,
        allDecisions: Boolean,
        decisions: List<String>,
        onlyErrors: Boolean,
        limit: Int,
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

/** An imported chat's id and last update (see [ConversationDao.importedStamps]). Read-only projection. */
data class ImportedStamp(val id: String, val updatedAt: Long, val pinned: Boolean)

/** A message found by [MessageDao.search]. Read-only projection. */
data class MessageHitRow(val messageId: String, val conversationId: String, val role: String, val createdAt: Long, val snippet: String)

/** A message's place in its chat's tree (see [MessageDao.links]). Read-only projection. */
data class LinkRow(val id: String, val parentId: String?, val activeLeafId: String?)

/** A message with its chat's active leaf (see [MessageDao.tree]). Read-only projection. */
data class TreeRow(@Embedded val message: MessageEntity, val activeLeafId: String?)

/** See [MessageDao.observeVersion]. [maxSeq] is 0 for a chat without messages. Read-only projection. */
data class TranscriptVersion(val count: Int, val maxSeq: Long, val activeLeafId: String?)

/** A message as the chat screen shows it (see [MessageDao.viewRows]): [MessageEntity] without `rawPayload`, `searchText` and `copiedFrom`. Read-only projection. */
data class MessageViewRow(
    val seq: Long,
    val id: String,
    val conversationId: String,
    val role: String,
    val partsJson: String,
    val createdAt: Long,
    val format: String?,
    val usageJson: String?,
    val parentId: String?,
    val providerId: String?,
    val model: String?,
    val firstTokenMs: Long?,
    val totalMs: Long?,
    val fallbackFrom: String?,
)

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

/**
 * One assistant turn of a chat, on any branch: its id, when it was stored, what it used and who answered.
 * A task's receipt counts the turns written during it on other branches too (Compare models writes two).
 */
data class ReplyUsageRow(
    val id: String,
    val createdAt: Long,
    val usageJson: String?,
    val providerId: String?,
    val model: String?,
    val fallbackFrom: String?,
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

    /** Every assistant turn of [conversationId], on every branch, oldest first; fork copies are left out. */
    @Query(
        "SELECT id, createdAt, usageJson, providerId, model, fallbackFrom FROM messages " +
            "WHERE conversationId = :conversationId AND role = 'ASSISTANT' AND copiedFrom IS NULL ORDER BY seq",
    )
    fun observeReplyUsage(conversationId: String): Flow<List<ReplyUsageRow>>

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

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun all(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

/** The events tasks' triggers received (docs/design/event-triggers.md). */
@Dao
interface TriggerEventDao {
    @Insert
    suspend fun insert(event: TriggerEventEntity)

    @Query("SELECT * FROM trigger_events WHERE id = :id")
    suspend fun get(id: String): TriggerEventEntity?

    /** [taskId]'s latest events, newest first. */
    @Query("SELECT * FROM trigger_events WHERE taskId = :taskId ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    fun observeForTask(taskId: String, limit: Int): Flow<List<TriggerEventEntity>>

    /** When [taskId]'s trigger last started a run for a real event (tests don't count), for its rate limit. */
    @Query("SELECT MAX(receivedAt) FROM trigger_events WHERE taskId = :taskId AND decision = 'FIRED'")
    suspend fun lastFiredAt(taskId: String): Long?

    /** Changes what happened to event [id] (a run that found its task busy, or couldn't start). */
    @Query("UPDATE trigger_events SET decision = :decision, detail = :detail WHERE id = :id")
    suspend fun setDecision(id: String, decision: String, detail: String?)

    /** Records event [id]'s run: its chat, and once it ended, how. */
    @Query("UPDATE trigger_events SET conversationId = :conversationId, outcome = :outcome, detail = :detail WHERE id = :id")
    suspend fun setRun(id: String, conversationId: String?, outcome: String?, detail: String?)

    /** Keeps [taskId]'s newest [keep] events. */
    @Query(
        "DELETE FROM trigger_events WHERE taskId = :taskId AND id NOT IN " +
            "(SELECT id FROM trigger_events WHERE taskId = :taskId ORDER BY receivedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(taskId: String, keep: Int)
}

/** Needs you's proposals (docs/design/needs-you.md). Every change of state is one guarded statement. */
@Dao
interface ProposalDao {
    @Insert
    suspend fun insert(proposal: ProposalEntity)

    @Query("SELECT * FROM proposals WHERE id = :id")
    suspend fun get(id: String): ProposalEntity?

    /** What Needs you lists: every proposal not dismissed, newest first (decided ones are filtered by the caller). */
    @Query("SELECT * FROM proposals WHERE dismissed = 0 ORDER BY createdAt DESC, id DESC")
    fun observeOpen(): Flow<List<ProposalEntity>>

    /** A proposal still waiting with exactly this call, in this chat: the run asked twice. */
    @Query(
        "SELECT id FROM proposals WHERE conversationId = :conversationId AND toolName = :toolName AND argumentsJson = :argumentsJson " +
            "AND status = 'PENDING' AND expiresAt > :now LIMIT 1",
    )
    suspend fun pendingDuplicate(conversationId: String, toolName: String, argumentsJson: String, now: Long): String?

    /** How many wait now (not expired). */
    @Query("SELECT COUNT(*) FROM proposals WHERE status = 'PENDING' AND expiresAt > :now")
    suspend fun pendingCount(now: Long): Int

    /** See [CLAIM_PROPOSAL]. 1 when this caller may run it, 0 when it isn't waiting any more (run, declined, expired). */
    @Query(CLAIM_PROPOSAL)
    suspend fun claim(id: String, now: Long): Int

    /** Records how a claimed proposal went; only while it's RUNNING. */
    @Query("UPDATE proposals SET status = :status, outcome = :outcome WHERE id = :id AND status = 'RUNNING'")
    suspend fun finish(id: String, status: String, outcome: String?): Int

    /** Declines a waiting one (expired or not: it never runs either way). */
    @Query("UPDATE proposals SET status = 'DECLINED', decidedAt = :now WHERE id = :id AND status = 'PENDING'")
    suspend fun decline(id: String, now: Long): Int

    @Query("UPDATE proposals SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: String): Int

    /** A process that died while running one: it may or may not have run, so it is never run again. */
    @Query("UPDATE proposals SET status = 'INTERRUPTED', outcome = :outcome WHERE status = 'RUNNING'")
    suspend fun interruptRunning(outcome: String): Int
}

/**
 * Behind [ProposalDao.claim]: one statement, so two taps (or a tap and a notification) can't both run it. It
 * succeeds only for a proposal still waiting and not past its expiry.
 */
const val CLAIM_PROPOSAL = "UPDATE proposals SET status = 'RUNNING', decidedAt = :now WHERE id = :id AND status = 'PENDING' AND expiresAt > :now"

/** Run on a restored database before it's swapped in: a proposal from a backup can be seen but never run. */
const val RETIRE_RESTORED_PROPOSALS = "UPDATE proposals SET status = 'STALE' WHERE status IN ('PENDING', 'RUNNING')"
