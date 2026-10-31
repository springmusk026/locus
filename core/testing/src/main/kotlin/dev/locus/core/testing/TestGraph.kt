package dev.locus.core.testing

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.agent.ReviewerModel
import dev.locus.core.data.agent.SubagentRunner
import dev.locus.core.data.auth.ChatGptSignIn
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.mcp.McpClients
import dev.locus.core.data.mcp.McpSignIn
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.ModelDirectory
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.repository.WorkspaceRepository
import dev.locus.core.data.script.SandboxScriptEngine
import dev.locus.core.data.ssh.SshService
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.datastore.SecretStore
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.model.AppSettings
import dev.locus.core.ondevice.OnDeviceEngines
import dev.locus.core.ondevice.OnDeviceModels
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Files

/**
 * The app's data layer wired as Hilt wires it (see DataModule and the @Inject constructors), with the
 * platform swapped out: an in-memory database and DataStores, a [TestContext] under a temp directory,
 * and an HTTP client whose answers come from [http]. Nothing reaches the network or the device.
 *
 * [scope] stands in for the application scope: pass runTest's backgroundScope so the runner's
 * long-lived collectors end with the test. Then drive it with runCurrent() (or advanceTimeBy):
 * advanceUntilIdle() doesn't wait for work in backgroundScope.
 */
class TestGraph(
    val scope: CoroutineScope,
    settings: AppSettings = AppSettings(),
    val root: File = Files.createTempDirectory("locus-test").toFile(),
) {
    /** Answers every HTTP request; replace it to script a provider. Unscripted requests get a 404. */
    var http: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NotFound) }

    /** Requests made so far, oldest first. */
    val requests = mutableListOf<HttpRequestData>()

    val httpClient = HttpClient(MockEngine { request -> requests += request; http(request) }) { expectSuccess = false }

    val context = TestContext(root)
    val database = FakeDatabase()
    val settingsStore = InMemoryDataStore(settings)
    val secretStore = InMemoryDataStore(emptyMap<String, String>())
    val secrets = SecretStore(secretStore)

    val home = AgentHome(File(context.filesDir, "agent-home"))
    val workspace = Workspace(File(context.filesDir, "workspace"), File(context.filesDir, "workspace-trash"))

    /** As DataModule provides it: connectors only ever see the `connector:` namespace. */
    val secretResolver = SecretResolver { name -> secrets.get(SecretStore.connectorSecret(name)) }

    val chatGpt = ChatGptSignIn(context, secrets, httpClient)
    val access = ProviderAccess(httpClient, secrets, chatGpt)
    val settingsRepository = SettingsRepository(settingsStore, secrets, access, chatGpt)
    val conversations = ConversationRepository(database.conversationDao, database.messageDao, database.auditDao, settingsRepository, home)
    val mcpSignIn = McpSignIn(context, secrets, httpClient)
    val mcp = McpClients(home, secretResolver, mcpSignIn)
    val library = LibraryRepository(home, secrets, mcp, mcpSignIn)
    val ssh = SshService(settingsRepository)
    val linux = LinuxEnvironment(context)
    val browser = AgentBrowser(context, workspace)
    val onDeviceModels = OnDeviceModels(context)
    val onDevice = OnDeviceEngines(context, onDeviceModels)
    val helper = HelperModel(access, settingsRepository)
    val reviewer = ReviewerModel(access, helper)
    val tasks = TaskRepository(context, database.scheduledTaskDao)
    val workspaceRepository = WorkspaceRepository(context, workspace)
    val directory = ModelDirectory(settingsRepository, httpClient, context, scope)

    private lateinit var subagentRunner: SubagentRunner
    val tools = ToolCatalog(
        workspace, httpClient, browser, linux, home, secretResolver, mcp, ssh, settingsRepository, context,
        SandboxScriptEngine(context), conversations,
    ) { subagentRunner }
    val subagents = SubagentRunner(scope, access, conversations, settingsRepository, tools, helper, home, workspace).also { subagentRunner = it }

    /** Created on first use: its init starts recovery and helper tracking in [scope]. */
    val runner: AgentRunner by lazy {
        AgentRunner(context, scope, access, conversations, settingsRepository, tools, library, onDevice, helper, reviewer, subagents)
    }
}
