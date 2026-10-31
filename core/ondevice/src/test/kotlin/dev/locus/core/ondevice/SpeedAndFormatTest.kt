package dev.locus.core.ondevice

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedAndFormatTest {

    @Test
    fun perSecond() {
        assertEquals(18.0, SpeedMath.perSecond(36, 2000))
        assertNull(SpeedMath.perSecond(0, 2000))
        assertNull(SpeedMath.perSecond(10, 0))
    }

    @Test
    fun estimate_decodeUsesPiecesAfterTheFirst() {
        // 37 pieces: the first at 500 ms, the other 36 over the next 2 s.
        val s = SpeedMath.estimate(Accelerator.GPU, pieces = 37, firstPieceMs = 500, totalMs = 2500, kvBefore = 1000, kvAfter = 1237)
        assertEquals(18.0, s.decodePerSecond)
        assertEquals(37, s.decodeTokens)
        // 237 new KV tokens - 37 decoded = 200 prefilled in 500 ms.
        assertEquals(200, s.prefillTokens)
        assertEquals(400.0, s.prefillPerSecond)
        assertFalse(s.measured)
    }

    @Test
    fun estimate_withoutKvCountsHasNoPrefill() {
        val s = SpeedMath.estimate(Accelerator.CPU, pieces = 10, firstPieceMs = 100, totalMs = 1000, kvBefore = null, kvAfter = 50)
        assertNull(s.prefillTokens)
        assertNull(s.prefillPerSecond)
        assertEquals(10.0, s.decodePerSecond)
    }

    @Test
    fun estimate_tooFewPiecesHasNoRate() {
        val s = SpeedMath.estimate(Accelerator.GPU, pieces = 2, firstPieceMs = 100, totalMs = 200, kvBefore = 0, kvAfter = 20)
        assertNull(s.decodePerSecond)
        assertNull(OnDeviceFormat.turnLine(s))
    }

    @Test
    fun fromRuntime_needsRealDecodeFigures() {
        assertNull(SpeedMath.fromRuntime(Accelerator.GPU, 0, 0.0, 0, 0.0))
        assertNull(SpeedMath.fromRuntime(Accelerator.GPU, 10, 100.0, 5, Double.NaN))
        val s = SpeedMath.fromRuntime(Accelerator.GPU, 0, 0.0, 64, 21.5)!!
        assertTrue(s.measured)
        assertNull(s.prefillTokens)
        assertNull(s.prefillPerSecond)
        assertEquals(64, s.decodeTokens)
    }

    @Test
    fun rateFormatting() {
        assertEquals("18 tok/s", OnDeviceFormat.rate(18.4))
        assertEquals("≈19 tok/s", OnDeviceFormat.rate(18.6, estimate = true))
        assertEquals("7.3 tok/s", OnDeviceFormat.rate(7.25))
        assertEquals("1293 tok/s", OnDeviceFormat.rate(1293.0))
    }

    @Test
    fun turnLine_marksEstimates() {
        val estimated = TurnSpeed(Accelerator.GPU, 200, 400.0, 37, 18.0, measured = false)
        assertEquals("GPU · ≈18 tok/s · prefill ≈400 tok/s", OnDeviceFormat.turnLine(estimated))
        val measured = estimated.copy(measured = true, prefillPerSecond = null)
        assertEquals("GPU · 18 tok/s", OnDeviceFormat.turnLine(measured))
    }

    @Test
    fun backendLine_gpuWithSpeed() {
        val status = EngineStatus(
            modelId = "m", backend = Accelerator.GPU,
            lastTurn = TurnSpeed(Accelerator.GPU, null, null, 40, 18.0, measured = true),
        )
        assertEquals("Running on GPU · 18 tok/s", OnDeviceFormat.backendLine(status, "m"))
        assertEquals("Not loaded", OnDeviceFormat.backendLine(status, "other"))
        assertEquals("Loading…", OnDeviceFormat.backendLine(EngineStatus(modelId = "m", loading = true), "m"))
    }

    @Test
    fun loadedLine() {
        val status = EngineStatus(modelId = "m", backend = Accelerator.GPU, loadMillis = 4230, memoryBytes = (1.5 * MemoryGate.GIB).toLong())
        assertEquals("Loaded in 4.2 s · ≈1.5 GB of memory", OnDeviceFormat.loadedLine(status))
        assertEquals("Loaded in 4.2 s", OnDeviceFormat.loadedLine(status.copy(memoryBytes = null)))
        assertNull(OnDeviceFormat.loadedLine(EngineStatus()))
    }

    @Test
    fun benchmarkFormatting() {
        val result = BenchmarkResult(
            modelId = "gemma-4-e2b", backend = Accelerator.GPU, gpuFailure = null, loadSeconds = 3.456,
            prefillTokens = 256, prefillPerSecond = 1234.4, decodeTokens = 128, decodePerSecond = 21.94,
        )
        assertEquals(
            listOf("Ran on GPU", "Load 3.5 s · prefill 1234 tok/s (256 tokens)", "Decode 22 tok/s (128 tokens)"),
            OnDeviceFormat.benchmark(result),
        )
        val cpu = result.copy(backend = Accelerator.CPU, gpuFailure = "No OpenCL", loadSeconds = 0.42, decodePerSecond = 6.04)
        assertEquals(
            listOf("Ran on CPU (GPU failed: No OpenCL)", "Load 0.42 s · prefill 1234 tok/s (256 tokens)", "Decode 6.0 tok/s (128 tokens)"),
            OnDeviceFormat.benchmark(cpu),
        )
    }

    @Test
    fun secondsFormatting() {
        assertEquals("12 s", OnDeviceFormat.seconds(12.4))
        assertEquals("1.5 s", OnDeviceFormat.seconds(1.46))
        assertEquals("0.35 s", OnDeviceFormat.seconds(0.351))
    }
}
