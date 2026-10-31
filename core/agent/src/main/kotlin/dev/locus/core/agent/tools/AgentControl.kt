package dev.locus.core.agent.tools

import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.str
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.Message
import dev.locus.core.model.ProposedPlan
import dev.locus.core.model.Role
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The agent's own bookkeeping tools: a checklist for multi-step work and a plan to propose in plan
 * mode. They only change what the chat shows, so they count as observation (allowed in plan mode and
 * read-only runs) and run without asking.
 *
 * Shapes follow the leading agents: `todo_write` replaces the whole list each call, like Claude Code's
 * TodoWrite (content, status pending/in_progress/completed, activeForm); one item in progress at a
 * time, like Codex's `update_plan`. `plan_propose` ends the turn like Claude Code's ExitPlanMode: the
 * user approves, edits or cancels in the app, and their answer arrives as the next message.
 */
fun agentControlTools(): List<Tool> = listOf(TodoWrite(), PlanPropose())

private class TodoWrite : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        AgentControlTools.TODO_WRITE,
        "Keep a checklist for multi-step work that the user sees live in the chat. Each call replaces the whole list: send every item " +
            "with its current status. Mark an item in_progress before starting it (only one at a time) and completed as soon as it's done. " +
            "Use it for tasks of 3 or more steps or when the user lists several things; skip it for quick, single-step requests.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("todos") {
                    put("type", "array")
                    put("description", "The complete, updated list.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("content") { put("type", "string"); put("description", "What to do, imperative: “Install dependencies”.") }
                            putJsonObject("status") {
                                put("type", "string")
                                putJsonArray("enum") { TodoStatus.entries.forEach { add(it.wire) } }
                            }
                            putJsonObject("activeForm") { put("type", "string"); put("description", "Shown while in progress: “Installing dependencies”.") }
                        }
                        putJsonArray("required") { add("content"); add("status") }
                    }
                }
            }
            putJsonArray("required") { add("todos") }
            put("additionalProperties", false)
        },
    )

    override fun describe(args: JsonObject) = "Update the checklist"

    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val items = Todos.parse(args) ?: throw ToolException("todos must be an array of {content, status} with status pending, in_progress or completed.")
        val done = items.count { it.status == TodoStatus.COMPLETED }
        val active = items.count { it.status == TodoStatus.IN_PROGRESS }
        return ToolResult(
            buildString {
                append("Checklist updated: $done of ${items.size} done.")
                if (active > 1) append(" Keep only one item in_progress at a time.")
                if (items.isNotEmpty() && done == items.size) append(" All done.")
            },
        )
    }
}

private class PlanPropose : Tool {
    override val risk = ToolRisk.READ
    override val endsTurn = true
    override val spec = ToolSpec(
        AgentControlTools.PLAN_PROPOSE,
        "Show the user your plan as a card with Approve, Edit and Cancel, then stop. Use it in plan mode once you've looked into the task, " +
            "or before a large or risky task. The user's answer arrives as the next message; on approval, carry out the plan.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string"); put("description", "The goal in a few words.") }
                putJsonObject("steps") {
                    put("type", "array")
                    put("description", "Concrete steps in order, each one short.")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("notes") { put("type", "string"); put("description", "Optional: assumptions, risks, or questions for the user (markdown).") }
            }
            putJsonArray("required") { add("title"); add("steps") }
            put("additionalProperties", false)
        },
    )

    override fun describe(args: JsonObject) = "Propose a plan: ${args.str("title").orEmpty()}"

    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val plan = Plans.parse(args) ?: throw ToolException("A plan needs a title and at least one step.")
        return ToolResult("The plan (${plan.steps.size} steps) is shown to the user with Approve, Edit and Cancel. Stop now; their answer arrives as the next message.")
    }
}

/** Reading the checklist back out of tool calls. Pure, so the chat can derive it from history. */
object Todos {
    fun parse(args: JsonObject): List<TodoItem>? {
        val list = args["todos"] as? JsonArray ?: return null
        return list.map { element ->
            val o = element as? JsonObject ?: return null
            val content = o.str("content")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val status = TodoStatus.parse(o.str("status")) ?: return null
            TodoItem(content, status, (o["activeForm"] ?: o["active_form"])?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    fun parse(argumentsJson: String): List<TodoItem>? =
        runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()?.let(::parse)

    /**
     * The latest checklist in [history]: the arguments of the last `todo_write` call that ran
     * successfully. Null when the agent never wrote one.
     */
    fun latest(history: List<Message>): List<TodoItem>? {
        val ok = history.filter { it.role == Role.TOOL }.flatMap { it.toolResults }
            .filter { it.toolName == AgentControlTools.TODO_WRITE && !it.isError }.map { it.callId }.toSet()
        val call = history.asReversed().asSequence()
            .filter { it.role == Role.ASSISTANT }
            .flatMap { it.toolCalls.asReversed().asSequence() }
            .firstOrNull { it.name == AgentControlTools.TODO_WRITE && it.id in ok } ?: return null
        return parse(call.argumentsJson)
    }
}

object Plans {
    fun parse(args: JsonObject): ProposedPlan? {
        val title = args.str("title")?.trim().orEmpty()
        val steps = (args["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        if (title.isEmpty() && steps.isEmpty()) return null
        if (steps.isEmpty()) return null
        return ProposedPlan(title.ifEmpty { "Plan" }, steps, args.str("notes")?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun parse(argumentsJson: String): ProposedPlan? =
        runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()?.let(::parse)
}
