package dev.locus.core.agent

import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Message
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.ReceiptApprovals
import dev.locus.core.model.ReceiptCheck
import dev.locus.core.model.ReceiptCost
import dev.locus.core.model.ReceiptModel
import dev.locus.core.model.ReceiptTokens
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentTools
import dev.locus.core.model.TASK_BUDGET_ENTRY
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskReceipt
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.UsageSource
import dev.locus.core.model.Verification
import dev.locus.core.model.ReceiptWorkflow
import dev.locus.core.agent.workflow.WorkflowChecks
import dev.locus.core.agent.workflow.WorkflowRender

/**
 * Builds task receipts from what Locus already keeps (see docs/design/task-receipt.md): the stored
 * [TaskRecord] says when a task ran and how it ended; the rest comes from the chat's messages on the
 * shown branch, the audit log, tracked changes, helpers' messages and side requests within that time.
 * Pure, so every field is tested from fixtures. Receipts are UI only and never reach a model.
 */
object TaskReceipts {

    /** One model request: who answered, what it used, when it was stored. */
    data class Usage(
        val providerId: String?,
        val model: String?,
        val usage: TokenUsage?,
        val at: Long,
        val source: UsageSource = UsageSource.CHAT,
        /** For a backup model's reply: the chat's own model it stood in for. */
        val fallbackFrom: String? = null,
    )

    /** A helper chat: the parent's call that started it, its profile, and its requests. */
    data class Helper(val conversationId: String, val parentCallId: String?, val label: String, val requests: List<Usage>)

    /**
     * Receipts for every finished task in [records] that left something on [path] (the chat's shown
     * branch, oldest first). Tasks still running, and tasks whose messages are on another branch, have none.
     */
    fun build(
        records: List<TaskRecord>,
        path: List<Message>,
        audit: List<AuditEntry> = emptyList(),
        /** Paths each turn changed and still stand, by turn id (the turn's first message after the user's). */
        changes: Map<String, List<String>> = emptyMap(),
        helpers: List<Helper> = emptyList(),
        /** Requests that aren't messages (compaction, the safety reviewer…), from the usage log. */
        side: List<Usage> = emptyList(),
        price: (providerId: String?, model: String?) -> ModelPrice? = { _, _ -> null },
    ): List<TaskReceipt> {
        if (records.isEmpty() || path.isEmpty()) return emptyList()
        val results = path.filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
        return records.sortedBy { it.startedAt }.mapNotNull { receipt(it, path, results, audit, changes, helpers, side, price) }
    }

