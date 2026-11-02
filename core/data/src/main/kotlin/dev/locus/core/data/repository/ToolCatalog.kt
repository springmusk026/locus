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
    browser: AgentBrowser,
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
) {
    val registry = ToolRegistry(
        allTools(
            workspace, http, browser, linux, home, secrets, mcp, ssh, settings, context, scripts, conversations, questions.desk, reads, changes,
            knownSecrets = { vault.valuesForScrubbing() },
            remote = remote,
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
        forChoices(registry, conversation?.mcpFirst.orEmpty(), conversation?.toolDiscovery, mcpDiscovery, perChat)

    /** Hosts the user set up in Locus: model providers, MCP servers and connectors (fetch_url's local allow-list, taint's trusted hosts). */
    suspend fun userHosts(settings: AppSettings): Set<String> = configuredHosts(settings, home)

    /**
     * The policy for [tool]: the user's global setting (or the tool's default), then the overlay of
     * the chat's project ([projectId]), which only narrows unless the user widened it there.
     */
    fun policy(tool: Tool, settings: AppSettings, projectId: String? = null): ToolPolicy =
        ProjectRepository.policyFor(tool.spec.name, tool.defaultPolicy, settings, projectId)

    companion object {
        /** [base] as a chat with these creation-time choices declares it; cached in [cache] per choice. */
        internal fun forChoices(
            base: ToolRegistry,
            mcpFirst: Set<String>,
            toolDiscovery: String?,
            extras: dev.locus.core.agent.DiscoverableTools?,
            cache: MutableMap<Pair<Set<String>, String?>, ToolRegistry>,
        ): ToolRegistry {
            val core = dev.locus.core.agent.ToolDiscovery.core(toolDiscovery)
            if (mcpFirst.isEmpty() && core == null) return base
            return cache.getOrPut(mcpFirst to toolDiscovery) {
                val kept = base.without(dev.locus.core.agent.integrations.McpFirst.leftOut(mcpFirst))
                if (core == null) kept else kept.deferring(core, extras)
            }
        }

        /**
         * fetch_url's reach: local addresses only for hosts the user set up themselves (model providers,
         * MCP servers and connectors), read fresh for every call.
         */
        internal fun fetchNetwork(settings: SettingsRepository, home: AgentHome) = dev.locus.core.agent.tools.FetchNetwork(
            allowedHosts = { configuredHosts(settings.current(), home) },
        )

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
            subagents: () -> dev.locus.core.agent.tools.SubagentEngine,
        ): List<Tool> =
            workspaceTools(workspace, dev.locus.core.linux.LinuxSyntaxCheck(linux, workspace), reads, changes) + webTools(http, fetchNetwork(settings, home)) + browserTools(browser, workspace) + linuxTools(linux, workspace, remote) + dev.locus.core.linux.codingLinuxTools(linux, workspace, remote) + homeTools(home, http, secrets, scripts) + integrationTools(http, secrets, workspace) + emailTools(secrets) + openDataTools(http) + mcpTools(mcp) + sshTools(ssh, settings, workspace) + deviceTools(context) +
                chatHistoryTools(conversations) { dev.locus.core.agent.SecretScrubber(knownSecrets()) } + dev.locus.core.agent.tools.agentControlTools() + dev.locus.core.agent.tools.AskUser(asker) +
                dev.locus.core.agent.tools.deliveryTools(workspace) +
                dev.locus.core.agent.tools.subagentTools(home, subagents)
    }
}
