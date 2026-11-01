package dev.locus.core.testing

import dev.locus.core.database.AuditCountRow
import dev.locus.core.database.FileChangeDao
import dev.locus.core.database.FileChangeEntity
import dev.locus.core.database.TurnSize
import dev.locus.core.database.AuditDao
import dev.locus.core.database.AuditEntity
import dev.locus.core.database.AuditStatRow
import dev.locus.core.database.ConversationDao
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.LinkRow
import dev.locus.core.database.MessageDao
import dev.locus.core.database.MessageEntity
import dev.locus.core.database.MessageHitRow
import dev.locus.core.database.MessageStatRow
import dev.locus.core.database.ScheduledTaskDao
import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.database.TreeRow
import dev.locus.core.database.UsageEventDao
import dev.locus.core.database.UsageEventEntity
import dev.locus.core.database.TaskRunDao
import dev.locus.core.database.TaskRunEntity
import dev.locus.core.database.ReceiptDao
import dev.locus.core.database.HelperUsageRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Room DAOs in memory, following the SQL in Daos.kt: ordering, the hidden flag, the active leaf
 * with its fallback to the newest message, and cascading deletes. Full-text search isn't modelled
 * ([MessageDao.search] matches a plain substring of searchText); the FTS query itself is covered by
 * the sqlite3-based migration tests.
 */
class FakeDatabase {
    private data class Tables(
        val conversations: Map<String, ConversationEntity> = emptyMap(),
        val messages: List<MessageEntity> = emptyList(),
        val audit: List<AuditEntity> = emptyList(),
        val tasks: Map<String, ScheduledTaskEntity> = emptyMap(),
        val usageEvents: List<UsageEventEntity> = emptyList(),
        val fileChanges: List<FileChangeEntity> = emptyList(),
        val taskRuns: List<TaskRunEntity> = emptyList(),
    )

    private val tables = MutableStateFlow(Tables())
    private var nextSeq = 1L

    private fun <T> select(query: (Tables) -> T): Flow<T> = tables.map(query).distinctUntilChanged()

    private fun write(change: (Tables) -> Tables) = synchronized(this) { tables.value = change(tables.value) }

    /** Rows for assertions. */
    val conversationRows: List<ConversationEntity> get() = tables.value.conversations.values.toList()
    val messageRows: List<MessageEntity> get() = tables.value.messages
    val auditRows: List<AuditEntity> get() = tables.value.audit
    val usageEventRows: List<UsageEventEntity> get() = tables.value.usageEvents
    val fileChangeRows: List<FileChangeEntity> get() = tables.value.fileChanges
    val taskRunRows: List<TaskRunEntity> get() = tables.value.taskRuns

    private fun Tables.visible() = conversations.values.filter { !it.hidden }.sortedWith(ORDER)
    private fun Tables.tree(id: String): List<TreeRow> {
        val leaf = conversations[id]?.activeLeafId
        return messages.filter { it.conversationId == id }.map { TreeRow(it, leaf) }
    }

    private fun updateConversation(id: String, change: (ConversationEntity) -> ConversationEntity) = write { t ->
        val c = t.conversations[id] ?: return@write t
        t.copy(conversations = t.conversations + (id to change(c)))
    }

