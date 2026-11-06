package dev.locus.feature.browser

import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The full-screen browser shows the approvals and questions of the chat it belongs to, from the same run
 * state as the chat and the notification (the real runner over TestGraph and a scripted provider):
 * answering in the browser resolves them everywhere, answering elsewhere clears them from the browser,
 * and the user's own browsing has none.
 */
class BrowserApprovalsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
        toolPolicies = mapOf("read_file" to ToolPolicy.ASK),
    )

    private class Fixture(val graph: TestGraph, val chat: String, val vm: BrowserViewModel)

    /** A chat whose agent reads notes.md (which asks), then says it's done. */
    private suspend fun TestScope.fixture(): Fixture {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        g.workspace.resolve("notes.md").writeText("buy milk")
        val replies = ArrayDeque(
            listOf(
                ScriptedProvider.toolCall("call_1", "read_file", """{"path":"notes.md"}"""),
                ScriptedProvider.text("Read it."),
            ),
        )
        g.http = { request ->
            if (request.url.host == "llm.test") {
                val next = synchronized(replies) { replies.removeFirstOrNull() } ?: error("No scripted reply left")
                respond(next, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val chat = g.conversations.create(settings.defaultProviderId).id
        // The chat's browser panel opened full screen: its browser is the focused one.
        g.browser.focus(chat)
        val vm = host.add(BrowserViewModel(g.browser, g.runner))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun BrowserViewModel.ui(until: (BrowserUi) -> Boolean) = uiState.first(until)

    @Test
    fun `the chat's pending approval shows in its browser, and answering there resolves it for everyone`() = runTest {
        val f = fixture()
        f.graph.runner.send(f.chat, "read my notes")

        val waiting = f.vm.ui { it.approvals.isNotEmpty() }
        val request = waiting.approvals.single()
        assertEquals("read_file", request.toolName)
        assertEquals(f.chat, request.conversationId)
        assertTrue(waiting.waitingForAnswer)
        // The same request the chat shows.
        assertEquals(request, f.graph.runner.states.value[f.chat]?.pendingApproval)

        f.vm.answer(request.callId, approved = true)
        f.vm.ui { it.approvals.isEmpty() && !it.agentRunning }
        val run = f.graph.runner.states.value[f.chat]
        assertTrue(run?.approvals.orEmpty().isEmpty())
        assertTrue(f.graph.runner.pendingApprovals.isEmpty(), "nothing left for the notification either")
        // Allowed: the tool ran, and the agent finished its turn.
        assertTrue(f.graph.conversations.history(f.chat).flatMap { it.toolResults }.single().content.contains("buy milk"))
    }

    @Test
    fun `a "don't ask again" answer from the browser is the chat's answer`() = runTest {
        val f = fixture()
        f.graph.runner.send(f.chat, "read my notes")
        val request = f.vm.ui { it.approvals.isNotEmpty() }.approvals.single()

        f.vm.answerRemembering(request.callId, ApprovalAnswer.ALLOW_FOR_CHAT)
        f.vm.ui { it.approvals.isEmpty() && !it.agentRunning }
        assertTrue(f.graph.runner.pendingApprovals.isEmpty())
    }

    @Test
    fun `answered elsewhere, as from the notification, it leaves the browser`() = runTest {
        val f = fixture()
        f.graph.runner.send(f.chat, "read my notes")
        val request = f.vm.ui { it.approvals.isNotEmpty() }.approvals.single()

        // What ApprovalReceiver does for the notification's Deny.
        f.graph.runner.answer(request.callId, false)
        val after = f.vm.ui { it.approvals.isEmpty() }
        assertFalse(after.waitingForAnswer)
    }

    @Test
    fun `the user's own browsing shows no approvals, even while a chat waits on one`() = runTest {
        val f = fixture()
        f.graph.runner.send(f.chat, "read my notes")
        f.vm.ui { it.approvals.isNotEmpty() }

        f.vm.showStandalone()
        val standalone = f.vm.ui { it.standalone }
        assertTrue(standalone.approvals.isEmpty())
        assertTrue(standalone.questions.isEmpty())
        assertFalse(standalone.waitingForAnswer)
        // Still waiting in the chat.
        assertEquals(1, f.graph.runner.states.value[f.chat]?.approvals?.size)
        f.graph.runner.cancel(f.chat)
    }

    @Test
    fun `another chat's approval doesn't show in this chat's browser`() = runTest {
        val f = fixture()
        val other = f.graph.conversations.create(settings.defaultProviderId).id
        f.graph.runner.send(other, "read my notes")
        f.graph.runner.states.first { it[other]?.approvals?.isNotEmpty() == true }
        runCurrent()

        assertTrue(f.vm.uiState.value.approvals.isEmpty())
        f.graph.runner.cancel(other)
    }

    @Test
    fun `what TalkBack hears and the queue count`() {
        assertEquals("1 of 3", queueText(3))
        assertEquals("Locus needs your approval. Click: Sign in", waitingAnnouncement("Click: Sign in", null, 1))
        assertEquals("Locus asks: Which date? 1 of 2 waiting.", waitingAnnouncement(null, "Which date?", 2))
        assertEquals("Locus needs your approval. Click: Sign in. 1 of 2 waiting.", waitingAnnouncement("Click: Sign in", null, 2))
        assertEquals("", waitingAnnouncement(null, null, 0))
    }
}
