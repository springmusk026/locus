package dev.locus.feature.chat

import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The chat screen's status lines and banner texts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatScreenTextTest {
    private val pause = BudgetPause(BudgetLimit.TOKENS, "Paused: reached the 200k-token budget for this task.")

    @Test fun aTaskPausedAtItsBudgetLeavesItToTheCardInsteadOfThinking() {
        val paused = ChatUiState(run = RunState(running = true, budgetPause = pause))
        assertNull(liveStatus(paused, thinking = false, hasText = false))
        val working = ChatUiState(run = RunState(running = true))
        assertEquals(UiText.Res(R.string.chat_screen_thinking) to Tone.Live, liveStatus(working, thinking = false, hasText = false))
        assertNull(liveStatus(ChatUiState(), thinking = false, hasText = false))
    }

    @Test fun theBudgetPauseCardDoesNotRepeatItsTitle() {
        val meter = BudgetMeter(tokens = 201_000, tokenLimit = 200_000, costUsd = null, costLimit = null, elapsedMs = 45_000, timeLimitMs = null)
        assertEquals(
            "Reached the 200k-token budget for this task. Used 201k tokens · 45s. Continue allows one more of the same.",
            budgetPauseText(testResources, pause.message, meter),
        )
        assertEquals("Reached the 200k-token budget for this task. Continue allows one more of the same.", budgetPauseText(testResources, pause.message, null))
    }

    @Test fun theLiveLineSaysWhatTheAgentIsDoing() {
        val asking = ChatUiState(run = RunState(running = true, questions = listOf(dev.locus.core.agent.tools.UserQuestion("c", "q1", "Which one?"))))
        assertEquals(UiText.Res(R.string.chat_screen_waiting_answer) to Tone.Caution, liveStatus(asking, thinking = false, hasText = false))
        // The checklist and plan tools aren't "working with files"; any other tool is named by core/model.
        val todo = ChatUiState(run = RunState(running = true, activeTool = dev.locus.core.model.AgentControlTools.TODO_WRITE))
        assertEquals(UiText.Res(R.string.chat_screen_live_checklist) to Tone.Live, liveStatus(todo, thinking = false, hasText = false))
        val plan = ChatUiState(run = RunState(running = true, activeTool = dev.locus.core.model.AgentControlTools.PLAN_PROPOSE))
        assertEquals(UiText.Res(R.string.chat_screen_live_plan) to Tone.Live, liveStatus(plan, thinking = false, hasText = false))
        val reading = ChatUiState(run = RunState(running = true, activeTool = "read_file"))
        assertEquals(dev.locus.core.designsystem.toolActivityText("read_file") to Tone.Live, liveStatus(reading, thinking = false, hasText = false))
        assertEquals(dev.locus.core.model.toolActivity("read_file"), liveStatus(reading, thinking = false, hasText = false)!!.first.asString(testResources))
        // The agent's own progress line is shown as it came.
        val progress = ChatUiState(run = RunState(running = true, progress = "Fetching page 2\nFetching page 3"))
        assertEquals(UiText.Plain("Fetching page 3") to Tone.Live, liveStatus(progress, thinking = false, hasText = false))
    }

    @Test fun oneBannerForAStoppedRunWithQueuedMessages() {
        assertEquals("Locus stopped. 1 queued message wasn't sent.", stoppedWithQueuedText(testResources, null, 1))
        assertEquals("Network error. 3 queued messages weren't sent.", stoppedWithQueuedText(testResources, "Network error.", 3))
    }
}