    private fun receipt(
        record: TaskRecord,
        path: List<Message>,
        results: Map<String, dev.locus.core.model.Part.ToolResult>,
        audit: List<AuditEntry>,
        changes: Map<String, List<String>>,
        helpers: List<Helper>,
        side: List<Usage>,
        price: (String?, String?) -> ModelPrice?,
    ): TaskReceipt? {
        val end = record.endedAt ?: return null
        val outcome = record.outcome ?: return null
        val start = record.startedAt
        fun inTask(at: Long) = at in start..end
        val messages = path.filter { inTask(it.createdAt) }
        if (messages.isEmpty()) return null
        val replies = messages.filter { it.role == Role.ASSISTANT }
        val callIds = replies.flatMapTo(HashSet()) { m -> m.toolCalls.map { it.id } }
        val started = helpers.filter { it.parentCallId != null && it.parentCallId in callIds }

        val requests = replies.map { Usage(it.reply?.providerId, it.reply?.model, it.usage, it.createdAt, UsageSource.CHAT, it.reply?.fallbackFrom) } +
            started.flatMap { h -> h.requests.filter { inTask(it.at) }.map { it.copy(source = UsageSource.HELPER) } } +
            side.filter { inTask(it.at) }

        val counted = requests.mapNotNull { r -> r.usage?.let { r to it } }
        val tokens = ReceiptTokens(
            input = counted.sumOf { it.second.input.toLong() },
            output = counted.sumOf { it.second.output.toLong() },
            cacheRead = counted.sumOf { it.second.cacheRead.toLong() },
            requests = counted.size,
        )
        val prices = counted.map { (r, u) -> price(r.providerId, r.model)?.cost(u) }
        val cost = if (prices.all { it == null }) null else ReceiptCost(prices.sumOf { it ?: 0.0 }, partial = prices.any { it == null })

        val ids = messages.map { it.id }
        val changeTurns = ids.filter { changes[it].orEmpty().isNotEmpty() }
        val files = changeTurns.flatMap { changes[it].orEmpty() }.toSet().size

        val checks = replies.flatMap { m ->
            // run_command through call_tool counts too (docs/design/tool-discovery.md).
            m.toolCalls.map { c -> dev.locus.core.model.DiscoveryTools.unwrap(c.name, c.argumentsJson).let { (n, a) -> c.copy(name = n, argumentsJson = a) } }
                .filter { it.name == "run_command" }.mapNotNull { call ->
                val result = results[call.id] ?: return@mapNotNull null
                Verification.ofRunCommand(call.argumentsJson, result.content, result.isError)
                    ?.let { ReceiptCheck(m.id, call.id, it.command.kind, it.passed) }
            }
        }

        val chats = started.mapTo(HashSet()) { it.conversationId } + record.conversationId
        val decisions = audit.filter { it.conversationId in chats && inTask(it.startedAt) && it.toolName != TASK_BUDGET_ENTRY }.map { it.decision }
        val approvals = ReceiptApprovals(
            approved = decisions.count { it in ReceiptApprovals.APPROVED },
            declined = decisions.count { it in ReceiptApprovals.DECLINED },
            blocked = decisions.count { it in ReceiptApprovals.BLOCKED },
        )

        // What the run read itself, and what it started with: a run is tainted by everything its model saw.
        val before = path.filter { it.createdAt < start }
        val inherited = Taint.sourcesIn(Compaction.modelContext(before))
        val read = Taint.sourcesIn(messages)
        val outside = (inherited + read).map(::sourceLabel).distinct().sorted()

        return TaskReceipt(
            taskId = record.id,
            conversationId = record.conversationId,
            outcome = outcome,
            detail = record.detail,
            startedAt = start,
            endedAt = end,
            steps = replies.size,
            models = models(requests),
            tokens = tokens,
            cost = cost,
            files = files,
            changeTurns = changeTurns,
            checks = checks,
            approvals = approvals,
            outsideContent = outside,
            taintedFromStart = inherited.isNotEmpty(),
            helpers = started.map { it.label },
            afterMessageId = messages.last().id,
            workflow = workflow(messages, checks, changeTurns.flatMap { changes[it].orEmpty() }.distinct()),
        )
    }

    /**
     * A workflow run's checks (docs/design/workflows.md): the plan rides on the task's first message;
     * its checks are judged from the task's runs, changes and last reply.
     */
    private fun workflow(messages: List<Message>, checks: List<ReceiptCheck>, changed: List<String>): ReceiptWorkflow? {
        val plan = messages.firstNotNullOfOrNull { WorkflowRender.planIn(it) } ?: return null
        val reply = messages.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text
        return ReceiptWorkflow(plan.id, plan.name, WorkflowChecks.evaluate(plan.checks, checks, reply, changed))
    }

    /** "Workflow checks: 2 of 3 passed". Null without checks. */
    fun workflowChecksLabel(r: TaskReceipt): String? {
        val w = r.workflow?.takeIf { it.checks.isNotEmpty() } ?: return null
        return "${w.passed} of ${w.checks.size} checks passed"
    }

    /** Each model once, in the order it first answered, with its role. */
    private fun models(requests: List<Usage>): List<ReceiptModel> = requests.mapNotNull { r ->
        val name = r.model?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        when (r.source) {
            UsageSource.CHAT -> if (r.fallbackFrom != null) ReceiptModel(name, ReceiptModel.Kind.BACKUP, r.fallbackFrom) else ReceiptModel(name)
            UsageSource.HELPER -> ReceiptModel(name, ReceiptModel.Kind.HELPER)
            UsageSource.REVIEWER -> ReceiptModel(name, ReceiptModel.Kind.REVIEWER)
            UsageSource.COMPACTION -> ReceiptModel(name, ReceiptModel.Kind.SUMMARY)
            else -> null
        }
    }.distinct()

