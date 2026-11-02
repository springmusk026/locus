package dev.locus.feature.chat

import org.junit.Test
import kotlin.test.assertEquals

/** When the composer shows the Terminal and Browser panel toggles. */
class PanelTogglesTest {
    private fun call(name: String) = ChatItem.ToolCall("call-$name", name, "{}", null)

    @Test fun noToggleForAChatThatUsedNeitherPanel() {
        assertEquals(PanelToggles(terminal = false, browser = false), panelToggles(listOf(call("read_file")), null, browserHasPage = true, open = null))
    }

    @Test fun terminalShowsOnceTheChatRanACommandOrOneIsRunning() {
        assertEquals(true, panelToggles(listOf(call("run_command")), null, browserHasPage = false, open = null).terminal)
        assertEquals(true, panelToggles(emptyList(), "run_command", browserHasPage = false, open = null).terminal)
    }

    @Test fun anOpenTerminalPanelKeepsItsToggle() {
        assertEquals(true, panelToggles(emptyList(), null, browserHasPage = false, open = LivePanelKind.Terminal).terminal)
    }

    @Test fun browserNeedsAnOpenPageUnlessBrowsingNow() {
        val browsed = listOf(call("browser_open"))
        assertEquals(true, panelToggles(browsed, null, browserHasPage = true, open = null).browser)
        assertEquals(false, panelToggles(browsed, null, browserHasPage = false, open = null).browser)
        assertEquals(true, panelToggles(emptyList(), "browser_open", browserHasPage = false, open = null).browser)
        // A page left open by another chat isn't this chat's.
        assertEquals(false, panelToggles(emptyList(), null, browserHasPage = true, open = LivePanelKind.Browser).browser)
    }
}
