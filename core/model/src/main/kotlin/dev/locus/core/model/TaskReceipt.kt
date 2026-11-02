package dev.locus.core.model

/**
 * How a task ended: one user message and everything it set off, helpers included. [wire] is what the
 * database stores (`task_runs.outcome`); values may be added but never renamed.
 */
enum class TaskOutcome(val wire: String) {
    DONE("done"),

    /** The user tapped Stop (or a scheduled run hit its time limit). */
    STOPPED("stopped"),

    /** The task reached its budget and the user (or, with nobody to ask, the rule) said stop. */
    BUDGET("budget"),
    FAILED("failed"),

    /** The model declined the request. */
    REFUSED("refused"),

    /** A scheduled run used all its steps. */
    STEP_LIMIT("step_limit"),

    /** Locus was closed (or killed) before the task ended. */
    INTERRUPTED("interrupted"),
    ;

    companion object {
        fun parse(wire: String?): TaskOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The one thing stored per task (database v14): when it ran and how it ended. Everything else on its
 * receipt is worked out from what's already kept (messages, the audit log, tracked changes, usage
 * events). [endedAt] and [outcome] are null while the task runs. UI only: never sent to a model.
 */
data class TaskRecord(
    val id: String,
    val conversationId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val outcome: TaskOutcome? = null,
    /** Why it failed, the budget pause's wording, the step count; null when there's nothing to add. */
    val detail: String? = null,
)

/**
 * A finished task at a glance (see docs/design/task-receipt.md): outcome, time, models, tokens and
 * cost, files changed, checks, approvals, outside content and helpers. Computed, never stored.
 */
data class TaskReceipt(
    val taskId: String,
    val conversationId: String,
    val outcome: TaskOutcome,
    val detail: String?,
    val startedAt: Long,
    val endedAt: Long,
    /** Model requests the chat's own agent made (helpers' aren't counted here). */
    val steps: Int,
    val models: List<ReceiptModel>,
    val tokens: ReceiptTokens,
    /** Estimated from models.dev prices; null when no request's price is known. */
    val cost: ReceiptCost?,
    /** Paths changed and still standing (undone ones aren't counted). */
    val files: Int,
    /** The turns whose changes card lists those files ([dev.locus.core.agent.tools.ChangeOwner.turnId]), oldest first. */
    val changeTurns: List<String>,
    /** Build and test runs, in order. */
    val checks: List<ReceiptCheck>,
    val approvals: ReceiptApprovals,
    /** What outside content the run had read ([dev.locus.core.agent.Taint] sources, as labels); empty for a clean run. */
    val outsideContent: List<String>,
    /** The run started tainted: earlier messages in the chat had read outside content. */
    val taintedFromStart: Boolean,
    /** Helpers it started, by profile ("researcher"). */
    val helpers: List<String>,
    /** The last message of the task on the chat's shown branch: the receipt goes under its turn. */
    val afterMessageId: String,
    /** Set when the task ran a saved workflow: its name and how each success check went (docs/design/workflows.md). */
    val workflow: ReceiptWorkflow? = null,
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    val tainted: Boolean get() = outsideContent.isNotEmpty()
}

/** A model that answered part of the task, and in what role. */
data class ReceiptModel(val name: String, val kind: Kind = Kind.CHAT, val standingInFor: String? = null) {
    enum class Kind { CHAT, BACKUP, HELPER, REVIEWER, SUMMARY }
}

/** Tokens over every request of the task. [input] is whole prompts, cached parts included. */
data class ReceiptTokens(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val requests: Int = 0) {
    /** The share of prompt tokens read from the provider's cache, 0–100; null with no input. */
    val cachedPercent: Int? get() = if (input <= 0) null else ((cacheRead.coerceAtMost(input) * 100.0) / input).toInt()
}

/** Estimated dollars. [partial]: some requests went to models without a known price, so it's a floor. */
data class ReceiptCost(val usd: Double, val partial: Boolean)

/** One build, test or check run: [messageId] and [callId] find its card in the chat. [passed] null: it timed out. */
data class ReceiptCheck(val messageId: String, val callId: String, val kind: Verification.Kind, val passed: Boolean?)

/**
 * The task's decisions as Activity has them: [approved] and [declined] are the calls the user was
 * asked about; [blocked] were refused by a policy or the safety reviewer without asking.
 */
data class ReceiptApprovals(val approved: Int = 0, val declined: Int = 0, val blocked: Int = 0) {
    val asked: Int get() = approved + declined

    companion object {
        val APPROVED = setOf(ApprovalDecision.USER_APPROVED)
        val DECLINED = setOf(ApprovalDecision.USER_DENIED)
        val ASKED = APPROVED + DECLINED
        val BLOCKED = setOf(ApprovalDecision.POLICY_DENIED, ApprovalDecision.REVIEWER_DENIED)
    }
}

/**
 * Activity narrowed to one task: its chat and helpers, its time span, and the [decisions] a receipt's
 * approval line counts ([label] names it, e.g. "You approved").
 */
data class TaskActivityFilter(
    val conversationId: String,
    val since: Long,
    val until: Long,
    val decisions: Set<ApprovalDecision>,
    val label: String,
)
