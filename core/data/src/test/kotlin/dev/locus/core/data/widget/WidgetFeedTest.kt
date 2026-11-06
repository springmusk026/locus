package dev.locus.core.data.widget

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.ScheduledTask
import dev.locus.core.model.TaskRunOutcome
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
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
        val status = widgetStatus(mapOf("a" to RunState(running = true, activeTool = "run_command")))
        assertEquals("Working: running a command", status.text)
        assertEquals(WidgetTone.WORKING, status.tone)
        assertEquals("a", status.chatId)
        assertEquals("Working", widgetStatus(mapOf("a" to RunState(running = true))).text)
    }

    @Test
    fun `approvals are counted and open their chat, never their summary`() {
        val one = widgetStatus(mapOf("a" to RunState(running = true, approvals = listOf(approval("a", summary = "rm -rf secret-dir")))))
        assertEquals("1 approval waiting", one.text)
        assertEquals(WidgetTone.WAITING, one.tone)
        assertEquals("a", one.chatId)
        assertFalse("secret-dir" in one.text)

        val two = widgetStatus(mapOf("a" to RunState(approvals = listOf(approval("a"))), "b" to RunState(approvals = listOf(approval("b")))))
        assertEquals("2 approvals waiting", two.text)
    }

    @Test
    fun `approvals and questions together`() {
        val status = widgetStatus(mapOf("a" to RunState(approvals = listOf(approval("a"))), "b" to RunState(questions = listOf(question("b")))))
        assertEquals("1 approval, 1 question waiting", status.text)
        assertEquals("a", status.chatId)
        assertEquals("1 question waiting", widgetStatus(mapOf("b" to RunState(questions = listOf(question("b"))))).text)
    }

    @Test
    fun `a budget pause waits for the user`() {
        val status = widgetStatus(mapOf("a" to RunState(budgetPause = BudgetPause(BudgetLimit.TOKENS, "Paused"))))
        assertEquals("Paused at the task budget", status.text)
        assertEquals(WidgetTone.WAITING, status.tone)
    }

    @Test
    fun `background helpers count as working`() {
        assertEquals("Working: 2 helpers", widgetStatus(mapOf("a" to RunState(helpers = 2))).text)
        assertEquals("Working: 1 helper", widgetStatus(mapOf("a" to RunState(helpers = 1))).text)
    }

    @Test
    fun `a scheduled run shows only as running a scheduled task`() {
        val status = widgetStatus(mapOf("s" to RunState(running = true, scheduled = true, activeTool = "web_read")))
        assertEquals("Running a scheduled task", status.text)
        assertNull(status.chatId)
        // A user's run wins over a scheduled one.
        assertEquals("Working: reading a page", widgetStatus(mapOf("s" to RunState(running = true, scheduled = true), "a" to RunState(running = true, activeTool = "web_read"))).text)
    }

    // Task line

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
        assertEquals("Backups: failed", taskLine(failed, noScrub))
        val stopped = latestTaskRun(listOf(task("Backups", 1, "Stopped: limit", TaskRunOutcome.STOPPED)))!!
        assertEquals("Backups: stopped", taskLine(stopped, noScrub))
    }

    @Test
    fun `a finished run shows its first line, cleaned and short`() {
        val run = LatestTaskRun("News", "## Today\n\n**Three** stories about [Rust](https://evil.example/x) and more " + "words ".repeat(40), TaskRunOutcome.DONE, 1)
        val line = taskLine(run, noScrub)!!
        assertEquals("News: Today", line)
        val long = taskLine(LatestTaskRun("News", "Three stories " + "words ".repeat(40), null, 1), noScrub)!!
        assertTrue(long.length <= WidgetText.LINE_MAX, long)
        assertTrue(long.endsWith("…"))
        assertEquals("News: done", taskLine(LatestTaskRun("News", "   ", TaskRunOutcome.DONE, 1), noScrub))
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

    // Vault scrubbing of the task line

    private val run = LatestTaskRun("Backups", "Pushed with token hunter2-secret-value", TaskRunOutcome.DONE, 1)

    @Test
    fun `the task line is scrubbed of vault values`() = runTest {
        val line = scrubbedTaskLine(run) { listOf("hunter2-secret-value") }!!
        assertFalse("hunter2-secret-value" in line, line)
    }

    @Test
    fun `no task line when the vault can't be read`() = runTest {
        assertNull(scrubbedTaskLine(run) { error("keystore unavailable") })
    }

    @Test
    fun `a cancelled vault read is rethrown, not turned into a pattern-only line`() = runTest {
        assertFailsWith<CancellationException> { scrubbedTaskLine(run) { throw CancellationException("newer run") } }
    }
}
