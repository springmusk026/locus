package dev.locus.feature.settings.ondevice

import dev.locus.core.model.SettingsDestination
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceCatalog
import dev.locus.core.ondevice.gguf.DownloadException
import dev.locus.core.ondevice.gguf.GgufCatalog
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.HfError
import dev.locus.feature.settings.SettingsPage
import dev.locus.feature.settings.settingsPageFor
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The on-device screens' decisions: routes, the fit verdict, filters, download states and spoken rows. */
class OnDeviceUiTest {
    private val gib = 1024L * 1024 * 1024
    private fun phone(gb: Long) = DeviceMemory(gb * gib, null, gb * gib / 2, false)

    @Test
    fun modelKeysSurviveTheRoute() {
        listOf(ModelKey.LiteRt("gemma-4-e2b"), ModelKey.Catalog("qwen3.5-2b"), ModelKey.Installed("gguf:qwen3.5-2b-q4_k_m"), ModelKey.Repo("unsloth/Qwen3.5-2B-GGUF")).forEach {
            assertEquals(it, ModelKey.parse(it.encode()))
        }
        assertNull(ModelKey.parse("nonsense"))
        assertNull(ModelKey.parse("repo:"))
        assertNull(ModelKey.parse("other:x"))
    }

    @Test
    fun linksToOnDeviceModelsOpenItsOwnScreen() {
        assertEquals(SettingsDestination.OnDeviceModels, SettingsDestination.inText("Import one in Settings → Models → On-device models."))
        assertEquals(SettingsDestination.Models, SettingsDestination.inText("Add a key in Settings → Models."))
        // A Settings route naming it (not routed by the app) still lands on its parent page.
        assertEquals(SettingsPage.Models, settingsPageFor(SettingsDestination.OnDeviceModels))
    }

    @Test
    fun theFitVerdictFollowsTheMemoryRules() {
        assertEquals(Fit.Fits, ggufFit(2 * gib, phone(8)))
        assertEquals(Fit.Tight, ggufFit(6 * gib, phone(8))) // above 65 %
        assertEquals(Fit.TooLarge, ggufFit(9 * gib, phone(8)))
        assertEquals(Fit.Unknown, ggufFit(2 * gib, DeviceMemory(0, null, 0, false)))
        assertEquals(Fit.Unknown, ggufFit(null, phone(8)))
        val e4b = OnDeviceCatalog.models.first { it.minRamGb == 12 }
        assertEquals(Fit.Tight, liteRtFit(MemoryGate.check(e4b.minRamGb, phone(8)), phone(8)), "below the minimum warns, never refuses")
        assertEquals(Fit.Fits, liteRtFit(MemoryGate.check(e4b.minRamGb, phone(16)), phone(16)))
    }

    private fun items(memory: DeviceMemory) =
        OnDeviceCatalog.models.map { DiscoverItem.of(it, MemoryGate.check(it.minRamGb, memory), memory) } + GgufCatalog.models.map { DiscoverItem.of(it, memory) }

    @Test
    fun discoverFiltersCombine() {
        val all = items(phone(4))
        val fits = DiscoverFilters().apply(all)
        assertTrue(fits.isNotEmpty() && fits.all { it.fit == Fit.Fits }, "fits this phone is on by default")
        assertTrue(fits.size < all.size, "a 4 GB phone doesn't fit everything")
        val small = DiscoverFilters(fitsOnly = false, smallOnly = true).apply(all)
        assertTrue(small.isNotEmpty() && small.all { it.sizeBytes < 1_000_000_000 })
        val liteRt = DiscoverFilters(fitsOnly = false, engine = Engine.LiteRt).apply(all)
        assertEquals(OnDeviceCatalog.models.size, liteRt.size)
        assertTrue(DiscoverFilters(fitsOnly = false, toolsOnly = true).apply(all).all { it.tools })
        assertTrue(DiscoverFilters(engine = Engine.LiteRt, smallOnly = true).apply(all).isEmpty())
    }

    @Test
    fun topPicksSpreadAcrossSizesThatFit() {
        val picks = topPicks(items(phone(8)))
        assertEquals(3, picks.size)
        assertTrue(picks.all { it.fit == Fit.Fits && it.tools })
        assertEquals(picks.sortedBy { it.sizeBytes }, picks)
    }

    @Test
    fun downloadStatesMapForBothEngines() {
        assertEquals(DownloadUi.Waiting(0, 100, wifiOnly = true), GgufDownloadState.Queued(0, 100, wifiOnly = true).toUi())
        val running = GgufDownloadState.Running(50, 100, 10, 5).toUi() as DownloadUi.Running
        assertEquals(0.5f, running.fraction)
        assertEquals(DownloadUi.Paused(30, 100), GgufDownloadState.Paused(30, 100).toUi())
        assertEquals(DownloadUi.Failed(0, 100, "x", false), GgufDownloadState.Failed("x", 0, 100, false).toUi())
        assertEquals(DownloadUi.Done("gguf:m", "ab", true), GgufDownloadState.Installed("gguf:m", "ab", true).toUi())

        assertEquals(DownloadUi.Waiting(0, 10, true), liteRtDownloadUi(ModelState.Downloading(0, 10, wifiOnly = true), 0, 10))
        assertTrue(liteRtDownloadUi(ModelState.Downloading(5, 10), 0, 10) is DownloadUi.Running)
        assertEquals(DownloadUi.Paused(4, 10), liteRtDownloadUi(ModelState.NotDownloaded, 4, 10))
        assertNull(liteRtDownloadUi(ModelState.NotDownloaded, 0, 10))
        assertNull(liteRtDownloadUi(ModelState.Ready, 0, 10))
        assertTrue(liteRtDownloadUi(ModelState.Failed("broke"), 0, 10) is DownloadUi.Failed)
    }

    @Test
    fun aRowIsReadAsOneItem() {
        assertEquals("Qwen3.5 2B, 1.3 GB, fits this phone, supports tools, Get", spokenRow("Qwen3.5 2B", 1_280_835_840, Fit.Fits, true, "Get"))
        assertEquals("SmolLM2, chat only", spokenRow("SmolLM2", null, null, false, null))
    }

    @Test
    fun progressIsAnnouncedInTenPercentSteps() {
        assertEquals(40, announceStep(0.43f))
        assertEquals(100, announceStep(1f))
        assertNull(announceStep(null))
    }

    @Test
    fun pastedUrlsAreCheckedAsTyped() {
        assertNull(urlWarning(""))
        assertNull(urlWarning("https://huggingface.co/a/b/resolve/main/x.gguf"))
        assertTrue(urlWarning("http://example.com/x.gguf")!!.contains("https"))
        assertTrue(urlWarning("example.com/x.gguf") != null)
    }

    @Test
    fun problemsSayWhatToDo() {
        assertEquals(Problem.Kind.Gated, Problem.of(HfError.Gated("meta-llama/x")).kind)
        assertTrue(Problem.of(HfError.Gated("meta-llama/x")).advice!!.contains("import"))
        assertEquals(Problem.Kind.RateLimited, Problem.of(HfError.RateLimited(30)).kind)
        assertEquals(Problem.Kind.Offline, Problem.of(HfError.Offline("No connection")).kind)
        assertEquals(Problem.Kind.Offline, Problem.of(DownloadException("No connection to huggingface.co.")).kind)
        assertEquals(Problem.Kind.Other, Problem.of(DownloadException("Blocked: 192.168.1.1", retryable = false)).kind)
    }
}
