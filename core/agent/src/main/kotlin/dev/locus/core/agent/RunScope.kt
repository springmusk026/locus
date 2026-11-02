package dev.locus.core.agent

import dev.locus.core.model.ChatScopeSpec
import kotlinx.serialization.json.JsonObject

/** A run-level check of one call: a reason (to refuse, or to ask), or null to leave it to the policy. */
typealias CallCheck = suspend (Tool, JsonObject) -> String?

/**
 * Everything that limits a run beyond the user's per-tool policy, in one value: whether anyone can be
 * asked, the chat's and project's limits, the workflow's declared tools, what outside content it has
 * read ([Taint]), the skills it has loaded ([SkillScope]) and its project.
 *
 * A run's own scope comes from its [AgentConfig]; a helper's run also gets its parent's
 * ([AgentConfig.inherited], taken from the spawning call's [ToolCallContext.run]) and is held to both
 * ([and]). So a helper is never less restricted than the run that started it, whatever new limit is
 * added here later.
 */
class RunScope(
    /**
     * Nobody can be asked (a scheduled run, or a chat in Read-only mode): only calls that look run, and
     * anything that would need the user is refused instead of asking.
     */
    val readOnly: Boolean = false,
    /** Refuses a call (plan mode, tools switched off, the project's limits); read fresh for every call. */
    val restrict: CallCheck? = null,
    /**
     * Makes a call ask even where the policy allows it (a workflow's declared tools and approval points,
     * see [dev.locus.core.agent.workflow.WorkflowScope]). It only ever adds an approval.
     */
    val escalate: CallCheck? = null,
    /** What the run had read and the hosts its user named; null: the run starts from its own history. */
    val taint: TaintSeed? = null,
    /** The skills the run has loaded, with their manifests' lookup; null: skills aren't checked. */
    val skills: SkillSeed? = null,
    /** The project the run works in; null: the whole workspace and global memory. */
    val project: ChatScopeSpec? = null,
    /** Hosts the user set up in Locus, trusted like hosts they typed once the run is tainted. */
    val trustedHosts: (suspend () -> Set<String>)? = null,
) {
    /**
     * This scope held to [other] as well: the stricter answer of the two for every check. Read-only if
     * either is; every refusal and every escalation of both applies; taint from both, trusting only the
     * hosts both trust; every skill either loaded.
     */
    infix fun and(other: RunScope?): RunScope {
        if (other == null) return this
        return RunScope(
            readOnly = readOnly || other.readOnly,
            restrict = firstOf(restrict, other.restrict),
            escalate = allOf(escalate, other.escalate),
            taint = when {
                taint == null -> other.taint
                other.taint == null -> taint
                else -> TaintSeed(taint.sources + other.taint.sources, taint.trustedHosts intersect other.taint.trustedHosts)
            },
            skills = when {
                skills == null -> other.skills
                other.skills == null -> skills
                else -> SkillSeed(skills.active + other.skills.active) { name -> skills.manifests.manifest(name) ?: other.skills.manifests.manifest(name) }
            },
            project = project ?: other.project,
            trustedHosts = when {
                trustedHosts == null -> other.trustedHosts
                other.trustedHosts == null -> trustedHosts
                else -> ({ trustedHosts.invoke() intersect other.trustedHosts.invoke() })
            },
        )
    }

    /** The same scope with the taint and skills the run has now. */
    fun with(taint: TaintSeed?, skills: SkillSeed?, readOnly: Boolean = this.readOnly) =
        RunScope(readOnly, restrict, escalate, taint, skills, project, trustedHosts)

    private companion object {
        /** Refusals: the first reason given. */
        fun firstOf(a: CallCheck?, b: CallCheck?): CallCheck? = when {
            a == null -> b
            b == null -> a
            else -> { tool, args -> a(tool, args) ?: b(tool, args) }
        }

        /** Escalations: every reason given, so the approval card says all of them. */
        fun allOf(a: CallCheck?, b: CallCheck?): CallCheck? = when {
            a == null -> b
            b == null -> a
            else -> { tool, args -> listOfNotNull(a(tool, args), b(tool, args)).distinct().joinToString("\n").ifEmpty { null } }
        }
    }
}
