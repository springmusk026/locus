package dev.locus.feature.activity

import dev.locus.core.designsystem.Accent
import dev.locus.core.designsystem.accentFor
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry

/** A quiet stretch longer than this between two calls of the same chat starts a new run. */
internal const val RUN_GAP_MS = 10 * 60_000L

/** The chat a run belongs to, as far as the screen needs it. */
data class RunChat(
    val title: String,
    /** Null when the chat was deleted; its audit rows outlive it. */
    val exists: Boolean,
    val provider: RunProvider? = null,
    /** The chat to open for this run: the parent chat for a helper's run, else the run's own. */
    val openId: String? = null,
)

/** Enough of a provider to draw its badge. */
data class RunProvider(val name: String, val baseUrl: String, val onDevice: Boolean, val model: String?)

/**
 * One burst of work in one chat: consecutive tool calls with no gap over [RUN_GAP_MS]. [entries]
 * are oldest first, so an expanded run reads like a story.
 */
data class Run(
    val key: String,
    val conversationId: String,
    val chat: RunChat,
    val entries: List<AuditEntry>,
) {
    val start: Long get() = entries.first().startedAt
    val end: Long get() = entries.maxOf { it.finishedAt.coerceAtLeast(it.startedAt) }
    val latest: AuditEntry get() = entries.last()
    val failed: Int = entries.count { it.failed }
    /** Calls the user was asked about. */
    val asked: Int = entries.count { it.decision == ApprovalDecision.USER_APPROVED || it.decision == ApprovalDecision.USER_DENIED }
    /** The kinds of tools used, most used first. */
    val accents: List<Accent> = entries.groupingBy { accentFor(it.toolName).first }.eachCount().entries
        .sortedByDescending { it.value }.map { it.key }
}

data class DaySection(val label: String, val runs: List<Run>)

/** Declined and blocked calls report an error to the model, but nothing failed: someone said no. */
val AuditEntry.refused: Boolean
    get() = decision == ApprovalDecision.USER_DENIED || decision == ApprovalDecision.POLICY_DENIED || decision == ApprovalDecision.REVIEWER_DENIED

val AuditEntry.failed: Boolean get() = isError && !refused

/**
 * Splits [newestFirst] into runs: each chat's calls are clustered on their own, so a scheduled task
 * running while you chat makes two runs rather than chopping both into pieces. Runs come back newest
 * first by their latest call.
 */
fun clusterRuns(newestFirst: List<AuditEntry>, chatOf: (String) -> RunChat, gapMs: Long = RUN_GAP_MS): List<Run> {
    // The run each chat is currently extending backwards in time, and every run built so far.
    val open = HashMap<String, MutableList<AuditEntry>>()
    val built = ArrayList<MutableList<AuditEntry>>()
    for (e in newestFirst) {
        val current = open[e.conversationId]
        // `current.last()` is the earliest call so far (we walk newest to oldest).
        if (current != null && current.last().startedAt - e.finishedAt.coerceAtLeast(e.startedAt) <= gapMs) {
            current += e
        } else {
            val run = mutableListOf(e)
            open[e.conversationId] = run
            built += run
        }
    }
    val chats = HashMap<String, RunChat>()
    return built.map { list ->
        val oldestFirst = list.asReversed().toList()
        val id = oldestFirst.first().conversationId
        // Keyed by the oldest call, which stays put while new calls arrive, so an open run stays open.
        Run("$id:${oldestFirst.first().id}", id, chats.getOrPut(id) { chatOf(id) }, oldestFirst)
    }.sortedByDescending { it.latest.startedAt }
}
