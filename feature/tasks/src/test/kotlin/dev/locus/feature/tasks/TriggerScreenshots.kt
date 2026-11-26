package dev.locus.feature.tasks

import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.data.tasks.triggers.TriggerState
import dev.locus.core.model.QuietHours
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.TriggerEventRecord
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Step
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

/** Event triggers in Tasks: rows in every source state, the When… sheet per source, the test sheet and the history. */
class TriggerScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    /**
     * Tasks list newest first by creation time. Each task is stamped a millisecond after the one before,
     * not left to the clock (a fast run makes several in the same millisecond), so their order is the same every run.
     */
    private var lastCreated = System.currentTimeMillis()

    private suspend fun TestGraph.inOrder(id: String): String {
        database.scheduledTaskDao.upsert(database.scheduledTaskDao.get(id)!!.copy(createdAt = ++lastCreated))
        return id
    }

    private fun vm(g: TestGraph, calendar: FakeCalendarReader = FakeCalendarReader()) = TasksViewModel(g.tasks, g.settingsRepository, g.secretResolver, calendar, g.reminders, g.io)

    private fun TasksViewModel.ready(until: (TasksUiState) -> Boolean): TasksUiState =
        runBlocking { withTimeout(10_000) { uiState.first(until) } }

    private val ntfy = TaskTrigger(TriggerSource.NTFY, topicUrl = "https://ntfy.sh/homelab_alerts_7f3k", contains = "backup", minPriority = 4)

    @Test
    fun rows() {
        val g = TestGraph(scope)
        runBlocking {
            val standup = g.inOrder(g.tasks.create("Standup prep", "Before standup, list what I did yesterday from my notes.", 60, trigger = TaskTrigger(TriggerSource.CALENDAR, contains = "standup")))
            g.tasks.setTriggerState(standup, TriggerState(health = TriggerHealth.NO_PERMISSION, message = "Locus can't read the calendar. Open the task and allow calendar access."))
            val backups = g.inOrder(g.tasks.create("Backup alerts", "Say what failed and the likely cause.", 60, trigger = ntfy))
            g.tasks.setTriggerState(backups, TriggerState(health = TriggerHealth.OFFLINE, message = "Couldn't reach ntfy.sh: no connection.", checkedAt = System.currentTimeMillis() - 600_000))
            val invoices = g.inOrder(g.tasks.create("Invoices", "Summarise the invoice and when it's due.", 60, trigger = TaskTrigger(TriggerSource.MAIL, from = "@billing.example")))
            g.tasks.setTriggerState(invoices, TriggerState(health = TriggerHealth.OK, checkedAt = System.currentTimeMillis() - 120_000))
            g.tasks.recordRun(invoices, "c1", "Hetzner invoice: €8.21, due 20 Nov.", TaskRunOutcome.DONE)
            val home = g.inOrder(g.tasks.create("Left home", "Remind me what's left today.", 60, trigger = TaskTrigger(TriggerSource.APP, eventName = "left-home")))
            g.taskWork.setEventRun(home, RunNowStatus.RUNNING)
            val paused = g.inOrder(g.tasks.create("Release notes", "Draft notes for the release mail.", 60, trigger = TaskTrigger(TriggerSource.APP, eventName = "release")))
            g.tasks.setEnabled(paused, false)
            g.inOrder(g.tasks.create("Morning weather", "Will it rain in Kathmandu today?", 1_440))
        }
        val vm = vm(g)
        vm.ready { it is TasksUiState.Ready && it.tasks.size == 6 && it.tasks.any { t -> t.active != null } }
        snap("tasks-triggers", heightDp = 1100) { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
        snapAfter("tasks-trigger-opened", steps = listOf(Step.Tap("Backup alerts")), heightDp = 1400) { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
        snapAfter("tasks-trigger-no-permission", steps = listOf(Step.Tap("Standup prep")), heightDp = 1400) { TasksRoute(onBack = {}, onOpenChat = {}, viewModel = vm) }
    }

    /** A new daily task starting at 07:30: the row, the next runs in the phone's time zone, and the picker. */
    @Test
    fun startingAt() {
        val g = TestGraph(scope)
        val now = java.time.ZonedDateTime.of(2026, 11, 26, 9, 15, 0, 0, java.time.ZoneId.of("Asia/Kathmandu"))
        val vm = vm(g).also { it.clock = { now.toInstant().toEpochMilli() }; it.zone = { now.zone } }
        val draft = TaskDraft(title = "Morning weather", prompt = "Will it rain in Kathmandu today? Say if I need an umbrella.", intervalMinutes = 1_440, firstRunAt = 7 * 60 + 30)
        snap("task-sheet-starting-at", heightDp = 1600) { TaskSheet(draft, null, vm, onDismiss = {}, onSave = {}) }
        snapAfter("task-sheet-starting-at-picker", listOf(Step.Tap("Starting at")), heightDp = 1600) { TaskSheet(draft, null, vm, onDismiss = {}, onSave = {}) }
    }

    private fun sheet(name: String, draft: TaskDraft, calendar: FakeCalendarReader = FakeCalendarReader(), heightDp: Int = 1800, editing: String? = null) {
        val g = TestGraph(scope)
        val vm = vm(g, calendar)
        runBlocking { withTimeout(10_000) { vm.setup.first { it.calendarAllowed == calendar.allowed && (!calendar.allowed || it.calendars.isNotEmpty()) } } }
        snap(name, heightDp = heightDp) { TaskSheet(draft, editing, vm, onDismiss = {}, onSave = {}) }
    }

    private val base = TaskDraft("Standup prep", "Before standup, list what I did yesterday from my notes.", mode = StartMode.EVENT)

    private val backups = TaskDraft("Backup alerts", "Say what failed and the likely cause, from the message and the server's logs.", mode = StartMode.EVENT)

    @Test
    fun calendarNoPermission() = sheet("task-sheet-calendar-no-permission", base.copy(trigger = TaskTrigger(TriggerSource.CALENDAR, contains = "standup")))

    @Test
    fun calendarAllowed() = sheet(
        "task-sheet-calendar",
        base.copy(trigger = TaskTrigger(TriggerSource.CALENDAR, contains = "standup", calendar = "Work", minutesBefore = 10), quietOn = true),
        FakeCalendarReader(allowed = true, names = listOf("Work", "Family", "Holidays in Nepal")),
    )

    @Test
    fun ntfySheet() = sheet("task-sheet-ntfy", backups.copy(trigger = ntfy, hasToken = true), editing = "Backup alerts")

    @Test
    fun ntfyInvalid() = sheet("task-sheet-ntfy-invalid", backups.copy(trigger = TaskTrigger(TriggerSource.NTFY, topicUrl = "ntfy.sh/x")))

    @Test
    fun mailNotSetUp() = sheet(
        "task-sheet-mail",
        base.copy(title = "Invoices", prompt = "Summarise the invoice: who it's from, the amount and when it's due.", trigger = TaskTrigger(TriggerSource.MAIL, from = "@billing.example", contains = "invoice")),
    )

    @Test
    fun appSheet() = sheet(
        "task-sheet-app",
        base.copy(title = "Left home", prompt = "Remind me what's left on today's list and anything I should pick up on the way.", trigger = TaskTrigger(TriggerSource.APP, eventName = "left-home", minGapMinutes = 60), apps = "net.dinglisch.android.taskerm"),
    )

    @Test
    fun digest() = sheet("task-sheet-digest", TaskDraft.morningDigest(TaskConditions()), heightDp = 1400)

    @Test
    fun testSheet() {
        val task = dev.locus.core.model.ScheduledTask("t", "Backup alerts", "p", 60, true, null, null, trigger = ntfy)
        snap("task-sheet-test-event", heightDp = 700) { TestEventSheet(task, onDismiss = {}, onSend = {}) }
    }

    @Test
    fun history() {
        val task = dev.locus.core.model.ScheduledTask("t", "Backup alerts", "p", 60, true, null, null, trigger = ntfy.copy(quietHours = QuietHours()))
        val now = System.currentTimeMillis()
        fun e(i: Int, title: String, decision: TriggerDecision, detail: String?, outcome: TaskRunOutcome? = null, chat: String? = null) =
            TriggerEventRecord("e$i", "t", TriggerSource.NTFY, now - i * 900_000L, title, decision, detail, chat, outcome)
        val events = listOf(
            e(0, "Backup failed on homelab", TriggerDecision.FIRED, null, chat = "c0"),
            e(1, "Backup failed on homelab", TriggerDecision.RATE_LIMITED, "At most one run every 15 minutes; the next can start in 4 min."),
            e(2, "Backup failed: disk full", TriggerDecision.FIRED, "Disk on homelab is 98% full; old snapshots weren't pruned.", TaskRunOutcome.DONE, "c2"),
            e(3, "3 ntfy messages", TriggerDecision.NO_MATCH, "Its priority is 3, below 4."),
            e(4, "Backup failed", TriggerDecision.QUIET_HOURS, "Quiet from 22:00–07:00."),
            e(5, "Backup failed", TriggerDecision.TEST, "Failed: the provider refused the request.", TaskRunOutcome.FAILED, "c5"),
            e(6, "Backup failed", TriggerDecision.BUSY, "Another run of this task was still going."),
        )
        snap("task-sheet-history", heightDp = 1400) { HistorySheet(task, events, onDismiss = {}, onOpenChat = {}) }
        snap("task-sheet-history-empty", heightDp = 600) { HistorySheet(task, emptyList(), onDismiss = {}, onOpenChat = {}) }
    }
}
