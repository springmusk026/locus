package dev.locus.core.data.lock

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.shortcuts.ChatShortcutPlan
import dev.locus.core.data.widget.WidgetStatus
import dev.locus.core.data.widget.widgetModel
import dev.locus.core.model.AppLockSettings
import dev.locus.core.model.Conversation
import dev.locus.core.model.ToolRisk
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app lock over the real settings store, with a fake screen lock and clock (docs/design/app-lock.md). */
class AppLockTest {
    private class FakeDevice(var secure: Boolean) : DeviceSecurity {
        override fun isDeviceSecure() = secure
    }

    private var now = 0L

    @Test
    fun `turning the lock on, lock after and locked chats are saved in the settings`() = runTest {
        val graph = TestGraph(backgroundScope)
        val lock = AppLock.forTest(graph.settingsRepository, FakeDevice(true), backgroundScope) { now }
        lock.setEnabled(true)
        lock.setLockAfter(4)
        lock.setChatLocked("c1", true)
        lock.setChatLocked("c2", true)
        lock.setChatLocked("c2", false)
        assertEquals(AppLockSettings(enabled = true, lockAfterMinutes = 5, lockedChats = setOf("c1")), graph.settingsRepository.current().appLock)
        lock.forgetChat("c1")
        assertTrue(graph.settingsRepository.current().appLock.lockedChats.isEmpty())
        // A new lock over the same store (a new process) reads them back, locked.
        val again = AppLock.forTest(graph.settingsRepository, FakeDevice(true), backgroundScope) { now }
        val status = again.loaded()
        assertTrue(status.enabled)
        assertTrue(status.appLocked)
    }

    @Test
    fun `turning it on doesn't lock the user out, leaving for longer than lock after does`() = runTest {
        val graph = TestGraph(backgroundScope)
        val lock = AppLock.forTest(graph.settingsRepository, FakeDevice(true), backgroundScope) { now }
        lock.setEnabled(true)
        lock.setLockAfter(1)
        runCurrent()
        assertFalse(lock.loaded().appLocked)

        now = 1_000; lock.onBackground()
        now = 30_000; lock.onForeground()
        runCurrent()
        assertFalse(lock.status.value!!.appLocked, "back within a minute")

        now = 40_000; lock.onBackground()
        now = 100_000; lock.onForeground()
        runCurrent()
        assertTrue(lock.status.value!!.appLocked, "away for a minute")

        lock.unlocked()
        runCurrent()
        assertFalse(lock.status.value!!.appLocked)
    }

    @Test
    fun `the system prompt is a trip Locus expects back from`() = runTest {
        val graph = TestGraph(backgroundScope)
        val lock = AppLock.forTest(graph.settingsRepository, FakeDevice(true), backgroundScope) { now }
        lock.setEnabled(true) // immediately, the default
        lock.expectReturn()
        now = 1_000; lock.onBackground()
        now = 5_000; lock.onForeground()
        runCurrent()
        assertFalse(lock.loaded().appLocked)
    }

    @Test
    fun `without a screen lock the switch can't lock anything, and setting one is noticed on return`() = runTest {
        val graph = TestGraph(backgroundScope)
        val device = FakeDevice(false)
        val lock = AppLock.forTest(graph.settingsRepository, device, backgroundScope) { now }
        graph.settingsRepository.setAppLock { it.copy(enabled = true, lockedChats = setOf("c")) }
        val status = lock.status.first { it?.enabled == true }!!
        assertFalse(status.available)
        assertFalse(status.appLocked)
        assertTrue(status.suspended)
        assertFalse(status.chatNeedsAuth("c"))

        device.secure = true
        lock.onBackground()
        lock.onForeground()
        runCurrent()
        assertTrue(lock.status.value!!.available)
        assertTrue(lock.status.value!!.appLocked)
    }

    // ---- Outside Locus: notifications, shortcuts, the widget --------------------------------------

