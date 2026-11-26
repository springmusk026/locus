package dev.locus.feature.tasks

import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.recreateAfter
import dev.locus.core.testing.FakeCalendarReader
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/** A half-written task survives a rotation: the editor stays open with its name and prompt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskSheetRotationTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the task editor keeps its draft when the phone turns`() {
        val g = TestGraph(scope)
        val vm = TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io)
        runBlocking { withTimeout(10_000) { vm.uiState.first { it is TasksUiState.Ready } } }
        val prompt = "Every morning, read today's calendar and list what needs preparing, with the times."
        val (before, after) = recreateAfter(listOf(Step.Tap("New task"), Step.Type("Morning prep", 0), Step.Type(prompt, 1))) {
            TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm)
        }
        assertTrue(before.shows(prompt), before.texts.toString())
        assertTrue(after.shows(prompt), after.texts.toString())
        assertTrue(after.shows("Morning prep"), after.texts.toString())
        // The new "Starting at" row is there for a new scheduled task.
        assertTrue(after.shows("Starting at"), after.texts.toString())
    }
}
