package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.SubagentTools
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.coroutines.currentCoroutineContext
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
 * Helpers (sub-agents): `agent_spawn`, `agent_send`, `agent_wait`, `agent_status`, `agent_cancel`, and
 * `agent_create` for drafting a new helper type. They are part of the fixed tool list; helpers
 * themselves have every `agent_*` tool denied (one level only). [engine] is resolved on first use, so
 * the registry can be built before the engine that runs on it.
 */
fun subagentTools(home: AgentHome, engine: () -> SubagentEngine): List<Tool> =
    listOf(AgentSpawn(engine), AgentWait(engine), AgentStatus(engine), AgentCancel(engine), AgentCreate(home), AgentSend(engine))

private suspend fun callContext() = currentCoroutineContext()[ToolCallContext]

private class AgentSpawn(private val engine: () -> SubagentEngine) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        SubagentTools.SPAWN,
        "Start a helper (sub-agent) with its own fresh context and a limited set of tools, for a large, self-contained piece of work. Use it only " +
            "for work that is genuinely independent and big enough to be worth it (e.g. researching several separate questions in parallel, " +
            "exploring a large folder); do simple or tightly linked tasks yourself. Never use a helper to check or verify work.\n" +
            "Types: " + AgentProfile.BUILT_IN.joinToString("; ") { "${it.type}: ${it.description}" } +
            "; plus any listed in <available_agents>.\n" +
            "The helper sees nothing of this chat: the task must state the objective, the output format you want back, and every path, error, " +
            "decision and constraint it needs. It returns a report in <subagent_report> (at most about 6,000 characters; longer ones are saved to a " +
            "workspace file). By default this waits for the report; with background true it returns an agentId at once so you can start more " +
            "helpers or keep working, then collect reports with agent_wait. At most 3 helpers run at once and 6 per turn.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("type") { put("type", "string"); put("description", "Helper type: researcher, coder, explorer, ops, browser, or a name from <available_agents>.") }
                putJsonObject("task") {
                    put("type", "string")
                    put("description", "Everything the helper needs: objective, expected output format, relevant paths/URLs/errors/decisions, constraints and what's out of scope.")
                }
                putJsonObject("context") {
                    put("type", "string")
                    putJsonArray("enum") { add("fresh"); add("fork") }
                    put("description", "fresh (default): only the task. fork: the helper also gets this whole chat so far (costly in tokens; same model).")
                }
                putJsonObject("model") {
                    put("type", "string")
                    putJsonArray("enum") { add("same"); add("helper") }
                    put("description", "same: this chat's model. helper: the user's cheaper helper model (good for research and extraction). Default depends on the type.")
                }
                putJsonObject("background") { put("type", "boolean"); put("description", "true: return at once with an agentId; collect the report with agent_wait.") }
            }
            putJsonArray("required") { add("type"); add("task") }
            put("additionalProperties", false)
        },
    )

    override fun describe(args: JsonObject) = "Start a ${args.str("type") ?: "helper"}: ${args.str("task").orEmpty().lineSequence().first().take(120)}"

    // A helper that only looks may start in plan mode and read-only runs; it inherits those limits either way.
    override suspend fun isObservation(args: JsonObject) = engine().profile(args.str("type").orEmpty())?.looksOnly == true

    override suspend fun execute(args: JsonObject): ToolResult = engine().spawn(
        callContext(),
        type = args.str("type").orEmpty(),
        task = args.str("task").orEmpty(),
        fork = args.str("context") == "fork",
        helperModel = args.str("model")?.let { it == "helper" },
        background = args.bool("background"),
    )
}

private class AgentSend(private val engine: () -> SubagentEngine) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        SubagentTools.SEND,
        "Continue a helper of this chat that has finished or stopped early (partial, failed, stopped or interrupted), with one more message: " +
            "a follow-up question, a correction, or \"finish the rest\". It keeps everything it saw and did, so this is cheaper than a new helper " +
            "when the work builds on its report. It runs with the same type, tools and limits, counts as a new helper toward the limits of 3 at " +
            "once and 6 per turn, and returns a new <subagent_report>. A helper that is still working can't take a message: collect it with " +
            "agent_wait first. The helper still sees nothing else of this chat, so include any new paths, errors or decisions it needs.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("agent_id") { put("type", "string"); put("description", "agentId of a helper started in this chat (from agent_spawn).") }
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "What to do next and what you want back in the new report.")
                }
                putJsonObject("background") { put("type", "boolean"); put("description", "true: return at once; collect the new report with agent_wait.") }
            }
            putJsonArray("required") { add("agent_id"); add("message") }
            put("additionalProperties", false)
        },
    )

    override fun describe(args: JsonObject) = "Continue helper ${args.str("agent_id").orEmpty()}: ${args.str("message").orEmpty().lineSequence().first().take(120)}"

    // Like agent_spawn: a helper that only looks may continue in plan mode and read-only runs.
    override suspend fun isObservation(args: JsonObject) = engine().profileOf(args.str("agent_id").orEmpty())?.looksOnly == true

    override suspend fun execute(args: JsonObject): ToolResult = engine().send(
        callContext(),
        agentId = args.str("agent_id").orEmpty(),
        message = args.str("message").orEmpty(),
        background = args.bool("background"),
    )
}