    val conversationDao: ConversationDao = object : ConversationDao {
        override fun observeAll() = select { t -> t.conversations.values.sortedWith(ORDER) }
        override fun observeVisible() = select { t -> t.visible() }
        override suspend fun children(parentId: String) =
            tables.value.conversations.values.filter { it.parentConversationId == parentId }.sortedBy { it.createdAt }
        override fun observeByParentCall(callId: String) = select { t -> t.conversations.values.firstOrNull { it.parentCallId == callId } }
        override suspend fun byParentCall(callId: String) = tables.value.conversations.values.firstOrNull { it.parentCallId == callId }
        override suspend fun helpersWithStatus(status: String) = tables.value.conversations.values.filter { it.hidden && it.agentStatus == status }
        override suspend fun countChildren(parentId: String, type: String) =
            tables.value.conversations.values.count { it.parentConversationId == parentId && it.agentType == type }
        override suspend fun setAgentStatus(id: String, status: String) = updateConversation(id) { it.copy(agentStatus = status) }
        override suspend fun setPinned(id: String, pinned: Boolean) = updateConversation(id) { it.copy(pinned = pinned) }
        override fun observe(id: String) = select { t -> t.conversations[id] }
        override suspend fun get(id: String) = tables.value.conversations[id]
        override suspend fun upsert(conversation: ConversationEntity) = write { t -> t.copy(conversations = t.conversations + (conversation.id to conversation)) }
        override suspend fun touch(id: String, updatedAt: Long) = updateConversation(id) { it.copy(updatedAt = updatedAt) }
        override suspend fun setProvider(id: String, providerId: String) = updateConversation(id) { it.copy(providerId = providerId) }
        override suspend fun setModelOverride(id: String, model: String?) = updateConversation(id) { it.copy(modelOverride = model) }
        override suspend fun setEffort(id: String, effort: String?) = updateConversation(id) { it.copy(effort = effort) }
        override suspend fun setPlanMode(id: String, on: Boolean) = updateConversation(id) { it.copy(planMode = on) }
        override suspend fun setDisabledTools(id: String, keys: String) = updateConversation(id) { it.copy(disabledTools = keys) }
        override suspend fun setActiveLeaf(id: String, leafId: String?) = updateConversation(id) { it.copy(activeLeafId = leafId) }
        override suspend fun rename(id: String, title: String) = updateConversation(id) { it.copy(title = title) }
        override suspend fun delete(id: String) = write { t ->
            // ON DELETE CASCADE for messages and tracked changes.
            t.copy(
                conversations = t.conversations - id,
                messages = t.messages.filterNot { it.conversationId == id },
                fileChanges = t.fileChanges.filterNot { it.conversationId == id },
                taskRuns = t.taskRuns.filterNot { it.conversationId == id },
            )
        }
        override fun observeEmptyIds() = select { t ->
            t.conversations.values.filter { c -> !c.hidden && t.messages.none { it.conversationId == c.id } }.map { it.id }
        }
    }

    val messageDao: MessageDao = object : MessageDao {
        override fun observeTree(conversationId: String) = select { t -> t.tree(conversationId) }
        override suspend fun tree(conversationId: String) = tables.value.tree(conversationId)
        override suspend fun insert(message: MessageEntity) = write { t ->
            require(t.conversations.containsKey(message.conversationId)) { "FOREIGN KEY constraint failed: no conversation ${message.conversationId}" }
            require(t.messages.none { it.id == message.id }) { "UNIQUE constraint failed: messages.id ${message.id}" }
            t.copy(messages = t.messages + message.copy(seq = nextSeq++))
        }
        override suspend fun insertAll(messages: List<MessageEntity>) = messages.forEach { insert(it) }
        override suspend fun leaf(conversationId: String): String? {
            val t = tables.value
            val mine = t.messages.filter { it.conversationId == conversationId }
            val active = t.conversations[conversationId]?.activeLeafId
            return mine.firstOrNull { it.id == active }?.id ?: mine.maxByOrNull { it.seq }?.id
        }
        override suspend fun search(match: String, tokens: Int, limit: Int): List<MessageHitRow> {
            val t = tables.value
            val words = match.split(' ').map { it.trim('"', '*') }.filter { it.isNotBlank() }
            return t.messages
                .filter { m -> t.conversations[m.conversationId]?.hidden == false && words.all { w -> m.searchText.orEmpty().contains(w, ignoreCase = true) } }
                .groupBy { it.conversationId }.values.map { it.maxBy { m -> m.seq } }
                .sortedWith(compareByDescending<MessageEntity> { it.createdAt }.thenByDescending { it.seq })
                .take(limit)
                .map { MessageHitRow(it.id, it.conversationId, it.role, it.createdAt, it.searchText.orEmpty()) }
        }
        override suspend fun links(conversationId: String) = tables.value.tree(conversationId).map { LinkRow(it.message.id, it.message.parentId, it.activeLeafId) }
        override suspend fun parentOf(id: String) = tables.value.messages.firstOrNull { it.id == id }?.parentId
        override suspend fun moveLeaf(conversationId: String, leafId: String, updatedAt: Long) =
            updateConversation(conversationId) { it.copy(activeLeafId = leafId, updatedAt = updatedAt) }
        override fun observeStats(since: Long) = select { t ->
            val byId = t.messages.associateBy { it.id }
            t.messages.filter { it.createdAt >= since && it.copiedFrom == null }
                .map { MessageStatRow(it.conversationId, it.role, it.createdAt, it.usageJson, it.parentId?.let(byId::get)?.createdAt) }
        }
    }

