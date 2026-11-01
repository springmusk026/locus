package dev.locus.core.data.repository

import dev.locus.core.database.AuditDao
import dev.locus.core.database.AuditEntity
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Activity screen asks of the audit log. Null fields are "any": [tools] and [decisions]
 * are exact sets, [text] is matched against arguments, result previews and chat titles, and
 * [textTools] are tool names whose plain-language label matched [text] (worked out by the caller,
 * since labels live in Kotlin, not in the database).
 */
data class AuditQuery(
    val since: Long = 0L,
    val conversationId: String? = null,
    val tools: Set<String>? = null,
    val decisions: Set<ApprovalDecision>? = null,
    val onlyErrors: Boolean = false,
    val text: String? = null,
    val textTools: Set<String> = emptySet(),
    /** The end of the time span (a task receipt's view); [Long.MAX_VALUE] for none. */
    val until: Long = Long.MAX_VALUE,
    /** With [conversationId]: the helper chats it started too. */
    val withHelpers: Boolean = false,
)

/** Calls in a slice of the log, and how many of them failed (declined and blocked calls aren't failures). */
data class AuditCounts(val total: Int, val failed: Int)

/** Read-only access to the audit log for browsing: filtered, windowed, newest first. */
@Singleton
class ActivityRepository @Inject constructor(private val audit: AuditDao) {

    /** The newest [limit] entries matching [query]; live, so new calls appear as they're recorded. */
    fun observe(query: AuditQuery, limit: Int): Flow<List<AuditEntry>> {
        // Room expands an empty list to `IN ()`; a placeholder that no tool or decision is called keeps the SQL plain.
        fun List<String>.orNone() = ifEmpty { listOf("\u0000") }
        val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
        return audit.observeFiltered(
            since = query.since,
            conversationId = query.conversationId,
            allTools = query.tools == null,
            tools = query.tools.orEmpty().toList().orNone(),
            allDecisions = query.decisions == null,
            decisions = query.decisions.orEmpty().map { it.name }.orNone(),
            onlyErrors = query.onlyErrors,
            pattern = text?.let { "%${like(it)}%" },
            textTools = query.textTools.toList().orNone(),
            limit = limit,
            until = query.until,
            withHelpers = query.withHelpers && query.conversationId != null,
        ).map { list -> list.mapNotNull { it.toModel() } }
    }

    /** Every tool name the log has seen. */
    fun observeToolNames(): Flow<List<String>> = audit.observeToolNames()

    fun observeCounts(since: Long, conversationId: String?): Flow<AuditCounts> =
        audit.observeCounts(since, conversationId).map { AuditCounts(it.total, it.failed) }

    private companion object {
        fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** Rows with a decision this build doesn't know are skipped rather than crashing the screen. */
        fun AuditEntity.toModel(): AuditEntry? {
            val d = ApprovalDecision.entries.firstOrNull { it.name == decision } ?: return null
            return AuditEntry(id, conversationId, callId, toolName, argumentsJson, d, resultPreview, isError, startedAt, finishedAt)
        }
    }
}
