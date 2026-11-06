package dev.locus.core.data.repository

import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolRegistry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.home.homeTools
import dev.locus.core.agent.integrations.emailTools
import dev.locus.core.agent.integrations.integrationTools
import dev.locus.core.agent.integrations.openDataTools
import dev.locus.core.agent.tools.webTools
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.browser.browserTools
import dev.locus.core.data.mcp.McpClients
import dev.locus.core.data.mcp.mcpTools
import dev.locus.core.data.ssh.SshService
import dev.locus.core.data.ssh.sshTools
import dev.locus.core.data.device.deviceTools
import dev.locus.core.data.history.chatHistoryTools
import dev.locus.core.data.script.SandboxScriptEngine
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.linuxTools
import dev.locus.core.agent.tools.workspaceTools
import io.ktor.client.HttpClient
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ToolPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** The tools every conversation gets. New tool groups (SSH, web, MCP) register here. */
@Singleton
class ToolCatalog @Inject constructor(
    workspace: Workspace,
    http: HttpClient,
    private val browser: AgentBrowser,
    linux: LinuxEnvironment,
    private val home: AgentHome,
    secrets: SecretResolver,
    mcp: McpClients,
    ssh: SshService,
    settings: SettingsRepository,
    @ApplicationContext context: Context,
    scripts: SandboxScriptEngine,
    conversations: ConversationRepository,
    // A Provider: the helper runner needs this registry, and the helper tools need the runner.
    subagents: javax.inject.Provider<dev.locus.core.data.agent.SubagentRunner>,
    questions: dev.locus.core.data.agent.UserQuestions,
    reads: dev.locus.core.agent.tools.FileReads,
    changes: ChangeRepository,
    vault: dev.locus.core.datastore.SecretStore,
    /** Routes a project's commands to the SSH machine it's linked to (docs/design/remote-execution.md). */
    remote: dev.locus.core.data.remote.RemoteExecution? = null,
    /** The calendar, contact and photo pickers (docs/design/android-bridge.md). */
    phone: dev.locus.core.data.device.AndroidPhone? = null,
    /** The calendar provider for calendar_create / calendar_update / calendar_delete (docs/design/phone-integrations.md). */
    calendar: dev.locus.core.data.device.CalendarData? = null,
    /** remind_me's reminders. */
    reminders: dev.locus.core.data.device.Reminders? = null,
) {
    val registry = ToolRegistry(
        allTools(
            workspace, http, browser, linux, home, secrets, mcp, ssh, settings, context, scripts, conversations, questions.desk, reads, changes,
            knownSecrets = { vault.valuesForScrubbing() },
            remote = remote,
            phone = phone ?: dev.locus.core.data.device.NoPhone,
            calendar = calendar ?: dev.locus.core.data.device.NoCalendar,
            reminders = reminders,
        ) { subagents.get().engine },
    )

    /**
     * Where a run saves a long tool result in full (`.locus/out/` in the workspace), scrubbed of the
     * vault's values, so the transcript can keep just its start and end (see [dev.locus.core.agent.LongOutput]).
     */
    val outputSpill: dev.locus.core.agent.OutputSpill = dev.locus.core.agent.WorkspaceOutputSpill(workspace) {
        dev.locus.core.agent.SecretScrubber(runCatching { vault.valuesForScrubbing() }.getOrDefault(emptyList()))
    }

    private val perChat = java.util.concurrent.ConcurrentHashMap<Pair<Set<String>, String?>, ToolRegistry>()

    /** The user's MCP servers' tools, found and run through find_tools and call_tool. */
    private val mcpDiscovery = dev.locus.core.data.mcp.McpDiscovery(mcp, home)

    /**
     * The tools [conversation] declares: every tool, less the built-ins it left out for their MCP
     * servers when it was created ([dev.locus.core.model.Conversation.mcpFirst]); with tool discovery
     * ([dev.locus.core.model.Conversation.toolDiscovery]), only that version's core set plus find_tools
     * and call_tool, the rest found on demand (docs/design/tool-discovery.md). Both are fixed when the
     * chat is created, so the chat's list never changes. Every request a chat makes (turns, compaction,
     * side questions, reflection, forked helpers) uses this list, so they all share its cached prefix.
     */
    fun forChat(conversation: dev.locus.core.model.Conversation?): ToolRegistry =
        if (conversation?.chatOnly == true) NO_TOOLS
        else forChoices(registry, conversation?.mcpFirst.orEmpty(), conversation?.toolDiscovery, mcpDiscovery, perChat)

    /** Hosts the user set up in Locus: model providers, MCP servers and connectors (fetch_url's local allow-list, taint's trusted hosts). */
    suspend fun userHosts(settings: AppSettings): Set<String> = configuredHosts(settings, home)

    /**
     * Hosts the user typed into the browser's address bar while [conversationId]'s browser was on screen:
     * named by the user, like hosts typed in the chat (docs/design/taint.md, "The address bar").
     */
    fun addressBarHosts(conversationId: String): Set<String> = browser.addressBarHosts(conversationId)

    /**
     * The policy for [tool]: the user's global setting (or the tool's default), then the overlay of
     * the chat's project ([projectId]), which only narrows unless the user widened it there.
     */
    fun policy(tool: Tool, settings: AppSettings, projectId: String? = null): ToolPolicy =
        ProjectRepository.policyFor(tool.spec.name, tool.defaultPolicy, settings, projectId)

    companion object {
        /**
         * What a chat-only chat ([dev.locus.core.model.Conversation.chatOnly]) declares: nothing. Its model
         * can't use tools, so it gets none rather than a list it would call and be refused from
         * (docs/design/model-capabilities.md, "Small models and tools").
         */
        val NO_TOOLS = ToolRegistry(emptyList())

        /** [base] as a chat with these creation-time choices declares it; cached in [cache] per choice. */
        internal fun forChoices(
            base: ToolRegistry,
            mcpFirst: Set<String>,
            toolDiscovery: String?,
            extras: dev.locus.core.agent.DiscoverableTools?,
            cache: MutableMap<Pair<Set<String>, String?>, ToolRegistry>,
        ): ToolRegistry {
            val core = dev.locus.core.agent.ToolDiscovery.core(toolDiscovery)
            return cache.getOrPut(mcpFirst to toolDiscovery) {
                val kept = base.without(dev.locus.core.agent.integrations.McpFirst.leftOut(mcpFirst))
                // A chat from before tool discovery keeps the list it had: tools added since are found on demand only.
                if (core == null) kept.without(dev.locus.core.agent.ToolDiscovery.DISCOVERY_ONLY) else kept.deferring(core, extras)
            }
        }

        /**
         * fetch_url's reach: local addresses only for hosts the user set up themselves (model providers,
         * MCP servers and connectors), read fresh for every call.
         */
        internal fun fetchNetwork(settings: SettingsRepository, home: AgentHome, http: HttpClient? = null): dev.locus.core.agent.tools.FetchNetwork {
            val allowed: suspend () -> Set<String> = { configuredHosts(settings.current(), home) }
            // The app's client is OkHttp: fetch_url gets its own from it, with the DNS check pinned to the connection.
            val okHttp = (http?.engine?.config as? io.ktor.client.engine.okhttp.OkHttpConfig)?.preconfigured
            return dev.locus.core.agent.tools.FetchNetwork(
                allowedHosts = allowed,
                client = okHttp?.let { dev.locus.core.data.network.FetchDns.client(it, allowed) },
            )
        }

        private fun configuredHosts(settings: AppSettings, home: AgentHome): Set<String> =
            (settings.providers.map { it.baseUrl } + home.connectors().map { it.baseUrl })
                .mapNotNull { dev.locus.core.model.LocalNetwork.hostOf(it) }
                .toSet()

        /** Every tool, in one place so tests can check each is classified (see [dev.locus.core.model.ToolCapability]). */
        internal fun allTools(
            workspace: Workspace,
            http: HttpClient,
            browser: AgentBrowser,
            linux: LinuxEnvironment,
            home: AgentHome,
            secrets: SecretResolver,
            mcp: McpClients,
            ssh: SshService,
            settings: SettingsRepository,
            context: Context,
            scripts: SandboxScriptEngine,
            conversations: ConversationRepository,
            asker: dev.locus.core.agent.tools.UserAsker,
            reads: dev.locus.core.agent.tools.FileReads = dev.locus.core.agent.tools.FileReads(),
            changes: dev.locus.core.agent.tools.ChangeRecorder? = null,
            /** The vault's values, which chat_history_read hides from what it shows ([dev.locus.core.agent.SecretScrubber]). */
            knownSecrets: suspend () -> Collection<String> = { emptyList() },
            remote: dev.locus.core.agent.tools.RemoteBackend? = null,
            phone: dev.locus.core.data.device.PhoneIntegrations = dev.locus.core.data.device.NoPhone,
            calendar: dev.locus.core.data.device.CalendarData = dev.locus.core.data.device.NoCalendar,
            reminders: dev.locus.core.data.device.Reminders? = null,
            subagents: () -> dev.locus.core.agent.tools.SubagentEngine,
        ): List<Tool> =
            workspaceTools(workspace, dev.locus.core.linux.LinuxSyntaxCheck(linux, workspace), reads, changes) + webTools(http, fetchNetwork(settings, home, http)) + browserTools(browser, workspace) + linuxTools(linux, workspace, remote) + dev.locus.core.linux.codingLinuxTools(linux, workspace, remote) + homeTools(home, http, secrets, scripts, origins = { mcp.origins.allowed(it) }) + integrationTools(http, secrets, workspace) + emailTools(secrets) + openDataTools(http) + mcpTools(mcp) + sshTools(ssh, settings, workspace) + deviceTools(context) + dev.locus.core.data.device.deviceIntegrationTools(phone) +
                dev.locus.core.data.device.calendarWriteTools(phone, calendar, dev.locus.core.data.device.SettingsCalendarChoice(settings)) +
                dev.locus.core.data.device.reminderTools(reminders ?: dev.locus.core.data.device.Reminders(settings, dev.locus.core.data.device.NoReminderAlarms, dev.locus.core.data.device.NoReminderNotifier)) +
                dev.locus.core.data.device.phoneHandoffTools(phone, workspace) { dev.locus.core.agent.SecretScrubber(knownSecrets()) } +
                chatHistoryTools(conversations) { dev.locus.core.agent.SecretScrubber(knownSecrets()) } + dev.locus.core.agent.tools.agentControlTools() + dev.locus.core.agent.tools.AskUser(asker) +
                dev.locus.core.agent.tools.deliveryTools(workspace) +
                dev.locus.core.agent.tools.subagentTools(home, subagents) +
                dev.locus.core.agent.tools.ProviderDraftTool(dev.locus.core.data.providers.SettingsProviderDrafts(settings, conversations, knownSecrets))
    }
}
