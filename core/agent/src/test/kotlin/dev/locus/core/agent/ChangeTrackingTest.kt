package dev.locus.core.agent

import dev.locus.core.agent.tools.ChangeOwner
import dev.locus.core.agent.tools.ChangeRecorder
import dev.locus.core.agent.tools.FileChange
import dev.locus.core.agent.tools.FileReads
import dev.locus.core.agent.tools.LineDiff
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.workspaceTools
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the workspace tools report for the changes card, the diff it shows, and read-before-edit after an undo. */
class ChangeTrackingTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val reads = FileReads()
    private val recorded = mutableListOf<FileChange>()
    private val tools = ToolRegistry(workspaceTools(ws, reads = reads, changes = ChangeRecorder { recorded += it }))
    private val owner = ChangeOwner("chat", "turn-1")

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun json(v: Any): JsonElement = when (v) {
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private suspend fun run(name: String, vararg args: Pair<String, Any>, owner: ChangeOwner? = this.owner): ToolResult {
        val obj = JsonObject(args.associate { (k, v) -> k to json(v) })
        val ctx = ToolCallContext("chat", "call-$name", depth = 0, reviewer = null, changeOwner = owner)
        return withContext(ctx) {
            tools[name]!!.precheck(obj)?.let { return@withContext ToolResult.error("precheck: $it") }
            try {
                tools[name]!!.execute(obj)
            } catch (e: ToolException) {
                ToolResult.error(e.message.orEmpty())
            }
        }
    }

    private fun write(path: String, text: String) = ws.resolve(path).apply { parentFile?.mkdirs(); writeText(text) }

    // What the tools report

    @Test
    fun `an edit is reported with the trash copy of the version before it`() = runTest {
        write("a.txt", "one\ntwo\n")
        run("read_file", "path" to "a.txt")
        assertFalse(run("edit_file", "path" to "a.txt", "old_text" to "two", "new_text" to "three").isError)
        val change = recorded.single()
        assertEquals(owner, change.owner)
        assertEquals("call-edit_file", change.callId)
        assertEquals("a.txt", change.path)
        assertTrue(change.existedBefore)
        assertEquals("one\ntwo\n", ws.trashPayload(change.beforeTrashId!!)!!.readText())
    }

    @Test
    fun `a new file has no before-state, a replaced one has`() = runTest {
        run("write_file", "path" to "new.md", "content" to "hi")
        assertEquals(false, recorded.single().existedBefore)
        assertNull(recorded.single().beforeTrashId)
        write("old.md", "before")
        run("read_file", "path" to "old.md")
        run("write_file", "path" to "old.md", "content" to "after")
        assertEquals("before", ws.trashPayload(recorded.last().beforeTrashId!!)!!.readText())
    }

    @Test
    fun `delete, move and copy report every path they change`() = runTest {
        write("gone.txt", "x")
        run("delete_path", "path" to "gone.txt")
        assertEquals(listOf("gone.txt"), recorded.map { it.path })
        assertNotNull(ws.trashPayload(recorded.single().beforeTrashId!!))

        recorded.clear()
        write("from.txt", "moving")
        run("move_path", "from" to "from.txt", "to" to "sub/to.txt")
        assertEquals(listOf("from.txt" to true, "sub/to.txt" to false), recorded.map { it.path to it.existedBefore })
        // The moved file is kept for undo, since the move took it away from its old path.
        assertEquals("moving", ws.trashPayload(recorded.first().beforeTrashId!!)!!.readText())

        recorded.clear()
        run("copy_path", "from" to "sub/to.txt", "to" to "copy.txt")
        assertEquals(listOf("copy.txt" to false), recorded.map { it.path to it.existedBefore })
    }

    @Test
    fun `nothing is reported outside a tracked turn or for failed calls`() = runTest {
        run("write_file", "path" to "a.txt", "content" to "x", owner = null)
        assertTrue(recorded.isEmpty())
        run("edit_file", "path" to "missing.txt", "old_text" to "a", "new_text" to "b")
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a recorder that fails never fails the change`() = runTest {
        val failing = ToolRegistry(workspaceTools(ws, changes = ChangeRecorder { error("disk full") }))
        val ctx = ToolCallContext("chat", "c", depth = 0, reviewer = null, changeOwner = owner)
        val r = withContext(ctx) { failing["write_file"]!!.execute(JsonObject(mapOf("path" to JsonPrimitive("b.txt"), "content" to JsonPrimitive("ok")))) }
        assertFalse(r.isError, r.content)
        assertEquals("ok", ws.resolve("b.txt").readText())
    }

    // After an undo

    @Test
    fun `after an undo the chat must read the file again before editing it`() = runTest {
        write("a.txt", "one\n")
        run("read_file", "path" to "a.txt")
        run("edit_file", "path" to "a.txt", "old_text" to "one", "new_text" to "two")
        // The user undoes: the file goes back, and every chat that read it is told to read again.
        ws.restore(recorded.single().beforeTrashId!!)
        reads.invalidate(ws.resolve("a.txt"))
        assertEquals(FileReads.State.REVERTED, reads.state("chat", ws.resolve("a.txt")))
        val refused = run("edit_file", "path" to "a.txt", "old_text" to "one", "new_text" to "three")
        assertTrue(refused.isError && "undid" in refused.content, refused.content)
        val write = run("write_file", "path" to "a.txt", "content" to "x")
        assertTrue(write.isError && "undid" in write.content, write.content)
        run("read_file", "path" to "a.txt")
        assertFalse(run("edit_file", "path" to "a.txt", "old_text" to "one", "new_text" to "three").isError)
    }

    @Test
    fun `invalidating a file nobody read changes nothing`() {
        reads.invalidate(write("x.txt", "x"))
        assertEquals(FileReads.State.NOT_READ, reads.state("chat", ws.resolve("x.txt")))
    }

    // Diffs

    @Test
    fun `a small edit gives a unified diff with context and line numbers`() {
        val before = (1..10).joinToString("\n", postfix = "\n") { "line $it" }
        val after = before.replace("line 5\n", "line five\nline 5b\n")
        val d = LineDiff.diff("src/a.txt", before, after)
        assertEquals(2, d.added)
        assertEquals(1, d.removed)
        assertEquals(
            """
            --- a/src/a.txt
            +++ b/src/a.txt
            @@ -2,7 +2,8 @@
             line 2
             line 3
             line 4
            -line 5
            +line five
            +line 5b
             line 6
             line 7
             line 8
            """.trimIndent() + "\n",
            d.unified,
        )
    }

    @Test
    fun `far-apart changes make separate hunks, close ones merge`() {
        val before = (1..30).map { "l$it" }
        val after = before.toMutableList().apply { this[1] = "x2"; this[27] = "x28" }
        val text = LineDiff.unified("f", LineDiff.diff(before, after))!!
        assertEquals(2, Regex("^@@", RegexOption.MULTILINE).findAll(text).count(), text)
        assertTrue("@@ -1,5 +1,5 @@" in text && "@@ -25,6 +25,6 @@" in text, text)
        val close = before.toMutableList().apply { this[1] = "x2"; this[6] = "x7" }
        assertEquals(1, Regex("^@@", RegexOption.MULTILINE).findAll(LineDiff.unified("f", LineDiff.diff(before, close))!!).count())
    }

    @Test
    fun `new and deleted files diff against nothing`() {
        val created = LineDiff.diff("n.txt", null, "a\nb\n")
        assertEquals(2 to 0, created.added to created.removed)
        assertTrue(created.unified!!.startsWith("--- /dev/null\n+++ b/n.txt\n@@ -0,0 +1,2 @@\n+a\n+b\n"), created.unified)
        val deleted = LineDiff.diff("n.txt", "a\n", null)
        assertEquals(0 to 1, deleted.added to deleted.removed)
        assertTrue(deleted.unified!!.startsWith("--- a/n.txt\n+++ /dev/null\n@@ -1 +0,0 @@\n-a\n"), deleted.unified)
    }

    @Test
    fun `the shortest edit script is found, and applying it gives the new text`() {
        val a = "the quick brown fox jumps over the lazy dog".split(" ")
        val b = "the quick red fox leaps over the very lazy dog".split(" ")
        val lines = LineDiff.diff(a, b)
        assertEquals(b, lines.filter { it.op != LineDiff.Op.DELETE }.map { it.text })
        assertEquals(a, lines.filter { it.op != LineDiff.Op.INSERT }.map { it.text })
        assertEquals(3, lines.count { it.op == LineDiff.Op.INSERT })
        assertEquals(2, lines.count { it.op == LineDiff.Op.DELETE })
    }

    @Test
    fun `binary files, line endings and huge rewrites`() {
        assertTrue(LineDiff.diff("img.png", "\u0000PNG", "\u0000PNG2").binary)
        // CRLF and LF read as the same lines.
        assertEquals(0, LineDiff.diff("a", "x\r\ny\r\n", "x\ny\n").added)
        // Past the step limit the middle is replaced wholesale: still a correct diff.
        val n = LineDiff.MAX_D + 50
        val a = (1..n).map { "a$it" }
        val b = (1..n).map { "b$it" }
        val lines = LineDiff.diff(a, b)
        assertEquals(b, lines.filter { it.op != LineDiff.Op.DELETE }.map { it.text })
        assertEquals(n, lines.count { it.op == LineDiff.Op.DELETE })
        // Over the cap only the counts are kept.
        val capped = LineDiff.diff("big", a.joinToString("\n"), b.joinToString("\n"), maxChars = 1_000)
        assertNull(capped.unified)
        assertEquals(n, capped.added)
    }
}
