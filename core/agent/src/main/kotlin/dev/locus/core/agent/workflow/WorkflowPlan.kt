package dev.locus.core.agent.workflow

import dev.locus.core.agent.Tool
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolGroup
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowParam
import dev.locus.core.model.WorkflowParamType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A workflow as one run sends it: parameters filled in, rides on the run's user message as a note
 * (never the system prompt, so the cached prefix holds). The message is also where Locus reads it
 * back from, for the run's approvals ([WorkflowScope]) and its receipt's checks ([WorkflowChecks]),
 * so it survives a restart and a "Continue" with nothing else stored.
 */
@Serializable
data class WorkflowPlan(
    val id: String,
    val name: String,
    val goal: String,
    val inputs: Map<String, String> = emptyMap(),
    val steps: List<String> = emptyList(),
    @SerialName("allowed_tools") val tools: List<String> = emptyList(),
    @SerialName("always_ask") val approvals: List<String> = emptyList(),
    @SerialName("success_checks") val checks: List<WorkflowCheck> = emptyList(),
    @SerialName("expected_output") val expectedOutput: String = "",
)

/** What a run sends: [text] is the user's visible message, [note] the plan for the model. */
data class RenderedWorkflow(val text: String, val note: String, val plan: WorkflowPlan)

/** A parameter value that can't be used, for the run sheet. */
class WorkflowInputException(val param: String, message: String) : IllegalArgumentException(message)

object WorkflowRender {
    private val json = Json { encodeDefaults = false; prettyPrint = false }
    private val SLOT = Regex("""\{([a-z][a-z0-9_]{0,31})\}""")

    /**
     * Fills [workflow]'s slots with [values] (defaults for what's missing) and builds the message.
     * Throws [WorkflowInputException] for a missing required value or one that isn't of its type.
     */
    fun render(workflow: Workflow, values: Map<String, String>): RenderedWorkflow {
        val filled = inputs(workflow.parameters, values)
        fun fill(text: String) = SLOT.replace(text) { m -> filled[m.groupValues[1]] ?: m.value }
        val plan = WorkflowPlan(
            id = workflow.id,
            name = workflow.name,
            goal = fill(workflow.goal).trim(),
            inputs = filled,
            steps = workflow.steps.map(::fill).filter { it.isNotBlank() },
            tools = workflow.tools,
            approvals = workflow.approvals,
            checks = workflow.checks.map { it.copy(label = fill(it.label), value = fill(it.value)) },
            expectedOutput = fill(workflow.expectedOutput),
        )
        return RenderedWorkflow(text = plan.goal, note = note(plan), plan = plan)
    }

