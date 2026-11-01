package dev.locus.core.agent

import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.Part
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.defaultPolicy
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * What a tool returns. [savedTo] is set by a tool that already saved its whole output to a workspace
 * file and returns a shortened copy (run_command): the agent loop then names that file instead of
 * saving its own copy (see [LongOutput]).
 */
data class ToolResult(val content: String, val isError: Boolean = false, val savedTo: String? = null) {
    companion object {
        fun error(message: String) = ToolResult(message, isError = true)
    }
}

/** Raised by tools for expected failures (bad path, file missing); reported to the model, not crashed on. */
class ToolException(message: String) : Exception(message)

interface Tool {
    val spec: ToolSpec
    val risk: ToolRisk

    /** Policy when the user hasn't chosen one. Usually follows [risk]. */
    val defaultPolicy: ToolPolicy get() = risk.defaultPolicy()

    /** One line shown to the user when asking for approval and in the activity log. */
    fun describe(args: JsonObject): String

    /**
     * A reason this particular call must be confirmed even when the tool is set to Allow (for
     * example a browser click on "Pay now"). Null means the normal policy applies. Deny still wins.
     */
    suspend fun mustConfirm(args: JsonObject): String? = null

    /**
     * Why this call would fail, when that is known before it runs (an edit to a file the chat hasn't
     * read), so the user isn't asked to approve a call that can only fail. Null to go ahead. Runs with
     * the call's [ToolCallContext]; [execute] must still check, since things can change while the user decides.
     */
    suspend fun precheck(args: JsonObject): String? = null

    /**
     * Whether this call only looks at things, so it may run in read-only (background) mode.
     * Reading a web page counts; clicking, typing, writing or sending does not.
     */
    suspend fun isObservation(args: JsonObject): Boolean = risk == ToolRisk.READ

    /**
     * Where this call sends the user's files off the phone, decided at run time, or null when it stays
     * on the phone. run_command and the Linux service tools return the SSH machine's alias in a project
     * linked to one (docs/design/remote-execution.md). Taint then treats the call as outward, and a
     * Workspace-mode chat refuses it. Runs with the call's [dev.locus.core.agent.ChatScope].
     */
    suspend fun sendsOffPhone(args: JsonObject): String? = null

    /**
     * After this tool runs successfully the turn ends without another model request; the user's next
     * message continues it (for example a proposed plan waiting for approval).
     */
    val endsTurn: Boolean get() = false

    /**
     * This tool must run on its own, never alongside other calls of the same turn, even when it only
     * looks: it drives something shared one step at a time (the browser), waits for the user
     * (ask_user), or updates state that later calls of the turn read (the checklist). Calls that only
     * look and aren't this run together; see AgentLoop's executeAll.
     */
    val runsAlone: Boolean get() = false

    /**
     * This tool keeps its own output short and lets the agent ask for more (read_file's start_line and
     * max_lines, the browser's page diffs), so the agent loop doesn't save its long results to a file.
     * They are still capped at [AgentConfig.maxResultChars].
     */
    val pagesOwnOutput: Boolean get() = false

    suspend fun execute(args: JsonObject): ToolResult
}

class ToolRegistry(
    tools: List<Tool>,
    /** Tools that run but aren't declared up front: the model finds them with find_tools (see [ToolDiscovery]). */
    private val deferred: Set<String> = emptySet(),
    /** Tools found and run through discovery that aren't in the registry (MCP servers'); null for none. */
    val discoverable: DiscoverableTools? = null,
) {
    private val byName = tools.associateBy { it.spec.name }

    init {
        require(byName.size == tools.size) { "Duplicate tool names" }
    }

    val all: List<Tool> = tools.sortedBy { it.spec.name }

    /**
     * The tools declared to the model. Every one of them, always, even ones the user has set to Deny:
     * changing the declared tool set mid-conversation would break the provider's cached and signed
     * prefix. A denied tool simply refuses to run. With discovery, the deferred tools are left out
     * (for the chat's whole life) and reached through find_tools and call_tool.
     */
    val specs: List<ToolSpec> = all.filter { it.spec.name !in deferred }.map { it.spec }

    /** The tools this chat can run but doesn't declare up front (see [deferring]). Empty without discovery. */
    val deferredSpecs: List<ToolSpec> = all.filter { it.spec.name in deferred }.map { it.spec }

    /** Whether this chat finds tools on demand (declares find_tools and call_tool). */
    val discovers: Boolean get() = deferred.isNotEmpty()

    operator fun get(name: String): Tool? = byName[name]

    /**
     * This registry without the tools named in [names], for a chat that left them out when it was
     * created (see [dev.locus.core.agent.integrations.McpFirst]). The chat declares this list for its
     * whole life, so its cached prefix stays stable.
     */
    fun without(names: Set<String>): ToolRegistry =
        if (names.none { it in byName }) this else ToolRegistry(all.filter { it.spec.name !in names }, deferred - names, discoverable)

    /**
     * This registry for a chat with tool discovery: [core] declared, everything else deferred, plus
     * find_tools and call_tool. [extras] adds tools found and run through discovery only (MCP servers').
     */
    fun deferring(core: Set<String>, extras: DiscoverableTools?): ToolRegistry {
        val base = all.filter { it.spec.name != DiscoveryTools.FIND_TOOLS && it.spec.name != DiscoveryTools.CALL_TOOL }
        val later = base.filter { it.spec.name !in core }
        return ToolRegistry(base + FindTools(later.map { it.spec }, extras) + CallTool(), later.map { it.spec.name }.toSet(), extras)
    }

    /**
     * What [call] really runs. A call_tool is replaced by the call of the tool it names (same id), so
     * every check after this sees that tool and its arguments exactly as if the model had called it;
     * an MCP server's tool becomes the `mcp_call` that runs it. Any other call is returned as it is.
     */
    fun resolve(call: Part.ToolCall): ResolvedCall {
        if (call.name != DiscoveryTools.CALL_TOOL) return ResolvedCall(call, call)
        fun fail(message: String) = ResolvedCall(call, call, message)
        val args = runCatching { dev.locus.core.llm.LlmJson.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull()
            ?: return fail("call_tool's arguments weren't valid JSON. Send {\"name\": \"<tool>\", \"arguments\": {…}}.")
        val (name, arguments) = callToolTarget(args)
        if (name == null) return fail("Missing `name`: say which tool to run, as find_tools showed it.")
        if (arguments == null) return fail("`arguments` must be a JSON object with the tool's arguments.")
        if (name == DiscoveryTools.CALL_TOOL || name == DiscoveryTools.FIND_TOOLS) {
            return fail("call_tool runs other tools; call $name directly.")
        }
        discoverable?.route(name, arguments)?.let { (tool, routed) ->
            if (byName[tool] == null) return fail("Unknown tool '$name'.")
            return ResolvedCall(call, Part.ToolCall(call.id, tool, routed.toString()))
        }
        if (byName[name] == null) return fail("Unknown tool '$name'. Use find_tools to search for one, and copy its exact name.")
        return ResolvedCall(call, Part.ToolCall(call.id, name, arguments.toString()))
    }
}
