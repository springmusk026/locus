package dev.locus.core.agent

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

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.spec.name }

    init {
        require(byName.size == tools.size) { "Duplicate tool names" }
    }

    val all: List<Tool> = tools.sortedBy { it.spec.name }

    /**
     * Every tool is always declared to the model, even ones the user has set to Deny: changing the
     * declared tool set mid-conversation would break the provider's cached and signed prefix. A
     * denied tool simply refuses to run.
     */
    val specs: List<ToolSpec> = all.map { it.spec }

    operator fun get(name: String): Tool? = byName[name]

    /**
     * This registry without the tools named in [names], for a chat that left them out when it was
     * created (see [dev.locus.core.agent.integrations.McpFirst]). The chat declares this list for its
     * whole life, so its cached prefix stays stable.
     */
    fun without(names: Set<String>): ToolRegistry =
        if (names.none { it in byName }) this else ToolRegistry(all.filter { it.spec.name !in names })
}
