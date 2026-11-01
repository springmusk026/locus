package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.Role
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android 12+ refuses to start a foreground service while the app is in the background. A turn Locus
 * starts on its own (a helper's report, a queued message) then waits for the user with "tap to
 * continue" instead of crashing; a turn the user started still runs.
 */
class BackgroundStartTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private fun TestGraph.chatRequests() = requests.count { it.url.host == "llm.test" }

    @Test
    fun `a refused background start doesn't run, offers Continue and posts tap to continue`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.script(ScriptedProvider.text("Picked it up."))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        g.conversations.appendUserMessage(chat, "summarise the report when it's ready")
        val runner = g.runner
        val posted = mutableListOf<Pair<String, String>>()
        runner.onBackgroundStartRefused = { id, title, _ -> posted += id to title }
        runCurrent()

        g.context.refuseForegroundServices = true
        runner.resumeAutomatically(chat)
        runCurrent()

        assertEquals(0, g.chatRequests())
        val state = runner.states.value.getValue(chat)
        assertFalse(state.running)
        assertTrue(state.interrupted)
        assertTrue(state.notice.orEmpty().contains("Continue"))
        assertEquals(listOf(chat to "Your message is waiting"), posted)

        // The user taps the notification: Locus is in front again and Continue runs the turn.
        g.context.refuseForegroundServices = false
        runner.resume(chat)
        val done = runner.states.first { it[chat]?.running == false && g.chatRequests() == 1 }.getValue(chat)
        assertFalse(done.interrupted)
        assertEquals(Role.ASSISTANT, g.conversations.history(chat).last().role)
    }

    @Test
    fun `a turn the user started still runs when the service is refused`() = runTest {
        val g = TestGraph(backgroundScope, settings)
        g.script(ScriptedProvider.text("Hello."))
        val chat = g.conversations.create(ScriptedProvider.config.id).id
        val runner = g.runner
        var posted = false
        runner.onBackgroundStartRefused = { _, _, _ -> posted = true }
        runCurrent()

        g.context.refuseForegroundServices = true
        runner.send(chat, "hi")
        runner.states.first { it[chat]?.running == false && g.chatRequests() == 1 }
        assertEquals("Hello.", g.conversations.history(chat).last().text)
        assertFalse(posted)
    }
}
