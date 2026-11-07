package dev.locus.feature.tasks

import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ModeNote
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.QuietHours
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource
import dev.locus.core.testing.FakeCalendarReader
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
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
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
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
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
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
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
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
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
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
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
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
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

    @Test
    fun `a trigger draft saves an event task, keeps its token out of the row, and reads back for editing`() = runTest {
        try {
            val g = graph()
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
            vm.uiState.launchIn(backgroundScope)
            val draft = TaskDraft(
                "Backups", "Tell me what failed.", mode = StartMode.EVENT,
                trigger = TaskTrigger(TriggerSource.NTFY, topicUrl = "https://ntfy.sh/backups_4f9a", contains = "failed"),
                quietOn = true, quietFrom = "23:00", quietTo = "6:30", token = "tk_secret",
            )
            assertTrue(draft.valid)
            vm.create(draft).join()
            val task = ready(vm).tasks.single().task
            assertEquals(TriggerSource.NTFY, task.trigger?.source)
            assertEquals(QuietHours(23 * 60, 6 * 60 + 30), task.trigger?.quietHours)
            assertTrue(task.id in g.taskWork.watched)
            assertFalse("tk_secret" in g.database.scheduledTaskDao.get(task.id)!!.toString())

            val back = vm.draftOf(task)
            assertEquals(StartMode.EVENT, back.mode)
            assertTrue(back.hasToken)
            assertEquals("", back.token)
            assertEquals("23:00", back.quietFrom)
            // Saving without typing a token keeps the saved one.
            assertNull(back.tokenToSave)
            assertEquals("", back.copy(clearToken = true).tokenToSave)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `a trigger draft says what stops it saving`() {
        val base = TaskDraft("N", "P", mode = StartMode.EVENT, trigger = TaskTrigger(TriggerSource.NTFY))
        assertEquals("Enter the topic's address, like https://ntfy.sh/a-long-secret-name.", base.problem)
        assertFalse(base.valid)
        val quiet = base.copy(trigger = base.trigger.copy(topicUrl = "https://ntfy.sh/t"), quietOn = true, quietFrom = "late")
        assertEquals("Quiet hours need two times, like 22:00 and 07:00.", quiet.problem)
        // On a schedule, the trigger's fields don't matter.
        assertTrue(base.copy(mode = StartMode.SCHEDULE).valid)
        assertNull(base.copy(mode = StartMode.SCHEDULE).toTrigger)
        val apps = TaskDraft("N", "P", mode = StartMode.EVENT, trigger = TaskTrigger(TriggerSource.APP), apps = "net.dinglisch.android.taskerm, com.llamalab.automate")
        assertEquals(listOf("net.dinglisch.android.taskerm", "com.llamalab.automate"), apps.toTrigger?.allowedApps)
    }

    @Test
    fun `a test event says whether it matched`() = runTest {
        try {
            val g = graph()
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io))
            val events = mutableListOf<TaskEvent>()
            backgroundScope.launchCollect(vm, events)
            val id = g.tasks.create("Standup prep", "Prepare me.", 60, trigger = TaskTrigger(TriggerSource.CALENDAR, contains = "standup"))
            vm.sendTestEvent(id, "Standup prep", "Lunch").join()
            vm.sendTestEvent(id, "Standup prep", "Team standup").join()
            advanceUntilIdle()
            val (miss, hit) = events.filterIsInstance<TaskEvent.TestSent>()
            assertEquals("The test didn't match “Standup prep”: it doesn't mention “standup”." to Tone.Caution, testSentMessage(miss))
            assertEquals("The test matched: “Standup prep” is starting." to Tone.Live, testSentMessage(hit))
            assertTrue(g.taskWork.fired.single().test)
        } finally {
            host.clear()
        }
    }

    @Test
    fun `the morning digest runs daily from 7 and asks for the calendar only when it's off`() = runTest {
        try {
            val g = graph()
            val calendar = FakeCalendarReader(allowed = false)
            val vm = host.add(TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, calendar, g.reminders, g.io))
            advanceUntilIdle()
            assertFalse(vm.setup.value.calendarAllowed)
            calendar.allowed = true
            calendar.names = listOf("Work")
            vm.refreshSetup()
            // The check reads the phone off the main thread.
            assertEquals(TasksViewModel.Setup(calendarAllowed = true, calendars = listOf("Work")), vm.setup.first { it.calendarAllowed })

            vm.zone = { java.time.ZoneOffset.UTC }
            val digest = TaskDraft.morningDigest(TaskConditions())
            assertEquals(1_440, digest.intervalMinutes)
            assertNull(digest.toTrigger)
            vm.create(digest).join()
            val id = g.taskWork.scheduled.keys.single()
            val delay = g.taskWork.initialDelays.getValue(id)
            assertTrue(delay in 1..86_400_000, "$delay")
        } finally {
            host.clear()
        }
    }

    @Test
    fun `an event task's line says its source and its problems`() {
        val task = ScheduledTask("t", "Backups", "p", 60, enabled = true, lastRunAt = null, lastSummary = null, trigger = TaskTrigger(TriggerSource.CALENDAR))
        val at: (Long) -> String = { "Today" }
        assertEquals("Calendar · not checked yet", taskStatusLine(task, at))
        assertEquals("Calendar · no access", taskStatusLine(task.copy(triggerHealth = TriggerHealth.NO_PERMISSION), at))
        assertTrue(triggerHasProblem(task.copy(triggerHealth = TriggerHealth.OFFLINE)))
        assertEquals("Calendar · checked today", taskStatusLine(task.copy(triggerHealth = TriggerHealth.OK, triggerCheckedAt = 1), at))
        assertEquals("Calendar · paused", taskStatusLine(task.copy(enabled = false), at))
        assertEquals("Running for an event", runNowLine(RunNowStatus.RUNNING, fromEvent = true))
        assertEquals(9 * 3_600_000L, delayUntil(7 * 60, java.time.ZonedDateTime.of(2026, 11, 5, 22, 0, 0, 0, java.time.ZoneOffset.UTC)))
    }

    private fun CoroutineScope.launchCollect(vm: TasksViewModel, into: MutableList<TaskEvent>) =
        launch(main.dispatcher) { vm.eventFlow.collect { into += it } }
}
