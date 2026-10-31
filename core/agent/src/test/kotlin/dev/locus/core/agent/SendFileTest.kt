package dev.locus.core.agent

import dev.locus.core.agent.tools.SendFile
import dev.locus.core.agent.tools.Workspace
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendFileTest {
    private val base: File = Files.createTempDirectory("locus").toFile()
    private val ws = Workspace(File(base, "workspace"), File(base, "trash"))
    private val tool = SendFile(ws)

    @AfterTest
    fun cleanup() {
        base.deleteRecursively()
    }

    private fun args(path: String) = JsonObject(mapOf("path" to JsonPrimitive(path)))

    @Test
    fun `sends an existing file and is an observation`() = runTest {
        ws.resolve("out/report.txt").apply { parentFile.mkdirs() }.writeText("hello")
        val result = tool.execute(args("out/report.txt"))
        assertFalse(result.isError)
        assertTrue("out/report.txt" in result.content)
        assertTrue("5 B" in result.content)
        assertTrue(tool.isObservation(args("out/report.txt")))
    }

    @Test
    fun `accepts the linux workspace prefix`() = runTest {
        ws.resolve("a.png").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(tool.execute(args("/workspace/a.png")).isError)
        assertEquals("a.png" to "pic", SendFile.parse("""{"path":"/workspace/a.png","note":"pic"}"""))
        assertNull(SendFile.parse("""{"note":"x"}"""))
    }

    @Test
    fun `rejects missing files, folders and escapes`() = runTest {
        File(base, "secret.txt").writeText("x")
        ws.resolve("dir").mkdirs()
        assertFailsWith<ToolException> { tool.execute(args("missing.pdf")) }
        assertFailsWith<ToolException> { tool.execute(args("dir")) }
        assertFailsWith<ToolException> { tool.execute(args("../secret.txt")) }
    }
}
