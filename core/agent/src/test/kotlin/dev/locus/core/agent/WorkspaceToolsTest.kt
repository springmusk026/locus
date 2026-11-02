package dev.locus.core.agent

import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.workspaceTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceToolsTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val tools = ToolRegistry(workspaceTools(ws))

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private suspend fun run(name: String, vararg args: Pair<String, Any>): ToolResult {
        val json = JsonObject(args.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        return try {
            tools[name]!!.execute(json)
        } catch (e: ToolException) {
            ToolResult.error(e.message.orEmpty())
        }
    }

    @Test
    fun `paths cannot escape the workspace`() = runTest {
        File(base, "secret.txt").writeText("x")
        val result = run("read_file", "path" to "../secret.txt")
        assertTrue(result.isError)
        assertTrue("outside the workspace" in result.content)
    }

    @Test
    fun `overwrite and delete are recoverable from trash`() = runTest {
        run("write_file", "path" to "notes/todo.md", "content" to "v1")
        run("write_file", "path" to "notes/todo.md", "content" to "v2")
        run("delete_path", "path" to "notes/todo.md")
        assertFalse(ws.resolve("notes/todo.md").exists())

        val entries = ws.trashEntries()
        assertEquals(2, entries.size)
        ws.restore(entries.first { it.reason.name == "DELETED" }.id)
        assertEquals("v2", ws.resolve("notes/todo.md").readText())
        ws.restore(ws.trashEntries().single().id)
        assertEquals("v1", ws.resolve("notes/todo.md").readText())
    }

    @Test
    fun `edit requires a unique match`() = runTest {
        run("write_file", "path" to "a.txt", "content" to "x\nx\n")
        assertTrue(run("edit_file", "path" to "a.txt", "old_text" to "x", "new_text" to "y").isError)
        assertFalse(run("edit_file", "path" to "a.txt", "old_text" to "x\nx", "new_text" to "y").isError)
        assertEquals("y\n", ws.resolve("a.txt").readText())
    }

    @Test
    fun `move refuses to clobber unless asked`() = runTest {
        run("write_file", "path" to "a.txt", "content" to "a")
        run("write_file", "path" to "b.txt", "content" to "b")
        assertTrue(run("move_path", "from" to "a.txt", "to" to "b.txt").isError)
        assertFalse(run("move_path", "from" to "a.txt", "to" to "b.txt", "overwrite" to true).isError)
        assertEquals("a", ws.resolve("b.txt").readText())
        // The replaced destination is in the trash; the moved file also keeps a copy there, so the move can be undone.
        val entries = ws.trashEntries()
        assertEquals("b", ws.trashPayload(entries.single { it.reason == dev.locus.core.agent.tools.TrashEntry.Reason.MOVED_OVER }.id)!!.readText())
        assertEquals("a", ws.trashPayload(entries.single { it.reason == dev.locus.core.agent.tools.TrashEntry.Reason.MOVED_AWAY }.id)!!.readText())
    }

    @Test
    fun `read and search report line numbers`() = runTest {
        run("write_file", "path" to "src/main.kt", "content" to "fun main() {\n  println(\"hi\")\n}\n")
        assertTrue("2\t  println" in run("read_file", "path" to "src/main.kt").content)
        assertEquals("src/main.kt:2:   println(\"hi\")", run("search_files", "pattern" to "println").content)
        assertTrue("src/main.kt" in run("list_files", "recursive" to true).content)
    }
}
