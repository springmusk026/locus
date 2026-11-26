package dev.locus.feature.chat

import dev.locus.core.data.repository.ChatDrafts
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The composer's unsent text outlives the chat screen (#319): leaving the chat, and restarting Locus. */
class ComposerDraftTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

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
    )

    private fun TestScope.chatScreen(g: TestGraph, chat: String, drafts: ChatDrafts): ChatViewModel {
        val vm = host.add(
            ChatViewModel(
                chat, g.conversations, g.settingsRepository, g.directory, g.runner, g.browser, g.workspaceRepository, g.library,
                g.workspace, g.onDevice, g.sentRequests, g.sideQuestions, g.changes, g.compute, g.receipts, drafts = drafts,
            ),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `a draft survives leaving the chat and a restart`() = runTest {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(settings.defaultProviderId).id
        val dir = File(tmp.root, "drafts")
        val drafts = ChatDrafts(dir)

        val first = chatScreen(g, chat, drafts)
        assertEquals("", first.savedDraft)
        first.setDraft("Compare the two quotes and")
        runCurrent()
        // Left before typing paused: the last keystrokes are kept all the same.
        host.clear()

        val again = chatScreen(g, chat, drafts)
        assertEquals("Compare the two quotes and", again.savedDraft)
        // On disk too: a new process reads it back.
        assertEquals("Compare the two quotes and", ChatDrafts(dir).get(chat))

        // Sent (the composer emptied): the draft goes, once typing pauses.
        again.setDraft("")
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(drafts.get(chat))
        assertNull(ChatDrafts(dir).get(chat))
    }

    @Test
    fun `a temporary chat's draft never goes to disk`() = runTest {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val chat = g.conversations.create(settings.defaultProviderId, temporary = true).id
        val dir = File(tmp.root, "drafts")
        val drafts = ChatDrafts(dir)

        val vm = chatScreen(g, chat, drafts)
        vm.setDraft("a private thought")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("a private thought", drafts.get(chat))
        assertNull(ChatDrafts(dir).get(chat))
    }
}
