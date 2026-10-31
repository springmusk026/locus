package dev.locus.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.GitHubSkillFetcher
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.SkillBundle
import dev.locus.core.agent.home.SkillImport
import dev.locus.core.agent.home.SkillImportException
import io.ktor.client.HttpClient
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

/** What an imported skill's name clashes with, if anything. */
enum class SkillClash { None, Draft, Active }

data class ConnectorEntry(val connector: Connector, val draft: Boolean, val hasSecret: Boolean)

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
    private val http: HttpClient,
    private val secretResolver: SecretResolver,
    @param:ApplicationContext private val context: Context,
) {
    private val version = MutableStateFlow(0)

    val library: Flow<Library> = combine(version, secrets.names) { _, names -> names }
        .map { names ->
            Library(
                memory = home.memoryTopics(),
                skills = home.skillDrafts() + home.skills(),
                connectors = home.connectorDrafts().map { ConnectorEntry(it, true, hasSecret(it, names)) } +
                    home.connectors().map { ConnectorEntry(it, false, hasSecret(it, names)) },
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

    // ---- Skill import: fetch and check first, then save as a draft once any name clash is settled ----

    /** Downloads and checks a skill from a GitHub link. Nothing is saved. Throws [SkillImportException]. */
    suspend fun fetchSkill(url: String): SkillBundle = withContext(Dispatchers.IO) { GitHubSkillFetcher(http, secretResolver).fetch(url) }

    /** Reads and checks a skill zip the user picked. Nothing is saved. Throws [SkillImportException]. */
    suspend fun readSkillZip(uri: Uri): SkillBundle = withContext(Dispatchers.IO) {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "skill.zip"
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw SkillImportException("Couldn't open that file.")
        input.use { SkillImport.readZip(it, name.take(200)) }
    }

    fun skillClash(name: String): SkillClash = when {
        home.hasSkill(name) -> SkillClash.Active
        home.hasSkillDraft(name) -> SkillClash.Draft
        else -> SkillClash.None
    }

    /** Saves a checked import as a draft named [name]. It stays inactive until the user approves it. */
    suspend fun saveImportedSkill(bundle: SkillBundle, name: String) = io { home.draftImportedSkill(bundle, name) }

    suspend fun skillFile(name: String, path: String, draft: Boolean): ByteArray? = withContext(Dispatchers.IO) { home.skillFileBytes(name, path, draft) }

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
        home.connectors().filter { it.auth.secret == name }.forEach { mcp.forget(it.id) }
        refresh()
    }

    private fun hasSecret(c: Connector, names: Set<String>) = when (c.auth.type) {
        "none" -> true
        "oauth" -> SecretStore.mcpOAuthSession(c.auth.secret) in names
        else -> SecretStore.connectorSecret(c.auth.secret) in names
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }.also { refresh() }
}
