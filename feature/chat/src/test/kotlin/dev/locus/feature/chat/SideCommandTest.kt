package dev.locus.feature.chat

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SideCommandTest {
    @Test
    fun `btw with a question asks it`() {
        assertEquals(SideCommand.Ask("what was that config file?"), SideCommand.parse("/btw what was that config file?"))
        assertEquals(SideCommand.Ask("why npm?"), SideCommand.parse("  /BTW   why npm?  "))
        assertEquals(SideCommand.Ask("line one\nline two"), SideCommand.parse("/btw\nline one\nline two"))
    }

    @Test
    fun `btw alone reopens the last answer`() {
        assertEquals(SideCommand.Reopen, SideCommand.parse("/btw"))
        assertEquals(SideCommand.Reopen, SideCommand.parse(" /btw   "))
    }

    @Test
    fun `anything else is an ordinary message`() {
        assertNull(SideCommand.parse("btw, also keep the drafts"))
        assertNull(SideCommand.parse("/btwx hello"))
        assertNull(SideCommand.parse("please /btw this"))
        assertNull(SideCommand.parse(""))
    }

    @Test
    fun `the Aside switch makes plain text a side question, the command wins either way`() {
        assertEquals(SideCommand.Ask("is it on main?"), sideCommandFor(" is it on main? ", asideOn = true))
        assertNull(sideCommandFor("keep going", asideOn = false))
        assertNull(sideCommandFor("   ", asideOn = true))
        assertEquals(SideCommand.Ask("x"), sideCommandFor("/btw x", asideOn = false))
        assertEquals(SideCommand.Reopen, sideCommandFor("/btw", asideOn = true))
    }

    @Test
    fun `the card says the task keeps going only while it runs`() {
        assertEquals("Not added to the chat. The task keeps going.", sideCaption(running = true))
        assertEquals("Not added to the chat.", sideCaption(running = false))
    }
}
