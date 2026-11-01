package dev.locus.feature.chat

import dev.locus.core.model.AppSettings
import dev.locus.core.model.Effort
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolGroup
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ChatViewModel over the real repositories and runner (TestGraph), with a scripted provider: what the
 * screen shows for a chat and what each action changes.
 */
class ChatViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    /** runTest that clears the view models at the end, before Main is reset. */
    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val quiet = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val chat: String, val vm: ChatViewModel)

    private suspend fun TestScope.fixture(settings: AppSettings = quiet): Fixture {
        val g = TestGraph(backgroundScope, settings)
        val chat = g.conversations.create(settings.defaultProviderId).id
        val vm = host.add(ChatViewModel(chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library, g.workspace, g.onDevice, g.sentRequests, g.sideQuestions))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, chat, vm)
    }

    private suspend fun ChatViewModel.state(until: (ChatUiState) -> Boolean) = uiState.first { !it.loading && until(it) }

    @Test
    fun `a chat shows its provider, model and the provider's defaults`() = runTest {
        val f = fixture()
        val s = f.vm.state { it.provider != null }
        assertEquals("Scripted", s.provider?.name)
        assertEquals("test-model", s.controls.model)
        assertFalse(s.controls.modelOverridden)
        assertEquals(Effort.HIGH, s.controls.effort)
        assertFalse(s.controls.effortOverridden)
        assertEquals(100_000, s.tokens.window)
        assertEquals(listOf("Scripted"), s.providers.map { it.name })
    }

    @Test
    fun `picking another model overrides it for this chat only, and hides a window that may not apply`() = runTest {
        val f = fixture()
        f.vm.setModel(ScriptedProvider.config.id, "bigger-model")
        val s = f.vm.state { it.controls.modelOverridden }
        assertEquals("bigger-model", s.controls.model)
        assertEquals("bigger-model", s.provider?.model)
        assertNull(s.tokens.window)
        assertEquals(ModelRef(ScriptedProvider.config.id, "bigger-model"), f.graph.settingsRepository.current().recentModels.first())

        // Picking the provider's own model goes back to following the provider.
        f.vm.setModel(ScriptedProvider.config.id, "test-model")
        assertFalse(f.vm.state { !it.controls.modelOverridden }.controls.modelOverridden)
        assertNull(f.graph.conversations.get(f.chat)?.modelOverride)
    }

    @Test
    fun `effort, plan mode and switched-off tools are saved on the chat`() = runTest {
        val f = fixture()
        f.vm.setEffort(Effort.LOW)
        f.vm.setPlanMode(true)
        f.vm.setToolEnabled(ToolGroup.WEB.key, enabled = false)
        f.vm.setToolEnabled(ToolGroup.connectorKey("github"), enabled = false)
        var s = f.vm.state { it.controls.disabledCount == 2 }
        assertEquals(Effort.LOW, s.controls.effort)
        assertTrue(s.controls.effortOverridden)
        assertTrue(s.controls.planMode)

        f.vm.setToolEnabled(ToolGroup.WEB.key, enabled = true)
        f.vm.cancelPlan()
        f.vm.setEffort(null)
        s = f.vm.state { it.controls.disabledCount == 1 && !it.controls.planMode && !it.controls.effortOverridden }
        assertEquals(setOf(ToolGroup.connectorKey("github")), s.controls.disabledTools)
        assertEquals(setOf(ToolGroup.connectorKey("github")), f.graph.conversations.get(f.chat)?.disabledTools)
    }

    @Test
    fun `sending runs a turn and the reply shows with its usage`() = runTest {
        val f = fixture()
        f.graph.script(ScriptedProvider.text("Hello! How can I help?", promptTokens = 1_200, completionTokens = 8))
        f.vm.send("  hi there  ")
        val s = f.vm.state { st -> !st.run.running && st.items.any { it is ChatItem.Assistant } }

        val user = s.items.first() as ChatItem.User
        assertEquals("hi there", user.text)
        val reply = s.items.filterIsInstance<ChatItem.Assistant>().single()
        assertEquals("Hello! How can I help?", reply.text)
        assertEquals(1_208, s.tokens.context)
        assertEquals(1, s.tokens.requests)
        assertEquals("hi there", s.title)
        assertNull(s.run.error)
        // The runner keeps the process alive with its foreground service while it works.
        assertTrue(f.graph.context.startedServices.isNotEmpty())
    }

    @Test
    fun `what was sent is kept for a reply from this run, and not for one from before the app started`() = runTest {
        val f = fixture()
        f.graph.script(ScriptedProvider.text("Hello!"))
        f.vm.send("hi")
        val reply = f.vm.state { st -> !st.run.running && st.items.any { it is ChatItem.Assistant } }
            .items.filterIsInstance<ChatItem.Assistant>().single()
        assertTrue(f.vm.sentKept(reply.key))

        // A reply whose request isn't in memory (from before a restart, or older): the menu says so, and the
        // sheet explains why.
        assertFalse(f.vm.sentKept("older-reply"))
        f.vm.showSent("older-reply")
        assertEquals(SentState.Missing, f.vm.sent.first { it != SentState.Loading })
    }

    @Test
    fun `a tool call and its result are paired on screen`() = runTest {
        val f = fixture()
        f.graph.workspace.resolve("notes.md").writeText("buy milk")
        f.graph.script(
            ScriptedProvider.toolCall("call_1", "read_file", """{"path":"notes.md"}"""),
            ScriptedProvider.text("Your note says: buy milk."),
        )
        f.vm.send("what's in my notes?")
        val s = f.vm.state { st -> !st.run.running && st.items.any { it is ChatItem.Assistant } }
        val call = s.items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("read_file", call.name)
        assertNotNull(call.result)
        assertFalse(call.result!!.isError)
        assertTrue("buy milk" in call.result!!.content)
        assertEquals("Your note says: buy milk.", s.items.filterIsInstance<ChatItem.Assistant>().single().text)
    }

    @Test
    fun `without a provider the turn stops with a reason and nothing is sent`() = runTest {
        val f = fixture(AppSettings())
        f.vm.send("hello")
        val s = f.vm.state { it.run.error != null }
        assertTrue("provider" in s.run.error!!)
        assertTrue(f.graph.requests.isEmpty())
        // The message itself is kept so the user doesn't lose it.
        assertEquals(listOf(Role.USER), f.graph.conversations.history(f.chat).map { it.role })
    }

    @Test
    fun `blank messages aren't sent`() = runTest {
        val f = fixture()
        f.vm.send("   ")
        runCurrent()
        assertTrue(f.graph.conversations.history(f.chat).isEmpty())
        assertTrue(f.graph.requests.isEmpty())
    }

    @Test
    fun `forking at a user message hands its text back as a draft`() = runTest {
        val f = fixture()
        f.graph.script(ScriptedProvider.text("First answer."))
        f.vm.send("first question")
        f.vm.state { st -> !st.run.running && st.items.any { it is ChatItem.Assistant } }
        val userId = f.graph.conversations.history(f.chat).first().id

        var created: Pair<String, String?>? = null
        f.vm.fork(userId, userMessage = true) { id, draft -> created = id to draft }.join()
        val (id, draft) = assertNotNull(created)
        assertEquals("first question", draft)
        assertTrue(f.graph.conversations.history(id).isEmpty())

        var fromReply: Pair<String, String?>? = null
        val replyId = f.graph.conversations.history(f.chat).last().id
        f.vm.fork(replyId, userMessage = false) { newId, d -> fromReply = newId to d }.join()
        assertNull(fromReply!!.second)
        assertEquals(2, f.graph.conversations.history(fromReply!!.first).size)
    }

    @Test
    fun `a proposed plan waits for the user, and approving sends the approval`() = runTest {
        val f = fixture()
        f.vm.setPlanMode(true)
        f.graph.script(
            ScriptedProvider.toolCall("p1", "plan_propose", """{"title":"Tidy","steps":["List files","Rename them"]}"""),
            ScriptedProvider.text("Renamed everything."),
        )
        f.vm.send("tidy my files")
        val waiting = f.vm.state { st -> !st.run.running && st.items.any { it is ChatItem.Plan } }
        assertEquals(PlanStatus.Waiting, waiting.items.filterIsInstance<ChatItem.Plan>().single().status)
        // The turn ended on the plan: no second request until the user answers.
        assertEquals(1, f.graph.requests.size)

        f.vm.approvePlan()
        // Wait for the reply too: right after approving, the run may not have started yet.
        val approved = f.vm.state { st ->
            st.items.filterIsInstance<ChatItem.Plan>().singleOrNull()?.status == PlanStatus.Approved && !st.run.running &&
                st.items.filterIsInstance<ChatItem.Assistant>().any { it.text == "Renamed everything." }
        }
        assertFalse(approved.controls.planMode)
        assertEquals("Renamed everything.", approved.items.filterIsInstance<ChatItem.Assistant>().last().text)
    }

    @Test
    fun `removing a pending attachment drops it before sending`() = runTest {
        val f = fixture()
        val a = Part.Attachment("a.txt", "text/plain", "/w/a.txt", "inbox/a.txt", 1)
        f.vm.removeAttachment(a)
        assertTrue(f.vm.state { true }.pendingAttachments.isEmpty())
    }
}
