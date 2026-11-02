package dev.locus.feature.library

import dev.locus.core.data.repository.Library
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.toList
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library's states (loading, ready) and actions that fail: shown with a retry, never a crash. */
class LibraryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    @Test
    fun `starts loading, then shows the library with project memory`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val project = assertNotNull(g.projects.create("Garden"))
            g.home.projectMemory(project.id).saveMemory("soil", "Soil pH", "Slightly acidic.")
            val vm = host.add(LibraryViewModel(g.library))
            assertEquals(LibraryUiState.Loading, vm.uiState.value)

            vm.uiState.launchIn(backgroundScope)
            val ready = vm.uiState.first { it is LibraryUiState.Ready } as LibraryUiState.Ready
            assertEquals("Garden", ready.library.projectMemory.single().projectName)
            assertEquals("soil", ready.library.projectMemory.single().topics.single().name)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `project memory is edited, moved and forgotten from Library`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val project = assertNotNull(g.projects.create("Garden"))
            g.home.projectMemory(project.id).saveMemory("soil", "Soil pH", "Slightly acidic.")
            g.home.projectMemory(project.id).saveMemory("beds", "Raised beds", "Three beds.")
            val vm = host.add(LibraryViewModel(g.library))
            vm.uiState.launchIn(backgroundScope)

            vm.saveMemory("soil", "Soil pH", "pH 6.2.", project.id).join()
            assertEquals("pH 6.2.\n", g.home.projectMemory(project.id).readMemory("soil"))

            vm.moveMemoryToGeneral(project.id, "soil").join()
            val moved = vm.uiState.first { (it as? LibraryUiState.Ready)?.library?.memory?.isNotEmpty() == true } as LibraryUiState.Ready
            assertEquals(listOf("soil"), moved.library.memory.map { it.name })
            assertEquals(listOf("beds"), moved.library.projectMemory.single().topics.map { it.name })

            vm.forgetMemory("beds", project.id).join()
            val gone = vm.uiState.first { (it as? LibraryUiState.Ready)?.library?.projectMemory?.isEmpty() == true } as LibraryUiState.Ready
            assertTrue(gone.library.projectMemory.isEmpty())
            assertNull(vm.error.value)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a failed action is reported with a retry instead of crashing`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val project = assertNotNull(g.projects.create("Garden"))
            val vm = host.add(LibraryViewModel(g.library))

            // The project has no such note: the move fails.
            vm.moveMemoryToGeneral(project.id, "soil").join()
            val error = assertNotNull(vm.error.value)
            assertEquals("Couldn't move “soil”", error.title)
            assertTrue(error.message.isNotBlank())

            // Once the note is there, Try again does the same move.
            g.home.projectMemory(project.id).saveMemory("soil", "Soil pH", "Slightly acidic.")
            assertNotNull(error.retry).invoke()
            assertNull(vm.error.value)
            testScheduler.advanceUntilIdle()
            assertNull(vm.error.value)
            assertEquals("Slightly acidic.\n", g.home.readMemory("soil"))

            vm.saveMemory("x", "s", "c", projectId = "not a project").join()
            assertEquals("Couldn't save “x”", vm.error.value?.title)
            vm.dismissError()
            assertNull(vm.error.value)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `leaving mid-action cancels it without reporting an error`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val vm = host.add(LibraryViewModel(g.library))
            g.home.saveMemory("coffee", "How the user takes coffee", "Flat white.")

            val job = vm.forgetMemory("coffee")
            job.cancel(CancellationException("Left the screen"))
            job.join()
            assertTrue(job.isCancelled)
            assertNull(vm.error.value)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a library that can't be read shows why, and a retry shows loading first`() = runTest {
        val failed = flow<Library> { throw IOException("Disk unavailable") }.asUiStates(reloading = false).toList()
        assertEquals(listOf<LibraryUiState>(LibraryUiState.Failed("Disk unavailable")), failed)
        val silent = flow<Library> { throw IOException() }.asUiStates(reloading = false).toList().single()
        assertTrue((silent as LibraryUiState.Failed).message.isNotBlank())

        val library = Library(memory = emptyList(), skills = emptyList(), connectors = emptyList())
        assertEquals(listOf(LibraryUiState.Loading, LibraryUiState.Ready(library)), flowOf(library).asUiStates(reloading = true).toList())
        assertEquals(listOf<LibraryUiState>(LibraryUiState.Ready(library)), flowOf(library).asUiStates(reloading = false).toList())
    }

    @Test
    fun `cancellation while reading isn't shown as a failure`() = runTest {
        assertFailsWith<CancellationException> {
            flow<Library> { throw CancellationException("gone") }.asUiStates(reloading = false).toList()
        }
    }

    @Test
    fun `retrying the read gets the library again`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            val vm = host.add(LibraryViewModel(g.library))
            vm.uiState.launchIn(backgroundScope)
            assertIs<LibraryUiState.Ready>(vm.uiState.first { it is LibraryUiState.Ready })
            g.home.saveMemory("coffee", "How the user takes coffee", "Flat white.")
            vm.retryLoad()
            val ready = vm.uiState.first { (it as? LibraryUiState.Ready)?.library?.memory?.isNotEmpty() == true } as LibraryUiState.Ready
            assertEquals("coffee", ready.library.memory.single().name)
        } finally {
            host.clear()
        }
    }
}
