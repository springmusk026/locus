package dev.locus.feature.tasks

import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ModeNote
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks' states, editing, and Run now followed from queued to how it ended. */
class TasksViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun TestScope.graph() = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.ready(vm: TasksViewModel): TasksUiState.Ready {
        advanceUntilIdle()
        return vm.uiState.first { it is TasksUiState.Ready } as TasksUiState.Ready
    }

    @Test
    fun `starts loading, then shows no tasks, then the one created`() = runTest {
        try {
            val g = graph()
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository))
            assertEquals(TasksUiState.Loading, vm.uiState.value)
            vm.uiState.launchIn(backgroundScope)
            assertEquals(emptyList(), ready(vm).tasks)

            vm.create(TaskDraft("Weather", "Will it rain?", 60, TaskConditions())).join()
            val item = ready(vm).tasks.single()
            assertEquals("Weather", item.task.title)
            assertNull(item.run)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `an edit saves and keeps a workflow's hidden plan`() = runTest {
        try {
            val g = graph()
            val note = "The user started their saved workflow “Digest”. <workflow_plan>{}</workflow_plan>"
            g.tasks.create("Digest", "Summarise the news" + ModeNote.wrap(note), 1_440)
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository))
            vm.uiState.launchIn(backgroundScope)
            val task = ready(vm).tasks.single().task

            // The sheet edits only the visible text.
            val draft = TaskDraft.of(task)
            assertEquals("Summarise the news", draft.prompt)
            vm.update(task.id, draft.copy(title = "Tech digest", prompt = "Summarise tech news", intervalMinutes = 10_080)).join()

            val edited = ready(vm).tasks.single().task
            assertEquals("Tech digest", edited.title)
            assertEquals(10_080, edited.intervalMinutes)
            assertEquals("Summarise tech news" + ModeNote.wrap(note), edited.prompt)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `run now shows queued and running, then says once how it ended, with its chat`() = runTest {
        try {
            val g = graph()
            g.tasks.create("Weather", "Will it rain?", 60)
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository))
            vm.uiState.launchIn(backgroundScope)
            val id = ready(vm).tasks.single().task.id
            val events = mutableListOf<TaskEvent>()
            backgroundScope.launchCollect(vm, events)

            vm.runNow(id)
            assertEquals(RunNowStatus.QUEUED, ready(vm).tasks.single().run)
            assertTrue(ready(vm).tasks.single().running)

            g.taskWork.setRunNow(id, RunNowStatus.RUNNING)
            assertEquals(RunNowStatus.RUNNING, ready(vm).tasks.single().run)
            // A second tap while it runs doesn't follow it twice.
            vm.runNow(id)

            g.tasks.recordRun(id, "chat-9", "No rain.", TaskRunOutcome.DONE)
            g.taskWork.setRunNow(id, RunNowStatus.DONE)
            val item = ready(vm).tasks.single()
            // A finished run isn't shown as a run in progress; the row shows the last outcome instead.
            assertNull(item.run)
            assertEquals("chat-9", item.task.lastConversationId)
            advanceUntilIdle()
            assertEquals(listOf<TaskEvent>(TaskEvent.RunFinished("Weather", RunNowStatus.DONE, "chat-9")), events)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `an old finished run isn't reported as the new one`() = runTest {
        try {
            val g = graph()
            g.tasks.create("Weather", "Will it rain?", 60)
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository))
            vm.uiState.launchIn(backgroundScope)
            val id = ready(vm).tasks.single().task.id
            // Yesterday's Run now failed, and WorkManager still has it.
            g.taskWork.setRunNow(id, RunNowStatus.FAILED)
            assertNull(ready(vm).tasks.single().run)
            val events = mutableListOf<TaskEvent>()
            backgroundScope.launchCollect(vm, events)

            vm.runNow(id)
            advanceUntilIdle()
            assertEquals(emptyList(), events)
            g.taskWork.setRunNow(id, RunNowStatus.RUNNING)
            g.taskWork.setRunNow(id, RunNowStatus.SKIPPED)
            advanceUntilIdle()
            assertEquals(RunNowStatus.SKIPPED, (events.single() as TaskEvent.RunFinished).status)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `pause, resume and delete reach the schedule`() = runTest {
        try {
            val g = graph()
            g.tasks.create("Weather", "Will it rain?", 60)
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository))
            vm.uiState.launchIn(backgroundScope)
            val id = ready(vm).tasks.single().task.id

            vm.setEnabled(id, false).join()
            assertFalse(ready(vm).tasks.single().task.enabled)
            assertFalse(id in g.taskWork.scheduled)
            vm.setEnabled(id, true).join()
            assertTrue(id in g.taskWork.scheduled)
            vm.delete(id).join()
            assertEquals(emptyList(), ready(vm).tasks)
            assertFalse(id in g.taskWork.scheduled)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a task list that can't be read shows an error, and retry reads it again`() = runTest {
        try {
            val g = graph()
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository))
            vm.uiState.launchIn(backgroundScope)
            g.database.failTaskReads = true
            vm.retry()
            advanceUntilIdle()
            assertIs<TasksUiState.Error>(vm.uiState.value)

            g.database.failTaskReads = false
            vm.retry()
            assertEquals(emptyList(), ready(vm).tasks)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a row's line says the schedule and how the last run went`() {
        val task = ScheduledTask("t", "Weather", "p", 1_440, enabled = true, lastRunAt = null, lastSummary = null)
        val at: (Long) -> String = { "Today" }
        assertEquals("Daily · not run yet", taskStatusLine(task, at))
        assertEquals("Daily · ran today", taskStatusLine(task.copy(lastRunAt = 1), at))
        assertEquals("Daily · last run failed today", taskStatusLine(task.copy(lastRunAt = 1, lastOutcome = TaskRunOutcome.FAILED), at))
        assertEquals("Every 45 min · paused", taskStatusLine(task.copy(intervalMinutes = 45, enabled = false), at))
        assertEquals("Queued: starts when there's a connection", runNowLine(RunNowStatus.QUEUED))
        assertEquals("Running now", runNowLine(RunNowStatus.RUNNING))
    }

    @Test
    fun `a finished run's message and tone`() {
        assertEquals("“W” ran." to Tone.Live, runFinishedMessage(TaskEvent.RunFinished("W", RunNowStatus.DONE, "c")))
        assertEquals(Tone.Caution, runFinishedMessage(TaskEvent.RunFinished("W", RunNowStatus.SKIPPED, "c")).second)
        assertEquals(Tone.Critical, runFinishedMessage(TaskEvent.RunFinished("W", RunNowStatus.FAILED, "c")).second)
    }

    @Test
    fun `a draft needs a name and a prompt`() {
        assertFalse(TaskDraft().valid)
        assertFalse(TaskDraft(title = "W").valid)
        assertTrue(TaskDraft(title = "W", prompt = "p").valid)
        assertEquals(1_440, TaskDraft().intervalMinutes)
    }

    private fun CoroutineScope.launchCollect(vm: TasksViewModel, into: MutableList<TaskEvent>) =
        launch(main.dispatcher) { vm.eventFlow.collect { into += it } }
}
