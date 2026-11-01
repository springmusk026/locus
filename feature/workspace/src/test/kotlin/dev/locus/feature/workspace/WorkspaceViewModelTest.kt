package dev.locus.feature.workspace

import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    val main = MainDispatcherRule(UnconfinedTestDispatcher(TestCoroutineScheduler()))

    private val host = ViewModelHost()

    /** The repository works on Dispatchers.IO, so wait for it in real time. */
    private suspend fun settle(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!done()) delay(5) }
    }

    @Test
    fun `a delete can be undone from its message`() = runTest {
        try {
            val g = TestGraph(backgroundScope)
            val file = g.workspace.resolve("notes.txt")
            file.parentFile?.mkdirs()
            file.writeText("buy milk")
            val vm = host.add(WorkspaceViewModel(g.workspaceRepository, g.workspace))

            vm.delete("notes.txt")
            settle { vm.uiState.value.undoTrashId != null }
            assertFalse(file.exists())
            val undo = assertNotNull(vm.uiState.value.undoTrashId)
            assertTrue(vm.uiState.value.message!!.startsWith("Moved to trash"))

            vm.messageShown()
            assertNull(vm.uiState.value.undoTrashId)
            vm.restore(undo)
            settle { file.exists() }
            assertEquals("buy milk", file.readText())
            settle { vm.uiState.value.message?.startsWith("Restored") == true }
            assertNull(vm.uiState.value.undoTrashId)
        } finally {
            host.clear()
        }
    }
}
