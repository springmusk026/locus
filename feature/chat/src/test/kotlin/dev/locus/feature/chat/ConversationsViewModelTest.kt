package dev.locus.feature.chat

import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Effort
import dev.locus.core.model.ToolGroup
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The home screen: chat list, search, shortcuts' counts and the composer that starts a chat. */
class ConversationsViewModelTest {
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

    private val other = ScriptedProvider.config.copy(id = "other", name = "Other", model = "other-model")
    private val settings = AppSettings(
        providers = listOf(ScriptedProvider.config, other), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
    )

    private class Fixture(val graph: TestGraph, val vm: ConversationsViewModel)

    private fun TestScope.fixture(s: AppSettings = settings): Fixture {
        val g = TestGraph(backgroundScope, s, dispatcher = StandardTestDispatcher(testScheduler))
        val vm = host.add(ConversationsViewModel(g.conversations, g.settingsRepository, g.directory, g.runner, g.library, g.tasks, g.workspaceRepository))
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.composer.collect {} }
        runCurrent()
        return Fixture(g, vm)
    }

    private suspend fun ConversationsViewModel.ready(until: (ConversationsUiState.Ready) -> Boolean = { true }) =
        uiState.first { it is ConversationsUiState.Ready && until(it) } as ConversationsUiState.Ready

    private suspend fun TestGraph.chatWith(text: String): String {
        val id = conversations.create(ScriptedProvider.config.id).id
        conversations.appendUserMessage(id, text)
        return id
    }

    @Test
    fun `with no provider the home says so`() = runTest {
        val s = fixture(AppSettings()).vm.ready()
        assertFalse(s.hasProvider)
        assertNull(s.defaultModel)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `lists chats with their provider and model, hiding ones left empty`() = runTest {
        val f = fixture()
        val used = f.graph.chatWith("hello")
        f.graph.conversations.create(ScriptedProvider.config.id) // opened and left
        f.graph.conversations.setModel(used, "other", "custom-model")

        val s = f.vm.ready { it.rows.singleOrNull()?.model == "custom-model" }
        val row = s.rows.single()
        assertEquals(used, row.conversation.id)
        assertEquals("Other", row.providerName)
        assertFalse(row.running)
        assertEquals("Scripted", s.defaultModel?.providerName)
        assertEquals("test-model", s.defaultModel?.model)
    }

    @Test
    fun `search shows nothing until its results arrive, then only the matches`() = runTest {
        val f = fixture()
        val lisbon = f.graph.chatWith("flights to lisbon")
        f.graph.chatWith("pasta recipe")
        f.vm.ready { it.rows.size == 2 }

        f.vm.search("lisbon")
        runCurrent()
        val pending = f.vm.ready { it.query == "lisbon" }
        assertTrue(pending.searchPending)
        assertTrue(pending.rows.isEmpty())

        advanceTimeBy(250)
        val found = f.vm.ready { it.query == "lisbon" && !it.searchPending }
        assertEquals(listOf(lisbon), found.rows.map { it.conversation.id })
        assertEquals(lisbon, found.hits.keys.single())

        f.vm.search(null)
        assertEquals(2, f.vm.ready { it.query == null }.rows.size)
    }

    @Test
    fun `starting from the composer applies its choices to the new chat, then resets`() = runTest {
        val f = fixture()
        f.graph.script(ScriptedProvider.text("Sure."))
        f.vm.setModel("other", " picked-model ")
        f.vm.setEffort(Effort.MAX)
        f.vm.setPlanMode(true)
        f.vm.setToolEnabled(ToolGroup.BROWSER.key, enabled = false)
        val composed = f.vm.composer.first { it.settings.controls.planMode }
        assertEquals("picked-model", composed.settings.controls.model)
        assertTrue(composed.settings.controls.modelOverridden)
        assertEquals("Other", composed.settings.provider?.name)

        var opened: String? = null
        f.vm.start("  plan my week  ") { opened = it }
        runCurrent()
        val id = f.vm.ready { r -> r.rows.any { it.conversation.id == opened } }.rows.first { it.conversation.id == opened }.conversation.id
        val chat = f.graph.conversations.get(id)!!
        assertEquals("other", chat.providerId)
        assertEquals("picked-model", chat.modelOverride)
        assertEquals(Effort.MAX, chat.effort)
        assertTrue(chat.planMode)
        assertEquals(setOf(ToolGroup.BROWSER.key), chat.disabledTools)
        assertEquals("plan my week", f.graph.conversations.history(id).first().userText)

        val reset = f.vm.composer.first { !it.settings.controls.planMode }
        assertEquals("test-model", reset.settings.controls.model)
        assertFalse(reset.settings.controls.effortOverridden)
        assertTrue(reset.settings.controls.disabledTools.isEmpty())
    }

    @Test
    fun `picking the provider's own model stores no override`() = runTest {
        val f = fixture()
        f.graph.script(ScriptedProvider.text("ok"))
        f.vm.setModel("other", "other-model")
        var opened: String? = null
        f.vm.start("hi") { opened = it }
        runCurrent()
        f.vm.ready { r -> r.rows.any { it.conversation.id == opened } }
        assertNull(f.graph.conversations.get(opened!!)!!.modelOverride)
        assertEquals("other", f.graph.conversations.get(opened!!)!!.providerId)
    }

    @Test
    fun `an empty composer starts nothing`() = runTest {
        val f = fixture()
        var opened: String? = null
        f.vm.start("   ") { opened = it }
        runCurrent()
        assertNull(opened)
        assertTrue(f.graph.database.conversationRows.isEmpty())
    }

    @Test
    fun `rename trims and caps titles and ignores blanks, pin and delete apply`() = runTest {
        val f = fixture()
        val id = f.graph.chatWith("hello")
        f.vm.rename(id, "   ")
        f.vm.rename(id, "  " + "t".repeat(200) + "  ")
        f.vm.setPinned(id, true)
        val row = f.vm.ready { it.rows.singleOrNull()?.conversation?.pinned == true }.rows.single()
        assertEquals(120, row.conversation.title.length)

        f.vm.delete(id)
        assertTrue(f.vm.ready { it.rows.isEmpty() }.rows.isEmpty())
    }

    @Test
    fun `home counts what the library and tasks hold, and only a fresh task result`() = runTest {
        val f = fixture()
        f.graph.home.saveMemory("coffee", "Flat white", "Flat white, no sugar.")
        f.graph.home.draftSkill("weekly-report", "Builds the report.", "1. Collect", emptyMap())
        val now = System.currentTimeMillis()
        fun task(id: String, enabled: Boolean, lastRunAt: Long?, summary: String?) =
            ScheduledTaskEntity(id, "Task $id", "p", 60, enabled, 0, lastRunAt, null, summary)
        f.graph.database.scheduledTaskDao.upsert(task("a", true, now - 60_000, " Inbox is empty. "))
        f.graph.database.scheduledTaskDao.upsert(task("b", false, now - 2 * 24 * 3_600_000L, "old news"))
        f.graph.library.refresh()

        // Wait for everything asserted below: a library read from before draftSkill could already show the
        // memory with no draft to review yet.
        val counts = f.vm.ready { it.counts.tasksTotal == 2 && it.counts.memories == 1 && it.counts.toReview == 1 }.counts
        assertEquals(1, counts.tasksActive)
        assertEquals(1, counts.toReview)
        assertEquals(0, counts.skills)
        assertEquals("Inbox is empty.", counts.latestResult?.summary)
        assertEquals("Task a", counts.latestResult?.title)
    }

    @Test
    fun `a chat that is running shows even before its first message lands`() = runTest {
        val f = fixture()
        f.graph.script(ScriptedProvider.text("hi"))
        val id = f.graph.conversations.create(ScriptedProvider.config.id).id
        // Wait for the list rather than read uiState.value: it is Ready only once the library has been read.
        // Left empty, the chat is hidden.
        assertTrue(f.vm.ready().rows.none { it.conversation.id == id })
        f.graph.runner.send(id, "hello")
        val done = f.vm.ready { r -> r.rows.any { it.conversation.id == id } && r.rows.none { it.running } }
        assertEquals(id, done.rows.single().conversation.id)
    }
}
