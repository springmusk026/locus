package dev.locus.core.agent

import dev.locus.core.agent.tools.ConnectedFolderSource
import dev.locus.core.agent.tools.DocEntry
import dev.locus.core.agent.tools.DocTree
import dev.locus.core.agent.tools.DocTreeException
import dev.locus.core.agent.tools.DocTreeException.Problem
import dev.locus.core.agent.tools.FileReads
import dev.locus.core.agent.tools.TrashEntry
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
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.ConnectedFolder
import dev.locus.core.model.ConnectedFolders
import dev.locus.core.model.Conversation
import dev.locus.core.model.MemoryScope
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A connected folder's file layer and the file tools on it (docs/design/connected-folders.md), over a plain directory. */
class ConnectedFoldersTest {
    private val tmp: File = Files.createTempDirectory("locus-connected").toFile()
    private val vaultDir = File(tmp, "phone/Documents/Vault").apply { mkdirs() }
    private val secret = File(tmp, "phone/secret.txt").apply { writeText("private") }

    /** A tree over a directory, with switches for the states a provider can be in. */
    private inner class FakeTree(private val root: File) : DocTree {
        var granted = true
        var writable = true

        private fun at(path: List<String>): File {
            if (!granted) throw DocTreeException(Problem.PERMISSION_LOST, "revoked")
            if (!root.isDirectory) throw DocTreeException(Problem.GONE, "gone")
            val f = path.fold(root) { d, p -> File(d, p) }
            if (!f.exists()) throw DocTreeException(Problem.NOT_FOUND, "missing")
            return f
        }

        private fun mutable() {
            if (!writable) throw DocTreeException(Problem.UNSUPPORTED, "read-only provider")
        }

        private fun entry(f: File) = DocEntry(f.name, f.isDirectory, if (f.isFile) f.length() else 0, f.lastModified(), writable, writable, writable, writable)
        override fun root() = entry(at(emptyList()))
        override fun list(path: List<String>) = at(path).listFiles().orEmpty().map(::entry)
        override fun open(path: List<String>): InputStream = at(path).inputStream()
        override fun write(path: List<String>, bytes: ByteArray) { mutable(); at(path).writeBytes(bytes) }
        override fun createFile(parent: List<String>, name: String): String { mutable(); File(at(parent), name).createNewFile(); return name }
        override fun createDirectory(parent: List<String>, name: String): String { mutable(); File(at(parent), name).mkdir(); return name }
        override fun delete(path: List<String>) { mutable(); at(path).deleteRecursively() }
        override fun rename(path: List<String>, newName: String): String { mutable(); val f = at(path); f.renameTo(File(f.parentFile, newName)); return newName }
    }

    private val tree = FakeTree(vaultDir)
    private var folders = listOf(ConnectedFolder("v1", "vault", "content://tree/vault", projectIds = listOf("p1")))
    private val source = object : ConnectedFolderSource {
        override fun folders() = this@ConnectedFoldersTest.folders
        override fun tree(folder: ConnectedFolder) = tree
    }
    private var now = 1_000_000L
    private val ws = Workspace(File(tmp, "workspace"), File(tmp, "trash"), clock = { now }, connectedFolders = source)
    private val reads = FileReads()
    private val tools = ToolRegistry(workspaceTools(ws, reads = reads))

    @AfterTest
    fun cleanUp() {
        tmp.deleteRecursively()
    }

