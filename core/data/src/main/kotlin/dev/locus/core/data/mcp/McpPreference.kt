package dev.locus.core.data.mcp

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.McpFirst
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Picks, when a chat is created, the built-in integrations it reaches through their MCP servers. */
fun interface McpFirstChooser {
    /** Integration id → the MCP connector id the new chat uses for it instead of the built-in tools. */
    suspend fun choose(): Map<String, String>

    companion object {
        val None = McpFirstChooser { emptyMap() }
    }
}

/** Where one built-in integration stands against the vendor's MCP server, for Settings → Integrations. */
data class McpFirstStatus(
    val integration: Integration,
    /** The user's connector to the vendor's own server, if they added one. */
    val connector: Connector?,
    val builtInKey: Boolean,
    val mcpReady: Boolean,
    /** Why the server isn't usable right now, when it was tried and failed. */
    val problem: String? = null,
    /** The user chose to keep the built-in tools in new chats. */
    val keptBuiltIn: Boolean = false,
) {
    /** Both are set up, so new chats have a choice to make. */
    val both: Boolean get() = builtInKey && connector != null

    /** New chats leave the built-in tools out and use [connector]. */
    val usingMcp: Boolean get() = both && mcpReady && !keptBuiltIn
}

/**
 * The rule behind [McpFirstChooser]: a new chat reaches a built-in integration through its MCP server
 * only when all of these hold, so a chat never loses a service that works:
 *
 * - the integration can hand its tools over at all ([McpFirst.leftOut]: not the search backends);
 * - its built-in key is saved (the user really has both);
 * - an active connector reaches the vendor's own server ([McpTemplates.official]) and has its key or sign-in;
 * - that server's last connection in this run didn't fail;
 * - the user hasn't switched the integration back to its built-in tools.
 */
object McpFirstRule {
    fun statuses(
        settings: AppSettings,
        secretNames: Set<String>,
        connectors: List<Connector>,
        health: RemoteMcpHealth,
    ): List<McpFirstStatus> = Integrations.all.filter { McpFirst.canHandOver(it.id) }.map { integration ->
        val official = connectors.filter { c -> c.local == null && McpTemplates.official(c)?.replaces == integration.id }
        // Several connectors to the same server: prefer one known to work, then one with its credentials.
        val connector = official.firstOrNull { it.id in health.working } ?: official.firstOrNull { hasCredentials(it, secretNames) && it.id !in health.failing }
            ?: official.firstOrNull()
        val problem = connector?.let { c ->
            when {
                !hasCredentials(c, secretNames) -> if (c.auth.type == "oauth") "Not signed in" else "Its key is missing"
                else -> health.failing[c.id]
            }
        }
        McpFirstStatus(
            integration = integration,
            connector = connector,
            builtInKey = SecretStore.connectorSecret(integration.secret) in secretNames,
            mcpReady = connector != null && problem == null,
            problem = problem,
            keptBuiltIn = integration.id in settings.builtInOverMcp,
        )
    }

    /** Integration id → connector id, for each integration a new chat reaches through MCP. */
    fun choose(statuses: List<McpFirstStatus>): Map<String, String> =
        statuses.filter { it.usingMcp }.associate { it.integration.id to it.connector!!.id }

    private fun hasCredentials(c: Connector, names: Set<String>) = when (c.auth.type) {
        "none" -> true
        "oauth" -> SecretStore.mcpOAuthSession(c.auth.secret) in names
        else -> SecretStore.connectorSecret(c.auth.secret) in names
    }
}

/** [McpFirstRule] over the app's live state. */
@Singleton
class McpPreference @Inject constructor(
    private val home: AgentHome,
    private val secrets: SecretStore,
    private val settings: androidx.datastore.core.DataStore<AppSettings>,
    private val mcp: McpClients,
) : McpFirstChooser {

    override suspend fun choose(): Map<String, String> = McpFirstRule.choose(statuses())

    /**
     * Reads the connector files on the caller's dispatcher, like the rest of chat creation (the home
     * snapshot), so a chat is created in one go; screens collect it with flowOn(IO).
     */
    suspend fun statuses(): List<McpFirstStatus> =
        McpFirstRule.statuses(settings.data.first(), secrets.names.first(), home.connectors(), mcp.health.value)

    /** Re-read whenever settings, the vault's names or a server's health change. */
    val changes: Flow<Unit> = combine(settings.data, secrets.names, mcp.health) { _, _, _ -> }

    /**
     * Connects to each server that's set up alongside its built-in but hasn't been tried in this run,
     * so Settings can say whether it works. Only servers the user added for a service they also use.
     */
    suspend fun checkUntried() {
        statuses().filter { it.both && it.mcpReady }.mapNotNull { it.connector?.id }
            .filter { it !in mcp.health.value.working }
            .forEach { mcp.check(it) }
    }
}
