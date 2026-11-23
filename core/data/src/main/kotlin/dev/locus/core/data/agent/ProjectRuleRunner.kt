package dev.locus.core.data.agent

import dev.locus.core.agent.ProjectRuleHooks
import dev.locus.core.agent.RuleOutcome
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProjectRule
import dev.locus.core.model.ProjectRules

/**
 * A project's rules for one run (docs/design/rules.md): read fresh from the sealed settings at each hook,
 * and run in Linux on the phone, never on a linked machine and never over the network by Locus. The
 * caller (the agent loop) runs [run] with the chat's [dev.locus.core.agent.ChatScope], so the project's
 * folder is `/workspace`.
 */
class ProjectRuleRunner(
    private val projectId: String,
    private val settings: suspend () -> AppSettings,
    private val linux: LinuxEnvironment,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProjectRuleHooks {
    override suspend fun rules(): List<ProjectRule> = settings().projectRules[projectId].orEmpty().filter { it.active }

    override suspend fun canRun(): Boolean = linux.status.value == LinuxStatus.Ready

    override suspend fun run(rule: ProjectRule, files: List<String>): RuleOutcome {
        val started = clock()
        val result = linux.run(
            command = ProjectRules.commandLine(rule.command, files),
            cwd = "/workspace",
            timeoutMs = rule.timeout * 1000L,
            maxChars = OUTPUT_CHARS,
        )
        val code = if (result.timedOut) null else result.exitCode
        return RuleOutcome(code, result.output, clock() - started)
    }

    companion object {
        /** What's kept in memory of a rule's output; the note keeps less ([dev.locus.core.agent.RuleHooks.MAX_OUTPUT]). */
        const val OUTPUT_CHARS = 16_000

        /** The rules of a chat's project, or null outside projects. */
        fun forProject(projectId: String?, settings: suspend () -> AppSettings, linux: LinuxEnvironment): ProjectRuleRunner? =
            projectId?.let { ProjectRuleRunner(it, settings, linux) }
    }
}
