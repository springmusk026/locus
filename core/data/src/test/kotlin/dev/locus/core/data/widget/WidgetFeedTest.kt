package dev.locus.core.data.widget

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.tasks.DailySlot
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetFeedTest {

    private fun approval(chat: String, summary: String = "Run ls") =
        ApprovalRequest(chat, "call-$chat", "run_command", ToolRisk.EXECUTE, summary, """{"command":"ls"}""")

    private fun question(chat: String) = UserQuestion(chat, "q-$chat", "Which branch?", emptyList(), false, true)

    private val noScrub: (String) -> String = { it }

    // Status line

    @Test
    fun `nothing running is Idle`() {
        assertEquals(WidgetStatus.IDLE, widgetStatus(emptyMap()))
        assertEquals(WidgetStatus.IDLE, widgetStatus(mapOf("a" to RunState())))
    }

    @Test
    fun `a running chat says what it is doing in Locus's own words`() {
        // Worded by the widget from the tool's fixed activity phrase ("Working: running a command", WidgetWordsTest).
        val status = widgetStatus(mapOf("a" to RunState(running = true, activeTool = "run_command")))
        assertEquals(WidgetSays.Working("run_command"), status.says)
        assertEquals(WidgetTone.WORKING, status.tone)
        assertEquals("a", status.chatId)
        assertEquals(WidgetSays.Working(null), widgetStatus(mapOf("a" to RunState(running = true))).says)
    }

    @Test
    fun `approvals are counted and open their chat, never their summary`() {
        val one = widgetStatus(mapOf("a" to RunState(running = true, approvals = listOf(approval("a", summary = "rm -rf secret-dir")))))
        assertEquals(WidgetSays.Waiting(approvals = 1, questions = 0), one.says)
        assertEquals(WidgetTone.WAITING, one.tone)
        assertEquals("a", one.chatId)
        assertFalse("secret-dir" in one.toString())

        val two = widgetStatus(mapOf("a" to RunState(approvals = listOf(approval("a"))), "b" to RunState(approvals = listOf(approval("b")))))
        assertEquals(WidgetSays.Waiting(approvals = 2, questions = 0), two.says)
    }

    @Test
    fun `approvals and questions together`() {
        val status = widgetStatus(mapOf("a" to RunState(approvals = listOf(approval("a"))), "b" to RunState(questions = listOf(question("b")))))
        assertEquals(WidgetSays.Waiting(approvals = 1, questions = 1), status.says)
        assertEquals("a", status.chatId)
        assertEquals(WidgetSays.Waiting(approvals = 0, questions = 1), widgetStatus(mapOf("b" to RunState(questions = listOf(question("b"))))).says)
        assertFalse("Which branch" in status.toString())
    }

    @Test
    fun `a budget pause waits for the user`() {
        val status = widgetStatus(mapOf("a" to RunState(budgetPause = BudgetPause(BudgetLimit.TOKENS, "Paused"))))
        assertEquals(WidgetSays.BudgetPaused, status.says)
        assertEquals(WidgetTone.WAITING, status.tone)
        assertEquals("a", status.chatId)
    }

    @Test
    fun `background helpers count as working`() {
        assertEquals(WidgetSays.Helpers(2), widgetStatus(mapOf("a" to RunState(helpers = 2))).says)
        assertEquals(WidgetSays.Helpers(1), widgetStatus(mapOf("a" to RunState(helpers = 1))).says)
    }

    @Test
    fun `a scheduled run shows only as running a scheduled task`() {
        val status = widgetStatus(mapOf("s" to RunState(running = true, scheduled = true, activeTool = "web_read")))
        assertEquals(WidgetSays.Scheduled, status.says)
        assertNull(status.chatId)
        // A user's run wins over a scheduled one.
        assertEquals(WidgetSays.Working("web_read"), widgetStatus(mapOf("s" to RunState(running = true, scheduled = true), "a" to RunState(running = true, activeTool = "web_read"))).says)
    }

    // The latest run

    private fun task(title: String, at: Long?, summary: String? = "ok", outcome: TaskRunOutcome? = TaskRunOutcome.DONE) =
        ScheduledTask("id-$title", title, "prompt", 60, true, at, summary, outcome)

    @Test
    fun `the latest run wins and tasks never run are skipped`() {
        assertNull(latestTaskRun(emptyList()))
        assertNull(latestTaskRun(listOf(task("Never", null))))
        assertEquals("News", latestTaskRun(listOf(task("Old", 1), task("News", 5), task("Never", null)))?.title)
    }

    @Test
    fun `failed and stopped runs say only that`() {
        val failed = latestTaskRun(listOf(task("Backups", 1, "Failed: token sk-abc leaked", TaskRunOutcome.FAILED)))!!
        assertEquals(WidgetRun("Backups", WidgetRunResult.FAILED, null, 1), widgetRun(failed, noScrub))
        val stopped = latestTaskRun(listOf(task("Backups", 1, "Stopped: limit", TaskRunOutcome.STOPPED)))!!
        assertEquals(WidgetRun("Backups", WidgetRunResult.STOPPED, null, 1), widgetRun(stopped, noScrub))
    }

    @Test
    fun `a finished run keeps several cleaned lines, its title and time apart`() {
        val run = LatestTaskRun("News", "## Today\n\n**Three** stories about [Rust](https://evil.example/x)\n- and more " + "words ".repeat(80), TaskRunOutcome.DONE, 7)
        val shown = widgetRun(run, noScrub)
        assertEquals("News", shown.title)
        assertEquals(7, shown.at)
        val text = shown.text!!
        assertTrue(text.startsWith("Today\nThree stories about Rust\nand more words"), text)
        assertFalse("evil.example" in text, text)
        assertTrue(text.length <= WidgetText.TEXT_MAX, text)
        assertTrue(text.endsWith("…"))
        // Nothing left to show: the widget says "Done".
        assertNull(widgetRun(LatestTaskRun("News", "   ", TaskRunOutcome.DONE, 1), noScrub).text)
        assertNull(widgetRun(LatestTaskRun("  ", "ok", TaskRunOutcome.DONE, 1), noScrub).title)
    }

    @Test
    fun `an answer's lines stop at the widget's most and say so`() {
        val text = WidgetText.cleanLines((1..12).joinToString("\n") { "line $it" }, noScrub, 1_000, 3)
        assertEquals("line 1\nline 2\nline 3…", text)
        assertEquals("one\ntwo", WidgetText.cleanLines("one\n\n\ntwo", noScrub, 100, 3))
        assertEquals("", WidgetText.cleanLines("  \n ", noScrub, 100, 3))
    }

    // Lock Locus and the next run

    private val doneRun = WidgetRun("Bank balance", WidgetRunResult.DONE, "You have 1,234 left", 5)
    private val nextRun = WidgetNext("Evening recap", 9)

    @Test
    fun `with Lock Locus on the widget keeps only that a task ran, how it ended and when`() {
        val open = widgetModel(WidgetStatus.IDLE, doneRun, nextRun, appLocked = false)
        assertEquals(doneRun, open.run)
        assertEquals(nextRun, open.next)
        val locked = widgetModel(WidgetStatus.IDLE, doneRun, nextRun, appLocked = true)
        assertEquals(WidgetRun(null, WidgetRunResult.DONE, null, 5, hidden = true), locked.run)
        // A task's title is the user's own words: only the time shows.
        assertEquals(WidgetNext(null, 9), locked.next)
        assertFalse("Bank" in locked.toString() || "1,234" in locked.toString() || "recap" in locked.toString(), locked.toString())
    }

    @Test
    fun `the next run shows only while idle`() {
        val working = widgetStatus(mapOf("a" to RunState(running = true)))
        assertNull(widgetModel(working, doneRun, nextRun, appLocked = false).next)
        assertEquals(nextRun, widgetModel(WidgetStatus.IDLE, null, nextRun, appLocked = false).next)
    }

    private val zone = ZoneId.of("Europe/Lisbon")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun daily(title: String, days: Int = 1, enabled: Boolean = true) =
        ScheduledTask("id-$title", title, "prompt", days * 1_440L, enabled, null, null)

    @Test
    fun `the next run is the soonest daily task ahead`() {
        val now = at(27, 17, 49)
        val slots = mapOf(
            "id-Morning digest" to DailySlot(7 * 60, at(28, 7)),
            "id-Evening recap" to DailySlot(18 * 60, at(27, 18)),
            "id-Off" to DailySlot(17 * 60 + 50, at(27, 17, 50)),
        )
        val tasks = listOf(daily("Morning digest"), daily("Evening recap"), daily("Off", enabled = false), task("Hourly", 1))
        assertEquals(WidgetNext("Evening recap", at(27, 18)), nextTaskRun(tasks, slots::get, now, zone))
        // Tasks on a plain period have no honest time: none.
        assertNull(nextTaskRun(listOf(task("Hourly", 1)), slots::get, now, zone))
    }

    @Test
    fun `a planned run already past gives way to the one after`() {
        // The tasks table changes as a run ends, before the next run is planned: the stored time is the old one.
        val now = at(27, 7, 2)
        val next = nextTaskRun(listOf(daily("Morning digest")), { DailySlot(7 * 60, at(27, 7)) }, now, zone)
        assertEquals(at(28, 7), next?.at)
        val everyTwo = nextTaskRun(listOf(daily("Plants", days = 2)), { DailySlot(7 * 60, at(27, 7)) }, now, zone)
        assertEquals(at(29, 7), everyTwo?.at)
        // Nothing planned yet: the next time of day.
        assertEquals(at(28, 7), nextTaskRun(listOf(daily("Morning digest")), { DailySlot(7 * 60, null) }, now, zone)?.at)
    }

    // Sanitising

    @Test
    fun `credentials are scrubbed before anything is cut`() {
        val scrubber = SecretScrubber(listOf("hunter2-vault-value"))
        val text = WidgetText.clean("Key sk-ABCDEFGHIJKLMNOPQRSTUV and hunter2-vault-value and Bearer abcdefghijkl", scrubber::scrub, 200)
        assertFalse("sk-ABC" in text, text)
        assertFalse("hunter2" in text, text)
        assertFalse("abcdefghijkl" in text, text)
        assertTrue("[secret]" in text, text)
    }

    @Test
    fun `links keep their label and bare URLs become a placeholder`() {
        assertEquals("Read docs now", WidgetText.clean("Read [docs](https://x.example/a) now", noScrub, 100))
        assertEquals("Go to [link] today", WidgetText.clean("Go to https://phish.example/login?x=1 today", noScrub, 100))
        assertEquals("Open [link]", WidgetText.clean("Open www.phish.example", noScrub, 100))
        assertEquals("Run [link]", WidgetText.clean("Run javascript:alert(1)", noScrub, 100))
        assertEquals("pic", WidgetText.clean("![pic](https://x.example/p.png)", noScrub, 100))
        // Prose with a colon is left alone.
        assertEquals("The data: 5 rows", WidgetText.clean("The data: 5 rows", noScrub, 100))
    }

    @Test
    fun `markdown markup is removed but words keep their underscores`() {
        assertEquals("Title", WidgetText.clean("# Title", noScrub, 100))
        assertEquals("quoted", WidgetText.clean("> quoted", noScrub, 100))
        assertEquals("item", WidgetText.clean("- item", noScrub, 100))
        assertEquals("first step", WidgetText.clean("1. **first** step", noScrub, 100))
        assertEquals("bold code file_name", WidgetText.clean("**bold** `code` file_name", noScrub, 100))
    }

    @Test
    fun `invisible and direction-changing characters are removed`() {
        val sneaky = "pay‮exe.txt​⁦now⁩\u0007﻿"
        assertEquals("payexe.txtnow", WidgetText.clean(sneaky, noScrub, 100))
    }

    @Test
    fun `emoji sequences keep their joiners, the shared stripper decides`() {
        val technologist = String(Character.toChars(0x1F469)) + "\u200D" + String(Character.toChars(0x1F4BB))
        assertEquals("hi $technologist", WidgetText.clean("hi $technologist", noScrub, 100))
        val tags = buildString { "run me".forEach { appendCodePoint(0xE0000 + it.code) } }
        assertEquals("hi", WidgetText.clean("hi$tags", noScrub, 100))
    }

    @Test
    fun `only the first non-empty line is kept and spaces collapse`() {
        assertEquals("first line", WidgetText.clean("\n\n  first   line \nsecond", noScrub, 100))
        assertEquals("a", WidgetText.clean("a b", noScrub, 100))
        assertEquals("", WidgetText.clean("  \n​ ", noScrub, 100))
    }

    @Test
    fun `truncation never splits a surrogate pair and prefers a space`() {
        val emoji = "😀" // one code point, two chars
        val cut = WidgetText.truncate("ab$emoji$emoji$emoji", 4)
        assertFalse(cut.dropLast(1).lastOrNull()?.let(Character::isHighSurrogate) ?: false, cut)
        assertEquals("one two…", WidgetText.truncate("one two three", 10))
        assertEquals("short", WidgetText.truncate("short", 10))
        assertEquals("", WidgetText.clean("anything", noScrub, 0))
    }

    // Vault scrubbing of the task parts

    private val run = LatestTaskRun("Backups", "Pushed with token hunter2-secret-value", TaskRunOutcome.DONE, 1)

    @Test
    fun `the run and the next title are scrubbed of vault values`() = runTest {
        val (shown, next) = scrubbedTasks(run, WidgetNext("Rotate hunter2-secret-value", 3)) { listOf("hunter2-secret-value") }
        assertFalse("hunter2-secret-value" in shown!!.text!!, shown.text)
        assertFalse("hunter2-secret-value" in next!!.title!!, next.title)
    }

    @Test
    fun `no run and no title when the vault can't be read, the next time still shows`() = runTest {
        val (shown, next) = scrubbedTasks(run, WidgetNext("Evening recap", 3)) { error("keystore unavailable") }
        assertNull(shown)
        assertEquals(WidgetNext(null, 3), next)
    }

    @Test
    fun `the vault isn't read with nothing to scrub`() = runTest {
        assertEquals(null to null, scrubbedTasks(null, null) { error("read") })
    }

    @Test
    fun `a cancelled vault read is rethrown, not turned into a pattern-only line`() = runTest {
        assertFailsWith<CancellationException> { scrubbedTasks(run, null) { throw CancellationException("newer run") } }
    }
}
