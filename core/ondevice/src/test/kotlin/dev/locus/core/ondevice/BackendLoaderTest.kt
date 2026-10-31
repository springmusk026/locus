package dev.locus.core.ondevice

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendLoaderTest {

    /** Stands in for a LiteRT-LM engine; remembers the backend it was created with. */
    private class FakeEngine(val createdWith: Accelerator)

    @Test
    fun gpuWorks_reportsGpuWithoutReason() {
        val attempts = mutableListOf<Accelerator>()
        val result = BackendLoader.load { attempts += it; FakeEngine(it) }
        assertEquals(Accelerator.GPU, result.backend)
        assertEquals(Accelerator.GPU, result.engine.createdWith)
        assertNull(result.gpuFailure)
        assertEquals(listOf(Accelerator.GPU), attempts)
    }

    @Test
    fun gpuFails_fallsBackToCpuAndKeepsTheReason() {
        val result = BackendLoader.load {
            if (it == Accelerator.GPU) throw IllegalStateException("OpenCL not found\n  at native frame 1")
            FakeEngine(it)
        }
        assertEquals(Accelerator.CPU, result.backend)
        assertEquals(Accelerator.CPU, result.engine.createdWith)
        assertEquals("OpenCL not found", result.gpuFailure)
    }

    @Test
    fun neverClaimsGpuWhenTheEngineWasCreatedOnCpu() {
        val result = BackendLoader.load { if (it == Accelerator.GPU) error("no") else FakeEngine(it) }
        assertEquals(result.engine.createdWith, result.backend)
    }

    @Test
    fun missingGpuLibrary_fallsBack() {
        val result = BackendLoader.load { if (it == Accelerator.GPU) throw UnsatisfiedLinkError("libLiteRtGpuAccelerator.so not found") else FakeEngine(it) }
        assertEquals(Accelerator.CPU, result.backend)
        assertEquals("libLiteRtGpuAccelerator.so not found", result.gpuFailure)
    }

    @Test
    fun outOfMemory_isNotRetriedOnCpu() {
        val attempts = mutableListOf<Accelerator>()
        assertFailsWith<OutOfMemoryError> {
            BackendLoader.load<FakeEngine> { attempts += it; throw OutOfMemoryError("oom") }
        }
        assertEquals(listOf(Accelerator.GPU), attempts)
    }

    @Test
    fun bothFail_throwsWithBothReasons() {
        val e = assertFailsWith<BackendLoadException> {
            BackendLoader.load<FakeEngine> { throw RuntimeException("$it broke") }
        }
        assertEquals("GPU broke", e.gpuReason)
        assertEquals("CPU broke", e.cpuReason)
        assertTrue(e.message!!.contains("GPU: GPU broke"))
    }

    @Test
    fun reason_isTrimmedToOneShortLine() {
        val long = "x".repeat(500)
        val reason = BackendLoader.reason(RuntimeException("\n\n  $long  \nsecond line"))
        assertEquals(BackendLoader.MAX_REASON, reason.length)
        assertTrue(reason.endsWith("…"))
        assertEquals("IllegalStateException", BackendLoader.reason(IllegalStateException()))
    }

    @Test
    fun statusTracker_reportsTheFallbackReason() {
        val tracker = EngineStatusTracker()
        tracker.loading("gemma-4-e2b")
        assertNull(tracker.status.value.backend, "no backend is claimed while loading")
        val result = BackendLoader.load { if (it == Accelerator.GPU) error("GPU delegate failed: out of memory") else FakeEngine(it) }
        tracker.loaded("gemma-4-e2b", result, loadMillis = 4200, memoryBytes = 1_400_000_000)
        val status = tracker.status.value
        assertEquals(Accelerator.CPU, status.backend)
        assertEquals("GPU delegate failed: out of memory", status.gpuFailure)
        assertTrue(status.loaded)
        assertEquals("Running on CPU (GPU failed: GPU delegate failed: out of memory)", OnDeviceFormat.backendLine(status, "gemma-4-e2b"))
    }

    @Test
    fun statusTracker_unloadClearsBackend() {
        val tracker = EngineStatusTracker()
        tracker.loaded("m", LoadedBackend(Unit, Accelerator.GPU, null), 1000, null)
        tracker.unloaded()
        assertNull(tracker.status.value.backend)
        assertEquals("Not loaded", OnDeviceFormat.backendLine(tracker.status.value, "m"))
    }

    @Test
    fun statusTracker_failedLoadShowsTheError() {
        val tracker = EngineStatusTracker()
        tracker.loading("m")
        tracker.loadFailed("m", "GPU: a; CPU: b")
        assertNull(tracker.status.value.backend)
        assertEquals("Couldn't load: GPU: a; CPU: b", OnDeviceFormat.backendLine(tracker.status.value, "m"))
    }

    @Test
    fun statusTracker_keepsRecentTurnsByTrimmedText() {
        val tracker = EngineStatusTracker()
        val speed = TurnSpeed(Accelerator.GPU, null, null, 40, 18.0, measured = false)
        repeat(EngineStatusTracker.MAX_RECENT + 5) { tracker.turn("reply $it", speed) }
        tracker.turn("  final answer \n", speed)
        val recent = tracker.recentTurns.value
        assertEquals(EngineStatusTracker.MAX_RECENT, recent.size)
        assertEquals(speed, recent["final answer"])
        assertNull(recent["reply 0"])
    }
}
