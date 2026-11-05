package dev.locus.feature.chat

import dev.locus.core.agent.tools.SendFile
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.MemoryScope
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The send_file card finds the file where send_file found it: in a project chat, inside the project's
 * folder. It used to resolve against the whole workspace and said "No longer in the workspace".
 */
class SentFileInfoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val workspace by lazy { Workspace(tmp.newFolder("workspace"), tmp.newFolder("trash")) }
    private val garden = ChatScopeSpec("p1", "projects/garden-app", MemoryScope.entries.first())
    private val mime: (String) -> String? = { if (it == "pdf") "application/pdf" else null }

    private fun put(path: String, text: String = "x"): File = File(workspace.base, path).apply { parentFile?.mkdirs(); writeText(text) }

    private fun info(scope: ChatScopeSpec?, path: String) = sentFileInfo(workspace, scope, SendFile.normalize(path), mime)

    @Test
    fun `a project chat finds a file sent at its folder's root`() {
        put("projects/garden-app/report.pdf", "pdf")
        val f = assertNotNull(info(garden, "report.pdf"))
        assertEquals("report.pdf", f.name)
        assertEquals(3, f.size)
        assertEquals("application/pdf", f.mimeType)
        // Named as the Workspace screen and the documents provider name it: from the workspace's root.
        assertEquals("projects/garden-app/report.pdf", f.relativePath)
    }

    @Test
    fun `a project chat finds a file sent in a subfolder`() {
        put("projects/garden-app/docs/plan.md")
        val f = assertNotNull(info(garden, "docs/plan.md"))
        assertEquals("projects/garden-app/docs/plan.md", f.relativePath)
        assertEquals("application/octet-stream", f.mimeType)
    }

    @Test
    fun `a chat outside projects resolves against the whole workspace, with the shell's workspace prefix`() {
        put("exports/summary.pdf")
        assertEquals("exports/summary.pdf", assertNotNull(info(null, "/workspace/exports/summary.pdf")).relativePath)
        assertEquals("exports/summary.pdf", assertNotNull(info(null, "exports/summary.pdf")).relativePath)
        // A project's file isn't found by its project-relative path outside the project.
        put("projects/garden-app/only-here.txt")
        assertNull(info(null, "only-here.txt"))
    }

    @Test
    fun `a file that is really gone still reads as gone`() {
        val f = put("projects/garden-app/old.csv")
        assertNotNull(info(garden, "old.csv"))
        f.delete()
        assertNull(info(garden, "old.csv"))
        assertNull(info(garden, "docs"))
    }

    @Test
    fun `paths that climb out are refused`() {
        put("secret.txt")
        put("projects/other/notes.md")
        // Out of the project, into the rest of the workspace.
        assertNull(info(garden, "../../secret.txt"))
        assertNull(info(garden, "../other/notes.md"))
        // Out of the workspace altogether.
        File(tmp.root, "outside.txt").writeText("no")
        assertNull(info(null, "../outside.txt"))
        assertNull(info(garden, "/../../../outside.txt"))
    }
}
