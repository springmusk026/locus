package dev.locus.core.data.repository

import dev.locus.core.agent.TaskReceipts
import dev.locus.core.data.di.IoDispatcher
import dev.locus.core.database.HelperUsageRow
import dev.locus.core.database.ReceiptDao
import dev.locus.core.database.TaskRunDao
import dev.locus.core.database.TaskRunEntity
import dev.locus.core.database.UsageEventEntity
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskReceipt
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.UsageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Prices by (provider id, model id) from models.dev, or null where unknown. */
typealias PriceLookup = (providerId: String?, model: String?) -> ModelPrice?

/**
 * Everything a chat's receipts are computed from, apart from its messages and tracked changes (which
 * the chat screen already watches): task records, the audit log of the chat and its helpers, helpers'
 * and side requests, and model prices.
 */
data class ReceiptInputs(
    val records: List<TaskRecord> = emptyList(),
    val audit: List<AuditEntry> = emptyList(),
    val helpers: List<TaskReceipts.Helper> = emptyList(),
    val side: List<TaskReceipts.Usage> = emptyList(),
    val price: PriceLookup = { _, _ -> null },
) {
    /** The receipts for the chat's shown branch [path], with [changes] from [ChangeRepository.observe]. */
    fun receipts(path: List<Message>, changes: List<TrackedChange>): List<TaskReceipt> =
        TaskReceipts.build(records, path, audit, standing(changes), helpers, side, price)

    companion object {
        /** Paths each turn changed that weren't undone, by turn id. */
        fun standing(changes: List<TrackedChange>): Map<String, List<String>> =
            changes.filter { !it.undone }.groupBy({ it.turnId }, { it.path })
    }
}

/**
 * Task records (database v14) and the receipts built from them; see docs/design/task-receipt.md. The
 * runner opens a record when a task starts and closes it with the outcome; nothing else is stored.
 * Recording never fails the task it describes.
 */
@Singleton
class TaskReceiptRepository @Inject constructor(
    private val runs: TaskRunDao,
    private val receiptDao: ReceiptDao,
    private val conversations: ConversationRepository,
    private val changes: ChangeRepository,
    private val settings: SettingsRepository,
    private val directory: ModelDirectory,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    internal var clock: () -> Long = System::currentTimeMillis

    /** Opens a record for a task starting now in [conversationId]; null if it couldn't be written. */
    suspend fun begin(conversationId: String): String? = guarded {
        val id = UUID.randomUUID().toString()
        runs.insert(TaskRunEntity(id, conversationId, clock()))
        id
    }

    /** Closes record [id] with how the task ended. Runs even while the caller is being cancelled. */
    suspend fun finish(id: String, outcome: TaskOutcome, detail: String? = null) {
        withContext(NonCancellable) { guarded { runs.finish(id, clock(), outcome.wire, detail?.take(MAX_DETAIL)) } }
    }

    /**
     * At startup: records still open belong to tasks the app was killed in the middle of (none run yet),
     * except those [running] says are going. They end now, as interrupted.
     */
    suspend fun closeInterrupted(running: (conversationId: String) -> Boolean) {
        val open = guarded { runs.open() }.orEmpty()
        open.filterNot { running(it.conversationId) }.forEach { finish(it.id, TaskOutcome.INTERRUPTED) }
    }

    /** What [conversationId]'s receipts are built from, live. */
    fun inputs(conversationId: String): Flow<ReceiptInputs> = combine(
        runs.observe(conversationId),
        receiptDao.observeAudit(conversationId),
        receiptDao.observeHelperUsage(conversationId),
        receiptDao.observeSideUsage(conversationId),
        prices(),
    ) { records, audit, helpers, side, price ->
        ReceiptInputs(records.map { it.toModel() }, audit.mapNotNull { it.toModel() }, helpers(helpers), side.map { it.toUsage() }, price)
    }.flowOn(io)

    /** The receipt of [conversationId]'s latest finished task (a scheduled run's notification); null when it has none. */
    suspend fun latest(conversationId: String): TaskReceipt? = guarded {
        directory.index()
        val inputs = inputs(conversationId).first()
        inputs.receipts(conversations.history(conversationId), changes.observe(conversationId).first()).lastOrNull()
    }

    /** Model prices as the budget uses them: from models.dev when the catalogue is on and loaded. */
    private fun prices(): Flow<PriceLookup> = combine(settings.settings, directory.catalog) { s, catalog ->
        val index = catalog.takeIf { s.modelCatalog }
        val lookup: PriceLookup = { providerId, model ->
            s.provider(providerId)?.let { p -> index?.lookup(p.baseUrl, p.format, model?.ifBlank { null } ?: p.model)?.price }
        }
        lookup
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The chat was deleted meanwhile (the foreign key refuses the row), or the disk is full.
        null
    }

    private companion object {
        const val MAX_DETAIL = 2_000
        val json = Json { ignoreUnknownKeys = true }

        fun TaskRunEntity.toModel() = TaskRecord(id, conversationId, startedAt, endedAt, TaskOutcome.parse(outcome), detail)

        fun usage(text: String?): TokenUsage? = text?.let { runCatching { json.decodeFromString(TokenUsage.serializer(), it) }.getOrNull() }

        fun UsageEventEntity.toUsage() = TaskReceipts.Usage(providerId, model, usage(usageJson), createdAt, UsageSource.parse(source))

        fun helpers(rows: List<HelperUsageRow>): List<TaskReceipts.Helper> = rows.groupBy { it.conversationId }.map { (id, list) ->
            val first = list.first()
            TaskReceipts.Helper(
                conversationId = id,
                parentCallId = first.parentCallId,
                label = first.agentType ?: "helper",
                requests = list.map { TaskReceipts.Usage(it.providerId, it.model, usage(it.usageJson), it.createdAt, UsageSource.HELPER) },
            )
        }

        /** Rows with a decision this build doesn't know are left out. */
        fun dev.locus.core.database.AuditEntity.toModel(): AuditEntry? {
            val d = ApprovalDecision.entries.firstOrNull { it.name == decision } ?: return null
            return AuditEntry(id, conversationId, callId, toolName, argumentsJson, d, resultPreview, isError, startedAt, finishedAt)
        }
    }
}
