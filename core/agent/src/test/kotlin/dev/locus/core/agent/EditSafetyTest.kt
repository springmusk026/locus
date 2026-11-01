package dev.locus.core.agent

import dev.locus.core.agent.tools.FileReads
import dev.locus.core.agent.tools.SyntaxCheck
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.workspaceTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edit tools' safety nets: lenient matching that applies only when one place fits, read-before-edit
 * per chat, and the syntax check after a change, which may add problems but never fails the edit.
 */
class EditSafetyTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private var checkResult: suspend (File, String) -> String? = { _, _ -> null }
    private val checked = mutableListOf<String>()
    private val tools = ToolRegistry(workspaceTools(ws, SyntaxCheck { file, path -> checked += path; checkResult(file, path) }))

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun json(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun context(chat: String) = ToolCallContext(chat, "call", depth = 0, reviewer = null)

    /** Runs a tool as the loop does: precheck first, then execute, both inside [chat]'s context (none when null). */
    private suspend fun run(name: String, vararg args: Pair<String, Any>, chat: String? = null): ToolResult {
        val obj = JsonObject(args.associate { (k, v) -> k to json(v) })
        validateArguments(tools[name]!!.spec.inputSchema, obj)?.let { error("bad test args: $it") }
        suspend fun go(): ToolResult {
            tools[name]!!.precheck(obj)?.let { return ToolResult.error("precheck: $it") }
            return try {
                tools[name]!!.execute(obj)
            } catch (e: ToolException) {
                ToolResult.error(e.message.orEmpty())
            }
        }
        return if (chat == null) go() else withContext(context(chat)) { go() }
    }

    private fun write(path: String, text: String) = ws.resolve(path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun read(path: String) = ws.resolve(path).readText()

    // Lenient matching

    @Test
    fun `trailing whitespace differences apply when one place fits`() = runTest {
        write("a.py", "def f():  \n    return 1   \n")
        val r = run("edit_file", "path" to "a.py", "old_text" to "def f():\n    return 1\n", "new_text" to "def f():\n    return 2\n")
        assertFalse(r.isError, r.content)
        assertTrue("ignoring trailing whitespace" in r.content && "lines 1–2" in r.content, r.content)
        assertEquals("def f():\n    return 2\n", read("a.py"))
    }

    @Test
    fun `a uniform indentation difference applies and re-indents new_text`() = runTest {
        write("a.py", "class A:\n    def f(self):\n        x = 1\n        return x\n")
        val r = run("edit_file", "path" to "a.py", "old_text" to "x = 1\nreturn x", "new_text" to "x = 2\nif x:\n    return x")
        assertFalse(r.isError, r.content)
        assertTrue("different indentation" in r.content && "8 spaces more" in r.content && "re-indented" in r.content, r.content)
        assertEquals("class A:\n    def f(self):\n        x = 2\n        if x:\n            return x\n", read("a.py"))

        // Too much indentation in old_text is taken off new_text too.
        write("b.js", "function f() {\n  go();\n  stop();\n}\n")
        val less = run("edit_file", "path" to "b.js", "old_text" to "      go();\n      stop();", "new_text" to "      go();\n      wait();\n      stop();")
        assertFalse(less.isError, less.content)
        assertTrue("4 spaces less" in less.content, less.content)
        assertEquals("function f() {\n  go();\n  wait();\n  stop();\n}\n", read("b.js"))
    }

    @Test
    fun `lenient tiers never guess between several places`() = runTest {
        val text = "if a:\n    x = 1\nif b:\n        x = 1\n"
        write("a.py", text)
        val r = run("edit_file", "path" to "a.py", "old_text" to "x = 1", "new_text" to "x = 2")
        // "x = 1" is found exactly twice, so the usual uniqueness error applies.
        assertTrue(r.isError && "2 times" in r.content, r.content)
        val trailing = run("edit_file", "path" to "a.py", "old_text" to "  x = 1  ", "new_text" to "x = 2")
        assertTrue(trailing.isError, trailing.content)
        assertEquals(text, read("a.py"))
    }

    @Test
    fun `curly quotes and dashes match their plain forms`() = runTest {
        write("a.md", "He said \u201Chello\u201D \u2014 twice.\n")
        val r = run("edit_file", "path" to "a.md", "old_text" to "He said \"hello\" - twice.", "new_text" to "He said hi.")
        assertFalse(r.isError, r.content)
        assertTrue("curly quotes" in r.content, r.content)
        assertEquals("He said hi.\n", read("a.md"))
    }

    @Test
    fun `lenient matching keeps windows line endings`() = runTest {
        write("a.txt", "one  \r\n  two\r\nthree\r\n")
        val r = run("edit_file", "path" to "a.txt", "old_text" to "one\n  two\n", "new_text" to "1\n  2\n")
        assertFalse(r.isError, r.content)
        assertEquals("1\r\n  2\r\nthree\r\n", read("a.txt"))
    }

    @Test
    fun `multi_edit says which edit matched leniently`() = runTest {
        write("a.kt", "fun a() {\n    val x = 1   \n}\n")
        val r = run(
            "multi_edit", "path" to "a.kt",
            "edits" to JsonArray(
                listOf(
                    JsonObject(mapOf("old_text" to JsonPrimitive("fun a()"), "new_text" to JsonPrimitive("fun b()"))),
                    JsonObject(mapOf("old_text" to JsonPrimitive("    val x = 1\n"), "new_text" to JsonPrimitive("    val x = 2\n"))),
                ),
            ),
        )
        assertFalse(r.isError, r.content)
        assertTrue("Edit 2: old_text matched" in r.content, r.content)
        assertEquals("fun b() {\n    val x = 2\n}\n", read("a.kt"))
    }

    // Read before edit

    @Test
    fun `a chat must read a file before changing it`() = runTest {
        write("a.kt", "val a = 1\n")
        val edit = run("edit_file", "path" to "a.kt", "old_text" to "1", "new_text" to "2", chat = "chat-1")
        assertTrue(edit.isError && "precheck" in edit.content && "hasn't read a.kt" in edit.content, edit.content)
        val overwrite = run("write_file", "path" to "a.kt", "content" to "x", chat = "chat-1")
        assertTrue(overwrite.isError && "hasn't read" in overwrite.content, overwrite.content)
        assertEquals("val a = 1\n", read("a.kt"))

        assertFalse(run("read_file", "path" to "a.kt", "start_line" to 1, "max_lines" to 1, chat = "chat-1").isError)
        assertFalse(run("edit_file", "path" to "a.kt", "old_text" to "1", "new_text" to "2", chat = "chat-1").isError)
        // Its own edit counts as seeing the file, so it can keep going.
        assertFalse(run("edit_file", "path" to "a.kt", "old_text" to "2", "new_text" to "3", chat = "chat-1").isError)
        assertEquals("val a = 3\n", read("a.kt"))

        // Another chat hasn't read it.
        assertTrue("hasn't read" in run("edit_file", "path" to "a.kt", "old_text" to "3", "new_text" to "4", chat = "chat-2").content)
    }

    @Test
    fun `new files and calls outside a chat need no read`() = runTest {
        assertFalse(run("write_file", "path" to "new/b.txt", "content" to "hi", chat = "chat-1").isError)
        write("c.txt", "c")
        assertFalse(run("edit_file", "path" to "c.txt", "old_text" to "c", "new_text" to "d").isError)
    }

    @Test
    fun `a file changed since the read takes exact edits only, and refuses a full rewrite`() = runTest {
        val file = write("a.py", "x = 1\ny = 2\n")
        run("read_file", "path" to "a.py", chat = "chat-1")
        file.writeText("x = 1\ny = 3\n")
        file.setLastModified(file.lastModified() + 5_000)

        val rewrite = run("write_file", "path" to "a.py", "content" to "z = 0\n", chat = "chat-1")
        assertTrue(rewrite.isError && "changed on disk" in rewrite.content, rewrite.content)

        // Indented differently: no lenient match on a file the chat hasn't seen like this.
        val loose = run("edit_file", "path" to "a.py", "old_text" to "  x = 1", "new_text" to "x = 5", chat = "chat-1")
        assertTrue(loose.isError && "only an exact match" in loose.content && "Read the file again" in loose.content, loose.content)

        val exact = run("edit_file", "path" to "a.py", "old_text" to "x = 1", "new_text" to "x = 5", chat = "chat-1")
        assertFalse(exact.isError, exact.content)
        assertTrue("other changes since this chat read it" in exact.content, exact.content)
        assertEquals("x = 5\ny = 3\n", read("a.py"))
        // After that edit the chat's view is current again.
        assertFalse(run("write_file", "path" to "a.py", "content" to "z = 0\n", chat = "chat-1").isError)
    }

    @Test
    fun `a touched but unchanged file still counts as read`() {
        val reads = FileReads()
        val file = write("a.txt", "same")
        reads.record("c", file)
        file.setLastModified(file.lastModified() + 10_000)
        assertEquals(FileReads.State.READ, reads.state("c", file))
        assertEquals(FileReads.State.NEW_FILE, reads.state("c", ws.resolve("missing.txt")))
        reads.forget("c")
        assertEquals(FileReads.State.NOT_READ, reads.state("c", file))
    }

    @Test
    fun `reads are bounded per chat and in number of chats`() {
        val reads = FileReads(maxChats = 2, maxFilesPerChat = 2)
        val a = write("a", "a")
        val b = write("b", "b")
        val c = write("c", "c")
        listOf(a, b, c).forEach { reads.record("one", it) }
        assertEquals(FileReads.State.NOT_READ, reads.state("one", a))
        assertEquals(FileReads.State.READ, reads.state("one", c))
        reads.record("two", a)
        reads.record("three", a)
        assertEquals(FileReads.State.NOT_READ, reads.state("one", c))
    }

    // Syntax check

    @Test
    fun `syntax problems are added to the edit result`() = runTest {
        checkResult = { _, path -> "Syntax check (python3) found problems; fix them before going on:\n$path:1:5: invalid syntax" }
        val r = run("write_file", "path" to "a.py", "content" to "def (:\n")
        assertFalse(r.isError, r.content)
        assertTrue(r.content.startsWith("Created a.py") && "a.py:1:5: invalid syntax" in r.content, r.content)
        assertEquals(listOf("a.py"), checked)
    }

    @Test
    fun `a failing or silent check never fails the edit, and a failed edit isn't checked`() = runTest {
        write("a.py", "x = 1\n")
        checkResult = { _, _ -> throw IllegalStateException("boom") }
        val r = run("edit_file", "path" to "a.py", "old_text" to "1", "new_text" to "2")
        assertFalse(r.isError, r.content)
        assertFalse("boom" in r.content)
        assertEquals("x = 2\n", read("a.py"))

        checkResult = { _, _ -> null }
        assertFalse("Syntax" in run("edit_file", "path" to "a.py", "old_text" to "2", "new_text" to "3").content)

        checked.clear()
        assertTrue(run("edit_file", "path" to "a.py", "old_text" to "missing", "new_text" to "3").isError)
        assertTrue(checked.isEmpty())
    }

    @Test
    fun `a slow check is cut off and the edit still succeeds`() = runTest {
        write("a.py", "x = 1\n")
        checkResult = { _, _ -> delay(60_000); "too late" }
        val r = run("edit_file", "path" to "a.py", "old_text" to "1", "new_text" to "2")
        assertFalse(r.isError, r.content)
        assertNull(Regex("too late").find(r.content))
        assertEquals("x = 2\n", read("a.py"))
    }
}