    /** A taint source (a tool name, or a note from [Taint.sourcesIn]) in plain words. */
    fun sourceLabel(source: String): String = when {
        source == "web_search" -> "web search"
        source == "fetch_url" || source == "web_read" || source.startsWith("browser_") -> "web pages"
        source == "wikipedia" -> "Wikipedia"
        source.startsWith("mcp_") || source.startsWith("connector_") -> "connectors"
        source.startsWith("email_") -> "email"
        source.startsWith("github_") -> "GitHub"
        source == "run_command" || source.startsWith("linux_") -> "command output"
        source.startsWith("ssh_") -> "remote machines"
        SubagentTools.isAgentTool(source) || source == "a helper's report" -> "helpers' reports"
        else -> source
    }

    // ---- Words -----------------------------------------------------------------------------------

    /** "Done", "Stopped by you", "Paused at budget", "Failed"… */
    fun outcomeLabel(r: TaskReceipt): String = when (r.outcome) {
        TaskOutcome.DONE -> "Done"
        TaskOutcome.STOPPED -> if (r.detail == null) "Stopped by you" else "Stopped"
        TaskOutcome.BUDGET -> "Paused at budget"
        TaskOutcome.FAILED -> "Failed"
        TaskOutcome.REFUSED -> "Model declined"
        TaskOutcome.STEP_LIMIT -> "Step limit reached"
        TaskOutcome.INTERRUPTED -> "Interrupted"
    }

    /** The outcome with its reason: "Failed: the provider refused the key". */
    fun outcomeLine(r: TaskReceipt): String {
        // The budget's own wording starts "Stopped: …" or "Paused: …"; the label already says that.
        val detail = r.detail?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.removeSuffix(".")
            ?.removePrefix("Stopped: ")?.removePrefix("Paused: ")
        return if (detail.isNullOrEmpty()) outcomeLabel(r) else "${outcomeLabel(r)}: $detail"
    }

    /** "4m 12s", "42s". */
    fun duration(r: TaskReceipt): String = BudgetText.duration(r.durationMs)

    /** "≈$0.04", "≈$0.04+" when some prices were missing, "<$0.01", or "cost unknown"; null with no requests. */
    fun costLabel(r: TaskReceipt): String? {
        if (r.tokens.requests == 0) return null
        val c = r.cost ?: return "cost unknown"
        val amount = if (c.usd > 0 && c.usd < 0.01) "<$0.01" else "≈" + RunBudget.usd(c.usd)
        return amount + if (c.partial) "+" else ""
    }

    /** "tests passed", "build failed", "checks timed out"; null when nothing was checked. */
    fun checksLabel(r: TaskReceipt): String? {
        if (r.checks.isEmpty()) return null
        val kinds = r.checks.map { it.kind }.toSet()
        val noun = when (kinds.singleOrNull()) {
            Verification.Kind.TEST -> "tests"
            Verification.Kind.BUILD -> "build"
            else -> "checks"
        }
        return when {
            r.checks.any { it.passed == false } -> "$noun failed"
            r.checks.any { it.passed == null } -> "$noun timed out"
            else -> "$noun passed"
        }
    }

    /** "2 passed · 1 failed · 1 timed out". */
    fun checksDetail(r: TaskReceipt): String = listOfNotNull(
        r.checks.count { it.passed == true }.takeIf { it > 0 }?.let { "$it passed" },
        r.checks.count { it.passed == false }.takeIf { it > 0 }?.let { "$it failed" },
        r.checks.count { it.passed == null }.takeIf { it > 0 }?.let { "$it timed out" },
    ).joinToString(" · ")

