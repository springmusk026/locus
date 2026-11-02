package dev.locus.core.data.tasks

import android.app.PendingIntent
import androidx.work.WorkInfo
import dev.locus.core.model.ModeNote
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.testing.FakeTaskWork
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks as the app sees them (the last run's chat included), editing, and how Run now and its notification are followed. */
class TaskRepositoryTest {
    @Test
    fun `a task carries its last run's chat and outcome`() = runTest {
        val g = TestGraph(backgroundScope)
        g.tasks.create("Weather", "Will it rain?", 60)
        val id = g.tasks.tasks.first().single().id
        assertNull(g.tasks.tasks.first().single().lastConversationId)

        g.tasks.recordRun(id, "chat-7", "No rain.", TaskRunOutcome.FAILED)
        val task = g.tasks.tasks.first().single()
        assertEquals("chat-7", task.lastConversationId)
        assertEquals(TaskRunOutcome.FAILED, task.lastOutcome)
        assertEquals("No rain.", task.lastSummary)
    }

    @Test
    fun `editing changes name, prompt, period and conditions, and reschedules`() = runTest {
        val g = TestGraph(backgroundScope)
        g.tasks.create("Weather", "Will it rain?", 60)
        val id = g.tasks.tasks.first().single().id
        val conditions = TaskConditions(requiresCharging = true, unmeteredOnly = true, batteryNotLow = false)

        assertTrue(g.tasks.update(id, " Forecast ", " Will it snow? ", 1_440, conditions))
        val task = g.tasks.tasks.first().single()
        assertEquals("Forecast", task.title)
        assertEquals("Will it snow?", task.prompt)
        assertEquals(1_440, task.intervalMinutes)
        assertEquals(conditions, task.conditions)
        assertEquals(FakeTaskWork.Scheduled(1_440, conditions), g.taskWork.scheduled[id])
    }

    @Test
    fun `editing a paused task doesn't schedule it, and a deleted one reports it's gone`() = runTest {
        val g = TestGraph(backgroundScope)
        g.tasks.create("Weather", "Will it rain?", 60)
        val id = g.tasks.tasks.first().single().id
        g.tasks.setEnabled(id, false)
        assertFalse(id in g.taskWork.scheduled)

        assertTrue(g.tasks.update(id, "Weather", "Will it rain?", 10_080, TaskConditions()))
        assertFalse(id in g.taskWork.scheduled)
        g.tasks.delete(id)
        assertFalse(g.tasks.update(id, "Weather", "x", 60, TaskConditions()))
    }

    @Test
    fun `editing a scheduled workflow keeps its plan`() = runTest {
        val g = TestGraph(backgroundScope)
        val note = "The user started their saved workflow “Digest”. <workflow_plan>{}</workflow_plan>"
        g.tasks.create("Digest", "Summarise the news" + ModeNote.wrap(note), 1_440)
        val id = g.tasks.tasks.first().single().id

        g.tasks.update(id, "Digest", "Summarise tech news", 1_440, TaskConditions())
        assertEquals("Summarise tech news" + ModeNote.wrap(note), g.tasks.tasks.first().single().prompt)
    }

    @Test
    fun `a period below WorkManager's minimum is raised to 15 minutes`() = runTest {
        val g = TestGraph(backgroundScope)
        g.tasks.create("Weather", "Will it rain?", 60)
        val id = g.tasks.tasks.first().single().id
        g.tasks.update(id, "Weather", "Will it rain?", 5, TaskConditions())
        assertEquals(15, g.tasks.tasks.first().single().intervalMinutes)
    }

    @Test
    fun `run now's work states map to what Tasks shows`() {
        assertNull(runNowStatus(null, null))
        assertEquals(RunNowStatus.QUEUED, runNowStatus(WorkInfo.State.ENQUEUED, null))
        assertEquals(RunNowStatus.QUEUED, runNowStatus(WorkInfo.State.BLOCKED, null))
        assertEquals(RunNowStatus.RUNNING, runNowStatus(WorkInfo.State.RUNNING, null))
        assertEquals(RunNowStatus.DONE, runNowStatus(WorkInfo.State.SUCCEEDED, ScheduledRun.Result.DONE.name))
        // Run before this version wrote a result: it ran.
        assertEquals(RunNowStatus.DONE, runNowStatus(WorkInfo.State.SUCCEEDED, null))
        assertEquals(RunNowStatus.SKIPPED, runNowStatus(WorkInfo.State.SUCCEEDED, ScheduledRun.Result.SKIPPED.name))
        assertEquals(RunNowStatus.FAILED, runNowStatus(WorkInfo.State.FAILED, ScheduledRun.Result.FAILED.name))
        assertEquals(RunNowStatus.FAILED, runNowStatus(WorkInfo.State.CANCELLED, null))
        assertTrue(RunNowStatus.QUEUED.active && RunNowStatus.RUNNING.active)
        assertFalse(RunNowStatus.DONE.active || RunNowStatus.SKIPPED.active || RunNowStatus.FAILED.active)
    }

    @Test
    fun `a result notification opens its run's chat, with one immutable intent per task`() {
        val a = TaskResultLink("task-a", "chat-1")
        val b = TaskResultLink("task-b", "chat-1")
        assertEquals("chat-1", a.chatId)
        // Each task has its own request code and notification; a later run of the same task reuses them.
        assertNotEquals(a.requestCode, b.requestCode)
        assertNotEquals(a.notificationId, b.notificationId)
        assertEquals(a.requestCode, TaskResultLink("task-a", "chat-2").requestCode)
        assertEquals(a.notificationId, TaskResultLink("task-a", "chat-2").notificationId)
        // Not the codes AgentService uses for a chat's own notifications (the chat id's hash).
        assertNotEquals("chat-1".hashCode(), a.requestCode)
        // Immutable (Android 12+), and the newest run's chat replaces the older one's extras.
        assertEquals(PendingIntent.FLAG_IMMUTABLE, a.flags and PendingIntent.FLAG_IMMUTABLE)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, a.flags and PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
