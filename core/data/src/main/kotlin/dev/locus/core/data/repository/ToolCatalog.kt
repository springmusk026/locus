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
    home: AgentHome,
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
) {
    val registry = ToolRegistry(
        allTools(workspace, http, browser, linux, home, secrets, mcp, ssh, settings, context, scripts, conversations, questions.desk) { subagents.get().engine },
    )

    fun policy(tool: Tool, settings: AppSettings): ToolPolicy = settings.toolPolicies[tool.spec.name] ?: tool.defaultPolicy

    companion object {
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
            subagents: () -> dev.locus.core.agent.tools.SubagentEngine,
        ): List<Tool> =
            workspaceTools(workspace, dev.locus.core.linux.LinuxSyntaxCheck(linux, workspace)) + webTools(http) + browserTools(browser, workspace) + linuxTools(linux, workspace) + dev.locus.core.linux.codingLinuxTools(linux, workspace) + homeTools(home, http, secrets, scripts) + integrationTools(http, secrets, workspace) + emailTools(secrets) + openDataTools(http) + mcpTools(mcp) + sshTools(ssh, settings, workspace) + deviceTools(context) +
                chatHistoryTools(conversations) + dev.locus.core.agent.tools.agentControlTools() + dev.locus.core.agent.tools.AskUser(asker) +
                dev.locus.core.agent.tools.deliveryTools(workspace) +
                dev.locus.core.agent.tools.subagentTools(home, subagents)
    }
}
