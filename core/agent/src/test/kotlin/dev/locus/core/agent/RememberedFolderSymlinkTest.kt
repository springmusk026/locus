package dev.locus.core.agent

import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.workspaceTools
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.RememberedApproval
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Remembered folder approvals match a path by name, while the workspace tools act on the real
 * (canonical) file. A path through a symbolic link is never covered by a folder rule, never offered as
 * one, and is checked again right before the write (docs/design/remembered-approvals.md).
 */
class RememberedFolderSymlinkTest {
    private val tmp = Files.createTempDirectory("locus-symlink").toFile()
    private val base = File(tmp, "workspace").apply { mkdirs() }
    private val outside = File(tmp, "outside").apply { mkdirs() }
    private val ws = Workspace(base, File(tmp, "trash"))

    @AfterTest
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    private val conversation = Conversation("c", "t", "p", "system", 0, 0)

    private class Scripted(turns: List<AssistantTurn>) : ChatModel {
        private val queue = ArrayDeque(turns)
        override val format = ApiFormat.OPENAI_CHAT
        override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(queue.removeFirst())) }
    }

    private class Store : TranscriptStore {
        val audit = mutableListOf<AuditEntry>()
        override suspend fun append(message: Message) = Unit
        override suspend fun record(entry: AuditEntry) { audit += entry }
    }

    private class Gate(private val answer: ApprovalAnswer = ApprovalAnswer.DENY) : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun request(request: ApprovalRequest) = answer(request).allowed
        override suspend fun answer(request: ApprovalRequest) = answer.also { asked += request }
    }

    private class Memory(var rules: List<RememberedApproval>) : ApprovalMemory {
        val remembered = mutableListOf<ApprovalScope>()
        override suspend fun match(toolName: String, args: JsonObject) =
            ApprovalScopes.applicable(rules, "c", null, now = 0).firstOrNull { ApprovalScopes.covers(it, toolName, args) }
        override suspend fun remember(scope: ApprovalScope, forChat: Boolean) { remembered += scope }
    }

    private fun srcRule(tool: String = "write_file") = RememberedApproval("s", tool, ApprovalScopeKind.FOLDER, "src")

    private fun write(path: String, content: String = "x") = AssistantTurn(
        "",
        listOf(Part.ToolCall("k", "write_file", buildJsonObject { put("path", path); put("content", content) }.toString())),
        StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null,
    )

    private val done = AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    private suspend fun run(turn: AssistantTurn, memory: Memory, gate: Gate, reviewer: ToolReviewer? = null): Store {
        val store = Store()
        var n = 0
        AgentLoop(
            Scripted(listOf(turn, done)), ToolRegistry(workspaceTools(ws)), { ToolPolicy.ASK }, gate, store,
            AgentConfig(
                approvals = memory,
                reviewer = reviewer?.let { ReviewerSetup(it, ReviewerMode.REVIEW_ALL_BUT_READS, allowOutward = true, trustedMessages = listOf("write it")) },
            ),
            ids = { "id${n++}" },
        ).run(conversation, listOf(Message("u", "c", Role.USER, listOf(Part.Text("write it")), 0))).toList()
        return store
    }

    private fun link(at: String, to: File) {
        val link = File(base, at).apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), to.toPath())
    }

    @Test
    fun `a plain path in the rule's folder is still allowed`() = runTest {
        val gate = Gate()
        val store = run(write("src/notes.txt"), Memory(listOf(srcRule())), gate)
        assertTrue(gate.asked.isEmpty())
        assertEquals(ApprovalDecision.REMEMBERED_ALLOWED, store.audit.single().decision)
        assertEquals("x", File(base, "src/notes.txt").readText())
    }

    @Test
    fun `a symlink inside the workspace doesn't carry a folder rule elsewhere`() = runTest {
        val private = File(base, "private").apply { mkdirs() }
        link("src/alias", private)
        val gate = Gate(ApprovalAnswer.DENY)
        val store = run(write("src/alias/config.txt"), Memory(listOf(srcRule())), gate)
        val asked = gate.asked.single()
        assertTrue("symbolic link" in asked.summary, asked.summary)
        // No new rule is offered for a path through a link.
        assertNull(asked.remember)
        assertEquals(ApprovalDecision.USER_DENIED, store.audit.single().decision)
        assertFalse(File(private, "config.txt").exists())
    }

    @Test
    fun `a symlink into git or Locus's folder is never covered`() = runTest {
        val hooks = File(base, ".git/hooks").apply { mkdirs() }
        File(base, ".locus").mkdirs()
        link("src/hooks", hooks)
        link("src/meta", File(base, ".locus"))
        listOf("src/hooks/pre-commit", "src/meta/rules.json").forEach { path ->
            val gate = Gate(ApprovalAnswer.DENY)
            run(write(path), Memory(listOf(srcRule())), gate)
            assertEquals(1, gate.asked.size, path)
        }
        assertFalse(File(hooks, "pre-commit").exists())
    }

    @Test
    fun `a symlink leaving the workspace is never covered`() = runTest {
        link("src/out", outside)
        val gate = Gate(ApprovalAnswer.DENY)
        run(write("src/out/x.txt"), Memory(listOf(srcRule())), gate)
        assertEquals(1, gate.asked.size)
        assertFalse(File(outside, "x.txt").exists())
    }

    @Test
    fun `no folder rule is offered for a path through a symlink`() = runTest {
        link("docs", File(base, "private").apply { mkdirs() })
        val gate = Gate(ApprovalAnswer.ALLOW_ALWAYS)
        val memory = Memory(emptyList())
        run(write("docs/a/b.txt"), memory, gate)
        assertNull(gate.asked.single().remember)
        assertTrue(memory.remembered.isEmpty())
    }

    @Test
    fun `a folder swapped for a symlink after the rule matched is not written`() = runTest {
        val private = File(base, "private").apply { mkdirs() }
        File(base, "src/sub").mkdirs()
        // The reviewer runs between the match and the write; it stands in for anything racing the write.
        val swapper = ToolReviewer {
            File(base, "src/sub").deleteRecursively()
            link("src/sub", private)
            ReviewVerdict(Verdict.ALLOW, "fine")
        }
        val gate = Gate()
        val store = run(write("src/sub/x.txt"), Memory(listOf(srcRule())), gate, reviewer = swapper)
        assertTrue(gate.asked.isEmpty())
        assertEquals(ApprovalDecision.POLICY_DENIED, store.audit.single().decision)
        assertFalse(File(private, "x.txt").exists())
    }

    @Test
    fun `read-before-write still applies under a folder rule`() = runTest {
        File(base, "src").mkdirs()
        File(base, "src/a.txt").writeText("old")
        val gate = Gate()
        val store = run(write("src/a.txt", "new"), Memory(listOf(srcRule())), gate)
        assertTrue(gate.asked.isEmpty())
        assertEquals(ApprovalDecision.INVALID_INPUT, store.audit.single().decision)
        assertEquals("old", File(base, "src/a.txt").readText())
    }

    @Test
    fun `indirection reads the path component by component`() {
        File(base, "src").mkdirs()
        assertNull(ws.indirection("src/new/deeper/file.txt"))
        assertNull(ws.indirection("./src//x.txt"))
        link("src/alias", File(base, "private").apply { mkdirs() })
        assertNotNull(ws.indirection("src/alias/x.txt"))
        assertNotNull(ws.indirection("src/alias"))
        assertNotNull(ws.indirection("src/../x.txt"))
    }
}
