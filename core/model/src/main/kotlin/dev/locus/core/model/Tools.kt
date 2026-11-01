package dev.locus.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What a tool can do, which decides its default approval policy. */
enum class ToolRisk { READ, WRITE, NETWORK, EXECUTE }

@Serializable
enum class ToolPolicy { ALLOW, ASK, DENY }

fun ToolRisk.defaultPolicy(): ToolPolicy = when (this) {
    ToolRisk.READ -> ToolPolicy.ALLOW
    ToolRisk.WRITE, ToolRisk.NETWORK, ToolRisk.EXECUTE -> ToolPolicy.ASK
}

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments object. */
    val inputSchema: JsonObject,
)

/**
 * How a tool call was let through or stopped; recorded in the audit log (stored by name, so values
 * may be added but never renamed). The REVIEWER_ ones are the AI safety reviewer's own decisions.
 */
enum class ApprovalDecision { AUTO_ALLOWED, USER_APPROVED, USER_DENIED, POLICY_DENIED, INVALID_INPUT, REVIEWER_ALLOWED, REVIEWER_DENIED }

/** Which tool calls the AI safety reviewer looks at before they run (Settings → Permissions). */
@Serializable
enum class ReviewerMode {
    /** No reviewer: calls set to Ask always ask the user. */
    OFF,

    /** Only calls that would ask the user; the reviewer may run them, block them, or pass them on to the user. */
    REVIEW_ASKS,

    /** As [REVIEW_ASKS], plus allowed calls that change something; a reviewer that doesn't allow one sends it to the user. */
    REVIEW_ALL_BUT_READS,
}

/**
 * What the safety reviewer said about one call, kept in the audit log as the first line of
 * [AuditEntry.resultPreview] so no database change is needed. [fallback] says why the user was asked
 * without (or despite) a verdict: the reviewer was unavailable, the action reaches outside, and so on.
 */
@Serializable
data class ReviewAudit(
    val model: String = "",
    /** "allow", "deny" or "ask"; null when there was no usable verdict. */
    val verdict: String? = null,
    val reason: String = "",
    val latencyMs: Long = 0,
    val fallback: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** The user ran the call anyway after the reviewer blocked it. */
    val overridden: Boolean = false,
) {
    /** Prepends this record to an audit preview. */
    fun prefix(preview: String): String = PREFIX + json.encodeToString(serializer(), this) + "\n" + preview

    companion object {
        private const val PREFIX = "[reviewer] "
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** The record at the start of [preview] and the preview without it; null record when there is none. */
        fun split(preview: String): Pair<ReviewAudit?, String> {
            if (!preview.startsWith(PREFIX)) return null to preview
            val end = preview.indexOf('\n').let { if (it < 0) preview.length else it }
            val audit = runCatching { json.decodeFromString(serializer(), preview.substring(PREFIX.length, end)) }.getOrNull()
                ?: return null to preview
            return audit to preview.substring((end + 1).coerceAtMost(preview.length))
        }
    }
}

data class AuditEntry(
    val id: String,
    val conversationId: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val decision: ApprovalDecision,
    val resultPreview: String,
    val isError: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
)

/** A prompt the agent runs on a schedule in read-only mode, notifying the user with the result. */
data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    val intervalMinutes: Long,
    val enabled: Boolean,
    val lastRunAt: Long?,
    val lastSummary: String?,
    /** How the last run ended; null before the first run (and for runs recorded before this existed, which finished). */
    val lastOutcome: TaskRunOutcome? = null,
)

/** How a scheduled task's run ended, shown in Tasks ("Last run failed"). Stored by name. */
enum class TaskRunOutcome {
    /** Finished with an answer. */
    DONE,

    /** The agent hit an error (no provider, a refused request, ...). */
    FAILED,

    /** Android stopped it (time limit, quota, network lost), or it ran past Locus's own time budget. */
    STOPPED,
}
