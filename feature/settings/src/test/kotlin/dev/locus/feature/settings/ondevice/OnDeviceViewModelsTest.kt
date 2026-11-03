package dev.locus.feature.settings.ondevice

import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceCatalog
import dev.locus.core.ondevice.gguf.GgufCatalog
import dev.locus.core.ondevice.gguf.GgufDownload
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufModel
import dev.locus.core.ondevice.gguf.GgufOffer
import dev.locus.core.ondevice.gguf.HfFile
import dev.locus.core.ondevice.gguf.HfFileKind
import dev.locus.core.ondevice.Accelerator
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The library's and the detail's view of the managers' states (OnDeviceLibraryViewModel.library, OnDeviceDetailViewModel.recommend). */
class OnDeviceViewModelsTest {
    private val gib = 1024L * 1024 * 1024
    private val phone = DeviceMemory(8 * gib, null, 4 * gib, false)
    private val qwen = GgufCatalog.models.first { it.id == "qwen3.5-2b" }
    private val small = GgufCatalog.models.first { it.id == "qwen3.5-0.8b" }

    private fun model(id: String, sha: String?, size: Long = 500_000_000) = GgufModel(
        id = id, name = id, fileName = "$id.gguf", architecture = "qwen35", quant = "Q4_K_M", sizeLabel = "0.8B", license = "apache-2.0",
        sizeBytes = size, trainedContext = 32_768, contextTokens = 16_384, kvBytesPerToken = 49_152, tools = true, hasTemplate = true,
        importedAt = 0, sha256 = sha, verified = sha != null,
    )

    private fun offer(catalogId: String?, sha: String?) = GgufOffer(
        name = "Download", fileName = "x.gguf", url = "https://huggingface.co/a/b/resolve/0123456789abcdef0123456789abcdef01234567/x.gguf",
        sizeBytes = 1000, sha256 = sha, source = "a/b@0123456", license = null, catalogId = catalogId,
    )

    private fun library(
        states: Map<String, ModelState> = emptyMap(),
        partial: Map<String, Long> = emptyMap(),
        engine: EngineStatus = EngineStatus(),
        gguf: List<GgufModel> = emptyList(),
        downloads: List<GgufDownload> = emptyList(),
        filters: DiscoverFilters = DiscoverFilters(),
        default: String? = null,
    ) = OnDeviceLibraryViewModel.library(OnDeviceCatalog.models, states, partial, engine, gguf, downloads, phone, filters, default)

    @Test
    fun anEmptyPhoneShowsPicksAndEveryModelInDiscover() {
        val s = library()
        assertTrue(s.empty)
        assertTrue(s.installed.isEmpty() && s.downloads.isEmpty())
        assertEquals(OnDeviceCatalog.models.size + GgufCatalog.models.size, s.discoverAll.size)
        assertEquals(3, s.picks.size)
    }

    @Test
    fun installedModelsOfBothEnginesAreOneList() {
        val liteRt = OnDeviceCatalog.models.first()
        val installed = model("gguf:small", small.sha256)
        val s = library(
            states = mapOf(liteRt.id to ModelState.Ready),
            engine = EngineStatus(modelId = installed.id, backend = Accelerator.CPU),
            gguf = listOf(installed),
            default = installed.id,
        )
        assertEquals(listOf(Engine.LiteRt, Engine.LlamaCpp), s.installed.map { it.engine })
        assertEquals("Loaded", s.installed.last().state)
        assertTrue(s.installed.last().inUse)
        assertEquals("Idle", s.installed.first().state)
        // Installed models leave Discover: the LiteRT one, and the catalogue file with the same checksum.
        assertFalse(s.discoverAll.any { it.key == ModelKey.LiteRt(liteRt.id) || it.key == ModelKey.Catalog(small.id) })
        assertFalse(s.empty)
    }

