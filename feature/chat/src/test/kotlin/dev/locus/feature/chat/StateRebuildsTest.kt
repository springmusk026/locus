package dev.locus.feature.chat

import dev.locus.core.browser.BrowserState
import dev.locus.core.browser.TabInfo
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.Conversation
import dev.locus.core.ondevice.Accelerator
import dev.locus.core.ondevice.TurnSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The chat and home screens rebuild their state only when something they show changed: not on each
 * percent of a page load, an on-device reply in another chat, every tool call of every chat, or Room
 * re-emitting an unchanged chat list (#342).
 */
class StateRebuildsTest {
    @Test
    fun `a page load's progress ticks don't rebuild the chat, a new title does`() = runTest {
        val page = BrowserState(url = "https://example.com", title = "Example", hasPage = true)
        val ticks = (0..100).map { page.copy(loading = it < 100, progress = it, tabs = listOf(TabInfo("Example", page.url))) }
        val seen = (ticks + ticks.last().copy(title = "Example Domain")).asFlow().forChat().toList()
        assertEquals(
            listOf(
                ChatBrowser(hasPage = true, title = "Example", url = "https://example.com", handoffReason = null),
                ChatBrowser(hasPage = true, title = "Example Domain", url = "https://example.com", handoffReason = null),
            ),
            seen,
        )
    }

    @Test
    fun `a cloud chat ignores on-device reply speeds, an on-device chat follows them`() = runTest(UnconfinedTestDispatcher()) {
        val speed = TurnSpeed(Accelerator.GPU, 10, 100.0, 20, 30.0, measured = true)
        val recent = MutableStateFlow<Map<String, TurnSpeed>>(emptyMap())
        val onDeviceChat = MutableStateFlow(false)
        val seen = mutableListOf<Pair<Boolean, Map<String, TurnSpeed>>>()
        val job = launch { onDeviceSpeeds(onDeviceChat, recent).collect { seen += it } }
        repeat(10) { recent.value = mapOf("reply $it" to speed) }
        assertEquals(listOf(false to emptyMap()), seen)

        onDeviceChat.value = true
        recent.value = mapOf("another" to speed)
        assertEquals(listOf(false to emptyMap(), true to mapOf("reply 9" to speed), true to mapOf("another" to speed)), seen)
        job.cancel()
    }

    @Test
    fun `tool calls don't rebuild home's rows, only the featured card's tool`() = runTest {
        val tools = listOf("web_search", "fetch_url", "run_command", "browser_click", null)
        val states = listOf(mapOf("a" to RunState(running = true))) +
            tools.map { mapOf("a" to RunState(running = true, activeTool = it, streamingText = "…")) } +
            listOf(mapOf("a" to RunState(running = false)))
        assertEquals(
            listOf(mapOf("a" to RowRun(running = true, approval = false, interrupted = false)), mapOf("a" to RowRun(running = false, approval = false, interrupted = false))),
            rowRuns(states.asFlow()).toList(),
        )
        assertEquals(
            listOf(emptyMap(), mapOf("a" to "web_search"), mapOf("a" to "fetch_url"), mapOf("a" to "run_command"), mapOf("a" to "browser_click"), emptyMap()),
            activeToolsOf(states.asFlow()).toList(),
        )
    }

    @Test
    fun `an open search runs again when a chat's messages or title change, not on every write to the table`() = runTest {
        val chat = Conversation("a", "Lisbon trip", "p", "", createdAt = 1, updatedAt = 2)
        val lists = listOf(
            listOf(chat),
            listOf(chat), // Room re-emits after a helper chat's write
            listOf(chat.copy(modelOverride = "other-model", pinned = true)),
            listOf(chat.copy(updatedAt = 3)), // a new message
            listOf(chat.copy(updatedAt = 3, title = "Lisbon in May")),
            listOf(chat.copy(updatedAt = 3, title = "Lisbon in May"), Conversation("b", "New", "p", "", 4, 4)),
        )
        assertEquals(4, searchInputs(lists.asFlow()).toList().size)
    }
}
