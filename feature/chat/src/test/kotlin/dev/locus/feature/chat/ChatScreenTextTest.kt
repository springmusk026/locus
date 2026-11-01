package dev.locus.feature.chat

import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.RunState
import dev.locus.core.designsystem.Tone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The chat screen's status lines and banner texts. */
class ChatScreenTextTest {
    private val pause = BudgetPause(BudgetLimit.TOKENS, "Paused: reached the 200k-token budget for this task.")

    @Test fun aTaskPausedAtItsBudgetSaysSoInsteadOfThinking() {
        val paused = ChatUiState(run = RunState(running = true, budgetPause = pause))
        assertEquals("Paused at the task budget" to Tone.Caution, liveStatus(paused, thinking = false, hasText = false))
        val working = ChatUiState(run = RunState(running = true))
        assertEquals("Thinking" to Tone.Live, liveStatus(working, thinking = false, hasText = false))
        assertNull(liveStatus(ChatUiState(), thinking = false, hasText = false))
    }

    @Test fun theBudgetPauseCardDoesNotRepeatItsTitle() {
        val meter = BudgetMeter(tokens = 201_000, tokenLimit = 200_000, costUsd = null, costLimit = null, elapsedMs = 45_000, timeLimitMs = null)
        assertEquals(
            "Reached the 200k-token budget for this task. Used 201k tokens · 45s. Continue allows one more of the same.",
            budgetPauseText(pause.message, meter),
        )
        assertEquals("Reached the 200k-token budget for this task. Continue allows one more of the same.", budgetPauseText(pause.message, null))
    }

    @Test fun oneBannerForAStoppedRunWithQueuedMessages() {
        assertEquals("Locus stopped. 1 queued message wasn't sent.", stoppedWithQueuedText(null, 1))
        assertEquals("Network error. 3 queued messages weren't sent.", stoppedWithQueuedText("Network error.", 3))
    }
}