    val auditDao: AuditDao = object : AuditDao {
        override fun observeRecent(limit: Int) = select { t -> t.audit.sortedByDescending { it.startedAt }.take(limit) }
        override suspend fun insert(entry: AuditEntity) = write { t -> t.copy(audit = t.audit + entry) }
        override fun observeStats(since: Long) = select { t ->
            t.audit.filter { it.startedAt >= since }.map { AuditStatRow(it.conversationId, it.toolName, it.decision, it.isError, it.startedAt, it.finishedAt) }
        }
        override fun observeFiltered(
            since: Long, conversationId: String?, allTools: Boolean, tools: List<String>, allDecisions: Boolean, decisions: List<String>,
            onlyErrors: Boolean, pattern: String?, textTools: List<String>, limit: Int, until: Long, withHelpers: Boolean,
        ) = select { t ->
            val needle = pattern?.trim('%')
            val helpers = if (withHelpers) t.conversations.values.filter { it.parentConversationId == conversationId }.map { it.id }.toSet() else emptySet()
            t.audit.filter { e ->
                e.startedAt >= since && e.startedAt <= until && (conversationId == null || e.conversationId == conversationId || e.conversationId in helpers) &&
                    (allTools || e.toolName in tools) && (allDecisions || e.decision in decisions) && (!onlyErrors || e.isError) &&
                    (needle == null || e.toolName in textTools || needle in e.argumentsJson || needle in e.resultPreview)
            }.sortedByDescending { it.startedAt }.take(limit)
        }
        override fun observeToolNames() = select { t -> t.audit.map { it.toolName }.distinct() }
        override fun observeCounts(since: Long, conversationId: String?) = select { t ->
            val rows = t.audit.filter { it.startedAt >= since && (conversationId == null || it.conversationId == conversationId) }
            AuditCountRow(rows.size, rows.count { it.isError && it.decision !in setOf("USER_DENIED", "POLICY_DENIED", "REVIEWER_DENIED") })
        }
    }

    val scheduledTaskDao: ScheduledTaskDao = object : ScheduledTaskDao {
        override fun observeAll() = select { t -> t.tasks.values.sortedByDescending { it.createdAt } }
        override suspend fun get(id: String) = tables.value.tasks[id]
        override suspend fun upsert(task: ScheduledTaskEntity) = write { t -> t.copy(tasks = t.tasks + (task.id to task)) }
        override suspend fun delete(id: String) = write { t -> t.copy(tasks = t.tasks - id) }
    }

    val usageEventDao: UsageEventDao = object : UsageEventDao {
        override suspend fun insert(event: UsageEventEntity) = write { t ->
            t.copy(usageEvents = t.usageEvents + event.copy(seq = t.usageEvents.size + 1L))
        }
        override fun observeSince(since: Long) = select { t -> t.usageEvents.filter { it.createdAt >= since }.sortedBy { it.seq } }
    }

    private var nextChangeSeq = 1L

