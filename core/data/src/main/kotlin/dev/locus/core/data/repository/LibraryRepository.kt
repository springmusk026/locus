package dev.locus.core.data.repository

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.data.mcp.LocalMcpHealth
import dev.locus.core.data.mcp.McpClients
import dev.locus.core.data.mcp.McpSignIn
import dev.locus.core.datastore.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A connector as Library shows it. [missingSecrets] are vault secrets it names that aren't stored
 * yet; [health] is set for an active local MCP server.
 */
data class ConnectorEntry(
    val connector: Connector,
    val draft: Boolean,
    val hasSecret: Boolean,
    val missingSecrets: List<String> = emptyList(),
    val health: LocalMcpHealth? = null,
)

data class Library(
    val memory: List<AgentHome.MemoryTopic>,
    val skills: List<AgentHome.Skill>,
    val connectors: List<ConnectorEntry>,
    /** Helper (sub-agent) definitions: drafts waiting for review first, then active ones. */
    val agents: List<AgentHome.AgentDefinition> = emptyList(),
)

/** What the agent has learned and built, for the Library screen. Drafts are approved here. */
@Singleton
class LibraryRepository @Inject constructor(
    private val home: AgentHome,
    private val secrets: SecretStore,
    private val mcp: McpClients,
    private val mcpSignIn: McpSignIn,
) {
    private val version = MutableStateFlow(0)

    val library: Flow<Library> = combine(version, secrets.names, mcp.local.health) { _, names, health -> names to health }
        .map { (names, health) ->
            Library(
                memory = home.memoryTopics(),
                skills = home.skillDrafts() + home.skills(),
                connectors = home.connectorDrafts().map { entry(it, true, names, null) } +
                    home.connectors().map { entry(it, false, names, health) },
                agents = home.agentDrafts() + home.agents(),
            )
        }
        .flowOn(Dispatchers.IO)

    /** The agent writes memory and drafts from its own tools; call after a run to pick those up. */
    fun refresh() {
        version.value++
    }

    suspend fun saveMemory(topic: String, summary: String, content: String) = io { home.saveMemory(topic, summary, content) }
    suspend fun forgetMemory(topic: String) = io { home.forgetMemory(topic) }
    suspend fun approveSkill(name: String) = io { home.approveSkill(name) }
    suspend fun rejectSkill(name: String) = io { home.rejectSkill(name) }
    suspend fun removeSkill(name: String) = io { home.removeSkill(name) }
    suspend fun approveConnector(id: String) = io { home.approveConnector(id) }
    suspend fun approveAgent(name: String) = io { home.approveAgent(name) }
    suspend fun rejectAgent(name: String) = io { home.rejectAgent(name) }
    suspend fun removeAgent(name: String) = io { home.removeAgent(name) }
    suspend fun rejectConnector(id: String) = io { home.rejectConnector(id) }
    suspend fun removeConnector(id: String) {
        io { home.removeConnector(id) }
        mcp.forget(id)
    }

    /**
     * A server the user adds themselves is trusted and active right away. Without a token, Locus
     * asks the server whether it needs OAuth and, if so, starts the browser sign-in.
     */
    suspend fun addMcpServer(name: String, url: String, token: String?) {
        val id = AgentHome.slug(name).take(48)
        // A server that can't be reached or misdescribes its sign-in is reported, not saved without auth.
        if (token.isNullOrBlank() && mcpSignIn.requiresSignIn(url.trim())) {
            val connector = Connector(
                id = id,
                kind = "mcp",
                name = name.trim(),
                description = "MCP server at ${url.substringAfter("://").substringBefore('/')}",
                baseUrl = url.trim(),
                auth = Connector.Auth("oauth", secret = "$id-oauth"),
                operations = emptyList(),
                createdBy = "user",
            )
            io { home.addConnector(connector) }
            mcp.forget(id)
            signIn(id)
            return
        }
        val connector = Connector(
            id = id,
            kind = "mcp",
            name = name.trim(),
            description = "MCP server at ${url.substringAfter("://").substringBefore('/')}",
            baseUrl = url.trim(),
            auth = if (token.isNullOrBlank()) Connector.Auth() else Connector.Auth("bearer", secret = "$id-token"),
            operations = emptyList(),
            createdBy = "user",
        )
        if (!token.isNullOrBlank()) secrets.put(SecretStore.connectorSecret("$id-token"), token.trim())
        io { home.addConnector(connector) }
        mcp.forget(id)
    }

    /** Browser sign-in for an OAuth MCP connector. Throws with a readable message on failure. */
    suspend fun signIn(id: String) {
        val connector = home.connector(id) ?: home.connectors().firstOrNull { it.id == id } ?: return
        mcpSignIn.signIn(connector.baseUrl, connector.auth.secret)
        mcp.forget(id)
        refresh()
    }

    suspend fun setConnectorSecret(name: String, value: String) {
        secrets.put(SecretStore.connectorSecret(name), value.trim())
        // A running local server keeps the old value in its environment: restart it on next use.
        home.connectors().filter { it.auth.secret == name || name in it.secretNames }.forEach { mcp.forget(it.id) }
        refresh()
    }

    /**
     * A local MCP server (stdio) run in Linux. Adding it here is the user's approval, as with a
     * remote server. Each env var's value goes to the vault as `<id>-<name>`; the connector file
     * keeps only that name. Nothing starts until a chat uses it.
     */
    suspend fun addLocalMcpServer(name: String, command: String, args: List<String>, env: List<Pair<String, String>>) {
        val id = AgentHome.slug(name).take(48)
        val vars = env.filter { it.first.isNotBlank() }.map { (key, _) -> Connector.EnvVar(key.trim(), "$id-${AgentHome.slug(key).ifBlank { "env" }}") }
        val connector = Connector(
            id = id,
            kind = "mcp",
            name = name.trim(),
            description = "Local MCP server: ${command.trim().take(120)}",
            local = Connector.Local(command.trim(), args.map { it.trim() }.filter { it.isNotEmpty() }, vars),
            createdBy = "user",
        )
        connector.validate()
        env.filter { it.first.isNotBlank() && it.second.isNotBlank() }.forEach { (key, value) ->
            secrets.put(SecretStore.connectorSecret(vars.first { it.name == key.trim() }.secret), value.trim())
        }
        io { home.addConnector(connector) }
        mcp.forget(id)
    }

    /** Starts a local server now, to check it works; failures show in its health. */
    suspend fun startLocalMcp(id: String) {
        val connector = home.connector(id)?.takeIf { it.isLocalMcp } ?: return
        runCatching { mcp.local.start(connector) }.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    suspend fun stopLocalMcp(id: String) = mcp.local.stop(id, "Stopped by you")

    private fun entry(c: Connector, draft: Boolean, names: Set<String>, health: Map<String, LocalMcpHealth>?): ConnectorEntry {
        if (c.local == null) return ConnectorEntry(c, draft, hasSecret(c, names))
        val missing = c.secretNames.filter { SecretStore.connectorSecret(it) !in names }
        return ConnectorEntry(c, draft, missing.isEmpty(), missing, health?.let { it[c.id] ?: LocalMcpHealth.Stopped() })
    }

    private fun hasSecret(c: Connector, names: Set<String>) = when (c.auth.type) {
        "none" -> true
        "oauth" -> SecretStore.mcpOAuthSession(c.auth.secret) in names
        else -> SecretStore.connectorSecret(c.auth.secret) in names
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }.also { refresh() }
}
