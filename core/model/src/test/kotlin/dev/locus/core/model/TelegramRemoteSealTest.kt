package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the Telegram chat may do is sealed with the permissions (docs/design/telegram-remote.md). */
class TelegramRemoteSealTest {
    @Test
    fun `remote settings are sealed, and a broken seal switches them off`() {
        val key = TestKey()
        val on = AppSettings(telegramRemote = TelegramRemoteSettings(approvals = true, inbound = true, since = 1, until = 2, widened = true, chatId = "tg"))
        assertFalse(PolicySeal.isDefault(on))
        val sealed = PolicySeal.sealed(on, key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        // Widened, or a later time limit, from outside Locus breaks the seal.
        val base = PolicySeal.sealed(AppSettings(telegramRemote = TelegramRemoteSettings(inbound = true, until = 2)), key)
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(base.copy(telegramRemote = base.telegramRemote.copy(widened = true)), key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(base.copy(telegramRemote = base.telegramRemote.copy(until = Long.MAX_VALUE)), key))
        val restricted = PolicySeal.restricted(sealed)
        assertFalse(restricted.telegramRemote.approvals || restricted.telegramRemote.inbound || restricted.telegramRemote.widened)
        assertEquals("tg", restricted.telegramRemote.chatId)
        // The chat id alone grants nothing, and settings without remote seal as before.
        assertTrue(PolicySeal.isDefault(AppSettings(telegramRemote = TelegramRemoteSettings(chatId = "tg"))))
        assertEquals(
            PolicySeal.seal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key),
            PolicySeal.seal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK), telegramRemote = TelegramRemoteSettings(chatId = "tg")), key),
        )
    }
}
