package dev.locus.core.data.agent

import dev.locus.core.model.AppSettings
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What happens after a task follows what the user changed while it ran (#341): reflection turned off mid-task
 * doesn't run, a title the user gave during title generation stays, and a scheduled run's chat is the user's
 * again once the run ends.
 */
class AfterTaskChangesTest {
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
        toolPolicies = mapOf("write_file" to ToolPolicy.ALLOW, "memory_save" to ToolPolicy.ALLOW),
    )

    /** One scripted reply; with [hold], it waits until completed. */
    private class Reply(val body: String, val hold: CompletableDeferred<Unit>? = null) {
        val asked = CompletableDeferred<Unit>()
    }

    private fun TestGraph.replies(vararg replies: Reply) {
        val queue = ArrayDeque(replies.toList())
        http = { request ->
            if (request.url.host == "llm.test") {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: error("No scripted reply left")
                next.asked.complete(Unit)
                next.hold?.await()
                respond(next.body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
    }

    private fun TestGraph.chatRequests() = requests.count { it.url.host == "llm.test" }

    private fun TestScope.graph(s: AppSettings) = TestGraph(backgroundScope, s, dispatcher = StandardTestDispatcher(testScheduler))

    /** Waits in real time: the stores use their own threads (as in DeferredWorkTest). */
    private suspend fun until(what: String, check: suspend () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (!check()) delay(10) }
        }
        assertTrue(check(), what)
    }

    @Test
    fun `reflection turned off during a long task doesn't run after it`() = runTest {
        val g = graph(settings.copy(reflectAfterTasks = true))
        val chat = g.conversations.create(settings.defaultProviderId).id
        val last = Reply(ScriptedProvider.text("All six written."), hold = CompletableDeferred())
        val reflection = Reply(ScriptedProvider.toolCall("r1", "memory_save", """{"topic":"lesson","summary":"s","content":"c"}"""))
        g.replies(
            *(1..6).map { Reply(ScriptedProvider.toolCall("c$it", "write_file", """{"path":"f$it.md","content":"$it"}""")) }.toTypedArray(),
            last, reflection, Reply(ScriptedProvider.text("saved")),
        )
        g.runner.send(chat, "write six files")
        last.asked.await()
        // Six tools ran, enough for a reflection; the user turns it off before the task ends.
        g.settingsRepository.setReflectAfterTasks(false)
        last.hold!!.complete(Unit)
        until("the task ended") { g.runner.states.value[chat]?.running == false }
        // A reflection starts right after the task; give it the time it would take to ask.
        withContext(Dispatchers.Default) { delay(500) }

        assertFalse(reflection.asked.isCompleted)
        assertEquals(7, g.chatRequests())
        assertTrue(g.home.memoryTopics().isEmpty())
    }

    @Test
    fun `a title the user gives while one is generated stays`() = runTest {
        // Follow-ups too: they're asked for after the title, so their request says the title step is done.
        val g = graph(settings.copy(autoTitle = true, suggestFollowUps = true))
        val chat = g.conversations.create(settings.defaultProviderId).id
        val title = Reply(ScriptedProvider.text("Fence painting plan"), hold = CompletableDeferred())
        val followUps = Reply(ScriptedProvider.text("Which brush?"))
        g.replies(Reply(ScriptedProvider.text("Blue suits it.")), title, followUps)
        g.runner.send(chat, "Which colour should I paint the fence?")
        title.asked.await()

        g.conversations.rename(chat, "Garden")
        title.hold!!.complete(Unit)
        followUps.asked.await()

        assertEquals("Garden", g.conversations.get(chat)?.title)
    }

    @Test
    fun `a scheduled run's chat isn't marked scheduled once the run ends`() = runTest {
        val g = graph(settings)
        val chat = g.conversations.create(settings.defaultProviderId).id
        g.replies(Reply(ScriptedProvider.text("No rain today.")))

        val run = g.runner.runInBackground(chat, "Will it rain?")

        assertEquals("No rain today.", run.text)
        assertFalse(g.runner.states.value[chat]?.scheduled == true)
    }
}