    private suspend fun run(name: String, vararg args: Pair<String, Any>): ToolResult {
        val json = JsonObject(args.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        return try {
            withContext(ToolCallContext("chat", "call", 0, null)) { tools[name]!!.execute(json) }
        } catch (e: ToolException) {
            ToolResult.error(e.message.orEmpty())
        }
    }

    private fun vault(path: String) = File(vaultDir, path)

    // ---- Paths

    @Test
    fun `paths map under folders and never climb out`() = runTest {
        vault("daily/2026-11-06.md").apply { parentFile.mkdirs(); writeText("# Today\n") }
        assertEquals("1\t# Today\n", run("read_file", "path" to "folders/vault/daily/2026-11-06.md").content)
        assertEquals("1\t# Today\n", run("read_file", "path" to "/folders/vault/./daily//2026-11-06.md").content)
        val up = run("read_file", "path" to "folders/vault/../../secret.txt")
        assertTrue(up.isError && "climbs out" in up.content, up.content)
        val back = run("read_file", "path" to "folders/vault/daily\\..\\x")
        assertTrue(back.isError && "backslash" in back.content, back.content)
        // Through the workspace's own folder, `..` can't reach a connected folder either.
        val around = run("read_file", "path" to "folders/../folders/vault/daily/2026-11-06.md")
        assertTrue(around.isError && "connected folder" in around.content, around.content)
        assertEquals("private", secret.readText())
    }

    @Test
    fun `other tools are refused a connected path, and Linux is named`() {
        val e = runCatching { ws.resolve("folders/vault/notes.md") }.exceptionOrNull()
        assertTrue(e is ToolException && "Linux and run_command can't see it" in e.message.orEmpty(), e?.message)
        // A workspace folder that isn't a connected name still resolves as a workspace path.
        assertEquals(File(ws.base, "folders/other/x.md").path, ws.resolve("folders/other/x.md").path)
        assertNull(ws.indirection("folders/vault/notes.md"))
    }

    @Test
    fun `a project chat sees only the folders linked to it`() = runTest {
        vault("a.md").writeText("a")
        val linked = ChatScopeSpec("p1", "projects/one", MemoryScope.PROJECT_AND_GLOBAL)
        val other = ChatScopeSpec("p2", "projects/two", MemoryScope.PROJECT_AND_GLOBAL)
        withContext(ChatScope.element(linked)) { assertFalse(run("read_file", "path" to "folders/vault/a.md").isError) }
        withContext(ChatScope.element(other)) {
            val r = run("read_file", "path" to "folders/vault/a.md")
            assertTrue(r.isError && "isn't linked to this project" in r.content, r.content)
            assertTrue("No connected folders" in run("list_files", "path" to "folders").content)
        }
    }

    // ---- Operations

    @Test
    fun `list, find and search work in a connected folder`() = runTest {
        vault("daily/mon.md").apply { parentFile.mkdirs(); writeText("met Ana about the garden\n") }
        vault("ideas.md").writeText("garden beds\nnothing\n")
        vault(".obsidian/app.json").apply { parentFile.mkdirs(); writeText("{\"garden\":1}") }
        vault("photo.png").writeBytes(byteArrayOf(0, 1, 2, 0))
        assertTrue("folders/  (connected folders: vault" in run("list_files").content)
        assertTrue("folders/vault/  (read and write; writes ask the user)" in run("list_files", "path" to "folders").content)
        val listing = run("list_files", "path" to "folders/vault").content
        assertTrue("folders/vault/daily/" in listing && "folders/vault/ideas.md  (" in listing, listing)
        assertEquals(setOf("folders/vault/daily/mon.md", "folders/vault/ideas.md"), run("find_files", "pattern" to "*.md", "path" to "folders/vault").content.lines().toSet())
        val found = run("search_files", "pattern" to "garden", "path" to "folders/vault").content
        assertEquals("folders/vault/daily/mon.md:1: met Ana about the garden\nfolders/vault/ideas.md:1: garden beds", found)
        assertTrue("binary file" in run("read_file", "path" to "folders/vault/photo.png").content)
    }

    @Test
    fun `write, edit, mkdir, rename, copy and delete keep before-copies in the trash`() = runTest {
        assertEquals("Created folders/vault/inbox/new.md (5 bytes).", run("write_file", "path" to "folders/vault/inbox/new.md", "content" to "hello").content)
        assertEquals("hello", vault("inbox/new.md").readText())
        // Edits need a read first, as in the workspace (the write counted as one; a file another app made didn't).
        vault("inbox/theirs.md").writeText("hello")
        assertTrue("hasn't read" in run("edit_file", "path" to "folders/vault/inbox/theirs.md", "old_text" to "hello", "new_text" to "hi").content)
        run("read_file", "path" to "folders/vault/inbox/new.md")
        assertFalse(run("edit_file", "path" to "folders/vault/inbox/new.md", "old_text" to "hello", "new_text" to "hi").isError)
        assertEquals("hi", vault("inbox/new.md").readText())
        assertEquals("hello", ws.trashPayload(ws.trashEntries().single().id)!!.readText())
        assertEquals("v1", ws.trashEntries().single().folderId)

        assertFalse(run("make_directory", "path" to "folders/vault/archive/2026").isError)
        assertTrue(vault("archive/2026").isDirectory)
        assertFalse(run("move_path", "from" to "folders/vault/inbox/new.md", "to" to "folders/vault/inbox/renamed.md").isError)
        assertEquals("hi", vault("inbox/renamed.md").readText())
        assertFalse(run("copy_path", "from" to "folders/vault/inbox/renamed.md", "to" to "notes/copy.md").isError)
        assertEquals("hi", File(ws.base, "notes/copy.md").readText())
        assertFalse(run("copy_path", "from" to "notes/copy.md", "to" to "folders/vault/archive/2026/back.md").isError)
        assertEquals("hi", vault("archive/2026/back.md").readText())

        assertFalse(run("delete_path", "path" to "folders/vault/archive").isError)
        assertFalse(vault("archive").exists())
        val deleted = ws.trashEntries().first { it.reason == TrashEntry.Reason.DELETED }
        assertEquals("folders/vault/archive", deleted.originalPath)
        ws.restore(deleted.id)
        assertEquals("hi", vault("archive/2026/back.md").readText())
    }

    @Test
    fun `a write over another app's change needs a fresh read`() = runTest {
        vault("todo.md").writeText("one\n")
        run("read_file", "path" to "folders/vault/todo.md")
        // Syncthing brings in a change.
        vault("todo.md").writeText("one\ntwo from the laptop\n")
        vault("todo.md").setLastModified(System.currentTimeMillis() + 5_000)
        now += 10_000
        val r = run("write_file", "path" to "folders/vault/todo.md", "content" to "mine\n")
        assertTrue(r.isError && "changed on disk" in r.content, r.content)
        assertEquals("one\ntwo from the laptop\n", vault("todo.md").readText())
    }

    @Test
    fun `read-only folders, hidden folders and rejected names refuse writes`() = runTest {
        vault(".obsidian/app.json").apply { parentFile.mkdirs(); writeText("{}") }
        val hidden = run("write_file", "path" to "folders/vault/.obsidian/app.json", "content" to "x")
        assertTrue(hidden.isError && "hidden" in hidden.content, hidden.content)
        val name = run("write_file", "path" to "folders/vault/a:b.md", "content" to "x")
        assertTrue(name.isError && "refuses the characters" in name.content, name.content)
        folders = folders.map { it.copy(readOnly = true) }
        val ro = run("write_file", "path" to "folders/vault/new.md", "content" to "x")
        assertTrue(ro.isError && "read-only for Locus" in ro.content, ro.content)
        assertTrue(run("delete_path", "path" to "folders/vault/.obsidian").isError)
        assertTrue(vault(".obsidian/app.json").exists())
        assertFalse(vault("new.md").exists())
    }

    @Test
    fun `permission loss, a vanished folder and a read-only provider say what to do`() = runTest {
        vault("a.md").writeText("a")
        tree.writable = false
        val provider = run("write_file", "path" to "folders/vault/b.md", "content" to "b")
        assertTrue(provider.isError && "doesn't support this" in provider.content, provider.content)
        tree.writable = true
        tree.granted = false
        val lost = run("read_file", "path" to "folders/vault/a.md")
        assertTrue(lost.isError && "no longer has access" in lost.content && "reconnect" in lost.content, lost.content)
        tree.granted = true
        vaultDir.deleteRecursively()
        now += 10_000
        val gone = run("list_files", "path" to "folders/vault")
        assertTrue(gone.isError && "is gone" in gone.content, gone.content)
    }

    @Test
    fun `listings are cached briefly and dropped after Locus's own changes`() = runTest {
        run("list_files", "path" to "folders/vault")
        vault("synced.md").writeText("from another device")
        // Within the cache's few seconds a listing can lag another app's change ...
        assertFalse("synced.md" in run("list_files", "path" to "folders/vault").content)
        now += 5_000
        assertTrue("synced.md" in run("list_files", "path" to "folders/vault").content)
        // ... but never Locus's own.
        run("write_file", "path" to "folders/vault/mine.md", "content" to "x")
        assertTrue("mine.md" in run("list_files", "path" to "folders/vault").content)
    }

    // ---- Approvals

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

    private class Gate(private val answer: ApprovalAnswer) : ApprovalGate {
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

    private fun call(tool: String, vararg args: Pair<String, String>) = AssistantTurn(
        "",
        listOf(Part.ToolCall("k", tool, buildJsonObject { args.forEach { (k, v) -> put(k, v) } }.toString())),
        StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null,
    )

    private val done = AssistantTurn("done", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null)

    /** Runs one call with every tool set to [policy] (the user's choice in Settings). */
    private suspend fun agent(turn: AssistantTurn, policy: ToolPolicy, gate: Gate, memory: Memory = Memory(emptyList()), reviewer: ReviewerSetup? = null): Store {
        val store = Store()
        var n = 0
        AgentLoop(Scripted(listOf(turn, done)), tools, { policy }, gate, store, AgentConfig(approvals = memory, reviewer = reviewer), ids = { "id${n++}" })
            .run(conversation, listOf(Message("u", "c", Role.USER, listOf(Part.Text("update my vault")), 0))).toList()
        return store
    }

    @Test
    fun `reads are allowed, writes ask even when write_file is set to Allow`() = runTest {
        vault("a.md").writeText("a")
        val reading = Gate(ApprovalAnswer.DENY)
        val read = agent(call("read_file", "path" to "folders/vault/a.md"), ToolPolicy.ALLOW, reading)
        assertTrue(reading.asked.isEmpty())
        assertEquals(ApprovalDecision.AUTO_ALLOWED, read.audit.single().decision)

        val gate = Gate(ApprovalAnswer.ALLOW_ALWAYS)
        val memory = Memory(emptyList())
        val store = agent(call("write_file", "path" to "folders/vault/daily/today.md", "content" to "x"), ToolPolicy.ALLOW, gate, memory)
        val asked = gate.asked.single()
        // "Always for this folder" is offered, through the existing FOLDER scope.
        assertEquals(ApprovalScopeKind.FOLDER, asked.remember?.scope?.kind)
        assertEquals("folders/vault/daily", asked.remember?.scope?.value)
        assertEquals(ApprovalDecision.USER_APPROVED, store.audit.single().decision)
        assertEquals("folders/vault/daily", memory.remembered.single().value)

        // With the rule, the next write in that folder runs without asking.
        val again = Gate(ApprovalAnswer.DENY)
        val rule = RememberedApproval("r", "write_file", ApprovalScopeKind.FOLDER, "folders/vault/daily")
        val second = agent(call("write_file", "path" to "folders/vault/daily/today.md", "content" to "y"), ToolPolicy.ALLOW, again, Memory(listOf(rule)))
        assertTrue(again.asked.isEmpty())
        assertEquals(ApprovalDecision.REMEMBERED_ALLOWED, second.audit.single().decision)
        assertEquals("y", vault("daily/today.md").readText())
    }

    @Test
    fun `deleting from a connected folder always asks, rule or not`() = runTest {
        vault("old.md").writeText("old")
        val gate = Gate(ApprovalAnswer.DENY)
        val rule = RememberedApproval("r", "delete_path", ApprovalScopeKind.FOLDER, "folders/vault")
        val store = agent(call("delete_path", "path" to "folders/vault/old.md"), ToolPolicy.ALLOW, gate, Memory(listOf(rule)))
        val asked = gate.asked.single()
        assertTrue("always asks" in asked.summary, asked.summary)
        assertNull(asked.remember)
        assertEquals(ApprovalDecision.USER_DENIED, store.audit.single().decision)
        assertTrue(vault("old.md").exists())
        // Moving a file out of the folder deletes it there: that asks too.
        val moving = Gate(ApprovalAnswer.DENY)
        agent(call("move_path", "from" to "folders/vault/old.md", "to" to "old.md"), ToolPolicy.ALLOW, moving)
        assertTrue("always asks" in moving.asked.single().summary)
    }

    @Test
    fun `the reviewer clears connected-folder writes only with the outward opt-in, and never a delete`() = runTest {
        vault("old.md").writeText("old")
        fun reviewer(outward: Boolean) = ReviewerSetup({ ReviewVerdict(Verdict.ALLOW, "you asked for it", model = "m") }, ReviewerMode.REVIEW_ASKS, allowOutward = outward)
        val write = call("write_file", "path" to "folders/vault/new.md", "content" to "x")
        // Opt-in off: the write comes to the user, with "Always for this folder" still offered.
        val off = Gate(ApprovalAnswer.DENY)
        agent(write, ToolPolicy.ALLOW, off, reviewer = reviewer(false))
        assertEquals("folders/vault", off.asked.single().remember?.scope?.value)
        assertFalse(vault("new.md").exists())
        // Opt-in on: the reviewer may clear it.
        val on = Gate(ApprovalAnswer.DENY)
        val cleared = agent(write, ToolPolicy.ALLOW, on, reviewer = reviewer(true))
        assertTrue(on.asked.isEmpty())
        assertEquals(ApprovalDecision.REVIEWER_ALLOWED, cleared.audit.single().decision)
        // A delete there is the user's alone, opt-in or not.
        val deleting = Gate(ApprovalAnswer.DENY)
        val store = agent(call("delete_path", "path" to "folders/vault/old.md"), ToolPolicy.ALLOW, deleting, reviewer = reviewer(true))
        assertTrue(deleting.asked.single().reviewerNote!!.startsWith("Reviewer: you asked for it"))
        assertEquals(ApprovalDecision.USER_DENIED, store.audit.single().decision)
        assertTrue(vault("old.md").exists())
    }

    @Test
    fun `a read-only folder refuses a write without asking`() = runTest {
        folders = folders.map { it.copy(readOnly = true) }
        val gate = Gate(ApprovalAnswer.ALLOW)
        val store = agent(call("write_file", "path" to "folders/vault/a.md", "content" to "x"), ToolPolicy.ASK, gate)
        assertTrue(gate.asked.isEmpty())
        assertEquals(ApprovalDecision.INVALID_INPUT, store.audit.single().decision)
        assertFalse(vault("a.md").exists())
    }

    @Test
    fun `names follow the rules`() {
        assertNull(ConnectedFolders.nameProblem("vault", emptyList()))
        assertTrue(ConnectedFolders.nameProblem("Vault", listOf("vault")) != null)
        assertTrue(ConnectedFolders.nameProblem("../x", emptyList()) != null)
        assertEquals("my-vault", ConnectedFolders.suggest("My Vault", emptyList()))
        assertEquals("my-vault-2", ConnectedFolders.suggest("My Vault", listOf("my-vault")))
    }
}
