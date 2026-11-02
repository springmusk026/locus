package dev.locus.core.data.agent

import dev.locus.core.data.auth.LoopbackRedirect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenChatLinkTest {
    private val token = OpenChatLink.newToken()

    @Test
    fun `the chat opens only with Locus's token`() {
        assertEquals("chat-1", OpenChatLink.verified("chat-1", token, token))
    }

    @Test
    fun `another app's intent without the token, or with a wrong one, opens nothing`() {
        assertNull(OpenChatLink.verified("chat-1", null, token))
        assertNull(OpenChatLink.verified("chat-1", "", token))
        assertNull(OpenChatLink.verified("chat-1", OpenChatLink.newToken(), token))
        assertNull(OpenChatLink.verified("chat-1", token.dropLast(1), token))
    }

    @Test
    fun `no chat id, or no expected token, opens nothing`() {
        assertNull(OpenChatLink.verified(null, token, token))
        assertNull(OpenChatLink.verified(" ", token, token))
        assertNull(OpenChatLink.verified("chat-1", "", ""))
    }

    @Test
    fun `tokens are 256 random bits in hex`() {
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(token, OpenChatLink.newToken())
    }

    @Test
    fun `the debug app returns from a browser sign-in to its own scheme`() {
        assertEquals("locus://signed-in", LoopbackRedirect.returnUri("dev.locus"))
        assertEquals("locus-debug://signed-in", LoopbackRedirect.returnUri("dev.locus.debug"))
    }
}
