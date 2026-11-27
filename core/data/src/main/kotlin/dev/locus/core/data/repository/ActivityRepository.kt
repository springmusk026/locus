package dev.locus.core.data.repository

import dev.locus.core.agent.AuditRedactor
import dev.locus.core.database.AuditDao
import dev.locus.core.datastore.SecretStore
import dev.locus.core.database.AuditEntity
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
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

/**
 * Each value as it comes, then nothing for [ms] while newer ones are replaced by the latest, which follows. A
 * burst (a run writing a chat every few tokens) is shown as its first and last result, not each in turn.
 */
internal fun <T> Flow<T>.throttleLatest(ms: Long): Flow<T> = conflate().transform { value ->
    emit(value)
    delay(ms)
}

/** Calls in a slice of the log, and how many of them failed (declined and blocked calls aren't failures). */
data class AuditCounts(val total: Int, val failed: Int)

/**
 * Read-only access to the audit log for browsing: filtered, windowed, newest first. Entries come out
 * redacted ([AuditRedactor]): vault values and secret-shaped arguments are replaced by a label naming
 * their kind. The stored rows, and what the model saw, are unchanged.
 */
@Singleton
class ActivityRepository @Inject constructor(private val audit: AuditDao, private val secrets: SecretStore) {

    /** The newest [limit] entries matching [query], redacted; live, so new calls appear as they're recorded. */
    fun observe(query: AuditQuery, limit: Int): Flow<List<AuditEntry>> {
        val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
        val tools = query.tools.orEmpty().toList().orNone()
        val decisions = query.decisions.orEmpty().map { it.name }.orNone()
        val chat = query.conversationId
        // Text search goes through the audit_log index (observeSearch) and is shown at most every SEARCH_REFRESH_MS.
        // Without text, one of three queries that read the log alone (or, for a task's receipt, its chat's helpers
        // too) and never need to be held back.
        val rows = if (text != null) {
            search(query, text, tools, decisions, limit).throttleLatest(SEARCH_REFRESH_MS)
        } else if (chat == null) {
            audit.observeBrowse(query.since, query.until, query.tools == null, tools, query.decisions == null, decisions, query.onlyErrors, limit)
        } else if (query.withHelpers) {
            audit.observeBrowseChatAndHelpers(chat, query.since, query.until, query.tools == null, tools, query.decisions == null, decisions, query.onlyErrors, limit)
        } else {
            audit.observeBrowseChat(chat, query.since, query.until, query.tools == null, tools, query.decisions == null, decisions, query.onlyErrors, limit)
        }
        return rows.map { list ->
            val redactor = AuditRedactor(runCatching { secrets.valuesForScrubbing() }.getOrDefault(emptyList()))
            list.mapNotNull { it.toModel()?.let(redactor::entry) }
        }
    }

    /**
     * The text search. Chat titles and a receipt's helper chats live in `conversations`, which a run writes all the
     * time, so they are looked up by queries of their own; the search itself only runs again when the chats they
     * name change, or a tool call is recorded. Text of three characters or more is looked up in the trigram index
     * ([AuditDao.observeSearch]); shorter text, which has no trigram, is matched row by row ([AuditDao.observeSearchScan]).
     * Both return exactly what the old `LIKE` search ([AuditDao.observeFiltered]) did, which is still used when the
     * chat lists are too long to bind.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun search(query: AuditQuery, text: String, tools: List<String>, decisions: List<String>, limit: Int): Flow<List<AuditEntity>> {
        val pattern = "%${like(text)}%"
        val chat = query.conversationId
        val textTools = query.textTools.toList().orNone()
        val helpers = if (chat != null && query.withHelpers) audit.observeHelperChats(chat) else flowOf(emptyList())
        return combine(audit.observeTitleMatches(pattern), helpers) { titles, helperChats -> titles to helperChats }
            .distinctUntilChanged()
            .flatMapLatest { (titles, helperChats) ->
                val chats = chat?.let { listOf(it) + helperChats }
                if (titles.size + chats.orEmpty().size > MAX_BOUND_CHATS) {
                    audit.observeFiltered(
                        since = query.since,
                        conversationId = chat,
                        allTools = query.tools == null,
                        tools = tools,
                        allDecisions = query.decisions == null,
                        decisions = decisions,
                        onlyErrors = query.onlyErrors,
                        pattern = pattern,
                        textTools = textTools,
                        limit = limit,
                        until = query.until,
                        withHelpers = query.withHelpers && chat != null,
                    )
                } else if (text.codePointCount(0, text.length) >= TRIGRAM) {
                    audit.observeSearch(
                        phrase(text), pattern, textTools, titles.orNone(), chats == null, chats.orEmpty().orNone(),
                        query.since, query.until, query.tools == null, tools, query.decisions == null, decisions, query.onlyErrors, limit,
                    )
                } else {
                    audit.observeSearchScan(
                        pattern, textTools, titles.orNone(), chats == null, chats.orEmpty().orNone(),
                        query.since, query.until, query.tools == null, tools, query.decisions == null, decisions, query.onlyErrors, limit,
                    )
                }
            }
    }

    /** Every tool name the log has seen. */
    fun observeToolNames(): Flow<List<String>> = audit.observeToolNames()

    fun observeCounts(since: Long, conversationId: String?): Flow<AuditCounts> =
        audit.observeCounts(since, conversationId).map { AuditCounts(it.total, it.failed) }

    private companion object {
        /** A text search is shown at most this often while tool calls are being recorded. */
        const val SEARCH_REFRESH_MS = 500L

        /** The shortest text the trigram index can look up: one trigram. */
        const val TRIGRAM = 3

        /**
         * At most this many chat ids (title matches plus a chat and its helpers) are bound into a search, each list
         * twice, well under SQLite's 32,766 variables (sqlite.org/limits.html); past it the old query is used.
         */
        const val MAX_BOUND_CHATS = 5_000

        /** Room expands an empty list to `IN ()`; a placeholder that no tool, decision or chat is called keeps the SQL plain. */
        fun List<String>.orNone() = ifEmpty { listOf("\u0000") }

        /** [text] as one FTS5 phrase: in double quotes, with `"` doubled (sqlite.org/fts5.html §3.1). */
        fun phrase(text: String) = "\"" + text.replace("\"", "\"\"") + "\""

        fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** Rows with a decision this build doesn't know are skipped rather than crashing the screen. */
        fun AuditEntity.toModel(): AuditEntry? {
            val d = ApprovalDecision.entries.firstOrNull { it.name == decision } ?: return null
            return AuditEntry(id, conversationId, callId, toolName, argumentsJson, d, resultPreview, isError, startedAt, finishedAt)
        }
    }
}
