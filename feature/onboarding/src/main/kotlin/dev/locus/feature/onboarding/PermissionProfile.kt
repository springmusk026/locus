package dev.locus.feature.onboarding

import androidx.annotation.StringRes
import dev.locus.core.agent.Tool
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk

/** A starting point for the approval policies; every tool can still be changed in Settings. */
enum class PermissionProfile(@StringRes val titleRes: Int, @StringRes val summaryRes: Int) {
    Careful(R.string.onboarding_profile_careful_title, R.string.onboarding_profile_careful_summary),
    Balanced(R.string.onboarding_profile_balanced_title, R.string.onboarding_profile_balanced_summary),
    Autonomous(R.string.onboarding_profile_autonomous_title, R.string.onboarding_profile_autonomous_summary);

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
