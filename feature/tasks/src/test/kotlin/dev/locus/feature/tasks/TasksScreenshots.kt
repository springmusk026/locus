package dev.locus.feature.tasks

import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.data.tasks.TaskRepository
import dev.locus.core.database.ScheduledTaskDao
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.testing.FakeCalendarReader
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Scheduled tasks over the real view model: loading, error, empty and a typical list. */
class TasksScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    /** Tasks list newest first by creation time: a millisecond apart, so their order is the same every run. */
    private fun tick() = Thread.sleep(3)

    private fun TasksViewModel.ready(until: (TasksUiState) -> Boolean): TasksUiState =
        runBlocking { withTimeout(10_000) { uiState.first(until) } }

    @Test
    fun populated() {
        val g = TestGraph(scope)
        runBlocking {
            g.tasks.create("Morning weather", "Every morning, check the weather in Kathmandu and tell me if I need an umbrella.", 1_440)
            tick()
            g.tasks.create("Hacker News digest", "Summarise the five most discussed Hacker News stories about Kotlin or Android.", 720, TaskConditions(unmeteredOnly = true))
            tick()
            g.tasks.create("Flight price watch", "Check the cheapest KTM → LIS fare for 12–19 March and tell me if it drops below €700.", 360, TaskConditions(requiresCharging = true))
            tick()
            g.tasks.create("Server disk check", "Check free disk space on homelab over SSH and warn me below 10%.", 60)
            val tasks = g.tasks.tasks.first().associateBy { it.title }
            g.tasks.recordRun(tasks.getValue("Morning weather").id, "run-1", "Light rain after 3 pm: take an umbrella.", TaskRunOutcome.DONE)
            g.tasks.recordRun(tasks.getValue("Flight price watch").id, "run-2", "Skyscanner blocked the request.", TaskRunOutcome.FAILED)
            g.tasks.setEnabled(tasks.getValue("Server disk check").id, false)
            g.taskWork.setRunNow(tasks.getValue("Hacker News digest").id, RunNowStatus.RUNNING)
        }
        val vm = TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io)
        vm.ready { it is TasksUiState.Ready && it.tasks.size == 4 && it.tasks.any { t -> t.running } }
        snap("tasks", heightDp = 1000) { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }

    /** Reminders from remind_me above the tasks: two to come (one snoozed) and one shown, each with its action. */
    @Test
    fun reminders() {
        val g = TestGraph(scope)
        val now = java.time.LocalDateTime.of(2026, 11, 6, 9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        g.reminders.clock = { now }
        runBlocking {
            g.tasks.create("Morning weather", "Every morning, check the weather in Kathmandu and tell me if I need an umbrella.", 1_440)
            val call = g.reminders.add("Call mum about Sunday lunch", now + 2 * 3_600_000L)
            g.reminders.add("Take the bins out", now + 26 * 3_600_000L)
            g.reminders.add("Renew the car insurance before it lapses on the 15th", now + 5 * 24 * 3_600_000L)
            g.reminders.add("Stretch", now + 60_000L).also { g.reminders.clock = { now + 60_000L }; g.reminders.fire(it.id); g.reminders.clock = { now } }
            g.reminders.snooze(call.id)
        }
        val vm = TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io).also { it.clock = { now } }
        vm.ready { it is TasksUiState.Ready && it.tasks.size == 1 }
        runBlocking { withTimeout(10_000) { vm.reminders.first { it.size == 4 } } }
        snap("tasks-reminders", heightDp = 1000) { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }

    /** Only reminders, no tasks yet: the list rather than the empty state, with New task under it. */
    @Test
    fun remindersOnly() {
        val g = TestGraph(scope)
        val now = java.time.LocalDateTime.of(2026, 11, 6, 9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        g.reminders.clock = { now }
        runBlocking { g.reminders.add("Pick up the dry cleaning", now + 45 * 60_000L) }
        val vm = TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io).also { it.clock = { now } }
        vm.ready { it is TasksUiState.Ready }
        runBlocking { withTimeout(10_000) { vm.reminders.first { it.size == 1 } } }
        snap("tasks-reminders-only") { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }

    @Test
    fun allPaused() {
        val g = TestGraph(scope)
        runBlocking {
            g.tasks.create("Morning weather", "Every morning, check the weather in Kathmandu and tell me if I need an umbrella.", 1_440)
            g.tasks.setEnabled(g.tasks.tasks.first().single().id, false)
        }
        val vm = TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io)
        vm.ready { it is TasksUiState.Ready && it.tasks.singleOrNull()?.task?.enabled == false }
        snap("tasks-all-paused") { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }

    @Test
    fun empty() {
        val g = TestGraph(scope)
        val vm = TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io)
        vm.ready { it is TasksUiState.Ready }
        snap("tasks-empty") { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }

    @Test
    fun loading() {
        val g = TestGraph(scope)
        val never = object : ScheduledTaskDao by g.database.scheduledTaskDao {
            override fun observeAll() = flow<List<dev.locus.core.database.ScheduledTaskEntity>> { awaitCancellation() }
        }
        val vm = TasksViewModel(TaskRepository(never, g.database.triggerEventDao, g.taskWork, g.secrets), g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io)
        snap("tasks-loading") { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }

    @Test
    fun error() {
        val g = TestGraph(scope)
        val broken = object : ScheduledTaskDao by g.database.scheduledTaskDao {
            override fun observeAll() = flow<List<dev.locus.core.database.ScheduledTaskEntity>> { throw IOException("database disk image is malformed") }
        }
        val vm = TasksViewModel(TaskRepository(broken, g.database.triggerEventDao, g.taskWork, g.secrets), g.settingsRepository, g.secretResolver, FakeCalendarReader(), g.reminders, g.io)
        vm.ready { it is TasksUiState.Error }
        snap("tasks-error") { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }
}
