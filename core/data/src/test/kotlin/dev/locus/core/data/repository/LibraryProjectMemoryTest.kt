package dev.locus.core.data.repository

import dev.locus.core.agent.home.MemoryAuthor
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library → Memory shows each project's own memory beside general memory, to edit, forget or move. */
class LibraryProjectMemoryTest {
    @Test
    fun `project memory is listed by project, apart from general memory`() = runTest {
        val g = TestGraph(backgroundScope)
        val garden = assertNotNull(g.projects.create("Garden"))
        val attic = assertNotNull(g.projects.create("Attic"))
        assertNotNull(g.projects.create("Empty"))
        g.home.saveMemory("coffee", "How the user takes coffee", "Flat white.")
        g.home.projectMemory(garden.id).saveMemory("soil", "Soil pH", "Slightly acidic.", by = MemoryAuthor.AGENT, chatId = "c1")
        g.home.projectMemory(attic.id).saveMemory("boxes", "What's in the boxes", "Winter clothes.")
        g.library.refresh()

        val library = g.library.library.first { it.projectMemory.size == 2 }
        assertEquals(listOf("coffee"), library.memory.map { it.name })
        // By name; a project with nothing saved isn't listed.
        assertEquals(listOf("Attic", "Garden"), library.projectMemory.map { it.projectName })
        val soil = library.projectMemory.single { it.projectId == garden.id }.topics.single()
        assertEquals("soil", soil.name)
        assertEquals("Soil pH", soil.summary)
        assertEquals(MemoryAuthor.AGENT, soil.provenance?.by)
    }

    @Test
    fun `listing a project's memory creates nothing on disk`() = runTest {
        val g = TestGraph(backgroundScope)
        val project = assertNotNull(g.projects.create("Garden"))
        assertTrue(g.library.library.first().projectMemory.isEmpty())
        assertFalse(g.home.projectDir(project.id).exists())
    }

    @Test
    fun `edits and forgets go to the project's own memory`() = runTest {
        val g = TestGraph(backgroundScope)
        val project = assertNotNull(g.projects.create("Garden"))
        g.home.projectMemory(project.id).saveMemory("soil", "Soil pH", "Slightly acidic.")

        g.library.saveMemory("soil", "Soil pH, measured", "pH 6.2.", project.id)
        val store = g.home.projectMemory(project.id)
        assertEquals("pH 6.2.\n", store.readMemory("soil"))
        assertEquals(MemoryAuthor.USER, store.memoryTopics().single().provenance?.by)
        assertTrue(g.home.memoryTopics().isEmpty())

        assertTrue(g.library.forgetMemory("soil", project.id))
        assertNull(store.readMemory("soil"))
        assertTrue(g.library.library.first { it.projectMemory.isEmpty() }.projectMemory.isEmpty())
    }

    @Test
    fun `moving a note to general memory keeps it and its provenance, and takes it out of the project`() = runTest {
        val g = TestGraph(backgroundScope)
        val project = assertNotNull(g.projects.create("Garden"))
        g.home.projectMemory(project.id).saveMemory("units", "Metric units", "Use metric.", by = MemoryAuthor.AGENT, chatId = "c1", taintedBy = listOf("web_fetch"))

        g.library.moveMemoryToGeneral(project.id, "units")

        val library = g.library.library.first { it.projectMemory.isEmpty() }
        val moved = library.memory.single()
        assertEquals("units", moved.name)
        assertEquals("Metric units", moved.summary)
        assertEquals("Use metric.\n", moved.content)
        // A note saved after reading outside content stays flagged in general memory.
        assertEquals(listOf("web_fetch"), moved.provenance?.taintedBy)
        assertEquals("c1", moved.provenance?.chatId)
        assertNull(g.home.projectMemory(project.id).readMemory("units"))
    }

    @Test
    fun `moving replaces a general note of the same name, and fails for one that's gone`() = runTest {
        val g = TestGraph(backgroundScope)
        val project = assertNotNull(g.projects.create("Garden"))
        g.home.saveMemory("units", "Imperial", "Use feet.")
        g.home.projectMemory(project.id).saveMemory("units", "Metric", "Use metres.")

        g.library.moveMemoryToGeneral(project.id, "units")
        assertEquals("Use metres.\n", g.home.readMemory("units"))

        assertFailsWith<IllegalStateException> { g.library.moveMemoryToGeneral(project.id, "units") }
    }

    @Test
    fun `a malformed project id is refused, not written anywhere`() = runTest {
        val g = TestGraph(backgroundScope)
        assertFailsWith<IllegalArgumentException> { g.library.saveMemory("x", "s", "c", projectId = "../memory") }
        assertTrue(g.home.memoryTopics().isEmpty())
    }
}
