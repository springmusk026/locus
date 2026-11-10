package dev.locus.feature.settings.ondevice

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.ondevice.Accelerator
import dev.locus.core.ondevice.BenchmarkResult
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.OnDeviceFormat
import dev.locus.core.ondevice.TurnSpeed
import dev.locus.core.ondevice.gguf.HfHub
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The on-device page's engine lines (OnDeviceText.kt) say in English exactly what [OnDeviceFormat] says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnDeviceTextTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private val turns = listOf(
        TurnSpeed(Accelerator.GPU, 300, 240.4, 120, 18.2, measured = true),
        TurnSpeed(Accelerator.CPU, null, null, 40, 6.04, measured = false),
        TurnSpeed(Accelerator.CPU, 40, 0.83, 40, 0.8, measured = false),
        TurnSpeed(Accelerator.GPU, null, null, null, null, measured = true),
    )

    private val statuses = listOf(
        EngineStatus(),
        EngineStatus(modelId = "m", loading = true),
        EngineStatus(modelId = "m", lastError = "Out of memory"),
        EngineStatus(modelId = "m", backend = Accelerator.GPU, loadMillis = 4_200, memoryBytes = 1_400_000_000, lastTurn = turns[0]),
        EngineStatus(modelId = "m", backend = Accelerator.CPU, gpuFailure = "OpenCL missing", loadMillis = 350, lastTurn = turns[1]),
        EngineStatus(modelId = "m", backend = Accelerator.GPU, gpuFailure = "ignored on GPU", loadMillis = 12_400, memoryBytes = 8L * 1024 * 1024 * 1024),
        EngineStatus(modelId = "other", backend = Accelerator.GPU),
    )

    @Test
    fun `the engine lines read as the core English`() {
        statuses.forEach { s ->
            assertEquals(OnDeviceFormat.backendLine(s, "m"), backendText(res, s, "m"), "$s")
            assertEquals(OnDeviceFormat.loadedLine(s), loadedText(res, s), "$s")
        }
        turns.forEach { assertEquals(OnDeviceFormat.turnLine(it), turnText(res, it), "$it") }
        listOf(
            BenchmarkResult("m", Accelerator.GPU, null, 4.2, 512, 240.0, 128, 18.4),
            BenchmarkResult("m", Accelerator.CPU, "OpenCL missing", 0.35, 64, 9.96, 32, 0.8),
        ).forEach { assertEquals(OnDeviceFormat.benchmark(it), benchmarkText(res, it)) }
        listOf(0.35, 4.2, 12.4).forEach { assertEquals(OnDeviceFormat.seconds(it), secondsText(res, it)) }
    }

    @Test
    fun `the search sorts read as before`() {
        HfHub.Sort.entries.forEach { assertEquals(it.label, res.getString(it.labelRes)) }
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish the engine line is Spanish and numbers use a comma`() {
        assertEquals("Funcionando en GPU · 18 tok/s", backendText(res, statuses[3], "m"))
        assertEquals("Cargado en 4,2 s · ≈1,3 GB de memoria", loadedText(res, statuses[3]))
    }
}
