package dev.locus.feature.onboarding

import dev.locus.core.agent.Tool
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk

/** A starting point for the approval policies; every tool can still be changed in Settings. */
enum class PermissionProfile(val title: String, val summary: String) {
    Careful(
        "Careful",
        "Asks before anything that writes, runs a command or goes online. Only reading your workspace and memory happens on its own.",
    ),
    Balanced(
        "Balanced",
        "The defaults. Looking things up and reading pages run on their own; writing files, running commands and sending anything ask first.",
    ),
    Autonomous(
        "Autonomous",
        "Runs almost everything without asking. Payments, logins, sensitive clicks and remote machines (SSH) still ask, and deleted files go to the trash.",
    );

    /**
     * The per-tool overrides this profile needs. Only differences from each tool's own default are
     * stored, so tools added later (and changed defaults) still apply. A tool that is denied by
     * default stays denied.
     */
    fun overrides(tools: List<Tool>): Map<String, ToolPolicy> = tools.mapNotNull { tool ->
        val default = tool.defaultPolicy
        val wanted = when {
            default == ToolPolicy.DENY -> default
            this == Careful -> if (tool.risk == ToolRisk.READ) default else ToolPolicy.ASK
            this == Autonomous -> if (reachesOtherMachines(tool.spec.name)) default else ToolPolicy.ALLOW
            else -> default
        }
        (tool.spec.name to wanted).takeIf { wanted != default }
    }.toMap()

    private fun reachesOtherMachines(name: String) = name.startsWith("ssh_") || name.startsWith("sftp_")
}
