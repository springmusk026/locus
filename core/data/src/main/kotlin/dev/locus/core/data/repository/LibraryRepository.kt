package dev.locus.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.data.mcp.LocalMcpHealth
import dev.locus.core.agent.home.GitHubSkillFetcher
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.SkillBundle
import dev.locus.core.agent.home.SkillImport
import dev.locus.core.agent.home.SkillImportException
import io.ktor.client.HttpClient
import dev.locus.core.data.mcp.McpClients
import dev.locus.core.data.mcp.McpSignIn
import dev.locus.core.data.mcp.McpTemplate
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.data.mcp.SavedKeys
import dev.locus.core.datastore.SecretStore
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
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
    /** Local MCP servers need Locus's Linux system; Library says so when it's missing. */
    val linuxInstalled: Boolean = true,
    /** Built-in integrations (ids) with a key saved in Settings → Integrations; names only, never values. */
    val savedIntegrationKeys: Set<String> = emptySet(),
)

/** What the agent has learned and built, for the Library screen. Drafts are approved here. */
@Singleton
class LibraryRepository @Inject constructor(
    private val home: AgentHome,
    private val secrets: SecretStore,
    private val mcp: McpClients,
    private val mcpSignIn: McpSignIn,
    private val linux: LinuxEnvironment,
    private val http: HttpClient,
    private val secretResolver: SecretResolver,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val version = MutableStateFlow(0)

    val library: Flow<Library> = combine(version, secrets.names, mcp.local.health, linux.status) { _, names, health, status -> Triple(names, health, status) }
        .map { (names, health, status) ->
            Library(
                memory = home.memoryTopics(),
                skills = home.skillDrafts() + home.skills(),
                connectors = home.connectorDrafts().map { entry(it, true, names, null) } +
                    home.connectors().map { entry(it, false, names, health) },
                agents = home.agentDrafts() + home.agents(),
                linuxInstalled = status == LinuxStatus.Ready,
                savedIntegrationKeys = dev.locus.core.agent.integrations.Integrations.all
                    .filter { SecretStore.connectorSecret(it.secret) in names }.map { it.id }.toSet(),
            )
        }
        .flowOn(ioDispatcher)

    /** The agent writes memory and drafts from its own tools; call after a run to pick those up. */
    fun refresh() {
        version.value++
    }

    suspend fun saveMemory(topic: String, summary: String, content: String) =
        io { home.saveMemory(topic, summary, content, by = dev.locus.core.agent.home.MemoryAuthor.USER) }
    suspend fun forgetMemory(topic: String) = io { home.forgetMemory(topic) }
    suspend fun approveSkill(name: String) = io { home.approveSkill(name) }
    suspend fun rejectSkill(name: String) = io { home.rejectSkill(name) }
    suspend fun removeSkill(name: String) = io { home.removeSkill(name) }
    suspend fun approveConnector(id: String) = io { home.approveConnector(id) }

    // ---- Skill import: fetch and check first, then save as a draft once any name clash is settled ----

    /** Downloads and checks a skill from a GitHub link. Nothing is saved. Throws [SkillImportException]. */
    suspend fun fetchSkill(url: String): SkillBundle = withContext(ioDispatcher) { GitHubSkillFetcher(http, secretResolver).fetch(url) }

    /** Reads and checks a skill zip the user picked. Nothing is saved. Throws [SkillImportException]. */
    suspend fun readSkillZip(uri: Uri): SkillBundle = withContext(ioDispatcher) {
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

    suspend fun skillFile(name: String, path: String, draft: Boolean): ByteArray? = withContext(ioDispatcher) { home.skillFileBytes(name, path, draft) }

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
        val description = "MCP server at ${url.substringAfter("://").substringBefore('/')}"
        // A server that can't be reached or misdescribes its sign-in is reported, not saved without auth.
        if (token.isNullOrBlank() && mcpSignIn.requiresSignIn(url.trim())) {
            saveRemoteMcp(id, name, description, url, Connector.Auth("oauth", secret = "$id-oauth"), null)
            return
        }
        val auth = if (token.isNullOrBlank()) Connector.Auth() else Connector.Auth("bearer", secret = "$id-token")
        saveRemoteMcp(id, name, description, url, auth, token)
    }

    /**
     * Adds a server from the template catalogue. A pasted [key] is sent the way the template says
     * (bearer, a named header or a URL parameter) and goes to the vault; without one, a server that
     * supports sign-in starts the browser sign-in, and one whose key is optional is added without.
     * [url] is the template's, possibly with options the user added. [env] is for a local server:
     * variable name and value.
     *
     * [useSavedKey] (the user tapped "Use the key saved for …" and saw where it goes) copies the
     * built-in integration's key into this connector's vault entry instead of [key]. The value stays
     * in the vault: it's never returned, shown or put in the connector file. See [SavedKeys].
     */
    suspend fun addMcpTemplate(
        template: McpTemplate,
        url: String = template.url,
        key: String? = null,
        env: List<Pair<String, String>> = emptyList(),
        useSavedKey: Boolean = false,
    ) {
        template.local?.let { local ->
            addLocalMcpServer(template.name, local.command, emptyList(), env)
            return
        }
        val target = url.trim()
        require(target.startsWith("https://") && target.length > "https://".length) { "${template.name} needs an https:// address." }
        // Adding a template again replaces it; a different connector that happens to use the id is kept.
        val id = home.connector(template.id)?.takeIf { McpTemplates.official(it)?.id != template.id }?.let { "${template.id}-mcp" } ?: template.id
        if (useSavedKey) {
            val source = SavedKeys.source(template) ?: throw IllegalArgumentException("${template.name} can't use a saved key.")
            require(SavedKeys.sameHost(template, target)) { "The saved ${source.name} key only goes to ${template.host}. Keep that address, or paste a key instead." }
            val auth = template.key!!.auth("$id-token")
            SavedKeys.copy(secrets, source, auth.secret)
            saveRemoteMcp(id, template.name, template.description, target, auth, token = null)
            return
        }
        val pasted = key?.trim()?.takeIf { it.isNotEmpty() }
        when {
            pasted != null && template.key != null -> saveRemoteMcp(id, template.name, template.description, target, template.key.auth("$id-token"), pasted)
            template.signIn -> saveRemoteMcp(id, template.name, template.description, target, Connector.Auth("oauth", secret = "$id-oauth"), null)
            template.key == null || template.key.optional -> saveRemoteMcp(id, template.name, template.description, target, Connector.Auth(), null)
            else -> throw IllegalArgumentException("${template.name} needs a ${template.key.label.lowercase()}.")
        }
    }

    /** Saves a remote MCP connector the user added (trusted, active), its [token] to the vault, and signs in for OAuth. */
    private suspend fun saveRemoteMcp(id: String, name: String, description: String, url: String, auth: Connector.Auth, token: String?) {
        val connector = Connector(
            id = id,
            kind = "mcp",
            name = name.trim(),
            description = description,
            baseUrl = url.trim(),
            auth = auth,
            operations = emptyList(),
            createdBy = "user",
        )
        if (!token.isNullOrBlank() && auth.type != "none" && auth.type != "oauth") secrets.put(SecretStore.connectorSecret(auth.secret), token.trim())
        io { home.addConnector(connector) }
        mcp.forget(id)
        if (auth.type == "oauth") signIn(id)
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

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }.also { refresh() }
}
