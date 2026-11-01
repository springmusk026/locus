package dev.locus.core.testing

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.SecretResolver
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.agent.ReviewerModel
import dev.locus.core.data.agent.SentRequests
import dev.locus.core.data.agent.SideQuestions
import dev.locus.core.data.agent.SubagentRunner
import dev.locus.core.data.agent.UserQuestions
import dev.locus.core.data.auth.ChatGptSignIn
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.integrations.TelegramSetup
import dev.locus.core.data.mcp.LocalMcpServers
import dev.locus.core.data.mcp.McpClients
import dev.locus.core.data.mcp.McpPreference
import dev.locus.core.data.network.ProviderHttpClients
import dev.locus.core.data.mcp.StdioLauncher
import dev.locus.core.data.mcp.McpSignIn
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.ModelDirectory
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.repository.UsageLog
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 *
 * [dispatcher], when given, stands in for Dispatchers.IO and Default wherever the app injects them
 * (@IoDispatcher, @DefaultDispatcher). View-model tests pass a test dispatcher on runTest's scheduler,
 * so the library, workspace and change reads behind a screen's state run in virtual time: runCurrent()
 * finishes them, and nothing the test waits on races on a real thread. Left null, the real ones are used.
 */
class TestGraph(
    val scope: CoroutineScope,
    settings: AppSettings = AppSettings(),
    val root: File = Files.createTempDirectory("locus-test").toFile(),
    dispatcher: CoroutineDispatcher? = null,
) {
    /** What @IoDispatcher gives in the app. */
    val io: CoroutineDispatcher = dispatcher ?: Dispatchers.IO

    /** What @DefaultDispatcher gives in the app. */
    val compute: CoroutineDispatcher = dispatcher ?: Dispatchers.Default

    /** Answers every HTTP request; replace it to script a provider. Unscripted requests get a 404. */
    var http: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.NotFound) }

    /** Requests made so far, oldest first. */
    val requests = mutableListOf<HttpRequestData>()

    private val engine: () -> MockEngine = { MockEngine { request -> requests += request; http(request) } }

    val httpClient = HttpClient(engine()) { expectSuccess = false }

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
    /** Providers with network settings get their own client over the same scripted [http]. */
    val providerClients = ProviderHttpClients.withEngine(httpClient, secrets, settingsStore, engine)
    val access = ProviderAccess(secrets, chatGpt, providerClients)
    val settingsRepository = SettingsRepository(settingsStore, secrets, access, chatGpt)
    val mcpSignIn = McpSignIn(context, secrets, httpClient)
    val linux = LinuxEnvironment(context)

    /** Starts local MCP servers; Linux isn't installed in tests, so replace it to run a fake one. */
    var stdioLauncher: StdioLauncher = StdioLauncher { linux.startPiped(it.command, it.env); error("unreachable") }
    val localMcp = LocalMcpServers({ stdioLauncher.launch(it) }, secretResolver, scope, linux.redactor)
    val mcp = McpClients(home, secretResolver, mcpSignIn, localMcp)
    val mcpPreference = McpPreference(home, secrets, settingsStore, mcp)
    val conversations = ConversationRepository(database.conversationDao, database.messageDao, database.auditDao, settingsRepository, home, mcpPreference)
    val library = LibraryRepository(home, secrets, mcp, mcpSignIn, linux, httpClient, secretResolver, context, io)
    val ssh = SshService(settingsRepository)
    val browser = AgentBrowser(context, workspace)
    val onDeviceModels = OnDeviceModels(context)
    val onDevice = OnDeviceEngines(context, onDeviceModels)
    val usage = UsageLog(database.usageEventDao)
    val helper = HelperModel(access, settingsRepository, usage)
    val reviewer = ReviewerModel(access, helper, usage)
    val tasks = TaskRepository(context, database.scheduledTaskDao)
    val workspaceRepository = WorkspaceRepository(context, workspace, io)
    val fileReads = dev.locus.core.agent.tools.FileReads()
    val changes = dev.locus.core.data.repository.ChangeRepository(database.fileChangeDao, workspace, fileReads, io)
    val telegram = TelegramSetup(httpClient, secrets)
    val directory = ModelDirectory(settingsRepository, httpClient, context, scope)

    private lateinit var subagentRunner: SubagentRunner
    val questions = UserQuestions { subagentRunner }
    val tools = ToolCatalog(
        workspace, httpClient, browser, linux, home, secretResolver, mcp, ssh, settingsRepository, context,
        SandboxScriptEngine(context), conversations, { subagentRunner }, questions, fileReads, changes,
    )
    val subagents = SubagentRunner(scope, access, conversations, settingsRepository, tools, helper, home, workspace).also { subagentRunner = it }

    val sentRequests = SentRequests(secrets, conversations)

    /** Created on first use: its init starts recovery and helper tracking in [scope]. */
    val runner: AgentRunner by lazy {
        AgentRunner(context, scope, access, conversations, settingsRepository, tools, library, onDevice, helper, reviewer, subagents, sentRequests, questions, usage, directory)
    }

    /** Without its time limit: on virtual time a request in flight would hit it at once. */
    val sideQuestions: SideQuestions by lazy { SideQuestions(scope, access, conversations, settingsRepository, tools, helper, usage, runner).also { it.timeoutMs = null } }
}
