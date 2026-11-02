package dev.locus.feature.chat

import dev.locus.core.model.ChatMode
import dev.locus.core.model.ToolGroup
import org.junit.Test
import kotlin.test.assertEquals

class ChatModeControlsTest {
    @Test
    fun `the mode shows on its own chip and doesn't count as a switched-off tool`() {
        val controls = ChatControls(disabledTools = ChatMode.withMode(setOf(ToolGroup.EMAIL.key), ChatMode.WORKSPACE))
        assertEquals(ChatMode.WORKSPACE, controls.mode)
        assertEquals(1, controls.disabledCount)
        assertEquals(ChatMode.FULL, ChatControls().mode)
    }
}
