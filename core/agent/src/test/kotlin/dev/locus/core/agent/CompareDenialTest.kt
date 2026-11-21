package dev.locus.core.agent

import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.agentControlTools
import dev.locus.core.agent.tools.workspaceTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compare models: the second model's answer may only read and look (docs/design/compare-models.md), so one
 * message never has its side effects done twice.
 */
class CompareDenialTest {
    private val dir = Files.createTempDirectory("compare").toFile()
    private val tools = (
        workspaceTools(Workspace(File(dir, "workspace"), File(dir, "trash"))) + agentControlTools()
        ).associateBy { it.spec.name }

    @Test
    fun `reading and looking run in the second answer`() = runTest {
        assertNull(ChatPolicy.compareDenial(tools.getValue("read_file"), buildJsonObject { put("path", "notes.txt") }))
        assertNull(ChatPolicy.compareDenial(tools.getValue("list_files"), buildJsonObject { }))
        assertNull(ChatPolicy.compareDenial(tools.getValue("todo_write"), buildJsonObject { }))
    }

    @Test
    fun `writing and deleting are refused with a reason the model can act on`() = runTest {
        val write = ChatPolicy.compareDenial(tools.getValue("write_file"), buildJsonObject { put("path", "notes.txt"); put("content", "x") })
        assertNotNull(write)
        assertTrue(write.startsWith("Not allowed: this answer is a comparison"), write)
        assertTrue("write_file" in write, write)
        assertTrue("Don't retry" in write, write)
        assertNotNull(ChatPolicy.compareDenial(tools.getValue("delete_path"), buildJsonObject { put("path", "notes.txt") }))
    }
}
