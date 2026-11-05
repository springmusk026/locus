package dev.locus.core.ondevice.gguf

import org.junit.Test
import kotlin.test.assertEquals

/** Names people read: the catalogue's for its files, a tidied header or file name otherwise. */
class GgufNamesTest {
    @Test
    fun headerAndFileNamesAreTidied() {
        assertEquals("Qwen3.5 0.8B", GgufNames.display("Qwen3.5-0.8B"))
        assertEquals("Qwen3.5 0.8B", GgufNames.display("Qwen3.5-0.8B-Q4_K_M.gguf"))
        assertEquals("Llama 3.2 3B Instruct", GgufNames.display("Llama-3.2-3B-Instruct"))
        assertEquals("Granite 4.0 micro", GgufNames.display("granite-4.0-micro-Q4_K_M.gguf"))
        assertEquals("Gemma 3 1B it", GgufNames.display("gemma-3-1b-it"), "sizes in capitals")
        assertEquals("Gemma 3n E2B it", GgufNames.display("gemma-3n-e2b-it"))
        assertEquals("Qwen3 30B A3B", GgufNames.display("Qwen3-30B-a3b"))
        assertEquals("Qwen2.5 1.5B Instruct", GgufNames.display("Qwen2.5 1.5B Instruct"), "a name with spaces is kept")
    }

    @Test
    fun aCatalogueFileTakesTheCatalogueName() {
        val entry = GgufCatalog.models.first { it.id == "qwen3.5-0.8b" }
        val m = GgufModel(
            id = "gguf:qwen3.5-0.8b-q4_k_m", name = "Qwen3.5-0.8B", fileName = entry.file, architecture = "qwen35", quant = "Q4_K_M",
            sizeLabel = "0.8B", license = null, sizeBytes = entry.sizeBytes, trainedContext = null, contextTokens = 16_384,
            kvBytesPerToken = null, tools = true, hasTemplate = true, importedAt = 0, sha256 = entry.sha256, verified = true,
        )
        assertEquals("Qwen3.5 0.8B", GgufNames.named(m).name)
        assertEquals("Qwen3.5 0.8B", GgufNames.named(m.copy(sha256 = null)).name)
    }
}
