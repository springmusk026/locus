package dev.locus.widget

import android.app.Application
import android.content.res.Resources
import dev.locus.core.data.widget.TaskBudgetFigures
import dev.locus.core.data.widget.UsageFigure
import dev.locus.core.data.widget.UsageNote
import dev.locus.core.data.widget.WidgetNext
import dev.locus.core.data.widget.WidgetRun
import dev.locus.core.data.widget.WidgetRunResult
import dev.locus.core.data.widget.WidgetSays
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The widgets' words (docs/design/android-bridge.md): in English exactly as core/data wrote them before
 * they moved into string resources, and in the app's language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WidgetWordsTest {
    private val res: Resources get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `the status line reads as before`() {
        assertEquals("Idle", WidgetWords.status(res, WidgetSays.Idle))
        assertEquals("Working: running a command", WidgetWords.status(res, WidgetSays.Working("run_command")))
        assertEquals("Working: reading a page", WidgetWords.status(res, WidgetSays.Working("web_read")))
        assertEquals("Working", WidgetWords.status(res, WidgetSays.Working(null)))
        assertEquals("1 approval waiting", WidgetWords.status(res, WidgetSays.Waiting(1, 0)))
        assertEquals("2 approvals waiting", WidgetWords.status(res, WidgetSays.Waiting(2, 0)))
        assertEquals("1 approval, 1 question waiting", WidgetWords.status(res, WidgetSays.Waiting(1, 1)))
        assertEquals("1 question waiting", WidgetWords.status(res, WidgetSays.Waiting(0, 1)))
        assertEquals("Paused at the task budget", WidgetWords.status(res, WidgetSays.BudgetPaused))
        assertEquals("Working: 2 helpers", WidgetWords.status(res, WidgetSays.Helpers(2)))
        assertEquals("Working: 1 helper", WidgetWords.status(res, WidgetSays.Helpers(1)))
        assertEquals("Running a scheduled task", WidgetWords.status(res, WidgetSays.Scheduled))
    }

    @Test
    fun `the latest and next runs`() {
        val run = WidgetRun("Morning digest", WidgetRunResult.DONE, "Good morning.\nThree things today.", 1)
        assertEquals("Morning digest · 7:02", WidgetWords.runLabel(res, run, "7:02"))
        assertEquals("Good morning.\nThree things today.", WidgetWords.runText(res, run))
        assertEquals("Done", WidgetWords.runText(res, run.copy(text = null)))
        assertEquals("Failed", WidgetWords.runText(res, run.copy(result = WidgetRunResult.FAILED, text = null)))
        assertEquals("Stopped", WidgetWords.runText(res, run.copy(result = WidgetRunResult.STOPPED, text = null)))
        // With Lock Locus on: no title, no answer.
        val hidden = WidgetRun(null, WidgetRunResult.DONE, null, 1, hidden = true)
        assertEquals("Scheduled task · 7:02", WidgetWords.runLabel(res, hidden, "7:02"))
        assertEquals("Open Locus to read it.", WidgetWords.runText(res, hidden))
        assertEquals("Next: Evening recap · 18:00", WidgetWords.next(res, WidgetNext("Evening recap", 1), "18:00"))
        assertEquals("Next task: 18:00", WidgetWords.next(res, WidgetNext(null, 1), "18:00"))
    }

    @Test
    fun `the usage figures read as before`() {
        assertEquals("$0.42", UsageWords.figure(res, UsageFigure.Spend(0.4213)))
        assertEquals("$1.50+", UsageWords.figure(res, UsageFigure.Spend(1.5, floor = true)))
        assertEquals("<$0.01", UsageWords.figure(res, UsageFigure.Spend(0.004)))
        assertEquals("1.2M tokens", UsageWords.figure(res, UsageFigure.Tokens(1_200_000)))
        assertEquals("950 tokens", UsageWords.figure(res, UsageFigure.Tokens(950)))
        assertEquals("1 token", UsageWords.tokens(res, 1))
        assertEquals("No usage yet", UsageWords.figure(res, UsageFigure.Nothing))
        assertEquals("Today", UsageWords.note(res, UsageNote.TODAY))
        assertEquals("Today · cost unknown", UsageWords.note(res, UsageNote.COST_UNKNOWN))
        assertEquals("Today · prices off", UsageWords.note(res, UsageNote.PRICES_OFF))
        assertEquals("123k tokens", UsageWords.tokens(res, 123_400))
        assertEquals("312 requests", UsageWords.requests(res, 312))
        assertEquals("1 request", UsageWords.requests(res, 1))
        assertEquals("7 days · $140.00", UsageWords.week(res, UsageFigure.Spend(140.0)))
    }

    @Test
    fun `the task budget reads as before`() {
        assertEquals("Task: 12k of 50k tokens · $0.08 of $0.50", UsageWords.task(res, TaskBudgetFigures(false, 12_000, 50_000, 0.08, false, 0.5)))
        assertEquals("Task: 12k tokens", UsageWords.task(res, TaskBudgetFigures(false, 12_000)))
        assertEquals("Task: 12k of 50k tokens · $0.08+", UsageWords.task(res, TaskBudgetFigures(false, 12_000, 50_000, 0.08, usdFloor = true)))
        assertEquals("Task paused: 50k of 50k tokens", UsageWords.task(res, TaskBudgetFigures(true, 50_000, 50_000)))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish`() {
        assertEquals("Inactivo", WidgetWords.status(res, WidgetSays.Idle))
        assertEquals("2 aprobaciones, 1 pregunta en espera", WidgetWords.status(res, WidgetSays.Waiting(2, 1)))
        assertEquals("Trabajando: 2 ayudantes", WidgetWords.status(res, WidgetSays.Helpers(2)))
        assertEquals("Hoy · gasto desconocido", UsageWords.note(res, UsageNote.COST_UNKNOWN))
        assertEquals("312 solicitudes", UsageWords.requests(res, 312))
        assertEquals("Próxima: Resumen · 18:00", WidgetWords.next(res, WidgetNext("Resumen", 1), "18:00"))
        // Money in the language's own way ("1,50 US$"), still a floor.
        val spend = UsageWords.figure(res, UsageFigure.Spend(1.5, floor = true))
        assertTrue("1,50" in spend && spend.endsWith("+"), spend)
    }

    // Times

    private val zone = ZoneId.of("Europe/Lisbon")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a time is written as near as it is`() {
        val now = at(27, 17, 49)
        assertEquals(WidgetWords.When.TIME, WidgetWords.whenOf(at(27, 7, 2), now, zone))
        // Ahead within a day: tomorrow's 07:00 is just "07:00".
        assertEquals(WidgetWords.When.TIME, WidgetWords.whenOf(at(28, 7), now, zone))
        // Yesterday's run says its weekday, so "7:02" never silently means yesterday.
        assertEquals(WidgetWords.When.WEEKDAY_TIME, WidgetWords.whenOf(at(26, 7, 2), now, zone))
        assertEquals(WidgetWords.When.WEEKDAY_TIME, WidgetWords.whenOf(at(30, 7), now, zone))
        assertEquals(WidgetWords.When.DATE, WidgetWords.whenOf(at(12, 7), now, zone))
    }

    @Test
    fun `times follow the phone's 12 or 24 hour setting`() {
        val now = at(27, 17, 49)
        assertEquals("18:00", WidgetWords.time(res, at(27, 18), now, zone, is24Hour = true))
        assertTrue(WidgetWords.time(res, at(27, 7, 2), now, zone, is24Hour = false).startsWith("7:02"))
        assertTrue("Thu" in WidgetWords.time(res, at(26, 7, 2), now, zone, is24Hour = true))
    }
}
