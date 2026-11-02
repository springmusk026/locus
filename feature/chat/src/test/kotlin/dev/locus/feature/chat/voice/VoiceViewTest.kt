package dev.locus.feature.chat.voice

import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.RunState
import dev.locus.feature.chat.ChatItem
import dev.locus.feature.chat.ChatUiState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceViewTest {

    @Test
    fun `the latest reply is what voice mode speaks, not notes or tool calls`() {
        val state = ChatUiState(
            items = listOf(
                ChatItem.User("u1", "hi"),
                ChatItem.Assistant("a1", "First"),
                ChatItem.User("u2", "again"),
                ChatItem.Note("n1", "Let me look…"),
                ChatItem.ToolCall("t1", "run_command", "{}", null),
                ChatItem.Assistant("a2", "Second"),
            ),
            run = RunState(running = false, activeTool = null),
        )
        val view = voiceView(state)
        assertEquals("a2", view.replyKey)
        assertEquals("Second", view.replyText)
        assertNull(view.question)
    }

    @Test
    fun `an open question is read with its options`() {
        val q = UserQuestion("chat", "q1", "Which city", options = listOf("Paris", "Rome", "Oslo"))
        val view = voiceView(ChatUiState(run = RunState(running = true, questions = listOf(q))))
        assertEquals("Which city. You can say Paris, Rome, or Oslo.", view.question)
        assertEquals("q1", view.questionId)
    }
}
