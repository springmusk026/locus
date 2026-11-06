package dev.locus.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.ConnectorKeys
import dev.locus.core.agent.mcp.McpOAuth
import dev.locus.core.data.mcp.ConnectorKeyOrigins
import dev.locus.core.data.connectors.ConnectionCheck
import dev.locus.core.data.connectors.ConnectorTemplate
import dev.locus.core.data.connectors.ServiceConnections
import dev.locus.core.data.mcp.HomeAssistantSafety
import dev.locus.core.model.ServiceAddress
import dev.locus.core.data.mcp.LocalMcpHealth
import dev.locus.core.agent.home.GitHubSkillFetcher
import dev.locus.core.agent.home.MemoryRef
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.SkillBundle
import dev.locus.core.agent.home.SkillImport
import dev.locus.core.agent.home.SkillImportException
import dev.locus.core.agent.home.SkillManifest
import dev.locus.core.agent.home.SkillManifestDiff
import dev.locus.core.agent.home.SkillReview
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
    /** For a remote connector: whether its key or sign-in may be sent to its own address. */
    val key: KeyState = if (hasSecret) KeyState.Ready else KeyState.Missing,
    /** Other active connectors that name the same key. */
    val sharedWith: List<String> = emptyList(),
)

/**
 * A remote connector's key or sign-in, as Library describes it. A key goes only to the address it
 * was saved for, so "saved" alone doesn't mean this connector can use it
 * (docs/design/mcp-connectors.md, "Keys belong to an address").
 */
sealed interface KeyState {
    /** Nothing saved (or no sign-in yet). */
    data object Missing : KeyState

    /** Saved for, or signed in to, this connector's own address (or it needs no auth). */
    data object Ready : KeyState

    /** Saved before Locus recorded addresses, and shared or ambiguous: the user confirms where it goes. */
    data object Unconfirmed : KeyState

    /** Saved for other addresses (or a sign-in issued for another server); never sent here. */
    data class Elsewhere(val where: List<String>) : KeyState
}

/** The chat a memory note was saved in, to link to from Library. */
data class MemoryChat(val id: String, val title: String)

/** One project's own memory (`projects/<id>/memory/`), as Library → Memory groups it. */
data class ProjectMemory(val projectId: String, val projectName: String, val topics: List<AgentHome.MemoryTopic>)

