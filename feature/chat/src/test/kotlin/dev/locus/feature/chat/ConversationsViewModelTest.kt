package dev.locus.feature.chat

import dev.locus.core.database.ScheduledTaskEntity
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Effort
import dev.locus.core.model.ToolGroup
import dev.locus.core.model.SetupItem
import dev.locus.core.model.SshHost
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.data.setup.SetupChecklist
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

    @Test
    fun `home knows when telegram remote mode is on, and until when`() = runTest {
        val f = fixture()
        backgroundScope.launch { f.vm.remoteModeUntil.collect {} }
        runCurrent()
        assertNull(f.vm.remoteModeUntil.value)

        f.graph.telegram.setRemoteMode(on = true, durationMs = 60 * 60_000L)
        val until = f.vm.remoteModeUntil.first { it != null }!!
        assertEquals(f.graph.settingsRepository.current().telegramRemote.until, until)
        assertTrue(until > System.currentTimeMillis())

        f.graph.telegram.setRemoteMode(on = false, durationMs = 0)
        assertNull(f.vm.remoteModeUntil.first { it == null })
    }

    // ---- Quick start's setup cards (docs/design/onboarding-quick-start.md)

    private fun TestScope.setupFixture(s: AppSettings): Fixture {
        val g = TestGraph(backgroundScope, s, dispatcher = StandardTestDispatcher(testScheduler))
        val checklist = SetupChecklist(g.settingsRepository, g.linux, g.library)
        val vm = host.add(ConversationsViewModel(g.conversations, g.settingsRepository, g.directory, g.runner, g.library, g.tasks, g.workspaceRepository, checklist = checklist))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, vm)
    }

    /** Linux can't run under Robolectric (no proot), so its card never shows there: it counts as done. */
    private fun Fixture.linuxShows() = graph.linux.status.value.let { it !is LinuxStatus.Unavailable && it != LinuxStatus.Ready }

    private val quickStarted = settings.copy(onboardingDone = true, setupLater = SetupItem.entries.toSet())

    @Test
    fun `after quick start home lists every skipped step, in order`() = runTest {
        val f = setupFixture(quickStarted)
        val s = f.vm.ready { it.setup.isNotEmpty() }
        assertEquals(SetupItem.entries.filter { it != SetupItem.Linux || f.linuxShows() }, s.setup)
        assertEquals(SetupItem.Identity, s.setup.first())
    }

    @Test
    fun `someone upgrading from an older version, or who did the full setup, gets no setup cards`() = runTest {
        // An older version's settings have no setupLater; it decodes to empty.
        val f = setupFixture(settings.copy(onboardingDone = true))
        val s = f.vm.ready()
        assertTrue(s.setup.isEmpty())
        runCurrent()
        assertTrue(f.vm.ready().setup.isEmpty())
    }

    @Test
    fun `an older version's saved settings decode without setup cards`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"providers":[],"onboardingDone":true}"""
        assertTrue(json.decodeFromString(AppSettings.serializer(), old).setupLater.isEmpty())
    }

    @Test
    fun `a step set up elsewhere drops its card and is cleared for good`() = runTest {
        val f = setupFixture(quickStarted)
        f.vm.ready { SetupItem.Ssh in it.setup }
        // Settings → SSH machines.
        f.graph.settingsRepository.saveSshHost(SshHost("h", "box", "10.0.0.2", 22, "me", fingerprint = "SHA256:x"), password = null, privateKey = "KEY", passphrase = null)
        val s = f.vm.ready { SetupItem.Ssh !in it.setup }
        assertTrue(SetupItem.Identity in s.setup)
        // Cleared from settings, so removing the machine later doesn't bring the card back.
        f.graph.settingsRepository.current().let { assertFalse(SetupItem.Ssh in it.setupLater, it.setupLater.toString()) }
        f.graph.settingsRepository.deleteSshHost("h")
        runCurrent()
        assertFalse(SetupItem.Ssh in f.vm.ready().setup)
    }

    @Test
    fun `a connector key saved anywhere drops the connectors card`() = runTest {
        val f = setupFixture(quickStarted)
        f.vm.ready { SetupItem.Connectors in it.setup }
        f.graph.settingsRepository.setIntegrationKey(dev.locus.core.agent.integrations.Integrations.brave.secret, "BSAx9f2Kq7Lm3Np8Rt4Vw6Yz1Ab5Cd0Ef")
        f.vm.ready { SetupItem.Connectors !in it.setup }
    }

    @Test
    fun `dismissing a card shows the next one and is remembered`() = runTest {
        val f = setupFixture(quickStarted)
        f.vm.ready { it.setup.firstOrNull() == SetupItem.Identity }
        f.vm.dismissSetup(SetupItem.Identity)
        val s = f.vm.ready { SetupItem.Identity !in it.setup }
        assertTrue(s.setup.isNotEmpty())
        assertFalse(SetupItem.Identity in f.graph.settingsRepository.current().setupLater)
        // A new Home (the app reopened) reads the same.
        val again = host.add(ConversationsViewModel(f.graph.conversations, f.graph.settingsRepository, f.graph.directory, f.graph.runner, f.graph.library, f.graph.tasks, f.graph.workspaceRepository, checklist = SetupChecklist(f.graph.settingsRepository, f.graph.linux, f.graph.library)))
        backgroundScope.launch { again.uiState.collect {} }
        runCurrent()
        assertFalse(SetupItem.Identity in again.ready().setup)
    }

    @Test
    fun `with every card dismissed home has none`() = runTest {
        val f = setupFixture(quickStarted)
        f.vm.ready { it.setup.isNotEmpty() }
        SetupItem.entries.forEach { f.vm.dismissSetup(it) }
        f.vm.ready { it.setup.isEmpty() }
        assertTrue(f.graph.settingsRepository.current().setupLater.isEmpty())
    }

    @Test
    fun `linux counts as done once installed or when it can't run on the phone`() {
        val s = AppSettings()
        assertTrue(SetupItem.Linux in SetupChecklist.doneItems(s, LinuxStatus.Ready, emptySet(), 0))
        assertTrue(SetupItem.Linux in SetupChecklist.doneItems(s, LinuxStatus.Unavailable("no proot"), emptySet(), 0))
        assertFalse(SetupItem.Linux in SetupChecklist.doneItems(s, LinuxStatus.NotInstalled, emptySet(), 0))
        assertFalse(SetupItem.Linux in SetupChecklist.doneItems(s, LinuxStatus.Installing("Downloading"), emptySet(), 0))
        assertTrue(SetupItem.Connectors in SetupChecklist.doneItems(s, LinuxStatus.NotInstalled, emptySet(), 1))
        assertEquals(listOf(SetupItem.Identity, SetupItem.Connectors), SetupChecklist.pendingItems(setOf(SetupItem.Connectors, SetupItem.Identity, SetupItem.Ssh), setOf(SetupItem.Ssh)))
    }

    @Test
    fun `linux installed later drops its card`() = runTest {
        val f = setupFixture(settings.copy(onboardingDone = true, setupLater = setOf(SetupItem.Linux, SetupItem.Ssh)))
        f.vm.ready { SetupItem.Ssh in it.setup }
        f.graph.linux.runOnHost { command, _ -> ProcessBuilder("/bin/sh", "-c", command) }
        f.vm.ready { it.setup == listOf(SetupItem.Ssh) }
        assertFalse(SetupItem.Linux in f.graph.settingsRepository.current().setupLater)
    }

    // ---- Home's projects shelf

    private fun project(id: String, updatedAt: Long) = dev.locus.core.model.Project(id, id, "projects/$id", updatedAt = updatedAt)
    private fun chat(id: String, projectId: String?, updatedAt: Long) =
        dev.locus.core.model.Conversation(id, id, "p", "", createdAt = updatedAt, updatedAt = updatedAt, projectId = projectId)

    @Test
    fun `home projects put a waiting approval first, then a working chat, then the most recently active`() {
        val projects = listOf(project("old", 10), project("recent", 50), project("working", 20), project("waiting", 5))
        val chats = listOf(
            chat("c1", "recent", 100), chat("c2", "working", 30), chat("c3", "waiting", 6), chat("c4", "waiting", 7),
            chat("c5", null, 999),
        )
        val shelf = homeProjects(projects, chats, running = setOf("c2", "c3"), approvals = setOf("c3"))
        assertEquals(listOf("waiting", "working", "recent", "old"), shelf.map { it.project.id })
        val waiting = shelf[0]
        assertEquals(2, waiting.chats)
        // The chat waiting for an approval counts as waiting, not also as working.
        assertEquals(1, waiting.approvals)
        assertEquals(0, waiting.running)
        assertEquals(1, shelf[1].running)
    }

    @Test
    fun `a project's last activity is its newest chat, or the project itself when newer`() {
        val shelf = homeProjects(
            listOf(project("a", 10), project("b", 500)),
            listOf(chat("c1", "a", 300), chat("c2", "a", 200), chat("c3", "b", 100)),
            running = emptySet(),
            approvals = emptySet(),
        )
        assertEquals(listOf("b" to 500L, "a" to 300L), shelf.map { it.project.id to it.lastActive })
        assertEquals(listOf(1, 2), shelf.map { it.chats })
    }

    @Test
    fun `home lists projects with their chat count, leaving out empty chats`() = runTest {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val vm = host.add(ConversationsViewModel(g.conversations, g.settingsRepository, g.directory, g.runner, g.library, g.tasks, g.workspaceRepository, g.projects))
        backgroundScope.launch { vm.projects.collect {} }
        val garden = g.projects.create("garden-app")!!
        val portfolio = g.projects.create("Portfolio site")!!
        // A chat left empty in Portfolio site (opening it marks the project used), then, later, two
        // chats with messages in garden-app and one in no project.
        g.conversations.create(ScriptedProvider.config.id, projectId = portfolio.id)
        Thread.sleep(5)
        repeat(2) { g.conversations.appendUserMessage(g.conversations.create(ScriptedProvider.config.id, projectId = garden.id).id, "hi $it") }
        g.chatWith("outside")
        val shelf = vm.projects.first { list -> list.size == 2 && list.any { it.chats == 2 } }
        // garden-app was active last.
        assertEquals(listOf("garden-app" to 2, "Portfolio site" to 0), shelf.map { it.project.name to it.chats })
        assertTrue(shelf.all { it.running == 0 && it.approvals == 0 })
    }
}
