package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lock Locus's timing and what a locked chat hides (docs/design/app-lock.md). */
class AppLockTest {
    private val unlocked = LockSessions.unlock(LockSession())

    private fun away(s: LockSession, from: Long, to: Long, minutes: Int) = LockSessions.foreground(LockSessions.background(s, from), to, minutes)

    // ---- Lock after ------------------------------------------------------------------------------

    @Test
    fun `a new process starts locked`() {
        assertFalse(LockSession().unlocked)
        assertTrue(AppLockStatus(AppLockSettings(enabled = true), available = true).appLocked)
    }

    @Test
    fun `immediately locks on any trip away, however short`() {
        val back = away(unlocked, from = 1_000, to = 1_001, minutes = 0)
        assertFalse(back.unlocked)
        assertEquals(1, back.epoch, "a new session")
    }

    @Test
    fun `one minute keeps it open for 59 seconds and locks at 60`() {
        assertTrue(away(unlocked, from = 0, to = 59_999, minutes = 1).unlocked)
        assertFalse(away(unlocked, from = 0, to = 60_000, minutes = 1).unlocked)
    }

    @Test
    fun `five and fifteen minutes count from when Locus left the screen`() {
        assertTrue(away(unlocked, 10_000, 10_000 + 4 * 60_000, minutes = 5).unlocked)
        assertFalse(away(unlocked, 10_000, 10_000 + 5 * 60_000, minutes = 5).unlocked)
        assertTrue(away(unlocked, 0, 14 * 60_000, minutes = 15).unlocked)
        assertFalse(away(unlocked, 0, 16 * 60_000, minutes = 15).unlocked)
    }

    @Test
    fun `a second background report keeps the first time`() {
        val first = LockSessions.background(unlocked, 1_000)
        val second = LockSessions.background(first, 50_000)
        assertEquals(1_000, second.awaySince)
        assertFalse(LockSessions.foreground(second, 61_000, 1).unlocked)
    }

    @Test
    fun `a clock that went backwards locks`() {
        assertFalse(away(unlocked, from = 5_000, to = 1_000, minutes = 15).unlocked)
    }

    @Test
    fun `a trip Locus expected (its own prompt or a picker) doesn't lock with immediately, within the grace`() {
        val expecting = LockSessions.expectReturn(unlocked)
        assertTrue(away(expecting, 0, 30_000, minutes = 0).unlocked)
        assertFalse(away(expecting, 0, LockAfter.EXPECTED_RETURN_GRACE_MS, minutes = 0).unlocked, "a long trip still locks")
        val back = away(expecting, 0, 1_000, minutes = 0)
        assertFalse(back.expectingReturn, "the next trip counts again")
        assertFalse(away(back, 2_000, 2_001, minutes = 0).unlocked)
    }

    @Test
    fun `coming back without having left changes nothing`() {
        val s = LockSessions.foreground(unlocked, 99_000, 0)
        assertTrue(s.unlocked)
        assertEquals(0, s.epoch)
    }

    @Test
    fun `lock after is one of the choices, the nearest for an odd value`() {
        assertEquals(listOf(0, 1, 5, 15), LockAfter.CHOICES)
        assertEquals(5, LockAfter.normalized(4))
        assertEquals(15, LockAfter.normalized(60))
        assertEquals(0, LockAfter.normalized(-3))
        assertEquals(300_000, LockAfter.millis(5))
    }

    // ---- What's locked -------------------------------------------------------------------------

    @Test
    fun `the app is locked only with the switch on, a screen lock, and no unlock this session`() {
        val on = AppLockSettings(enabled = true)
        assertTrue(AppLockStatus(on, available = true).appLocked)
        assertFalse(AppLockStatus(on, available = true, session = unlocked).appLocked)
        assertFalse(AppLockStatus(AppLockSettings(), available = true).appLocked)
        assertFalse(AppLockStatus(on, available = false).appLocked, "no screen lock: suspended, not a lock-out")
        assertTrue(AppLockStatus(on, available = false).suspended)
    }

    @Test
    fun `a locked chat is hidden in the app until the session is unlocked, and always outside it`() {
        val settings = AppLockSettings(lockedChats = setOf("secret"))
        val locked = AppLockStatus(settings, available = true)
        assertTrue(locked.chatNeedsAuth("secret"))
        assertTrue(locked.redactInApp("secret"))
        assertFalse(locked.redactInApp("other"))
        assertFalse(locked.redactInApp(null))

        val open = locked.copy(session = unlocked)
        assertFalse(open.chatNeedsAuth("secret"))
        assertFalse(open.redactInApp("secret"))
        assertTrue(open.redactOutside("secret"), "notifications and shortcuts never name it")
        assertFalse(open.redactOutside("other"))
    }

    @Test
    fun `locked chats work without Lock Locus on`() {
        val status = AppLockStatus(AppLockSettings(enabled = false, lockedChats = setOf("c")), available = true)
        assertFalse(status.appLocked)
        assertTrue(status.chatNeedsAuth("c"))
    }

    @Test
    fun `the session key changes when the session locks and differs between processes`() {
        val a = AppLockStatus(session = unlocked, process = "p1")
        val relocked = a.copy(session = LockSessions.lock(a.session))
        assertTrue(a.sessionKey != relocked.sessionKey)
        assertTrue(a.sessionKey != a.copy(process = "p2").sessionKey)
    }

    // ---- Settings ------------------------------------------------------------------------------

    @Test
    fun `the lock's settings default to off and survive a round trip through the settings file`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        assertEquals(AppLockSettings(), AppSettings().appLock)
        assertFalse(AppSettings().appLock.enabled)
        val saved = AppSettings(appLock = AppLockSettings(enabled = true, lockAfterMinutes = 5, lockedChats = setOf("a", "b")))
        assertEquals(saved.appLock, json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), saved)).appLock)
        // Settings written before the lock existed read as off.
        assertEquals(AppLockSettings(), json.decodeFromString(AppSettings.serializer(), "{}").appLock)
    }

    // ---- Authenticator per API level -----------------------------------------------------------

    @Test
    fun `Android 11 and up use allowed authenticators, 10 the credential flag, 8 to 9 the keyguard intent`() {
        assertEquals(DeviceAuthPath.AUTHENTICATORS, DeviceAuthPath.forSdk(37))
        assertEquals(DeviceAuthPath.AUTHENTICATORS, DeviceAuthPath.forSdk(30))
        assertEquals(DeviceAuthPath.CREDENTIAL_ALLOWED, DeviceAuthPath.forSdk(29))
        assertEquals(DeviceAuthPath.CONFIRM_CREDENTIAL_INTENT, DeviceAuthPath.forSdk(28))
        assertEquals(DeviceAuthPath.CONFIRM_CREDENTIAL_INTENT, DeviceAuthPath.forSdk(26))
    }
}
