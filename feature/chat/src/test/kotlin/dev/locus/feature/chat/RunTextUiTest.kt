package dev.locus.feature.chat

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.agent.BudgetReached
import dev.locus.core.agent.BudgetText
import dev.locus.core.agent.ContextOverflowText
import dev.locus.core.agent.LikelyCause
import dev.locus.core.agent.RunBudget
import dev.locus.core.agent.RunFailure
import dev.locus.core.agent.TaskReceipts
import dev.locus.core.data.agent.RunNote
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.agent.withNotice
import dev.locus.core.llm.ChatGptErrors
import dev.locus.core.llm.LlmErrorText
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmFailure
import dev.locus.core.llm.ProviderErrorText
import dev.locus.core.model.ReceiptApprovals
import dev.locus.core.model.ReceiptCheck
import dev.locus.core.model.ReceiptCost
import dev.locus.core.model.ReceiptHelper
import dev.locus.core.model.ReceiptModel
import dev.locus.core.model.ReceiptTokens
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskReceipt
import dev.locus.core.model.Verification
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The run's errors, notices, budget and receipt lines read in English exactly as core writes them, and in
 * another language once the app's language changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RunTextUiTest {

    private fun resources(locale: Locale): Resources {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config).resources
    }

    private val english = resources(Locale.US)
    private val spanish = resources(Locale.forLanguageTag("es"))

    /** Values that fit each kind: the provider, a status, words, ids. */
    private fun sample(kind: LlmErrorText.Kind): LlmErrorText = LlmErrorText(kind, listOf("OpenRouter", "429", "Rate limit exceeded"))

    @Test
    fun everyProviderErrorReadsAsCoreWritesIt() {
        LlmErrorText.Kind.entries.forEach { kind ->
            val text = sample(kind)
            assertEquals(text.english, llmErrorInWords(english, text), kind.name)
            assertNotEquals(text.english, llmErrorInWords(spanish, text), kind.name)
        }
        // With a reset time, today and another day, as core/llm words it.
        val now = System.currentTimeMillis() / 1000
        for (at in listOf(now + 600, now + 3 * 86_400)) {
            val limit = LlmErrorText(LlmErrorText.Kind.CHATGPT_LIMIT, resetsAt = at)
            assertEquals(limit.english, llmErrorInWords(english, limit))
        }
    }

    @Test
    fun realFailuresReadTheSame() {
        val errors = listOf(
            ProviderErrorText.messageText("OpenRouter", 502, "<html><head><title>502 Bad Gateway</title></head></html>"),
            ProviderErrorText.messageText("Groq", 418, "<html><head><title>I'm a teapot</title></head></html>"),
            ProviderErrorText.messageText("Mistral", 400, """{"error":{"message":"Bad things"}}"""),
            ChatGptErrors.fromHttp(429, """{"error":{"type":"usage_limit_reached"}}""").text!!,
            ChatGptErrors.fromCode("subscription_sharing_unsupported_capability", "no images", param = "input_image").text!!,
            ChatGptErrors.fromCode(null, null, requestId = "req_1").text!!,
        )
        errors.forEach { assertEquals(it.english, llmErrorInWords(english, it)) }
    }

    @Test
    fun theBannerWordsTheErrorWithoutIdsAndKeepsWhatWasAdded() {
        val e = ChatGptErrors.fromCode(null, null, requestId = "req_1")
        val failure = RunFailure.of(e, e.message.orEmpty(), "ChatGPT", "gpt-5", 0, null, "m1", null)
        assertEquals("ChatGPT ended the response with an error and gave no reason.", runErrorInWords(english, e.message.orEmpty(), failure))
        val more = e.message + "\nBackup models skipped: x."
        assertEquals("ChatGPT ended the response with an error and gave no reason.\nBackup models skipped: x.", runErrorInWords(english, more, failure))
        // Not the failure's words: shown as it came.
        assertEquals("Something else", runErrorInWords(english, "Something else", failure))
        assertEquals("ChatGPT terminó la respuesta con un error y no dio ningún motivo.", runErrorInWords(spanish, e.message.orEmpty(), failure))
    }

    @Test
    fun overflowReadsAsCoreWritesIt() {
        for (summarised in listOf(true, false)) {
            val o = ContextOverflowText("gpt-5", summarised, "context_length_exceeded")
            assertEquals(o.english, overflowInWords(english, o))
        }
    }

    @Test
    fun adviceReadsAsCoreWritesIt() {
        val e = LlmException("x", retryable = true)
        val one = RunFailure.of(e, "x", "P", "m", 0, null, "t", null, contextTokens = 141_000, contextWindow = 272_000)
        val two = RunFailure.of(e, "x", "P", "m", 0, one, "t", null, contextTokens = 141_000, contextWindow = 272_000)
        val three = RunFailure.of(e, "x", "P", "m", 0, two, "t", null, contextTokens = 1_000, contextWindow = 272_000)
        val quota = RunFailure.of(LlmException("q", kind = LlmFailure.QUOTA), "q", "P", "m", 0, null, "t", null)
        val busy = RunFailure.of(LlmException("b", retryable = true, kind = LlmFailure.OVERLOADED), "b", "P", "m", 0, null, "t", null)
        val busyAgain = RunFailure.of(LlmException("b", retryable = true, kind = LlmFailure.OVERLOADED), "b", "P", "m", 0, busy, "t", null)
        val other = RunFailure.of(LlmException("o"), "o", "P", "m", 0, null, "t", null)
        val otherAgain = RunFailure.of(LlmException("o"), "o", "P", "m", 0, other, "t", null)
        listOf(one, two, three, quota, busy, busyAgain, other, otherAgain).forEach { assertEquals(it.advice, failureAdviceInWords(english, it), "${it.cause} ${it.occurrences}") }
        assertEquals(LikelyCause.REQUEST, two.cause)
    }

    @Test
    fun everyRunNoteReadsAsCoreWritesIt() {
        val reached = RunBudget(TaskBudget(tokens = 200_000)).reached(BudgetLimit.TOKENS, paused = false)
        val notes = listOf(
            RunNote.Summarising, RunNote.Compacting(true), RunNote.Compacting(false), RunNote.Compacted(1), RunNote.Compacted(12),
            RunNote.CompactionFailed(null), RunNote.CompactionFailed("timeout\nmore"), RunNote.CompactNowFailed("boom"), RunNote.CompactNowFailed(null),
            RunNote.Retrying(true, null, 1), RunNote.Retrying(false, 30, 2), RunNote.Retrying(true, 7, 3), RunNote.Retrying(false, null, 4),
            RunNote.FellBack("a", "b", emptyList(), null), RunNote.FellBack("a", "b", listOf("c: no vision", "d: no tools"), "vision"),
            RunNote.Refused(null), RunNote.Refused("safety"), RunNote.StepLimit(1), RunNote.StepLimit(40), RunNote.BudgetStopped(reached),
            RunNote.BranchUnfinished, RunNote.ClosedMidTask, RunNote.BackgroundRefused(true), RunNote.BackgroundRefused(false),
            RunNote.ToolGone, RunNote.NoProvider, RunNote.LessonsSaved(1), RunNote.LessonsSaved(3),
        )
        notes.forEach {
            assertEquals(it.english, runNoteInWords(english, it), it.toString())
            assertNotEquals(it.english, runNoteInWords(spanish, it), it.toString())
        }
        // A notice the note no longer matches is shown as it came.
        val state = RunState().withNotice(RunNote.ToolGone)
        assertEquals("No se pudo ejecutar: esa herramienta ya no está disponible.", runNotice(spanish, state))
        assertEquals("Other", runNotice(spanish, state.copy(notice = "Other")))
    }

    @Test
    fun budgetWordsReadAsCoreWritesThem() {
        val budget = RunBudget(TaskBudget(tokens = 200_000, costUsd = 2.0, minutes = 30), costKnown = true)
        for (limit in BudgetLimit.entries) {
            assertEquals(budget.pauseMessage(limit), budgetReachedInWords(english, budget.reached(limit, paused = true)))
            assertEquals(budget.stopMessage(limit), budgetReachedInWords(english, budget.reached(limit, paused = false)))
        }
        assertEquals(
            "Reached the 1-minute budget for this task. Continue allows one more of the same.",
            budgetPauseText(english, "Paused: reached the 1-minute budget for this task.", null, BudgetReached(BudgetLimit.TIME, true, 0, 0.0, 1)),
        )
        listOf(0L, 42_000L, 372_000L, 3_900_000L).forEach { assertEquals(BudgetText.duration(it), durationInWords(english, it)) }
        val meters = listOf(
            BudgetMeter(38_000, 200_000, 0.12, 1.0, 372_000, 1_800_000, costPartial = true),
            BudgetMeter(950, null, 0.12, 1.0, 42_000, 5_400_000),
            BudgetMeter(950, null, null, null, 42_000, 1_800_000),
            BudgetMeter(950, null, null, null, 42_000, null),
        )
        meters.forEach { m ->
            assertEquals(BudgetText.chip(m), budgetChip(english, m))
            assertEquals(BudgetText.spokenChip(m), budgetSpokenChip(english, m))
            assertEquals(BudgetText.limits(m), budgetLimits(english, m))
            assertEquals(BudgetText.used(m), budgetUsed(english, m))
        }
    }

    @Test
    fun receiptLinesReadAsCoreWritesThem() {
        val base = TaskReceipt(
            taskId = "t", conversationId = "c", outcome = TaskOutcome.DONE, detail = null, startedAt = 0, endedAt = 252_000, steps = 4,
            models = listOf(ReceiptModel("claude-opus-5-5"), ReceiptModel("gpt-5.2", ReceiptModel.Kind.BACKUP, "claude-opus-5-5 (Anthropic)"), ReceiptModel("haiku", ReceiptModel.Kind.HELPER), ReceiptModel("r", ReceiptModel.Kind.REVIEWER), ReceiptModel("s", ReceiptModel.Kind.SUMMARY)),
            tokens = ReceiptTokens(120_000, 8_200, 76_800, 3), cost = ReceiptCost(0.04, partial = true), files = 3, changeTurns = emptyList(),
            checks = listOf(ReceiptCheck("m", "c1", Verification.Kind.TEST, true), ReceiptCheck("m", "c2", Verification.Kind.TEST, true, uncertain = true)),
            approvals = ReceiptApprovals(approved = 2, declined = 1, blocked = 1), outsideContent = listOf("web pages", "GitHub", "odd_tool"), taintedFromStart = false,
            helpers = listOf("Researcher #1", "Explorer #1"),
            helperRuns = listOf(ReceiptHelper("Researcher #1", "claude-haiku-5-5", ReceiptCost(0.01, false)), ReceiptHelper("Explorer #1", "gpt-5-mini", null), ReceiptHelper("Scout #1", null, ReceiptCost(0.002, true)), ReceiptHelper("Bare #1", null, null)),
            afterMessageId = "m", completionCheck = 2,
        )
        val variants = listOf(
            base,
            base.copy(outcome = TaskOutcome.STOPPED, completionCheck = 1, cost = null, files = 1, approvals = ReceiptApprovals(approved = 1)),
            base.copy(outcome = TaskOutcome.STOPPED, detail = "Stopped: reached the 200k-token budget for this task."),
            base.copy(outcome = TaskOutcome.BUDGET, detail = "Paused: x", checks = listOf(ReceiptCheck("m", "c", Verification.Kind.BUILD, false))),
            base.copy(outcome = TaskOutcome.FAILED, checks = listOf(ReceiptCheck("m", "c", Verification.Kind.BUILD, null)), tokens = ReceiptTokens()),
            base.copy(outcome = TaskOutcome.REFUSED, checks = listOf(ReceiptCheck("m", "c", Verification.Kind.TEST, true, uncertain = true), ReceiptCheck("m", "d", Verification.Kind.BUILD, true))),
            base.copy(outcome = TaskOutcome.STEP_LIMIT, endedAt = 4_000_000, helperRuns = emptyList()),
            base.copy(outcome = TaskOutcome.INTERRUPTED, checks = emptyList(), helperRuns = emptyList(), helpers = emptyList()),
            base.copy(outcome = TaskOutcome.TOOL_LOOP, detail = "Stopped: the model kept calling tools it can't use."),
        )
        variants.forEach { r ->
            assertEquals(TaskReceipts.summary(r), receiptSummary(english, r))
            assertEquals(TaskReceipts.outcomeLine(r), receiptOutcomeLine(english, r))
            assertEquals(TaskReceipts.modelsLabel(r), receiptModels(english, r))
            assertEquals(TaskReceipts.tokensLabel(r), receiptTokens(english, r))
            assertEquals(TaskReceipts.costLabel(r), receiptCost(english, r))
            assertEquals(TaskReceipts.checksDetail(r), receiptChecksDetail(english, r))
            assertEquals(TaskReceipts.helpersLabel(r, "\n"), receiptHelpers(english, r, "\n"))
            assertEquals(TaskReceipts.completionCheckLabel(r), receiptCompletionCheck(english, r))
            assertEquals(r.outsideContent.joinToString(", "), receiptOutside(english, r))
        }
        assertNotEquals(TaskReceipts.summary(base), receiptSummary(spanish, base))
    }
}
