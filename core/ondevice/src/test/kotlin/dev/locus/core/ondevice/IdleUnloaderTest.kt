package dev.locus.core.ondevice

import dev.locus.core.model.DeviceState
import dev.locus.core.model.PowerPolicy
import dev.locus.core.model.ThermalLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IdleUnloaderTest {
    private class Rig(scope: TestScope, idle: Boolean = false, state: DeviceState = DeviceState()) {
        val idle = MutableStateFlow(idle)
        val state = MutableStateFlow(state)
        var unloads = 0
        init {
            IdleUnloader(scope.backgroundScope, this.idle, this.state, now = { scope.testScheduler.currentTime }) {
                unloads++
                this.idle.value = false
                true
            }
            scope.runCurrent()
        }
    }

    @Test fun `an idle model is unloaded after ten minutes, not before`() = runTest {
        val rig = Rig(this, idle = true)
        advanceTimeBy(PowerPolicy.IDLE_UNLOAD_MS - 1)
        runCurrent()
        assertEquals(0, rig.unloads)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, rig.unloads)
    }

    @Test fun `work in between restarts the wait`() = runTest {
        val rig = Rig(this, idle = true)
        advanceTimeBy(PowerPolicy.IDLE_UNLOAD_MS - 1_000)
        rig.idle.value = false
        runCurrent()
        rig.idle.value = true
        runCurrent()
        advanceTimeBy(PowerPolicy.IDLE_UNLOAD_MS - 1_000)
        runCurrent()
        assertEquals(0, rig.unloads)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1, rig.unloads)
    }

    @Test fun `battery saver shortens the wait to two minutes`() = runTest {
        val rig = Rig(this, idle = true, state = DeviceState(powerSave = true))
        advanceTimeBy(PowerPolicy.IDLE_UNLOAD_SAVING_MS + 1)
        runCurrent()
        assertEquals(1, rig.unloads)
    }

    @Test fun `low memory partway through counts from when it went idle`() = runTest {
        val rig = Rig(this, idle = true)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, rig.unloads)
        // Idle for a minute already, past the 20 s low-memory wait: unloaded at once.
        rig.state.value = DeviceState(lowMemory = true)
        runCurrent()
        assertEquals(1, rig.unloads)
    }

    @Test fun `heat during the wait unloads at two minutes from idle`() = runTest {
        val rig = Rig(this, idle = true)
        advanceTimeBy(30_000)
        rig.state.value = DeviceState(thermal = ThermalLevel.SEVERE)
        advanceTimeBy(PowerPolicy.IDLE_UNLOAD_SAVING_MS - 30_000 - 1)
        runCurrent()
        assertEquals(0, rig.unloads)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, rig.unloads)
    }

    @Test fun `a busy or unloaded model is never unloaded`() = runTest {
        val rig = Rig(this, idle = false)
        advanceTimeBy(PowerPolicy.IDLE_UNLOAD_MS * 3)
        runCurrent()
        assertEquals(0, rig.unloads)
    }

    @Test fun `the stop note says why and what was dropped`() {
        val note = HeatStop.note(droppedCalls = 1)
        assertTrue("critical temperature" in note && "kept" in note && "tool call" in note, note)
    }
}
