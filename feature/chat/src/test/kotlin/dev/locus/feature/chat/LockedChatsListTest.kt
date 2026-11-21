package dev.locus.feature.chat

import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.lock.DeviceSecurity
import dev.locus.core.model.AppSettings
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
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
import kotlin.test.assertTrue

/** Locked chats on home, in All chats and in search (docs/design/app-lock.md). */
class LockedChatsListTest {
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
    )

    private class Fixture(val graph: TestGraph, val lock: AppLock, val vm: ConversationsViewModel)

    private fun TestScope.fixture(secure: Boolean = true): Fixture {
        val g = TestGraph(backgroundScope, settings, dispatcher = StandardTestDispatcher(testScheduler))
        val lock = AppLock.forTest(g.settingsRepository, DeviceSecurity { secure }, backgroundScope)
        val vm = host.add(ConversationsViewModel(g.conversations, g.settingsRepository, g.directory, g.runner, g.library, g.tasks, g.workspaceRepository, appLock = lock))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, lock, vm)
    }

    private suspend fun ConversationsViewModel.ready(until: (ConversationsUiState.Ready) -> Boolean = { true }) =
        uiState.first { it is ConversationsUiState.Ready && until(it) } as ConversationsUiState.Ready

    private suspend fun TestGraph.chat(title: String, text: String): String {
        val id = conversations.create(ScriptedProvider.config.id).id
        conversations.appendUserMessage(id, text)
        conversations.rename(id, title)
        return id
    }

    @Test
    fun `a locked chat keeps its place but not its name until the session is unlocked`() = runTest {
        val f = fixture()
        val diary = f.graph.chat("My diary", "dear diary")
        f.graph.chat("Groceries", "milk")
        f.lock.setChatLocked(diary, true)
        val hidden = f.vm.ready { s -> s.rows.size == 2 && s.rows.any { it.redacted } }
        val row = hidden.rows.single { it.conversation.id == diary }
        assertTrue(row.locked)
        assertEquals("", row.conversation.title, "blanked in the state, not only on screen")
        assertTrue(hidden.chatLockAvailable)
        assertEquals("Groceries", hidden.rows.single { it.conversation.id != diary }.conversation.title)

        f.lock.unlocked()
        val open = f.vm.ready { s -> s.rows.none { it.redacted } }
        assertEquals("My diary", open.rows.single { it.conversation.id == diary }.conversation.title)
        assertTrue(open.rows.single { it.conversation.id == diary }.locked, "still a locked chat, shown with its padlock")
    }

    @Test
    fun `search leaves out a hidden locked chat, even a match on its name`() = runTest {
        val f = fixture()
        val diary = f.graph.chat("Lisbon diary", "flights to lisbon")
        val trip = f.graph.chat("Trip", "lisbon hotels")
        f.lock.setChatLocked(diary, true)
        f.vm.ready { s -> s.rows.any { it.redacted } }

        f.vm.search("lisbon")
        runCurrent()
        advanceTimeBy(250)
        val found = f.vm.ready { it.query == "lisbon" && !it.searchPending }
        assertEquals(listOf(trip), found.rows.map { it.conversation.id })
        assertFalse(diary in found.hits)
    }

    @Test
    fun `without a screen lock nothing is hidden and locking isn't offered`() = runTest {
        val f = fixture(secure = false)
        val diary = f.graph.chat("My diary", "dear diary")
        f.lock.setChatLocked(diary, true)
        val s = f.vm.ready { st -> st.rows.any { it.locked } }
        assertFalse(s.chatLockAvailable)
        assertFalse(s.rows.single().redacted)
    }

    @Test
    fun `deleting a locked chat drops it from the locked set`() = runTest {
        val f = fixture()
        val diary = f.graph.chat("My diary", "dear diary")
        f.lock.setChatLocked(diary, true)
        f.vm.ready { s -> s.rows.any { it.locked } }
        f.vm.delete(diary)
        runCurrent()
        assertTrue(f.graph.settingsRepository.current().appLock.lockedChats.isEmpty())
    }
}