    /** The values to run with: [values] checked against each parameter's type, defaults where blank. */
    fun inputs(params: List<WorkflowParam>, values: Map<String, String>): Map<String, String> = params.associate { p ->
        val raw = values[p.name]?.trim().orEmpty().ifEmpty { p.default?.trim().orEmpty() }
        if (raw.isEmpty()) {
            if (p.required) throw WorkflowInputException(p.name, "${p.title} is required.")
            return@associate p.name to ""
        }
        val problem = when (p.type) {
            WorkflowParamType.TEXT -> null
            WorkflowParamType.URL -> if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(raw)) null else "${p.title} must be a web address starting with http:// or https://."
            WorkflowParamType.PATH -> if (raw.split('/', '\\').any { it == ".." }) "${p.title} can't go up a folder with “..”." else null
            WorkflowParamType.NUMBER -> if (raw.toDoubleOrNull() == null) "${p.title} must be a number." else null
            WorkflowParamType.BOOLEAN -> if (raw.lowercase() in setOf("true", "false")) null else "${p.title} must be true or false."
            WorkflowParamType.CHOICE -> if (p.options.isEmpty() || raw in p.options) null else "${p.title} must be one of ${p.options.joinToString()}."
        }
        if (problem != null) throw WorkflowInputException(p.name, problem)
        p.name to (if (p.type == WorkflowParamType.BOOLEAN) raw.lowercase() else raw)
    }

    const val OPEN = "<workflow_plan>"
    const val CLOSE = "</workflow_plan>"
    private const val LEAD = "The user started their saved workflow"

    /**
     * The note for the model. The plan is guidance, not a script: the agent adapts steps to what it
     * finds, and says so. Tools outside the list and the approval points ask the user. It ends with
     * one line per check, which Locus reads for the checks it can't verify itself.
     */
    fun note(plan: WorkflowPlan): String = buildString {
        append("$LEAD “${plan.name}”. The plan below worked before; follow it, but it is a plan, not a script: ")
        append("check what you find, adapt or skip a step when the situation differs, and say briefly what you changed. ")
        append("Calls outside allowed_tools, and those in always_ask, will ask the user first. ")
        if (plan.checks.isNotEmpty()) {
            append("Before your final answer, verify each success check and end with one line per check, exactly: ")
            append("“Check: <label> — passed” or “Check: <label> — failed: <why>”. ")
        }
        if (plan.expectedOutput.isNotBlank()) append("Your final answer should give: ${plan.expectedOutput}. ")
        append("\n").append(OPEN).append(json.encodeToString(WorkflowPlan.serializer(), plan)).append(CLOSE)
    }

    /** The plan carried by [message], or null when it doesn't carry one. */
    fun planIn(message: Message): WorkflowPlan? {
        if (message.role != Role.USER) return null
        val text = message.parts.filterIsInstance<Part.Text>().firstOrNull { ModeNote.isNote(it.text) && OPEN in it.text }?.text ?: return null
        val body = text.substringAfter(OPEN, "").substringBefore(CLOSE, "")
        return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(WorkflowPlan.serializer(), body) }.getOrNull()
    }

    /**
     * The workflow the chat's current task runs: the plan on the latest message that started a task.
     * Messages that join a task (sent mid-task, notes from Locus, helper reports) don't end it; anything
     * else the user sent starts a new task, which isn't the workflow's.
     */
    fun active(history: List<Message>): WorkflowPlan? {
        for (m in history.asReversed()) {
            if (m.role != Role.USER) continue
            planIn(m)?.let { return it }
            if (ModeNote.isAgentNote(m) || ModeNote.isMidTask(m)) continue
            if (m.userText.startsWith(HELPER_REPORT)) continue
            if (m.parts.any { it is Part.Checkpoint }) continue
            return null
        }
        return null
    }

    private const val HELPER_REPORT = "[sub-agent report]"
}

/**
 * A workflow's declared tools at run time, the way a skill's manifest limits its own: a call outside
 * them, or one marked always-ask, goes to the user (Ask) instead of running on its own. It only ever
 * adds an approval; Deny, read-only runs and the user's own Ask still win.
 */
object WorkflowScope {
    /** Tools that steer the agent itself and change nothing (the checklist, a question, a plan). */
    private val CONTROL = setOf(AgentControlTools.TODO_WRITE, AgentControlTools.ASK_USER, AgentControlTools.PLAN_PROPOSE)

    /** The capability key of a call: `connector:<id>` for connector and MCP tools, else the tool's name. */
    fun key(toolName: String, args: JsonObject): String {
        if (toolName in ToolGroup.CONNECTOR_TOOLS) {
            (args["connector"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return ToolGroup.connectorKey(it) }
        }
        return toolName
    }

    /**
     * Why this call must ask the user under [plan], or null to leave the decision to the normal policy.
     * [observation] is whether the call only looks ([Tool.isObservation]): looking is always allowed.
     */
    fun escalation(plan: WorkflowPlan, toolName: String, args: JsonObject, observation: Boolean): String? {
        val key = key(toolName, args)
        if (key in plan.approvals || toolName in plan.approvals) {
            return "The workflow “${plan.name}” asks before this step: it needed your approval when the workflow was saved."
        }
        if (observation || toolName in CONTROL || plan.tools.isEmpty()) return null
        if (key in plan.tools) return null
        // A connector tool named in the list without a connector covers every connector.
        if (key != toolName && toolName in plan.tools) return null
        return "$key isn't one of the workflow “${plan.name}”'s tools, so it needs your approval."
    }

    suspend fun escalation(plan: WorkflowPlan, tool: Tool, args: JsonObject): String? =
        escalation(plan, tool.spec.name, args, tool.isObservation(args))
}