    @Test
    fun downloadsArePinnedAndLeaveDiscover() {
        val liteRt = OnDeviceCatalog.models.first()
        val s = library(
            states = mapOf(liteRt.id to ModelState.Downloading(100, liteRt.sizeBytes)),
            downloads = listOf(
                GgufDownload("q", offer(qwen.id, qwen.sha256), unmeteredOnly = true, state = GgufDownloadState.Failed("No connection", 10, 1000, retryable = true)),
                GgufDownload("u", offer(null, null), unmeteredOnly = true, state = GgufDownloadState.Paused(10, null)),
            ),
        )
        assertEquals(3, s.downloads.size)
        assertTrue(s.downloads[0].ui is DownloadUi.Running)
        assertTrue(s.downloads[1].ui is DownloadUi.Failed)
        assertEquals(ModelKey.Catalog(qwen.id), s.downloads[1].key)
        assertEquals(null, s.downloads[2].key, "a URL download has no detail until it's installed")
        assertFalse(s.downloads[2].verified)
        assertFalse(s.discoverAll.any { it.key == ModelKey.Catalog(qwen.id) || it.key == ModelKey.LiteRt(liteRt.id) })
    }

    @Test
    fun aFinishedDownloadIsAMessageNotARow() {
        val done = GgufDownload("d", offer(null, "ab"), unmeteredOnly = true, state = GgufDownloadState.Installed("gguf:m", "ab", true, existing = true))
        assertTrue(library(gguf = listOf(model("gguf:m", "ab")), downloads = listOf(done)).downloads.isEmpty(), "Downloads lists only what's still to finish")
        assertEquals("Qwen3.5 0.8B is already on this phone", Finished("Qwen3.5 0.8B", "gguf:m", existing = true, verified = true).message)
        assertEquals("Qwen3.5 0.8B installed and verified", Finished("Qwen3.5 0.8B", "gguf:m", existing = false, verified = true).message)
    }

    @Test
    fun filtersApplyToDiscoverOnly() {
        val s = library(filters = DiscoverFilters(fitsOnly = false, engine = Engine.LiteRt))
        assertEquals(OnDeviceCatalog.models.size, s.discover.size)
        assertEquals(OnDeviceCatalog.models.size + GgufCatalog.models.size, s.discoverAll.size)
    }

    @Test
    fun theDetailPreselectsQ4KMWhenItFits() {
        fun file(name: String, size: Long, kind: HfFileKind = HfFileKind.Model) = HfFile(name, size, "a".repeat(64), kind)
        val files = listOf(
            file("m-Q8_0.gguf", 2_000_000_000),
            file("m-Q4_K_M.gguf", 1_200_000_000),
            file("m-Q2_K.gguf", 700_000_000),
            file("mmproj-F16.gguf", 600_000_000, HfFileKind.Projector),
            file("m-BF16-00001-of-00002.gguf", 9_000_000_000, HfFileKind.Split(1, 2)),
        ).map { OnDeviceDetailViewModel.option(it, 49_152, 32_768, phone) }
        val picked = OnDeviceDetailViewModel.recommend(files)
        assertEquals("Q4_K_M", picked.single { it.recommended }.quant)
        assertTrue(picked.takeWhile { it.usable }.size == 3, "usable files first")
        assertFalse(picked.first { it.fileName.startsWith("mmproj") }.usable)
        assertNotNull(picked.first { it.fileName.contains("00001") }.problem)

        // On a 2 GB phone Q4_K_M is too large: the largest that fits is picked instead.
        val tiny = DeviceMemory(2 * gib, null, gib, false)
        val onTiny = OnDeviceDetailViewModel.recommend(
            listOf(file("m-Q4_K_M.gguf", 1_200_000_000), file("m-Q2_K.gguf", 500_000_000)).map { OnDeviceDetailViewModel.option(it, 49_152, 32_768, tiny) },
        )
        assertEquals("Q2_K", onTiny.single { it.recommended }.quant)
    }
}
