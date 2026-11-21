package dev.locus.feature.settings

import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.lock.DeviceSecurity
import dev.locus.core.model.AppLockSettings
import dev.locus.core.model.AppLockStatus
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings → About & privacy → Lock Locus (docs/design/app-lock.md). */
class AppLockSettingsTest {
    @Test
    fun `without a screen lock the switch can't be turned on`() = runTest {
        val graph = TestGraph(backgroundScope)
        val lock = AppLock.forTest(graph.settingsRepository, DeviceSecurity { false }, backgroundScope)
        val state = AppLockSectionState.of(lock.loaded())
        assertFalse(state.available)
        assertFalse(state.enabled)
        assertFalse(state.switchEnabled)
    }

    @Test
    fun `with a screen lock it can, and it shows what's saved`() = runTest {
        val graph = TestGraph(backgroundScope)
        val lock = AppLock.forTest(graph.settingsRepository, DeviceSecurity { true }, backgroundScope)
        assertTrue(AppLockSectionState.of(lock.loaded()).switchEnabled)
        lock.setEnabled(true)
        lock.setLockAfter(15)
        lock.setChatLocked("a", true)
        val state = AppLockSectionState.of(lock.status.value!!.copy(settings = graph.settingsRepository.current().appLock))
        assertTrue(state.enabled)
        assertEquals(15, state.lockAfterMinutes)
        assertEquals(1, state.lockedChats)
    }

    @Test
    fun `a lock left on after the screen lock was removed can still be turned off`() {
        val state = AppLockSectionState.of(AppLockStatus(AppLockSettings(enabled = true), available = false))
        assertTrue(state.switchEnabled)
    }

    @Test
    fun `the switch waits for the settings and for the prompt`() {
        assertFalse(AppLockSectionState.of(null).switchEnabled)
        assertFalse(AppLockSectionState.of(AppLockStatus(available = true), busy = true).switchEnabled)
    }
}
