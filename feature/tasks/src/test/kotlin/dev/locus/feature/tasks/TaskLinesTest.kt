package dev.locus.feature.tasks

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.data.tasks.RunNowStatus
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.TaskTrigger
import dev.locus.core.model.TriggerDecision
import dev.locus.core.model.TriggerHealth
import dev.locus.core.model.TriggerSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A task row's status lines and the Run now and test messages, in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskLinesTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun taskStatusLine(task: ScheduledTask, now: (Long) -> String) = dev.locus.feature.tasks.taskStatusLine(task, now).asString(res)
    private fun runNowLine(status: RunNowStatus, fromEvent: Boolean = false) = dev.locus.feature.tasks.runNowLine(status, fromEvent).asString(res)
    private fun runFinishedMessage(event: TaskEvent.RunFinished) = dev.locus.feature.tasks.runFinishedMessage(event).let { (text, tone) -> text.asString(res) to tone }
    private fun testSentMessage(event: TaskEvent.TestSent) = dev.locus.feature.tasks.testSentMessage(event).let { (text, tone) -> text.asString(res) to tone }

    @Test
    fun `a row's line says the schedule and how the last run went`() {
        val task = ScheduledTask("t", "Weather", "p", 1_440, enabled = true, lastRunAt = null, lastSummary = null)
        val at: (Long) -> String = { "Today" }
        assertEquals("Daily · not run yet", taskStatusLine(task, at))
        assertEquals("Daily · ran today", taskStatusLine(task.copy(lastRunAt = 1), at))
        assertEquals("Daily · last run failed today", taskStatusLine(task.copy(lastRunAt = 1, lastOutcome = TaskRunOutcome.FAILED), at))
        assertEquals("Daily · last run stopped", taskStatusLine(task.copy(lastOutcome = TaskRunOutcome.STOPPED), at))
        assertEquals("Every 45 min · paused", taskStatusLine(task.copy(intervalMinutes = 45, enabled = false), at))
        assertEquals("Queued: starts when there's a connection", runNowLine(RunNowStatus.QUEUED))
        assertEquals("Running now", runNowLine(RunNowStatus.RUNNING))
        assertEquals("Event run queued", runNowLine(RunNowStatus.QUEUED, fromEvent = true))
        assertEquals("Last run failed", lastRunProblem(task.copy(lastOutcome = TaskRunOutcome.FAILED))?.asString(res))
        assertNull(lastRunProblem(task.copy(lastOutcome = TaskRunOutcome.DONE)))
    }

    @Test
    fun `a finished run's message and tone`() {
        assertEquals("“W” ran." to Tone.Live, runFinishedMessage(TaskEvent.RunFinished("W", RunNowStatus.DONE, "c")))
        assertEquals("“W” was already running, so it didn't run again." to Tone.Caution, runFinishedMessage(TaskEvent.RunFinished("W", RunNowStatus.SKIPPED, "c")))
        assertEquals("“W” didn't finish." to Tone.Critical, runFinishedMessage(TaskEvent.RunFinished("W", RunNowStatus.FAILED, "c")))
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
        assertEquals("Calendar · last run failed", taskStatusLine(task.copy(lastRunAt = 1, lastOutcome = TaskRunOutcome.FAILED), at))
        assertEquals("App · waiting for an event", taskStatusLine(task.copy(trigger = TaskTrigger(TriggerSource.APP)), at))
        assertEquals("Running for an event", runNowLine(RunNowStatus.RUNNING, fromEvent = true))
    }

    @Test
    fun `a test event's message says whether it matched`() {
        assertEquals(
            "The test didn't match “Standup prep”: it doesn't mention “standup”." to Tone.Caution,
            testSentMessage(TaskEvent.TestSent("Standup prep", null, "it doesn't mention “standup”")),
        )
        assertEquals("The test didn't match “Standup prep”." to Tone.Caution, testSentMessage(TaskEvent.TestSent("Standup prep", null, null)))
        assertEquals("The test matched: “Standup prep” is starting." to Tone.Live, testSentMessage(TaskEvent.TestSent("Standup prep", TriggerDecision.TEST, null)))
        assertEquals(Tone.Critical, testSentMessage(TaskEvent.TestSent("Standup prep", TriggerDecision.FAILED, null)).second)
        assertEquals("The test was recorded: didn't match." to Tone.Caution, testSentMessage(TaskEvent.TestSent("Standup prep", TriggerDecision.NO_MATCH, null)))
        assertEquals("The test was recorded: quiet hours." to Tone.Caution, testSentMessage(TaskEvent.TestSent("Standup prep", TriggerDecision.QUIET_HOURS, null)))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish the schedule and the decision are Spanish too`() {
        val task = ScheduledTask("t", "Weather", "p", 1_440, enabled = false, lastRunAt = null, lastSummary = null)
        assertEquals("A diario · en pausa", taskStatusLine(task) { "Hoy" })
        assertEquals("Cada 2 días · en pausa", taskStatusLine(task.copy(intervalMinutes = 2_880)) { "Hoy" })
        assertEquals("La prueba se registró: no coincidió.", testSentMessage(TaskEvent.TestSent("W", TriggerDecision.NO_MATCH, null)).first)
    }

    @Test
    fun `the test sheet names one event of each source`() {
        assertEquals(
            listOf("calendar entry", "ntfy message", "mail", "event from another app"),
            TriggerSource.entries.map { res.getString(it.nounRes()) },
        )
        // Matches core's English nouns, which the subtitle used before.
        assertEquals(TriggerSource.entries.map { it.noun }, TriggerSource.entries.map { res.getString(it.nounRes()) })
    }
}
