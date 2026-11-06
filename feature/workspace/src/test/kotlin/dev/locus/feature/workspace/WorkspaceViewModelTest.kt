package dev.locus.feature.workspace

import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deleting from the workspace offers Undo, which restores the file from the trash. */
class WorkspaceViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    @Test
    fun `a recent file from Home opens in the viewer, and back from it leaves the screen`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val file = g.workspace.resolve("garden-app/notes/plan.md")
            file.parentFile?.mkdirs()
            file.writeText("# Plan\nWater the tomatoes.")
            val vm = host.add(WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io))

            vm.openFromOutside("garden-app/notes/plan.md")
            // Until it's read, the screen shows the file loading rather than the folder list.
            assertEquals("garden-app/notes/plan.md", vm.uiState.value.opening)
            val open = vm.uiState.first { it.openFile != null && it.opening == null }.openFile!!
            assertEquals("garden-app/notes/plan.md", open.path)
            assertEquals("plan.md", open.name)
            // Opening again (a rotation re-runs the screen's effect) changes nothing.
            vm.openFromOutside("garden-app/notes/plan.md")
            // Back closes the screen (returns to Home), not to the folder list.
            assertFalse(vm.up())
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a file picked in the list after opening from Home closes back to the list`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            listOf("a.txt", "b.txt").forEach { g.workspace.resolve(it).apply { parentFile?.mkdirs() }.writeText(it) }
            val vm = host.add(WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io))
            vm.openFromOutside("a.txt")
            vm.uiState.first { it.openFile?.path == "a.txt" }
            val b = vm.uiState.first { s -> s.entries.any { it.path == "b.txt" } }.entries.first { it.path == "b.txt" }
            vm.open(b)
            vm.uiState.first { it.openFile?.path == "b.txt" }
            assertTrue(vm.up())
            assertNull(vm.uiState.value.openFile)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a recent file that has since gone shows the list with a message, not a blank viewer`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            g.workspace.resolve("kept.txt").apply { parentFile?.mkdirs() }.writeText("still here")
            val vm = host.add(WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io))

            vm.openFromOutside("old/report.pdf")
            val gone = vm.uiState.first { it.message != null && it.opening == null }
            assertNull(gone.openFile)
            assertTrue("report.pdf" in gone.message!! && "moved or deleted" in gone.message!!, gone.message)
            // The list is there to pick from, and back leaves as from the root.
            assertTrue(vm.uiState.first { !it.loading }.entries.any { it.path == "kept.txt" })
            assertFalse(vm.up())
            // A path outside the workspace is treated the same way, never opened.
            val vm2 = host.add(WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io))
            vm2.openFromOutside("../../etc/passwd")
            assertNull(vm2.uiState.first { it.message != null }.openFile)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a delete can be undone from its message`() = runTest {
        try {
            // The repository's file work runs on the test dispatcher, so waiting on uiState is enough.
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val file = g.workspace.resolve("notes.txt")
            file.parentFile?.mkdirs()
            file.writeText("buy milk")
            val vm = host.add(WorkspaceViewModel(g.workspaceRepository, g.workspace, g.io))

            vm.delete("notes.txt")
            val deleted = vm.uiState.first { it.undoTrashId != null }
            assertFalse(file.exists())
            val undo = assertNotNull(deleted.undoTrashId)
            assertTrue(deleted.message!!.startsWith("Moved to trash"))

            vm.messageShown()
            assertNull(vm.uiState.value.undoTrashId)
            vm.restore(undo)
            val restored = vm.uiState.first { it.message?.startsWith("Restored") == true }
            assertEquals("buy milk", file.readText())
            assertNull(restored.undoTrashId)
        } finally {
            host.clear()
        }
    }
}
