package dev.locus.core.data.agent

import dev.locus.core.agent.AgentConfig
import dev.locus.core.agent.AgentLoop
import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.agent.ApprovalGate
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ApprovalScopes
import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolRegistry
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.TranscriptStore
import dev.locus.core.agent.UntrustedContent
import dev.locus.core.agent.str
import dev.locus.core.agent.tools.FetchNetwork
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.webTools
import dev.locus.core.agent.tools.workspaceTools
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.Conversation
import dev.locus.core.model.MemoryScope
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.io.File
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remembered approvals as a daily chat meets them (docs/design/remembered-approvals.md): the real agent
 * loop, the real file and fetch tools, the app's [ChatApprovalMemory] over the real settings store, and
 * the calls a model typically makes, in a chat outside projects and in a project chat, at the start of a
 * chat, after a command has run in it, and after a web read. For each call: whether the card offers
 * "Don't ask again" (or says why not), whether answering "Always" stores a rule the Settings list shows,
 * and whether the same call in the next turn then runs without a card.
 */
class RememberedApprovalsInChatsTest {
    /** A run_command that answers as the real one does: status outside, output marked as outside text; its folder checked like the real one's. */
    private class Command(private val ws: Workspace) : Tool {
        override val risk = ToolRisk.EXECUTE
        override val spec = ToolSpec("run_command", "run", ObjectSchema().string("command", "c").string("cwd", "d", required = false).build())
        override fun describe(args: JsonObject) = "Run: ${args.str("command")}"
        override suspend fun indirectTarget(args: JsonObject) = ApprovalScopes.commandCall(args)?.dir?.takeIf { it.isNotEmpty() }?.let { ws.indirection(it) }
        override suspend fun execute(args: JsonObject) = ToolResult("Exit code 0 (1.2 s).\n" + UntrustedContent.wrap("All tests passed.", UntrustedContent.note("command output")))
    }

