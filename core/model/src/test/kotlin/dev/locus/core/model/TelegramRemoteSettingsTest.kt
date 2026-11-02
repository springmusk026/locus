package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramRemoteSettingsTest {
    @Test
    fun `remote mode has an end time only while it's switched on`() {
        assertNull(TelegramRemoteSettings().switchesOffAt)
        // Switched off with an old end time left behind: nothing to show.
        assertNull(TelegramRemoteSettings(inbound = false, until = 5_000).switchesOffAt)
        val on = TelegramRemoteSettings(inbound = true, since = 1_000, until = 5_000)
        assertEquals(5_000, on.switchesOffAt)
        // A screen ticking against it sees it go off at that moment.
        assertTrue(on.inboundOn(4_999))
        assertFalse(on.inboundOn(5_000))
    }
}