data class Library(
    /** General memory: what chats outside projects (and, by default, project chats too) read. */
    val memory: List<AgentHome.MemoryTopic>,
    /** Each project's own memory, for projects that have saved any, by project name. */
    val projectMemory: List<ProjectMemory> = emptyList(),
    /** For each chat id in [memory]'s and [projectMemory]'s provenance that still exists: the chat to open (a helper's parent chat). */
    val memoryChats: Map<String, MemoryChat> = emptyMap(),
    val skills: List<AgentHome.Skill>,
    val connectors: List<ConnectorEntry>,
    /** Helper (sub-agent) definitions: drafts waiting for review first, then active ones. */
    val agents: List<AgentHome.AgentDefinition> = emptyList(),
    /** Local MCP servers need Locus's Linux system; Library says so when it's missing. */
    val linuxInstalled: Boolean = true,
    /** Built-in integrations (ids) with a key saved in Settings → Integrations; names only, never values. */
    val savedIntegrationKeys: Set<String> = emptySet(),
    /** For each draft that would replace an active skill of the same name: how its manifest changes. */
    val skillUpdates: Map<String, SkillManifestDiff> = emptyMap(),
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
    /** Titles of the chats memory notes were saved in. */
    private val chats: dev.locus.core.database.ConversationDao,
    /** Project names, for grouping project memory. */
    private val projects: dev.locus.core.database.ProjectDao,
    /** Which address each connector key was saved for. */
    private val keyOrigins: ConnectorKeyOrigins,
) {
    private val version = MutableStateFlow(0)

    private data class Inputs(
        val names: Set<String>,
        val health: Map<String, LocalMcpHealth>,
        val status: LinuxStatus,
        val projects: List<dev.locus.core.database.ProjectEntity>,
    )

    val library: Flow<Library> = combine(version, secrets.names, mcp.local.health, linux.status, projects.observeAll()) { _, names, health, status, projects ->
        Inputs(names, health, status, projects)
    }
        .map { (names, health, status, projectList) ->
            val drafts = home.skillDrafts()
            val active = home.skills()
            val memory = home.memoryTopics()
            val projectMemory = projectList
                .mapNotNull { p ->
                    home.projectMemoryTopics(p.id).takeIf { it.isNotEmpty() }?.let { ProjectMemory(p.id, p.name.ifBlank { "Untitled project" }, it) }
                }
                .sortedBy { it.projectName.lowercase() }
            Library(
                memory = memory,
                projectMemory = projectMemory,
                memoryChats = memoryChats(memory + projectMemory.flatMap { it.topics }),
                skills = drafts + active,
                skillUpdates = drafts.mapNotNull { draft ->
                    val current = active.firstOrNull { it.name == draft.name } ?: return@mapNotNull null
                    draft.name to SkillManifestDiff.between(
                        current.manifest, current.source?.contentHash ?: home.skillContentHash(current.name, draft = false), current.source,
                        draft.manifest, draft.source?.contentHash ?: home.skillContentHash(draft.name, draft = true), draft.source,
                    )
                }.toMap(),
                connectors = home.connectors().let { active ->
                    // Keys saved before addresses were recorded are tied first, so the cards say where each goes.
                    runCatching { keyOrigins.migrate() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    home.connectorDrafts().map { entry(it, true, names, null, active) } + active.map { entry(it, false, names, health, active) }
                },
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

    /** Saves a topic the user edited: in general memory, or in project [projectId]'s own memory. */
    suspend fun saveMemory(topic: String, summary: String, content: String, projectId: String? = null): Boolean = io {
        val by = dev.locus.core.agent.home.MemoryAuthor.USER
        if (projectId == null) home.saveMemory(topic, summary, content, by = by)
        else home.projectMemory(projectId).saveMemory(topic, summary, content, by = by)
    }

    /** Forgets a topic from general memory, or from project [projectId]'s memory. False when it was already gone. */
    suspend fun forgetMemory(topic: String, projectId: String? = null): Boolean = io {
        if (projectId == null) home.forgetMemory(topic) else home.projectMemory(projectId).forgetMemory(topic)
    }

    /**
     * Moves a project's topic to general memory, so every chat can use it (docs/design/projects.md,
     * "Memory"). A general topic of the same name is replaced; history keeps both. Throws when the
     * project no longer has it.
     */
    suspend fun moveMemoryToGeneral(projectId: String, topic: String) = io {
        check(home.moveProjectMemoryToGlobal(projectId, topic)) { "That note isn't in the project's memory any more." }
    }

    /** Forgets a topic a chat read (its steps card), from the memory that chat uses. False when it was already gone. */
    suspend fun forgetMemoryRead(ref: MemoryRef, scope: ChatScopeSpec?): Boolean = io { home.memory(scope).forgetRead(ref) }

    /** Which of [refs] are still saved in the memory a chat with [scope] uses. */
    suspend fun memoryStillSaved(refs: Collection<MemoryRef>, scope: ChatScopeSpec?): Set<MemoryRef> =
        withContext(ioDispatcher) { home.memory(scope).let { view -> refs.filter(view::hasRead).toSet() } }

    /** The chats memory notes were saved in, by id; a hidden helper chat links to the chat that started it. */
    private suspend fun memoryChats(memory: List<AgentHome.MemoryTopic>): Map<String, MemoryChat> =
        memory.mapNotNull { it.provenance?.chatId }.distinct().mapNotNull { id ->
            var chat = chats.get(id) ?: return@mapNotNull null
            if (chat.hidden) chat = chat.parentConversationId?.let { chats.get(it) } ?: return@mapNotNull null
            id to MemoryChat(chat.id, chat.title.ifBlank { "Untitled chat" })
        }.toMap()
    suspend fun approveSkill(name: String) = io { home.approveSkill(name) }
    suspend fun rejectSkill(name: String) = io { home.rejectSkill(name) }
    suspend fun removeSkill(name: String) = io { home.removeSkill(name) }
    suspend fun approveConnector(id: String) {
        // Keys from before addresses were recorded are tied to the connectors approved so far, never to this draft.
        keyOrigins.migrate()
        io { home.approveConnector(id) }
        mcp.forget(id)
    }

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

    /**
     * What the import sheet shows for [bundle]: its manifest in plain words and, when a skill of the
     * same name exists (the active one first, else a waiting draft), how the manifest changes.
     */
    suspend fun reviewImport(bundle: SkillBundle): SkillReview = withContext(ioDispatcher) {
        val active = home.skill(bundle.name)
        val previous = active ?: home.skillDrafts().firstOrNull { it.name == bundle.name }
        SkillReview.of(bundle, previous, previous?.let { home.skillContentHash(it.name, draft = active == null) })
    }

    /** The manifest of an active skill, which a chat that loaded it is held to (see [dev.locus.core.agent.SkillScope]). */
    suspend fun skillManifest(name: String): SkillManifest? = withContext(ioDispatcher) { home.skillManifest(name) }

    suspend fun skillFile(name: String, path: String, draft: Boolean): ByteArray? = withContext(ioDispatcher) { home.skillFileBytes(name, path, draft) }

    suspend fun approveAgent(name: String) = io { home.approveAgent(name) }
    suspend fun rejectAgent(name: String) = io { home.rejectAgent(name) }
    suspend fun removeAgent(name: String) = io { home.removeAgent(name) }
    suspend fun rejectConnector(id: String) = io { home.rejectConnector(id) }
    suspend fun removeConnector(id: String) {
        io { home.removeConnector(id) }
        mcp.forget(id)
        // Adding it again is a first connect: its tools are pinned as they are then.
        mcp.pins.forget(id)
    }

    /**
     * MCP tools waiting for the user's approval, by connector id: new tools, and tools whose description,
     * arguments or hints changed since they were approved (docs/design/mcp-connectors.md, "Tool pinning").
     */
    val pendingTools: kotlinx.coroutines.flow.StateFlow<Map<String, List<dev.locus.core.data.mcp.PendingTool>>> get() = mcp.pins.pending

    /** Lists [id]'s tools afresh, so a change made on the server shows up for review. */
    suspend fun checkTools(id: String): Boolean = mcp.check(id)

    /** Approves [id]'s waiting tools ([names], or all): they reach chats created from now on. */
    suspend fun approveTools(id: String, names: Set<String>? = null) = mcp.pins.approve(id, names)

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

    private val services by lazy { ServiceConnections(http) }

    /**
     * The value to store for [template]'s key: the pasted key, `user:password` for basic auth, or for a
     * sign-in exchange (FreshRSS) the token the server returns, so the password itself isn't kept.
     */
    private suspend fun serviceSecret(template: ConnectorTemplate, base: String, username: String, key: String): String = when {
        template.auth.login == ConnectorTemplate.Login.GOOGLE_READER -> services.googleReaderLogin(base, username, key)
        template.auth.type == "basic" -> ConnectorKeys.basicValue(username, key)
        else -> key.trim().also { require(it.isNotEmpty()) { "Paste the ${template.auth.label.lowercase()}." } }
    }

    /** Test connection for a service template: one GET of its test path with the key, at [address]. */
    suspend fun testService(template: ConnectorTemplate, address: String, username: String, key: String): ConnectionCheck {
        val checked = ServiceAddress.check(address)
        val server = checked.url ?: return ConnectionCheck.Failed(checked.error.orEmpty())
        val draft = template.connector(template.id, server, "test")
        val secret = try {
            serviceSecret(template, draft.baseUrl, username, key)
        } catch (e: IllegalArgumentException) {
            return ConnectionCheck.Failed(e.message.orEmpty())
        }
        return services.test(draft.baseUrl.trimEnd('/') + template.testPath, draft.auth, secret)
    }

    /**
     * Adds a connector from [template] for the user's server at [address], active right away: the user
     * set it up themselves. Its key goes to the vault, tied to that address only.
     */
    suspend fun addService(template: ConnectorTemplate, address: String, username: String, key: String) {
        val checked = ServiceAddress.check(address)
        val server = checked.url ?: throw IllegalArgumentException(checked.error)
        val id = home.connector(template.id)?.takeIf { it.template != template.id }?.let { "${template.id}-2" } ?: template.id
        val secretName = "$id-token"
        val connector = template.connector(id, server, secretName)
        val value = serviceSecret(template, connector.baseUrl, username, key)
        secrets.put(SecretStore.connectorSecret(secretName), value)
        keyOrigins.saved(secretName, connector.baseUrl)
        io { home.addConnector(connector) }
    }

    /** Test connection for Home Assistant: a GET of its REST API root (`/api/`) with the token. */
    suspend fun testHomeAssistant(address: String, token: String): ConnectionCheck {
        val template = McpTemplates.byId(HomeAssistantSafety.TEMPLATE_ID)?.selfHosted ?: return ConnectionCheck.Failed("Home Assistant isn't in the catalogue.")
        val checked = ServiceAddress.check(address)
        val server = checked.url ?: return ConnectionCheck.Failed(checked.error.orEmpty())
        if (token.isBlank()) return ConnectionCheck.Failed("Paste a long-lived access token.")
        return when (val result = services.test(server + template.testPath, Connector.Auth("bearer", secret = "test"), token.trim())) {
            is ConnectionCheck.Passed -> ConnectionCheck.Passed("Reached Home Assistant at ${ConnectorKeys.host(ConnectorKeys.origin(server).orEmpty())} and it accepted the token.")
            is ConnectionCheck.Failed -> result
        }
    }

    /**
     * Adds the user's Home Assistant as an MCP server: [address] plus `/api/mcp/assist` (only what they
     * exposed to Assist), or `/api/mcp` with [full] access. Its tools are listed once right away, which pins
     * them (first connect, docs/design/mcp-connectors.md "Tool pinning").
     */
    suspend fun addHomeAssistant(address: String, full: Boolean, token: String, rules: Connector.HomeAssistantRules) {
        val template = McpTemplates.byId(HomeAssistantSafety.TEMPLATE_ID) ?: return
        val hosted = template.selfHosted ?: return
        val checked = ServiceAddress.check(address)
        val server = checked.url ?: throw IllegalArgumentException(checked.error)
        require(token.isNotBlank()) { "Paste a long-lived access token." }
        rules.validate()
        val id = home.connector(template.id)?.takeIf { !HomeAssistantSafety.applies(it) }?.let { "${template.id}-2" } ?: template.id
        val url = server + (if (full) hosted.fullPath ?: hosted.path else hosted.path)
        val connector = Connector(
            id = id,
            kind = "mcp",
            name = template.name,
            description = if (full) "Your Home Assistant, with full access (every tool set you enabled)." else "Your Home Assistant: the devices you exposed to Assist.",
            baseUrl = url,
            auth = Connector.Auth("bearer", secret = "$id-token"),
            createdBy = "user",
            template = template.id,
            homeAssistant = rules,
        )
        secrets.put(SecretStore.connectorSecret(connector.auth.secret), token.trim())
        keyOrigins.saved(connector.auth.secret, url)
        io { home.addConnector(connector) }
        mcp.forget(id)
        // First connect: list (and so pin) its tools now, while the user is here. A failure shows on the row.
        runCatching { mcp.check(id) }
        refresh()
    }

    /** Changes a Home Assistant server's always-confirm rules. */
    suspend fun setHomeAssistantRules(id: String, rules: Connector.HomeAssistantRules) {
        rules.validate()
        val connector = home.connector(id)?.takeIf(HomeAssistantSafety::applies) ?: return
        io { home.addConnector(connector.copy(homeAssistant = rules)) }
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
        // The user added this key for this address (typed, or a saved key they chose to send here).
        if (auth.type in ConnectorKeys.KEY_TYPES) keyOrigins.saved(auth.secret, url)
        io { home.addConnector(connector) }
        mcp.forget(id)
        if (auth.type == "oauth") signIn(id)
    }

    /** Browser sign-in for an OAuth MCP connector. Throws with a readable message on failure. */
    suspend fun signIn(id: String) {
        var connector = home.connector(id) ?: home.connectors().firstOrNull { it.id == id } ?: return
        // A connector from before sign-ins were tied to their connector may name a session another one
        // shares: signing in again gives it its own.
        val own = Connector.oauthSecret(id)
        if (connector.auth.type == "oauth" && connector.auth.secret != own) {
            connector = connector.copy(auth = connector.auth.copy(secret = own))
            io { home.addConnector(connector) }
        }
        mcpSignIn.signIn(connector.baseUrl, connector.auth.secret)
        mcp.forget(id)
        refresh()
    }

    /**
     * Saves [value] as the vault secret [name]. With [connectorId] (a remote connector's Add key or
     * Replace key), the key is tied to that connector's address and sent nowhere else; a name other
     * connectors share is first replaced by one of this connector's own, so their keys are untouched.
     */
    suspend fun setConnectorSecret(name: String, value: String, connectorId: String? = null) {
        val draft = connectorId?.let { id -> home.connectorDrafts().firstOrNull { it.id == id } }
        val connector = draft ?: connectorId?.let(home::connector)
        if (connector != null && connector.local == null && connector.auth.type in ConnectorKeys.KEY_TYPES && connector.auth.secret == name) {
            val shared = (home.connectors() + home.connectorDrafts()).any { it.id != connector.id && name in it.secretNames }
            val key = if (shared) unshareKey(connector, draft = draft != null) else name
            secrets.put(SecretStore.connectorSecret(key), value.trim())
            keyOrigins.saved(key, connector.baseUrl)
            mcp.forget(connector.id)
            refresh()
            return
        }
        secrets.put(SecretStore.connectorSecret(name), value.trim())
        // A running local server keeps the old value in its environment: restart it on next use.
        home.connectors().filter { it.auth.secret == name || name in it.secretNames }.forEach { mcp.forget(it.id) }
        refresh()
    }

    /** Gives [connector] a key name of its own (`<id>-token`, numbered if taken) and saves it; returns the name. */
    private suspend fun unshareKey(connector: Connector, draft: Boolean): String {
        val used = (home.connectors() + home.connectorDrafts()).flatMap { it.secretNames }.toSet()
        val name = generateSequence(1) { it + 1 }.map { n -> if (n == 1) "${connector.id}-token" else "${connector.id}-token-$n" }
            .first { it !in used && secrets.get(SecretStore.connectorSecret(it)) == null }
        val renamed = connector.copy(auth = connector.auth.copy(secret = name))
        io { if (draft) home.draftConnector(renamed) else home.addConnector(renamed) }
        return name
    }

    /** The user confirmed that [id]'s saved key, from before addresses were recorded, may go to its address. */
    suspend fun confirmConnectorKey(id: String) {
        val connector = home.connector(id) ?: home.connectorDrafts().firstOrNull { it.id == id } ?: return
        if (connector.local != null || connector.auth.type !in ConnectorKeys.KEY_TYPES) return
        keyOrigins.confirm(connector.auth.secret, connector.baseUrl)
        mcp.forget(id)
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

    private suspend fun entry(c: Connector, draft: Boolean, names: Set<String>, health: Map<String, LocalMcpHealth>?, active: List<Connector>): ConnectorEntry {
        if (c.local == null) {
            val key = keyState(c, names)
            val sharedWith = if (c.auth.type in ConnectorKeys.KEY_TYPES) active.filter { it.id != c.id && c.auth.secret in it.secretNames }.map { it.name } else emptyList()
            return ConnectorEntry(c, draft, key == KeyState.Ready, key = key, sharedWith = sharedWith)
        }
        val missing = c.secretNames.filter { SecretStore.connectorSecret(it) !in names }
        return ConnectorEntry(c, draft, missing.isEmpty(), missing, health?.let { it[c.id] ?: LocalMcpHealth.Stopped() })
    }

    /** Whether [c]'s key or sign-in exists and may be sent to its own address. */
    private suspend fun keyState(c: Connector, names: Set<String>): KeyState = runCatching {
        when (c.auth.type) {
            "none" -> KeyState.Ready
            "oauth" -> {
                val resource = if (SecretStore.mcpOAuthSession(c.auth.secret) in names) mcpSignIn.sessionResource(c.auth.secret) else null
                when {
                    resource == null -> KeyState.Missing
                    McpOAuth.covers(resource, c.baseUrl) -> KeyState.Ready
                    else -> KeyState.Elsewhere(listOf(resource))
                }
            }
            else -> {
                if (SecretStore.connectorSecret(c.auth.secret) !in names) return@runCatching KeyState.Missing
                val allowed = keyOrigins.read(c.auth.secret)
                when {
                    ConnectorKeys.origin(c.baseUrl) in allowed -> KeyState.Ready
                    allowed.isEmpty() -> KeyState.Unconfirmed
                    else -> KeyState.Elsewhere(allowed.map(ConnectorKeys::host))
                }
            }
        }
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else KeyState.Missing }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }.also { refresh() }
}