    private class Scripted(turns: List<AssistantTurn>) : ChatModel {
        private val queue = ArrayDeque(turns)
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(queue.removeFirst())) }
    }

    private class Store : TranscriptStore {
        val messages = mutableListOf<Message>()
        override suspend fun append(message: Message) { messages += message }
        override suspend fun record(entry: AuditEntry) = Unit
    }

    /** Answers [target] with [answer] and every other card with a plain Allow, recording the cards. */
    private class Gate(private val target: String, private val answer: ApprovalAnswer) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest) = answer(request).allowed
        override suspend fun answer(request: ApprovalRequest): ApprovalAnswer {
            asked += request
            return if (request.callId.startsWith(target)) answer else ApprovalAnswer.ALLOW
        }
    }

    data class Call(val name: String, val args: String)

    data class Row(
        val call: String,
        val where: String,
        val before: String,
        val offered: String?,
        val note: String?,
        val stored: Boolean,
        val listed: Boolean,
        val nextAsks: Boolean,
    )

    private val done = AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)
    private fun turn(id: String, c: Call) = AssistantTurn("", listOf(Part.ToolCall(id, c.name, c.args)), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null)

    /** One chat: [before] calls run (answered Allow), then [target] is answered "Always", then repeated in a new turn. */
    private suspend fun TestScope.scenario(
        target: Call,
        project: Boolean,
        before: List<Call>,
        beforeLabel: String,
        typed: String = "please fix the failing test",
        setUp: (File) -> Unit = {},
    ): Row {
        val g = TestGraph(backgroundScope)
        g.http = { respond("""{"ok":true}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val projectId = if (project) "p1" else null
        val scope = if (project) ChatScopeSpec("p1", "projects/garden", MemoryScope.PROJECT_AND_GLOBAL) else null
        val folder = File(g.workspace.base, scope?.folder ?: "").apply { mkdirs() }
        File(folder, "src").mkdirs()
        File(folder, "src/x.kt").writeText("fun main() {}\n")
        File(folder, "app").mkdirs()
        setUp(folder)
        val publicAddress = FetchNetwork(resolve = { listOf(InetAddress.getByAddress(it, byteArrayOf(140.toByte(), 82, 112, 6))) })
        val tools = workspaceTools(g.workspace, reads = g.fileReads) + webTools(g.httpClient, publicAddress) + Command(g.workspace)
        val conversation = Conversation("c1", "t", "p", "system", 0, 0, projectId = projectId)
        val memory = ChatApprovalMemory(g.settingsRepository, "c1", projectId)
        val history = mutableListOf<Message>()
        var n = 0

        suspend fun run(calls: List<Pair<String, Call>>, gate: Gate, text: String) {
            history += Message("u${n++}", "c1", Role.USER, listOf(Part.Text(text)), 0)
            val store = Store()
            AgentLoop(
                Scripted(calls.map { (id, c) -> turn(id, c) } + done), ToolRegistry(tools), { it.defaultPolicy }, gate, store,
                AgentConfig(approvals = memory, scope = scope), ids = { "id${n++}" },
            ).run(conversation, history.toList()).toList()
            history += store.messages
        }

        // edit_file needs a read first, as in the app.
        val reads = if (target.name == "edit_file") listOf(Call("read_file", """{"path":"src/x.kt"}""")) else emptyList()
        val gate = Gate("target", ApprovalAnswer.ALLOW_ALWAYS)
        run((before + reads).mapIndexed { i, c -> "pre$i" to c } + ("target1" to target), gate, typed)
        val card = gate.asked.lastOrNull { it.callId == "target1" }
        val rules = g.settingsRepository.current().rememberedApprovals
        // What Settings → Permissions → Remembered lists (SettingsViewModel): every live rule.
        val listed = rules.any { !it.expired(System.currentTimeMillis()) }
        val again = Gate("target", ApprovalAnswer.ALLOW)
        val repeat = if (target.name == "edit_file") Call("edit_file", """{"path":"src/x.kt","old_text":"fun main() { run() }","new_text":"fun main() { go() }"}""") else target
        run(listOf("target2" to repeat), again, "go on")
        return Row(
            call = "${target.name} ${target.args}", where = if (project) "project" else "chat", before = beforeLabel,
            offered = card?.remember?.let { "${it.scope.label}${if (it.always) " (chat+always)" else " (chat only)"}" },
            note = card?.rememberNote,
            stored = rules.isNotEmpty(), listed = listed, nextAsks = again.asked.any { it.callId == "target2" },
        )
    }

    private val calls = listOf(
        Call("write_file", """{"path":"src/new.kt","content":"val a = 1\n"}"""),
        Call("write_file", """{"path":"NOTES.md","content":"notes\n"}"""),
        Call("edit_file", """{"path":"src/x.kt","old_text":"fun main() {}","new_text":"fun main() { run() }"}"""),
        Call("run_command", """{"command":"npm test"}"""),
        Call("run_command", """{"command":"./gradlew test"}"""),
        Call("run_command", """{"command":"cd app && npm test"}"""),
        Call("run_command", """{"command":"python3 script.py"}"""),
        Call("run_command", """{"command":"npm test","cwd":"/workspace"}"""),
        Call("run_command", """{"command":"npm test","cwd":"/workspace/app"}"""),
        Call("run_command", """{"command":"npm test 2>&1 | tail -30"}"""),
        Call("fetch_url", """{"url":"https://api.github.com/repos/a/b"}"""),
    )

    private val befores = listOf(
        "fresh chat" to emptyList(),
        "after a command" to listOf(Call("run_command", """{"command":"ls"}""")),
        "after a web read" to listOf(Call("fetch_url", """{"url":"https://docs.example.com/guide"}""")),
    )

    private fun print(rows: List<Row>) {
        println("| call | chat | before | offered | reason line | stored | in list | next call asks |")
        println("|---|---|---|---|---|---|---|---|")
        rows.forEach { r -> println("| `${r.call.take(70)}` | ${r.where} | ${r.before} | ${r.offered ?: "—"} | ${r.note ?: "—"} | ${r.stored} | ${r.listed} | ${r.nextAsks} |") }
    }

    @Test
    fun `typical calls are offered, stored, listed and then remembered, in chats and project chats`() = runTest {
        val rows = mutableListOf<Row>()
        for (project in listOf(false, true)) for ((label, before) in befores) for (c in calls) rows += scenario(c, project, before, label)
        print(rows)
        rows.forEach { r ->
            val web = r.before == "after a web read"
            // fetch_url reads outside content itself, so taint asks before a request to a host the user didn't name.
            val isFetch = r.call.startsWith("fetch_url")
            if (web || (isFetch && r.before == "after a command")) {
                assertNull(r.offered, r.toString())
                assertFalse(r.stored, r.toString())
                assertTrue(r.nextAsks, r.toString())
                if (web && !isFetch) assertNotNull(r.note, r.toString())
            } else {
                assertNotNull(r.offered, r.toString())
                assertTrue(r.stored && r.listed, r.toString())
                assertEquals(isFetch, r.nextAsks, r.toString())
            }
        }
    }

    @Test
    fun `after a web read, what the user named is still offered`() = runTest {
        val typed = "read the guide, then run npm test and put the code in src/"
        val web = befores.last().second
        val command = scenario(Call("run_command", """{"command":"npm test"}"""), project = true, before = web, beforeLabel = "after a web read, named", typed = typed)
        val write = scenario(Call("write_file", """{"path":"src/new.kt","content":"x"}"""), project = false, before = web, beforeLabel = "after a web read, named", typed = typed)
        print(listOf(command, write))
        assertNotNull(command.offered)
        assertNotNull(write.offered)
        // A command rule still asks while the chat holds outside content; a folder rule applies.
        assertTrue(command.nextAsks)
        assertFalse(write.nextAsks)
    }

    @Test
    fun `a fetching command's output counts as outside content, a local one's doesn't`() = runTest {
        val write = Call("write_file", """{"path":"src/new.kt","content":"x"}""")
        val install = scenario(write, false, listOf(Call("run_command", """{"command":"npm install left-pad"}""")), "after npm install")
        val test = scenario(write, false, listOf(Call("run_command", """{"command":"npm test"}""")), "after npm test")
        print(listOf(install, test))
        assertNull(install.offered)
        assertNotNull(install.note)
        assertNotNull(test.offered)
    }

    @Test
    fun `commands are never remembered in a linked folder, for git elsewhere, outside, or chained`() = runTest {
        val linked = scenario(Call("run_command", """{"command":"cd app && npm test"}"""), false, emptyList(), "app is a link") { folder ->
            File(folder, "app").delete()
            java.nio.file.Files.createSymbolicLink(File(folder, "app").toPath(), File(folder, "src").toPath())
        }
        val git = scenario(Call("run_command", """{"command":"cd app && git status"}"""), false, emptyList(), "fresh chat")
        val chain = scenario(Call("run_command", """{"command":"cd app && npm test && rm -rf ."}"""), false, emptyList(), "fresh chat")
        val outside = scenario(Call("run_command", """{"command":"cd /tmp && npm test"}"""), false, emptyList(), "fresh chat")
        val rows = listOf(linked, git, chain, outside)
        print(rows)
        rows.forEach { r ->
            assertNull(r.offered, r.toString())
            assertNotNull(r.note, r.toString())
            assertFalse(r.stored, r.toString())
        }
    }
}
