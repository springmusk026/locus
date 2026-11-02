package dev.locus.core.data.integrations

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.datastore.SecretStore
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Telegram counts as connected only with a saved token and chat that parse. */
class TelegramSetupTest {
    private val token = "123456789:AAHabcdefghijklmnopqrstuvwxyz012345"
    private val name = SecretStore.connectorSecret(Integrations.telegram.secret)

    @Test
    fun `connected only when the saved value parses`() = runTest {
        val g = TestGraph(backgroundScope)
        assertFalse(g.telegram.connected.first())

        // A bare token (what a plain key field would save) can't deliver anything.
        g.secrets.put(name, token)
        assertFalse(g.telegram.connected.first())

        g.telegram.save(token, 42L)
        assertTrue(g.telegram.connected.first())
        assertEquals(42L, g.telegram.savedChatId())

        g.telegram.remove()
        assertFalse(g.telegram.connected.first())
    }
}
