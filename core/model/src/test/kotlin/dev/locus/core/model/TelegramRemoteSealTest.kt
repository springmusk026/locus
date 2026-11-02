package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // The chat id isn't trusted after a broken seal either.
        assertNull(restricted.telegramRemote.chatId)
        assertEquals(0L, restricted.telegramRemote.since)
        // Settings without remote seal as before.
        assertEquals(
            PolicySeal.legacySeal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key),
            PolicySeal.seal(AppSettings(toolPolicies = mapOf("x" to ToolPolicy.ASK)), key),
        )
    }

    @Test
    fun `the chat id and since are sealed`() {
        val key = TestKey()
        // The chat id alone is now something to protect: it picks the remote chat and the read-only one.
        assertFalse(PolicySeal.isDefault(AppSettings(telegramRemote = TelegramRemoteSettings(chatId = "tg"))))
        assertFalse(PolicySeal.isDefault(AppSettings(telegramRemote = TelegramRemoteSettings(since = 5))))
        val sealed = PolicySeal.sealed(AppSettings(telegramRemote = TelegramRemoteSettings(inbound = true, since = 10, until = 99, chatId = "tg")), key)
        assertEquals(SealCheck.VALID, PolicySeal.check(sealed, key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(telegramRemote = sealed.telegramRemote.copy(chatId = "other")), key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(sealed.copy(telegramRemote = sealed.telegramRemote.copy(since = 0)), key))
        // Off, with only the chat id left, is sealed too.
        val off = PolicySeal.sealed(AppSettings(telegramRemote = TelegramRemoteSettings(chatId = "tg")), key)
        assertEquals(SealCheck.VALID, PolicySeal.check(off, key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(off.copy(telegramRemote = off.telegramRemote.copy(chatId = "other")), key))
    }

    @Test
    fun `an install sealed before the chat id was migrates without tripping the seal`() {
        val key = TestKey()
        val old = AppSettings(
            toolPolicies = mapOf("x" to ToolPolicy.ALLOW),
            telegramRemote = TelegramRemoteSettings(approvals = true, inbound = true, since = 10, until = 99, chatId = "tg"),
        )
        val oldSealed = old.copy(policySeal = PolicySeal.legacySeal(old, key))
        assertEquals(SealCheck.LEGACY, PolicySeal.check(oldSealed, key))
        val migrated = PolicySeal.migrated(oldSealed, now = 50)
        // What the old seal didn't cover is made safe: a new Telegram chat, and nothing older than now.
        assertNull(migrated.telegramRemote.chatId)
        assertEquals(50L, migrated.telegramRemote.since)
        assertTrue(migrated.telegramRemote.approvals && migrated.telegramRemote.inbound)
        assertEquals(mapOf("x" to ToolPolicy.ALLOW), migrated.toolPolicies)
        assertEquals(SealCheck.VALID, PolicySeal.check(PolicySeal.sealed(migrated, key), key))
        // An old seal over different permissions is still tampering.
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(oldSealed.copy(toolPolicies = mapOf("y" to ToolPolicy.ALLOW)), key))
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(oldSealed.copy(telegramRemote = oldSealed.telegramRemote.copy(widened = true)), key))

        // Unsealed, because only the chat id was set (once "default"): migrated too, not an alert.
        val unsealed = AppSettings(telegramRemote = TelegramRemoteSettings(since = 10, chatId = "tg"))
        assertEquals(SealCheck.LEGACY, PolicySeal.check(unsealed, key))
        assertTrue(PolicySeal.isDefault(PolicySeal.migrated(unsealed, now = 50)))
        // But unsealed with something else granted stays tampering.
        assertEquals(SealCheck.TAMPERED, PolicySeal.check(unsealed.copy(toolPolicies = mapOf("x" to ToolPolicy.ALLOW)), key))
    }
}
