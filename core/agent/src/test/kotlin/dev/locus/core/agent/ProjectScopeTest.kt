package dev.locus.core.agent

import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.ProjectPrompt
import dev.locus.core.agent.tools.TrashEntry
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.model.ChatScopeSpec
import dev.locus.core.model.MemoryScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A project chat's scope (docs/design/projects.md): the workspace is rooted in the project's folder
 * with no way out, and the memory tools see the project's memory (plus general memory when the
 * scope includes it, never another project's).
 */
class ProjectScopeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val garden = ChatScopeSpec("11111111-aaaa-bbbb-cccc-000000000001", "projects/garden", MemoryScope.PROJECT_AND_GLOBAL)
    private val secret = ChatScopeSpec("22222222-aaaa-bbbb-cccc-000000000002", "projects/secret", MemoryScope.PROJECT_ONLY)

    private fun workspace(): Workspace {
        val ws = Workspace(tmp.newFolder("workspace"), tmp.newFolder("trash"))
        File(ws.base, "projects/garden").mkdirs()
        File(ws.base, "projects/garden/plan.md").writeText("plan")
        File(ws.base, "projects/secret/keys.txt").apply { parentFile.mkdirs(); writeText("keys") }
        File(ws.base, "notes.txt").writeText("global")
        return ws
    }

    @Test
    fun `paths resolve inside the project's folder`() {
        val ws = workspace()
        ChatScope.with(garden) {
            assertEquals(File(ws.base, "projects/garden/plan.md").canonicalFile, ws.resolve("plan.md"))
            assertEquals(File(ws.base, "projects/garden").canonicalFile, ws.resolve("."))
            assertEquals("plan.md", ws.relative(ws.resolve("plan.md")))
            // An absolute-looking path is taken from the project's folder, like from the workspace before.
            assertEquals(ws.resolve("plan.md"), ws.resolve("/plan.md"))
        }
        // Outside a project nothing changed.
        assertEquals(File(ws.base, "notes.txt").canonicalFile, ws.resolve("notes.txt"))
    }

    @Test
    fun `nothing outside the project's folder can be named`() {
        val ws = workspace()
        ChatScope.with(garden) {
            listOf("../secret/keys.txt", "../../notes.txt", "..", "a/../../secret", "../garden/../secret/keys.txt").forEach { path ->
                assertFailsWith<ToolException>(path) { ws.resolve(path) }
            }
        }
    }

    @Test
    fun `a symlink out of the project's folder is refused`() {
        val ws = workspace()
        val link = File(ws.base, "projects/garden/escape").toPath()
        runCatching { Files.createSymbolicLink(link, File(ws.base, "projects/secret").toPath()) }.getOrElse { return }
        ChatScope.with(garden) {
            assertFailsWith<ToolException> { ws.resolve("escape/keys.txt") }
        }
    }

    @Test
    fun `a project folder that isn't clean is refused, not widened`() {
        val ws = workspace()
        ChatScope.with(garden.copy(folder = "../outside")) {
            assertFailsWith<ToolException> { ws.resolve("x") }
        }
        ChatScope.with(garden.copy(folder = ".locus")) {
            assertFailsWith<ToolException> { ws.resolve("x") }
        }
    }

    @Test
    fun `trash and change records use paths from the whole workspace`() {
        val ws = workspace()
        val entry = ChatScope.with(garden) {
            val file = ws.resolve("plan.md")
            assertEquals("projects/garden/plan.md", ws.stored(file))
            ws.trash(file, TrashEntry.Reason.DELETED)
        }
        assertEquals("projects/garden/plan.md", entry.originalPath)
        // Restored from the Workspace screen, outside any chat, it goes back where it was.
        assertEquals("projects/garden/plan.md", ws.restore(entry.id))
        assertTrue(File(ws.base, "projects/garden/plan.md").isFile)
    }

    @Test
    fun `the scope follows the coroutine across threads and ends with it`() = runBlocking {
        val ws = workspace()
        withContext(ChatScope.element(garden)) {
            withContext(Dispatchers.IO) { assertEquals("plan.md", ws.relative(ws.resolve("plan.md"))) }
        }
        assertNull(ChatScope.current())
        assertEquals(File(ws.base, "notes.txt").canonicalFile, ws.resolve("notes.txt"))
    }

    @Test
    fun `memory saves go to the project and reads fall back to general memory`() {
        val home = AgentHome(tmp.newFolder("home"))
        home.saveMemory("coffee", "Flat white", "Flat white, no sugar.")
        val view = home.memory(garden)
        view.save("beds", "Three raised beds", "Three raised beds by the fence.")
        assertNull(home.readMemory("beds"))
        assertEquals("Three raised beds by the fence.\n", home.projectMemory(garden.projectId).readMemory("beds"))
        assertEquals("Flat white, no sugar.", view.read("coffee")?.trim())
        assertTrue(view.search("coffee sugar").single().second.startsWith("(general memory)"))
        // Forgetting only touches the project's own memory.
        assertFalse(view.forget("coffee"))
        assertTrue(view.forget("beds"))
    }

    @Test
    fun `project-only memory sees neither general memory nor other projects`() {
        val home = AgentHome(tmp.newFolder("home"))
        home.saveMemory("coffee", "Flat white", "Flat white.")
        home.memory(garden).save("beds", "Raised beds", "Beds.")
        val view = home.memory(secret)
        assertNull(view.read("coffee"))
        assertNull(view.read("beds"))
        assertTrue(view.search("coffee beds").isEmpty())
        view.save("vault", "Where keys are", "In the vault.")
        assertNull(home.memory(garden).read("vault"))
        assertNull(home.readMemory("vault"))
    }

    @Test
    fun `the prompt carries the project's instructions and its memory indexes`() {
        val home = AgentHome(tmp.newFolder("home"))
        home.saveMemory("coffee", "Flat white", "Flat white.")
        home.memory(garden).save("beds", "Raised beds", "Beds.")
        home.writeProjectInstructions(garden.projectId, "Use metric units.")
        val both = home.snapshot(project = ProjectPrompt(garden, allowedConnectors = null))
        assertTrue("<project_instructions file=\"AGENTS.md\">\nUse metric units." in both)
        assertTrue("<project_memory_index>\n- [beds](beds.md) — Raised beds" in both)
        assertTrue("<memory_index>\n- [coffee](coffee.md) — Flat white" in both)

        home.writeProjectInstructions(secret.projectId, "Keep it private.")
        val only = home.snapshot(project = ProjectPrompt(secret, allowedConnectors = null))
        assertTrue("Keep it private." in only)
        assertFalse("coffee" in only)
        assertFalse("beds" in only)
        assertFalse("Use metric units." in only)

        // Outside a project: as before, and no project block.
        val plain = home.snapshot()
        assertFalse("<project>" in plain)
        assertFalse("beds" in plain)
    }

    @Test
    fun `instruction edits reach new prompts only`() {
        val home = AgentHome(tmp.newFolder("home"))
        home.writeProjectInstructions(garden.projectId, "Version one.")
        val frozen = SystemPrompt.build("", home.snapshot(project = ProjectPrompt(garden, null)))
        home.writeProjectInstructions(garden.projectId, "Version two.")
        // The chat keeps the prompt it was created with; a new one gets the edit.
        assertTrue("Version one." in frozen && "Version two." !in frozen)
        assertTrue("Version two." in SystemPrompt.build("", home.snapshot(project = ProjectPrompt(garden, null))))
    }

    @Test
    fun `project ids are checked before they become paths`() {
        val home = AgentHome(tmp.newFolder("home"))
        assertFailsWith<IllegalArgumentException> { home.projectDir("../memory") }
        assertFailsWith<IllegalArgumentException> { home.projectMemory("a/b") }
    }
}
