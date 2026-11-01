package dev.locus.core.data.remote

import dev.locus.core.agent.ChatPolicy
import dev.locus.core.agent.ChatScope
import dev.locus.core.agent.RunScope
import dev.locus.core.agent.Taint
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.tools.ChangeOwner
import dev.locus.core.datastore.SecretStore
import dev.locus.core.llm.LlmJson
import dev.locus.core.model.ChatMode
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.DiscoveryTools
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Part
import dev.locus.core.model.Project
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import dev.locus.core.model.Verification
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remote execution end to end (docs/design/remote-execution.md): the real data layer, the real tools
 * and SshService, against an in-process SSH and SFTP server ([TestSshServer]) whose commands run on
 * this machine. Real time, not virtual: the server and the jobs run on real threads.
 */
class RemoteExecutionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph = TestGraph(scope)
    private val dir = Files.createTempDirectory("remote-exec").toFile()
    private val sshHome = File(dir, "home/${TestSshServer.USER}").apply { mkdirs() }
    private val keyDir = File(dir, "keys").apply { mkdirs() }
    private val servers = mutableListOf<TestSshServer>()
    private lateinit var project: Project
    private lateinit var chatId: String

    private val remoteFolder get() = File(sshHome, "locus/app")

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        // Jobs left running (a service) end with the test.
        runCatching { ProcessBuilder("pkill", "-f", sshHome.path).start().waitFor() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun server(keyless: Boolean = false, publicKey: java.security.PublicKey? = null, port: Int = 0) =
        TestSshServer(sshHome, keyDir, keyless, publicKey, port).also { servers += it }

    /** A project with a few files, and the machine saved in Settings with its host key confirmed. */
    private suspend fun setUp(server: TestSshServer, auth: SshAuth = SshAuth.PASSWORD, privateKey: String? = null) {
        graph.remote.pollMs = 200
        graph.remote.reconnectMs = 500
        project = graph.projects.create("App", "projects/app")!!
        chatId = graph.conversations.create(null, project.id).id
        val local = local()
        File(local, "src").mkdirs()
        File(local, "src/main.py").writeText("print('hi')\n")
        File(local, "a.txt").writeText("first\n")
        File(local, "gone.txt").writeText("to be deleted on the machine\n")
        File(local, "gradlew").apply { writeText("#!/bin/sh\necho wrapper\n"); setExecutable(true) }
        // Never copied: secrets by name, a file holding a vault value, folders that stay.
        File(local, ".env").writeText("TOKEN=abc\n")
        File(local, "id_rsa").writeText("-----BEGIN OPENSSH PRIVATE KEY-----\n")
        graph.secrets.put(SecretStore.providerKey("anthropic"), "sk-ant-test-0123456789abcdef")
        File(local, "config.js").writeText("const key = 'sk-ant-test-0123456789abcdef'\n")
        File(local, "node_modules/x").mkdirs()
        File(local, "node_modules/x/index.js").writeText("module.exports = 1\n")
        File(local, ".git").mkdirs()
        File(local, ".git/HEAD").writeText("ref: refs/heads/main\n")
        val fingerprint = graph.ssh.probeFingerprint("127.0.0.1", server.port)
        graph.settingsRepository.saveSshHost(
            SshHost("h1", "build", "127.0.0.1", server.port, TestSshServer.USER, auth.id, fingerprint),
            password = if (auth == SshAuth.PASSWORD) TestSshServer.PASSWORD else null, privateKey = privateKey, passphrase = null,
        )
    }

    private fun local() = File(graph.workspace.base, "projects/app")

    private suspend fun link() {
        val preview = graph.remote.preview(project.folder, "h1", "~/locus/app")
        graph.remote.link(project.id, project.folder, "h1", preview.folder)
    }

    private val chatScope get() = ChatScopeSpec(project.id, project.folder, MemoryScope.PROJECT_AND_GLOBAL)

    private fun context(call: String = "call-1") = ToolCallContext(
        conversationId = chatId, callId = call, depth = 0, reviewer = null,
        changeOwner = ChangeOwner(chatId, "turn-1"), run = RunScope(project = chatScope),
    )

    private fun tool(name: String): Tool = graph.tools.registry[name]!!

    private suspend fun call(name: String, vararg args: Pair<String, Any>): ToolResult = withContext(ChatScope.element(chatScope) + context()) {
        tool(name).execute(JsonObject(args.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) }))
    }

    @Test
    fun `the preview lists what will be copied, and secrets and heavy folders never leave`() = runBlocking<Unit> {
        setUp(server())
        val preview = graph.remote.preview(project.folder, "h1", "~/locus/app")
        assertEquals(remoteFolder.path, preview.folder)
        assertFalse(preview.folderExists)
        assertEquals(setOf("a.txt", "gone.txt", "gradlew", "src/main.py"), preview.upload.toSet())
        assertTrue(listOf(".env", "id_rsa", "config.js").all { it in preview.secrets }, preview.secrets.toString())
        assertTrue("node_modules" in preview.stays && ".git" in preview.stays, preview.stays.toString())
        assertFalse(remoteFolder.exists(), "a preview copies nothing")

        graph.remote.link(project.id, project.folder, "h1", preview.folder)
        assertEquals("print('hi')\n", File(remoteFolder, "src/main.py").readText())
        assertTrue(File(remoteFolder, "gradlew").canExecute(), "the executable bit is kept")
        listOf(".env", "id_rsa", "config.js", "node_modules", ".git").forEach { assertFalse(File(remoteFolder, it).exists(), "$it must not be copied") }
        assertEquals("h1", graph.settingsRepository.current().projectRemotes[project.id]?.hostId)
    }

    @Test
    fun `run_command runs on the machine in the synced folder and brings changes back`() = runBlocking<Unit> {
        setUp(server())
        link()
        // An edit on the phone reaches the machine before the command runs.
        File(local(), "a.txt").writeText("edited on the phone\n")
        val result = call(
            "run_command",
            "command" to "pwd && cat a.txt && echo made > made.txt && echo more >> a.txt && rm gone.txt && cat .env 2>&1 || true",
        )
        assertFalse(result.isError, result.content)
        assertTrue(result.content.startsWith("Exit code 0 ("), result.content)
        assertTrue("[Ran on build in ${remoteFolder.path}" in result.content, result.content)
        assertTrue(remoteFolder.canonicalPath in result.content || remoteFolder.path in result.content)
        assertTrue("edited on the phone" in result.content)
        assertFalse("TOKEN=abc" in result.content, "the .env file is not on the machine")

        // The command's changes came back, each tracked on the turn's changes card.
        assertEquals("made\n", File(local(), "made.txt").readText())
        assertEquals("edited on the phone\nmore\n", File(local(), "a.txt").readText())
        assertFalse(File(local(), "gone.txt").exists())
        val tracked = graph.changes.observe(chatId).first().associateBy { it.path }
        assertEquals(setOf("projects/app/made.txt", "projects/app/a.txt", "projects/app/gone.txt"), tracked.keys)

        // The verification card reads the result like a local one, and says where it ran.
        val outcome = Verification.parse(Verification.Command(Verification.Kind.TEST, "make"), result.content, result.isError)
        assertEquals(true, outcome.passed)
        assertEquals("build", outcome.host)
        assertNotNull(outcome.durationMs)
    }

    @Test
    fun `a long command keeps running on the machine and the same call picks up its result`() = runBlocking<Unit> {
        setUp(server())
        link()
        val command = "sleep 3; echo built > out.bin.txt; echo finished"
        val first = call("run_command", "command" to command, "timeout_seconds" to 1)
        assertTrue(first.content.startsWith("Still running on build"), first.content)
        assertFalse(File(local(), "out.bin.txt").exists())

        // As after the phone slept: the same command attaches to the running job instead of starting it again.
        val second = call("run_command", "command" to command, "timeout_seconds" to 60)
        assertTrue(second.content.startsWith("Exit code 0"), second.content)
        assertTrue("Started by an earlier call" in second.content)
        assertTrue("finished" in second.content)
        assertEquals("built\n", File(local(), "out.bin.txt").readText())

        // Reported once: running it again starts it afresh.
        val third = call("run_command", "command" to "echo again", "timeout_seconds" to 30)
        assertFalse("Started by an earlier call" in third.content)
    }

    @Test
    fun `a dropped connection is reconnected and the job followed to its end`() = runBlocking<Unit> {
        val first = server()
        setUp(first)
        link()
        val run = scope.async { call("run_command", "command" to "sleep 3; echo survived", "timeout_seconds" to 60) }
        delay(1_000)
        // The machine's SSH server goes away mid-wait (as a network change would cut it) and comes back.
        first.close()
        delay(1_500)
        server(port = first.port)
        val result = run.await()
        assertTrue(result.content.startsWith("Exit code 0"), result.content)
        assertTrue("survived" in result.content)
    }

    @Test
    fun `services run on the machine and their logs are read there`() = runBlocking<Unit> {
        setUp(server())
        link()
        val started = call("linux_service_start", "name" to "web", "command" to "echo listening on 8000; sleep 30")
        assertFalse(started.isError, started.content)
        assertTrue("running on build" in started.content)
        assertTrue("listening on 8000" in call("linux_service_log", "name" to "web").content)
        assertTrue("web (running)" in call("linux_service_list").content)
        assertTrue("Stopped" in call("linux_service_stop", "name" to "web").content)
    }

    @Test
    fun `in a tool-discovery chat, call_tool and deferred tools still run on the machine`() = runBlocking<Unit> {
        setUp(server())
        link()
        val chat = graph.conversations.get(chatId)
        assertEquals(DiscoveryTools.CURRENT, chat?.toolDiscovery)
        val tools = graph.tools.forChat(chat)
        assertFalse("linux_service_list" in tools.specs.map { it.name }, "linux_service_list is deferred in a discovery chat")

        /** What the agent loop does first: a call_tool (or a deferred tool called by name) becomes the real call. */
        suspend fun run(name: String, args: String): ToolResult {
            val resolved = tools.resolve(Part.ToolCall("c-$name", name, args))
            assertNull(resolved.error, resolved.error)
            val tool = tools[resolved.call.name]!!
            val parsed = LlmJson.parseToJsonElement(resolved.call.argumentsJson) as JsonObject
            return withContext(ChatScope.element(chatScope) + context()) { tool.execute(parsed) }
        }

        // Through call_tool, run_command is the same remote-routed tool.
        val viaCallTool = run(DiscoveryTools.CALL_TOOL, """{"name":"run_command","arguments":{"command":"pwd"}}""")
        assertTrue("[Ran on build in ${remoteFolder.path}" in viaCallTool.content, viaCallTool.content)
        // A deferred tool through call_tool, and by its own name (provider-native search), runs there too.
        run(DiscoveryTools.CALL_TOOL, """{"name":"linux_service_start","arguments":{"name":"web","command":"echo up; sleep 30"}}""")
        assertTrue("web (running)" in run(DiscoveryTools.CALL_TOOL, """{"name":"linux_service_list","arguments":{}}""").content)
        assertTrue("Stopped" in run("linux_service_stop", """{"name":"web"}""").content)

        // Taint still sees where it runs: the resolved call asks first in a tainted run.
        val resolved = tools.resolve(Part.ToolCall("c-t", DiscoveryTools.CALL_TOOL, """{"name":"run_command","arguments":{"command":"make"}}"""))
        val reason = withContext(ChatScope.element(chatScope)) {
            Taint.reason(tools[resolved.call.name]!!, LlmJson.parseToJsonElement(resolved.call.argumentsJson) as JsonObject, setOf("web_read"), emptySet())
        }
        assertTrue(reason != null && "runs on build" in reason, reason)
    }

    @Test
    fun `in a tainted run a remote command asks first, and Workspace mode refuses it`() = runBlocking<Unit> {
        setUp(server())
        val runCommand = tool("run_command")
        val args = JsonObject(mapOf("command" to JsonPrimitive("make")))
        // Before linking it stays on the phone: run_command is a local tool, which taint doesn't hold back.
        withContext(ChatScope.element(chatScope)) { assertNull(Taint.reason(runCommand, args, setOf("web_read"), emptySet())) }
        link()
        withContext(ChatScope.element(chatScope)) {
            val reason = Taint.reason(runCommand, args, setOf("web_read"), emptySet())
            assertTrue(reason != null && "runs on build" in reason, reason)
            assertNull(Taint.reason(runCommand, args, emptySet(), emptySet()), "an untainted run goes by the usual policy")
            assertNotNull(ChatPolicy.modeDenial(ChatMode.WORKSPACE, runCommand, args))
            assertNull(ChatPolicy.modeDenial(ChatMode.FULL, runCommand, args))
        }
        // Outside the project nothing is routed.
        assertNull(Taint.reason(runCommand, args, setOf("web_read"), emptySet()))
    }

    @Test
    fun `keyless machines work`() = runBlocking<Unit> {
        setUp(server(keyless = true), auth = SshAuth.NONE)
        link()
        val result = call("run_command", "command" to "echo keyless")
        assertTrue(result.content.startsWith("Exit code 0") && "keyless" in result.content, result.content)
    }

    @Test
    fun `key logins work`() = runBlocking<Unit> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = ByteArrayOutputStream().also { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test", null, it) }.toString(Charsets.UTF_8)
        setUp(server(publicKey = pair.public), auth = SshAuth.KEY, privateKey = pem)
        link()
        val result = call("run_command", "command" to "echo with-key")
        assertTrue(result.content.startsWith("Exit code 0") && "with-key" in result.content, result.content)
    }

    @Test
    fun `a machine presenting another host key is refused`() = runBlocking<Unit> {
        setUp(server())
        link()
        val host = graph.settingsRepository.current().sshHosts.single()
        graph.settingsRepository.saveSshHost(host.copy(fingerprint = "SHA256:not-the-real-key"), null, null, null)
        val e = assertFailsWith<ToolException> { call("run_command", "command" to "echo hi") }
        assertTrue(e.message.orEmpty().startsWith("SECURITY"), e.message)
    }
}