    val fileChangeDao: FileChangeDao = object : FileChangeDao {
        private fun Tables.byTurn() = fileChanges.groupBy { it.turnId }

        override fun observe(conversationId: String) = select { t -> t.fileChanges.filter { it.conversationId == conversationId }.sortedBy { it.seq } }
        override suspend fun get(turnId: String, path: String) = tables.value.fileChanges.firstOrNull { it.turnId == turnId && it.path == path }
        override suspend fun bySeq(seq: Long) = tables.value.fileChanges.firstOrNull { it.seq == seq }
        override suspend fun turn(conversationId: String, turnId: String) =
            tables.value.fileChanges.filter { it.conversationId == conversationId && it.turnId == turnId }.sortedBy { it.seq }
        override suspend fun insert(change: FileChangeEntity): Long = synchronized(this@FakeDatabase) {
            val t = tables.value
            check(t.conversations.containsKey(change.conversationId)) { "FOREIGN KEY constraint failed" }
            check(t.fileChanges.none { it.turnId == change.turnId && it.path == change.path }) { "UNIQUE constraint failed" }
            val seq = nextChangeSeq++
            tables.value = t.copy(fileChanges = t.fileChanges + change.copy(seq = seq))
            seq
        }
        override suspend fun update(change: FileChangeEntity) = write { t -> t.copy(fileChanges = t.fileChanges.map { if (it.seq == change.seq) change else it }) }
        override suspend fun delete(seq: Long) = write { t -> t.copy(fileChanges = t.fileChanges.filterNot { it.seq == seq }) }
        override suspend fun markUndone(seq: Long, at: Long) = write { t -> t.copy(fileChanges = t.fileChanges.map { if (it.seq == seq) it.copy(undoneAt = at) else it }) }
        override suspend fun turnsNewestFirst(conversationId: String) =
            tables.value.fileChanges.filter { it.conversationId == conversationId }.groupBy { it.turnId }
                .entries.sortedByDescending { e -> e.value.maxOf { it.seq } }.map { it.key }
        override suspend fun turnSizesOldestFirst() = tables.value.byTurn().entries
            .sortedBy { e -> e.value.maxOf { it.seq } }
            .map { e -> TurnSize(e.key, e.value.sumOf { (it.diff?.length ?: 0).toLong() }) }
        override suspend fun deleteTurns(turnIds: List<String>) = write { t -> t.copy(fileChanges = t.fileChanges.filterNot { it.turnId in turnIds }) }
    }

    val taskRunDao: TaskRunDao = object : TaskRunDao {
        override suspend fun insert(run: TaskRunEntity) = synchronized(this@FakeDatabase) {
            val t = tables.value
            check(t.conversations.containsKey(run.conversationId)) { "FOREIGN KEY constraint failed" }
            check(t.taskRuns.none { it.id == run.id }) { "UNIQUE constraint failed" }
            tables.value = t.copy(taskRuns = t.taskRuns + run)
        }
        override suspend fun finish(id: String, endedAt: Long, outcome: String, detail: String?) = write { t ->
            t.copy(taskRuns = t.taskRuns.map { if (it.id == id) it.copy(endedAt = endedAt, outcome = outcome, detail = detail) else it })
        }
        override fun observe(conversationId: String) = select { t -> t.taskRuns.filter { it.conversationId == conversationId }.sortedBy { it.startedAt } }
        override suspend fun all(conversationId: String) = tables.value.taskRuns.filter { it.conversationId == conversationId }.sortedBy { it.startedAt }
        override suspend fun open() = tables.value.taskRuns.filter { it.endedAt == null }
    }

    val receiptDao: ReceiptDao = object : ReceiptDao {
        override fun observeHelperUsage(parentId: String) = select { t ->
            val helpers = t.conversations.values.filter { it.parentConversationId == parentId }.associateBy { it.id }
            t.messages.filter { it.conversationId in helpers && it.role == "ASSISTANT" }.sortedBy { it.seq }.map { m ->
                val c = helpers.getValue(m.conversationId)
                HelperUsageRow(m.conversationId, c.parentCallId, c.agentType, m.createdAt, m.usageJson, m.providerId, m.model)
            }
        }
        override fun observeSideUsage(conversationId: String) = select { t -> t.usageEvents.filter { it.conversationId == conversationId }.sortedBy { it.seq } }
        override fun observeAudit(conversationId: String) = select { t ->
            val helpers = t.conversations.values.filter { it.parentConversationId == conversationId }.map { it.id }.toSet()
            t.audit.filter { it.conversationId == conversationId || it.conversationId in helpers }.sortedBy { it.startedAt }
        }
    }

    private companion object {
        val ORDER = compareByDescending<ConversationEntity> { it.pinned }.thenByDescending { it.updatedAt }
    }
}
