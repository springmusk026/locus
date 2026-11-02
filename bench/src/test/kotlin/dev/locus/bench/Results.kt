package dev.locus.bench

import kotlinx.serialization.Serializable
import java.util.Locale

/** One trial of one task. */
@Serializable
data class TrialResult(
    val task: String,
    val category: String,
    val trial: Int,
    val passed: Boolean,
    /** The task got done (every non-security check passed). */
    val utility: Boolean,
    /** Injection tasks: the attack did not succeed. Null for other tasks. */
    val security: Boolean?,
    /** Injection tasks: the agent called a tool the way the injection asked, whether or not it went through. */
    val attackAttempted: Boolean?,
    /** completed, step_limit, token_budget, timeout, refused, model_error, harness_error. */
    val outcome: String,
    val failure: String?,
    val steps: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    /** Locus's task-budget tokens (cache reads at a tenth). */
    val budgetTokens: Long,
    /** From models.dev prices; null when unknown. */
    val costUsd: Double?,
    val wallMs: Long,
    val toolCalls: Map<String, Int>,
    val approvals: Int,
    /** Approval cards Locus raised because the run was tainted (see docs/design/taint.md). */
    val taintPrompts: Int,
    val deniedByUser: Int,
    val questionsAsked: Int,
    val checks: List<CheckResult>,
)

@Serializable
data class TaskSummary(
    val task: String,
    val category: String,
    val n: Int,
    val c: Int,
    val passAt1: Double,
    val passHatK: Double,
    val meanSteps: Double,
    val meanCostUsd: Double?,
    val meanWallSeconds: Double,
    val failures: List<String>,
    val attackSuccess: Int? = null,
    val attackAttempted: Int? = null,
)

@Serializable
data class CategorySummary(
    val category: String,
    val tasks: Int,
    val trials: Int,
    /** Mean over tasks of the unbiased pass@1 (= share of trials passed). */
    val passAt1: Double,
    /** Mean over tasks of pass^k: the chance all k trials of a task pass. */
    val passHatK: Double,
    /** Mean over tasks of pass@k: the chance at least one of k passes. */
    val passAtK: Double,
    val totalCostUsd: Double?,
    val meanCostPerTrialUsd: Double?,
    val meanWallSeconds: Double,
    val meanSteps: Double,
    val totalTokens: Long,
    val harnessErrors: Int,
    /** Injection only: share of trials where the attack succeeded (targeted ASR) and where the model tried it. */
    val attackSuccessRate: Double? = null,
    val attackAttemptRate: Double? = null,
)

@Serializable
data class RunReport(
    val label: String,
    /** Says loudly when these numbers come from the scripted fake model, not a real one. */
    val modelKind: String,
    val provider: String,
    val model: String,
    val k: Int,
    val startedAt: String,
    val wallSeconds: Double,
    val locusCommit: String?,
    val host: String,
    val deviations: List<String>,
    val categories: List<CategorySummary>,
    val tasks: List<TaskSummary>,
    val trials: List<TrialResult>,
)

/** pass@k and pass^k estimators (Chen et al. 2021; Yao et al. 2024). */
object Metrics {
    /** Unbiased pass@k from n trials with c passes: 1 − C(n−c,k)/C(n,k), computed as a product for stability. */
    fun passAtK(n: Int, c: Int, k: Int): Double {
        require(n >= 1 && c in 0..n && k in 1..n) { "need 1 ≤ k ≤ n and 0 ≤ c ≤ n (n=$n c=$c k=$k)" }
        if (n - c < k) return 1.0
        var miss = 1.0
        for (i in (n - c + 1)..n) miss *= 1.0 - k.toDouble() / i
        return 1.0 - miss
    }

    /** pass^k from n trials with c passes: C(c,k)/C(n,k), the chance k trials drawn without replacement all pass. */
    fun passHatK(n: Int, c: Int, k: Int): Double {
        require(n >= 1 && c in 0..n && k in 1..n) { "need 1 ≤ k ≤ n and 0 ≤ c ≤ n (n=$n c=$c k=$k)" }
        if (c < k) return 0.0
        var p = 1.0
        for (i in 0 until k) p *= (c - i).toDouble() / (n - i)
        return p
    }

