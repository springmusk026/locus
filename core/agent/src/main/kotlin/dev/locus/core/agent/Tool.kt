package dev.locus.core.agent

import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.defaultPolicy
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

data class ToolResult(val content: String, val isError: Boolean = false) {
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
     * After this tool runs successfully the turn ends without another model request; the user's next
     * message continues it (for example a proposed plan waiting for approval).
     */
    val endsTurn: Boolean get() = false

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