private class AgentWait(private val engine: () -> SubagentEngine) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        SubagentTools.WAIT,
        "Wait for background helpers to finish and get their reports. Lists the reports of those that finished and what the others are doing " +
            "if the time runs out. Without ids, waits for every helper of this chat whose report you haven't collected.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("ids") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "agentIds from agent_spawn.")
                }
                putJsonObject("timeoutSec") { put("type", "integer"); put("description", "How long to wait at most (default 300, max 900).") }
            }
            putJsonArray("required") {}
            put("additionalProperties", false)
        },
    )

    override fun describe(args: JsonObject) = "Wait for helpers"

    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult {
        val ids = (args["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
        return engine().wait(callContext(), ids, args.int("timeoutSec")?.let { it * 1_000L })
    }
}

private class AgentStatus(private val engine: () -> SubagentEngine) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        SubagentTools.STATUS,
        "Check on a helper without waiting: its progress, or its report if it finished.",
        ObjectSchema().string("id", "agentId from agent_spawn.").build(),
    )

    override fun describe(args: JsonObject) = "Check on helper ${args.str("id").orEmpty()}"

    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult = engine().status(callContext(), args.str("id").orEmpty())
}

private class AgentCancel(private val engine: () -> SubagentEngine) : Tool {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        SubagentTools.CANCEL,
        "Stop a helper that is no longer needed or going the wrong way. Returns what it had done so far.",
        ObjectSchema().string("id", "agentId from agent_spawn.").build(),
    )

    override fun describe(args: JsonObject) = "Stop helper ${args.str("id").orEmpty()}"

    override suspend fun isObservation(args: JsonObject) = true

    override suspend fun execute(args: JsonObject): ToolResult = engine().cancel(callContext(), args.str("id").orEmpty())
}

private class AgentCreate(private val home: AgentHome) : Tool {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        SubagentTools.CREATE,
        "Propose a new helper type for agent_spawn: a role with its own instructions and a narrow tool list, for work that recurs (e.g. a " +
            "\"price-checker\" that only searches and reads shops). It is saved as a draft; the user reviews it under Library → Agents before " +
            "it can be used. Proposing an existing name drafts a new version.",
        ObjectSchema()
            .string("name", "lowercase-with-hyphens, max 64 chars, e.g. \"price-checker\". Not a built-in type.")
            .string("description", "What it does and when to use it, one or two sentences (shown in <available_agents>).")
            .string("tools", "Comma-separated tool names it may use; a prefix ending in * covers a family, e.g. \"web_search, web_read, browser_*\".")
            .string("instructions", "Its system prompt in Markdown: role, how to work, what to report back.")
            .string("model", "inherit (the chat's model, default), helper (the cheaper helper model) or a model id.", required = false)
            .integer("maxSteps", "Step limit, 1–25 (default 25).")
            .string("effort", "Thinking effort: off, low, medium, high or max (default: the provider's).", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Draft helper “${args.str("name")}”"

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.str("name").orEmpty().trim()
        if (AgentProfile.BUILT_IN.any { it.type == name }) throw ToolException("“$name” is a built-in helper type; pick another name.")
        val definition = AgentHome.AgentDefinition(
            name = name,
            description = args.str("description").orEmpty().trim(),
            tools = args.str("tools").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
            model = args.str("model")?.trim()?.takeIf { it.isNotEmpty() } ?: "inherit",
            maxSteps = args.int("maxSteps"),
            effort = args.str("effort")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            body = args.str("instructions").orEmpty(),
        )
        try {
            home.draftAgent(definition)
        } catch (e: IllegalArgumentException) {
            throw ToolException(e.message.orEmpty())
        }
        return ToolResult("Drafted helper “$name”. Tell the user it is waiting for their review in Library → Agents; it can't be used until they approve it.")
    }
}