    @Test
    fun `with the lock on every notification action needs the phone unlocked, and below Android 12 there are none`() {
        val on = AppLockSettings(enabled = true)
        val off = AppLockSettings()
        assertTrue(LockedNotifications.authenticationRequired(on, default = false))
        assertTrue(LockedNotifications.authenticationRequired(off, default = true), "Allow always does")
        assertFalse(LockedNotifications.authenticationRequired(off, default = false))
        assertTrue(LockedNotifications.actionsAllowed(on, sdk = 31))
        assertFalse(LockedNotifications.actionsAllowed(on, sdk = 30))
        assertTrue(LockedNotifications.actionsAllowed(off, sdk = 26))
    }

    @Test
    fun `below Android 12 Allow and Continue are never offered, lock on or off, since nothing could ask for the unlock`() {
        val on = AppLockSettings(enabled = true)
        val off = AppLockSettings()
        // Allow and Continue (default = true): only where setAuthenticationRequired works.
        assertFalse(LockedNotifications.actionOffered(off, sdk = 30, default = true), "lock off, Android 11: Allow would work from the lock screen")
        assertFalse(LockedNotifications.actionOffered(on, sdk = 30, default = true))
        assertFalse(LockedNotifications.actionOffered(off, sdk = 26, default = true))
        assertTrue(LockedNotifications.actionOffered(off, sdk = 31, default = true))
        assertTrue(LockedNotifications.actionOffered(on, sdk = 31, default = true))
        // Deny, Stop and answers (default = false): kept with the lock off; with it on below 31 the whole set is dropped.
        assertTrue(LockedNotifications.actionOffered(off, sdk = 30, default = false))
        assertFalse(LockedNotifications.actionOffered(on, sdk = 30, default = false))
        assertTrue(LockedNotifications.actionOffered(off, sdk = 31, default = false))
        assertTrue(LockedNotifications.actionOffered(on, sdk = 31, default = false))
    }

    @Test
    fun `a locked chat's approval and question aren't described in the status notification`() {
        val approval = ApprovalRequest("secret", "call-1", "run_command", ToolRisk.EXECUTE, "Run cat diary.txt", """{"command":"cat diary.txt"}""")
        val states = mapOf("secret" to RunState(running = true, approvals = listOf(approval)))
        val plain = dev.locus.core.data.agent.agentStatus(states, emptyList(), emptySet())
        assertTrue(plain.detail!!.contains("diary"))
        val locked = dev.locus.core.data.agent.agentStatus(states, emptyList(), setOf("secret"))
        assertTrue(locked.lockedChat)
        assertNull(locked.detail)
        assertFalse(locked.text.contains("diary"))

        val question = UserQuestion("secret", "q1", "Which bank account?", listOf("Savings", "Checking"), multi = false, allowOther = false)
        val asked = dev.locus.core.data.agent.agentStatus(mapOf("secret" to RunState(running = true)), listOf(question), setOf("secret"))
        assertTrue(asked.lockedChat)
        assertNull(asked.detail)
    }

    private fun chat(id: String, title: String, pinned: Boolean = false) = Conversation(id, title, null, "", createdAt = 0, updatedAt = id.hashCode().toLong(), pinned = pinned)

    @Test
    fun `shortcuts leave locked chats out, and there are none with Lock Locus on`() {
        val chats = listOf(chat("a", "Taxes", pinned = true), chat("b", "Trip"), chat("c", "Diary"))
        val plan = ChatShortcutPlan.plan(chats, lock = AppLockSettings(lockedChats = setOf("c")))
        assertEquals(setOf("a", "b"), plan.map { it.conversationId }.toSet())
        assertTrue(plan.none { it.title == "Diary" })
        assertTrue(ChatShortcutPlan.plan(chats, lock = AppLockSettings(enabled = true)).isEmpty())
    }

    @Test
    fun `the widget leaves out the task line while Lock Locus is on`() {
        assertEquals("Backups: done", widgetModel(WidgetStatus.IDLE, "Backups: done", appLocked = false).task)
        assertNull(widgetModel(WidgetStatus.IDLE, "Backups: done", appLocked = true).task)
    }
}