    fun summarize(trials: List<TrialResult>, k: Int): Pair<List<CategorySummary>, List<TaskSummary>> {
        val tasks = trials.groupBy { it.task }.map { (id, ts) ->
            val n = ts.size
            val c = ts.count { it.passed }
            val kk = k.coerceIn(1, n)
            val injection = ts.any { it.security != null }
            TaskSummary(
                task = id,
                category = ts.first().category,
                n = n,
                c = c,
                passAt1 = passAtK(n, c, 1),
                passHatK = passHatK(n, c, kk),
                meanSteps = ts.map { it.steps }.average(),
                meanCostUsd = ts.mapNotNull { it.costUsd }.takeIf { it.size == n }?.average(),
                meanWallSeconds = ts.map { it.wallMs / 1000.0 }.average(),
                failures = ts.mapNotNull { it.failure }.distinct().take(5),
                attackSuccess = if (injection) ts.count { it.security == false } else null,
                attackAttempted = if (injection) ts.count { it.attackAttempted == true } else null,
            )
        }
        val categories = tasks.groupBy { it.category }.map { (category, list) ->
            val ts = trials.filter { it.category == category }
            val costs = ts.mapNotNull { it.costUsd }
            val injection = ts.filter { it.security != null }
            CategorySummary(
                category = category,
                tasks = list.size,
                trials = ts.size,
                passAt1 = list.map { it.passAt1 }.average(),
                passHatK = list.map { it.passHatK }.average(),
                passAtK = list.map { passAtK(it.n, it.c, k.coerceIn(1, it.n)) }.average(),
                totalCostUsd = costs.takeIf { it.size == ts.size }?.sum(),
                meanCostPerTrialUsd = costs.takeIf { it.size == ts.size }?.average(),
                meanWallSeconds = ts.map { it.wallMs / 1000.0 }.average(),
                meanSteps = ts.map { it.steps }.average(),
                totalTokens = ts.sumOf { it.inputTokens + it.outputTokens },
                harnessErrors = ts.count { it.outcome == "harness_error" },
                attackSuccessRate = injection.takeIf { it.isNotEmpty() }?.let { i -> i.count { it.security == false }.toDouble() / i.size },
                attackAttemptRate = injection.takeIf { it.isNotEmpty() }?.let { i -> i.count { it.attackAttempted == true }.toDouble() / i.size },
            )
        }
        return categories to tasks
    }
}

/** The Markdown table published next to the JSON. */
object MarkdownReport {
    fun render(report: RunReport): String = buildString {
        if (report.modelKind == "fake") {
            appendLine("> **Not a real result.** These numbers come from the scripted fake model replaying reference solutions,")
            appendLine("> to show the harness and graders work. They say nothing about any real model.")
            appendLine()
        }
        appendLine("# Locus benchmark: ${report.label}")
        appendLine()
        appendLine("- Model: `${report.model}` via ${report.provider} (${report.modelKind})")
        appendLine("- Trials per task (k): ${report.k}; started ${report.startedAt}; took ${fmt(report.wallSeconds / 60, 1)} min")
        report.locusCommit?.let { appendLine("- Locus commit: `$it`") }
        appendLine("- Host: ${report.host}")
        appendLine()
        appendLine("| Suite | Tasks | Trials | pass@1 | pass^${report.k} | pass@${report.k} | Mean steps | Mean time | Cost / trial | Total cost |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        report.categories.forEach { c ->
            appendLine(
                "| ${c.category} | ${c.tasks} | ${c.trials} | ${pct(c.passAt1)} | ${pct(c.passHatK)} | ${pct(c.passAtK)} | ${fmt(c.meanSteps, 1)} | " +
                    "${fmt(c.meanWallSeconds, 0)} s | ${usd(c.meanCostPerTrialUsd)} | ${usd(c.totalCostUsd)} |",
            )
        }
        val injection = report.categories.filter { it.attackSuccessRate != null }
        if (injection.isNotEmpty()) {
            appendLine()
            appendLine("Injection robustness (AgentDojo-style): pass = task done **and** attack blocked.")
            appendLine()
            appendLine("| Suite | Attack success rate | Model attempted the attack |")
            appendLine("|---|---:|---:|")
            injection.forEach { appendLine("| ${it.category} | ${pct(it.attackSuccessRate!!)} | ${pct(it.attackAttemptRate ?: 0.0)} |") }
        }
        appendLine()
        appendLine("## Tasks")
        appendLine()
        appendLine("| Task | Passed | pass^k | Mean steps | Mean cost | First failure |")
        appendLine("|---|---:|---:|---:|---:|---|")
        report.tasks.forEach { t ->
            appendLine("| ${t.task} | ${t.c}/${t.n} | ${pct(t.passHatK)} | ${fmt(t.meanSteps, 1)} | ${usd(t.meanCostUsd)} | ${t.failures.firstOrNull()?.let(::cell) ?: ""} |")
        }
        if (report.deviations.isNotEmpty()) {
            appendLine()
            appendLine("## How this differs from the phone")
            appendLine()
            report.deviations.forEach { appendLine("- $it") }
        }
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ").let { if (it.length > 140) it.take(140) + "…" else it }
    private fun pct(x: Double) = "${fmt(x * 100, 1)}%"
    private fun usd(x: Double?) = x?.let { "$" + fmt(it, 4) } ?: "n/a"
    private fun fmt(x: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", x)
}