    /** "claude-opus-5-5 · gpt-5.2 (backup for claude-opus-5-5 (Anthropic)) · haiku (helper)". */
    fun modelsLabel(r: TaskReceipt): String? = r.models.takeIf { it.isNotEmpty() }?.joinToString(" · ") { m ->
        m.name + when (m.kind) {
            ReceiptModel.Kind.CHAT -> ""
            ReceiptModel.Kind.BACKUP -> " (backup for ${m.standingInFor})"
            ReceiptModel.Kind.HELPER -> " (helper)"
            ReceiptModel.Kind.REVIEWER -> " (reviewer)"
            ReceiptModel.Kind.SUMMARY -> " (summary)"
        }
    }

    /** "120k in · 8.2k out · 64% cached". */
    fun tokensLabel(r: TaskReceipt): String = listOfNotNull(
        "${RunBudget.compactTokens(r.tokens.input)} in",
        "${RunBudget.compactTokens(r.tokens.output)} out",
        r.tokens.cachedPercent?.let { "$it% cached" },
    ).joinToString(" · ")

    /**
     * The receipt in one line, as the collapsed card shows it:
     * "Done · 4m 12s · 3 files · tests passed · 2 approvals · ≈$0.04".
     */
    fun summary(r: TaskReceipt): String = listOfNotNull(
        outcomeLabel(r),
        duration(r),
        r.files.takeIf { it > 0 }?.let { plural(it, "file") },
        checksLabel(r),
        workflowChecksLabel(r),
        r.approvals.asked.takeIf { it > 0 }?.let { plural(it, "approval") },
        r.approvals.blocked.takeIf { it > 0 }?.let { "$it blocked" },
        costLabel(r),
    ).joinToString(" · ")

    /** For a scheduled run's notification, under its answer (which already says why it failed): the [summary]. */
    fun notificationLine(r: TaskReceipt): String = summary(r)

    /**
     * A short Markdown block for "Share chat": the one-line summary, then a line per field that has
     * something to say. Nothing here is a secret (no arguments, no results), and the export redacts
     * key shapes anyway.
     */
    fun exportText(r: TaskReceipt): String = buildString {
        append("_Task receipt: ").append(summary(r)).append("_\n")
        fun line(label: String, value: String?) {
            if (!value.isNullOrBlank()) append("- ").append(label).append(": ").append(value).append('\n')
        }
        if (r.detail != null && r.outcome != TaskOutcome.DONE) line("Outcome", outcomeLine(r))
        line("Steps", r.steps.takeIf { it > 0 }?.toString())
        line("Models", modelsLabel(r))
        if (r.tokens.requests > 0) line("Tokens", tokensLabel(r))
        line("Cost", costLabel(r)?.let { if (r.cost != null) "$it (estimate from models.dev prices)" else it })
        line("Files changed", r.files.takeIf { it > 0 }?.toString())
        line("Checks", checksDetail(r).ifEmpty { null })
        r.workflow?.let { w ->
            line("Workflow", w.name)
            w.checks.forEach { c -> line("Check “${c.check.label}”", (when (c.passed) { true -> "passed"; false -> "failed"; null -> "not checked" }) + " (${c.note})") }
        }
        line("Approvals", approvalsDetail(r))
        line("Outside content read", r.outsideContent.takeIf { it.isNotEmpty() }?.joinToString(", "))
        line("Helpers", r.helpers.takeIf { it.isNotEmpty() }?.joinToString(", "))
    }.trimEnd()

    /** "3 asked · 2 you approved · 1 you declined · 1 blocked"; null when there were none. */
    fun approvalsDetail(r: TaskReceipt): String? {
        val a = r.approvals
        if (a.asked == 0 && a.blocked == 0) return null
        return listOfNotNull(
            "${a.asked} asked",
            a.approved.takeIf { it > 0 }?.let { "$it you approved" },
            a.declined.takeIf { it > 0 }?.let { "$it you declined" },
            a.blocked.takeIf { it > 0 }?.let { "$it blocked" },
        ).joinToString(" · ")
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
