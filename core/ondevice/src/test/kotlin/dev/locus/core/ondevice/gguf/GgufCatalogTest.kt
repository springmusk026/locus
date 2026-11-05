package dev.locus.core.ondevice.gguf

import dev.locus.core.ondevice.DeviceMemory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The recommended list as shipped: every entry pinned, checkable and runnable (tools/gguf-catalog.py writes it). */
class GgufCatalogTest {
    private val models = GgufCatalog.models

    @Test
    fun everyEntryIsPinnedWithSizeAndChecksum() {
        assertTrue(models.size >= 4, "the catalogue should offer a few sizes")
        models.forEach { e ->
            assertTrue(Regex("^[0-9a-f]{64}$").matches(e.sha256), "${e.id}: sha256")
            assertTrue(e.sizeBytes > 100_000_000, "${e.id}: size")
            assertTrue(HfHub.isCommit(e.revision), "${e.id}: revision must be a commit, not a branch")
            assertTrue(HfHub.isRepoId(e.repo), "${e.id}: repo")
            assertTrue(e.file.endsWith(".gguf") && e.file.isNotBlank(), "${e.id}: file")
            assertTrue(e.licenseName.isNotBlank(), "${e.id}: licence")
        }
        assertEquals(models.size, models.map { it.id }.toSet().size, "ids are unique")
        assertEquals(models.size, models.map { it.sha256 }.toSet().size, "files are distinct")
    }

    @Test
    fun everyEntryRunsHereAndIsASingleFileModel() {
        models.forEach { e ->
            assertTrue(GgufArchitectures.isSupported(e.architecture) && GgufArchitectures.isChat(e.architecture), "${e.id}: ${e.architecture}")
            assertEquals(HfFileKind.Model, HfHub.classify(e.file), "${e.id}: not a split, projector or draft")
            assertTrue(e.tools, "${e.id}: recommended models call tools")
            assertTrue(e.quant != null, "${e.id}: quant from the file name")
        }
    }

    @Test
    fun downloadsArePinnedHuggingFaceUrls() {
        models.forEach { e ->
            val url = e.source.resolveUrl
            assertTrue(url.startsWith("https://huggingface.co/${e.repo}/resolve/${e.revision}/"), url)
            assertEquals(e.source, HfHub.parseFileUrl(url))
        }
    }

    @Test
    fun memoryIsEstimatedForThePhone() {
        val gib = 1024L * 1024 * 1024
        val small = models.minBy { it.sizeBytes }
        val phone8 = DeviceMemory(8 * gib, null, 4 * gib, false)
        val ram = small.ramBytes(phone8)
        assertTrue(ram > small.sizeBytes && ram < 8 * gib, "estimate $ram")
    }
}
