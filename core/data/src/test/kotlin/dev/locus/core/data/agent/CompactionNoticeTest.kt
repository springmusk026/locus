package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A summary of earlier messages that fails is told to the user (a notice), not swallowed: the chat may
 * no longer fit, and the next request's overflow error wouldn't say why. The turn itself still runs.
 */
class CompactionNoticeTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestGraph.chatRequests() = requests.count { it.url.host == "llm.test" }

    /** A chat whose last reply used 95% of the scripted model's 100k window, so the next send summarises first. */
    private suspend fun TestGraph.nearlyFullChat(): String {
        val chat = conversations.create(ScriptedProvider.config.id).id
        listOf("plan a trip", "three days?", "yes, in spring", "Day 1: lakeside…").forEachIndexed { i, text ->
            val role = if (i % 2 == 0) Role.USER else Role.ASSISTANT
            val usage = if (i == 3) TokenUsage(input = 95_000, output = 100) else null
            conversations.append(Message("m$i", chat, role, listOf(Part.Text(text)), i + 1L, usage = usage))
        }
        return chat
    }

    @Test
    fun `a summary that fails before a turn leaves a notice and the turn still runs`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        // The summary comes back empty; then the turn's own reply.
        g.script(ScriptedProvider.text(""), ScriptedProvider.text("Here's day 2."))
        val chat = g.nearlyFullChat()
        val runner = g.runner
        runCurrent()

        runner.send(chat, "and day 2?")
        val done = runner.states.first { it[chat]?.running == false && g.chatRequests() == 2 }.getValue(chat)

        assertEquals("Here's day 2.", g.conversations.history(chat).last().text)
        val notice = done.notice.orEmpty()
        assertTrue(notice.startsWith("Couldn't summarise earlier messages (the model returned no summary)"), notice)
        assertEquals("", done.progress)
    }

    @Test
    fun `a summary that fails inside a run leaves a notice`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        // A step that fills 95% of the window, so the loop summarises at the step boundary; the summary is empty.
        val bigStep = ScriptedProvider.toolCall("c1", "read_file", """{"path":"notes.txt"}""").replace(
            "data: [DONE]",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":95000,\"completion_tokens\":5}}\n\ndata: [DONE]",
        )
        g.script(bigStep, ScriptedProvider.text(""), ScriptedProvider.text("No notes yet."))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        runCurrent()

        runner.send(chat, "read my notes")
        val done = runner.states.first { it[chat]?.running == false && g.chatRequests() == 3 }.getValue(chat)

        assertEquals("No notes yet.", g.conversations.history(chat).last().text)
        assertTrue(done.notice.orEmpty().startsWith("Couldn't summarise earlier messages (the model returned no summary)"), done.notice)
    }

    @Test
    fun `the notice names the first line of the reason`() {
        assertTrue(compactionFailedNotice("HTTP 500\nstack…").startsWith("Couldn't summarise earlier messages (HTTP 500)."))
        assertTrue(compactionFailedNotice(null).startsWith("Couldn't summarise earlier messages (unknown error)."))
    }
}
