package dev.locus.core.agent.workflow

import dev.locus.core.model.ReceiptCheck
import dev.locus.core.model.Verification
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowCheck.CheckKind
import dev.locus.core.model.WorkflowCheckResult

/**
 * Judges a workflow run's success checks from what the task left: its build and test runs (the
 * verification cards), the files it changed and its final reply. Pure, so each kind is tested from
 * fixtures. Only [CheckKind.AGENT] checks rely on the agent's own report, and the result says so.
 */
object WorkflowChecks {

    fun evaluate(
        checks: List<WorkflowCheck>,
        /** The task's build, test and lint runs, in order. */
        runs: List<ReceiptCheck>,
        /** The task's last reply text; null when it ended without one. */
        reply: String?,
        /** Paths the task changed that still stand. */
        changed: Collection<String>,
    ): List<WorkflowCheckResult> = checks.map { check ->
        when (check.kind) {
            CheckKind.TESTS_PASS -> verification(check, runs, Verification.Kind.TEST, "test")
            CheckKind.BUILD_PASS -> verification(check, runs, Verification.Kind.BUILD, "build")
            CheckKind.CHECK_PASS -> verification(check, runs, Verification.Kind.CHECK, "lint or type check")
            CheckKind.FILE_CHANGED -> {
                val want = check.value.trim().trimStart('/').removePrefix("./")
                when {
                    want.isEmpty() -> WorkflowCheckResult(check, changed.isNotEmpty(), if (changed.isEmpty()) "No files changed" else "${changed.size} changed")
                    changed.any { it == want || it.endsWith("/$want") } -> WorkflowCheckResult(check, true, "$want changed")
                    else -> WorkflowCheckResult(check, false, "$want wasn't changed")
                }
            }
            CheckKind.REPLY_CONTAINS -> when {
                reply == null -> WorkflowCheckResult(check, false, "The task ended without a reply")
                reply.contains(check.value.trim(), ignoreCase = true) -> WorkflowCheckResult(check, true, "The reply has “${check.value.trim()}”")
                else -> WorkflowCheckResult(check, false, "The reply doesn't have “${check.value.trim()}”")
            }
            CheckKind.AGENT -> agentReport(check, reply)
        }
    }

    /** The last run of [kind] decides: a fix after a failing run counts. */
    private fun verification(check: WorkflowCheck, runs: List<ReceiptCheck>, kind: Verification.Kind, what: String): WorkflowCheckResult {
        val last = runs.lastOrNull { it.kind == kind } ?: return WorkflowCheckResult(check, null, "No $what run in this task")
        return when (last.passed) {
            true -> WorkflowCheckResult(check, true, "The last $what run passed")
            false -> WorkflowCheckResult(check, false, "The last $what run failed")
            null -> WorkflowCheckResult(check, false, "The last $what run timed out")
        }
    }

    private val REPORT = Regex("""^\W*check\s*:\s*(.+?)\s*[—–:-]+\s*(passed|failed|pass|fail)\b(.*)$""", setOf(RegexOption.IGNORE_CASE))

    /** "Check: <label> — passed" lines at the end of the reply, as the plan asks for. */
    private fun agentReport(check: WorkflowCheck, reply: String?): WorkflowCheckResult {
        val label = normal(check.label)
        val line = reply?.lineSequence()?.mapNotNull { REPORT.find(it.trim()) }?.lastOrNull { normal(it.groupValues[1]) == label }
            ?: return WorkflowCheckResult(check, null, "The agent didn't report it")
        val passed = line.groupValues[2].lowercase().startsWith("pass")
        val why = line.groupValues[3].trim().trimStart(':', ' ').take(200)
        return WorkflowCheckResult(check, passed, "Reported by the agent" + if (!passed && why.isNotEmpty()) ": $why" else "")
    }

    private fun normal(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
