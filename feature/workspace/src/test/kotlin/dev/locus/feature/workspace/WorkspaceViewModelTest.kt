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
    fun `a delete can be undone from its message`() = runTest {
        try {
            // The repository's file work runs on the test dispatcher, so waiting on uiState is enough.
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val file = g.workspace.resolve("notes.txt")
            file.parentFile?.mkdirs()
            file.writeText("buy milk")
            val vm = host.add(WorkspaceViewModel(g.workspaceRepository, g.workspace))

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
