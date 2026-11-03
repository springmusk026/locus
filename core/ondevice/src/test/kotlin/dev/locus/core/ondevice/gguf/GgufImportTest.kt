package dev.locus.core.ondevice.gguf

import dev.locus.core.ondevice.DeviceMemory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The import's decisions: what's refused, what's recorded, and the context and memory a model gets. */
class GgufImportTest {
    private val gib = 1024L * 1024 * 1024
    private fun phone(gb: Long, low: Boolean = false) = DeviceMemory(gb * gib, null, gb * gib / 2, low)

    private fun header(arch: String = "qwen2", meta: Map<String, Any> = emptyMap(), required: Long? = 1_000) = GgufHeader(
        version = 3,
        tensorCount = 10,
        metadata = mapOf(
            "general.architecture" to arch,
            "general.name" to "Qwen2.5 1.5B Instruct",
            "general.file_type" to 15L,
            "$arch.context_length" to 32_768L,
            "$arch.block_count" to 28L,
            "$arch.embedding_length" to 1_536L,
            "$arch.attention.head_count" to 12L,
            "$arch.attention.head_count_kv" to 2L,
            "tokenizer.chat_template" to "{% if tools %}{{ tools }}{% endif %}",
        ) + meta,
        dataOffset = 64,
        requiredBytes = required,
    )

    @Test
    fun aGoodModelPasses() {
        assertNull(GgufImportCheck.problem(header(), 2_000, "qwen2.5-1.5b-instruct-q4_k_m.gguf"))
    }

    @Test
    fun refusals() {
        assertTrue("split" in GgufImportCheck.problem(header(meta = mapOf("split.count" to 3L)), 2_000, "x.gguf")!!)
        assertTrue("projector" in GgufImportCheck.problem(header(arch = "clip"), 2_000, "x.gguf")!!)
        assertTrue("projector" in GgufImportCheck.problem(header(), 2_000, "mmproj-model-f16.gguf")!!)
        assertTrue("can't run" in GgufImportCheck.problem(header(arch = "some-future-arch"), 2_000, "x.gguf")!!)
        assertTrue("embeddings" in GgufImportCheck.problem(header(arch = "bert"), 2_000, "x.gguf")!!)
        assertTrue("truncated" in GgufImportCheck.problem(header(required = 5_000), 2_000, "x.gguf")!!)
        assertTrue("architecture" in GgufImportCheck.problem(header(arch = ""), 2_000, "x.gguf")!!)
        // Size unknown (the provider didn't say): checked after the copy instead.
        assertNull(GgufImportCheck.problem(header(required = 5_000), null, "x.gguf"))
    }

    @Test
    fun slugs() {
        assertEquals("qwen2.5-1.5b-instruct-q4_k_m", GgufImportCheck.slug("Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"))
        assertEquals("my-model-v2", GgufImportCheck.slug("My Model (v2).gguf"))
        assertEquals("model", GgufImportCheck.slug("###.gguf"))
        assertEquals(60, GgufImportCheck.slug("a".repeat(200) + ".gguf").length)
    }

    @Test
    fun theRecordComesFromTheHeader() {
        val m = GgufImportCheck.record(header(), "gguf:q", "q.gguf", 1_100_000_000, phone(8), now = 5)
        assertEquals("Qwen2.5 1.5B Instruct", m.name)
        assertEquals("Q4_K_M", m.quant)
        assertEquals(32_768, m.trainedContext)
        assertEquals(16_384, m.contextTokens)
        // 28 layers × 2 KV heads × (128 + 128) × 2 bytes.
        assertEquals(28L * 2 * 256 * 2, m.kvBytesPerToken)
        assertTrue(m.tools)
        assertTrue(m.hasTemplate)
        val chatOnly = GgufImportCheck.record(header(meta = mapOf("tokenizer.chat_template" to "{{ messages }}")), "gguf:c", "c.gguf", 1, phone(8), 5)
        assertFalse(chatOnly.tools)
    }

    @Test
    fun contextShrinksToFitThePhone() {
        val kv = 114_688L // Llama 3.2 3B: 28 layers × 8 KV heads × 256 × 2 bytes
        val file = 2_000_000_000L
        assertEquals(16_384, GgufMemory.chooseContext(131_072, file, kv, phone(12)))
        // 4 GB phone: 16K needs ≈4.2 GB, 8K ≈3.3 GB, 4K ≈2.9 GB > 2.6 GB budget: the smallest is used anyway.
        assertEquals(4_096, GgufMemory.chooseContext(131_072, file, kv, phone(4)))
        assertEquals(8_192, GgufMemory.chooseContext(131_072, file, kv, phone(6)))
        // Never above what the model was trained for; unknown memory never shrinks it.
        assertEquals(8_192, GgufMemory.chooseContext(8_192, file, kv, phone(12)))
        assertEquals(16_384, GgufMemory.chooseContext(32_768, file, kv, DeviceMemory(0, null, 0, false)))
        assertEquals(GgufMemory.MIN_CONTEXT, GgufMemory.chooseContext(512, 1, null, phone(8)))
    }

    @Test
    fun memoryWarnings() {
        val need = 3L * gib
        assertNull(GgufMemory.warning("M", need, phone(12)))
        assertNotNull(GgufMemory.warning("M", need, phone(4)))
        assertNotNull(GgufMemory.warning("M", need, phone(12, low = true)))
        assertTrue("stop Locus while loading" in GgufMemory.warning("M", 5 * gib, phone(4))!!)
        assertTrue(GgufMemory.tooLarge(5 * gib, phone(4)))
        assertFalse(GgufMemory.tooLarge(3 * gib, phone(4)))
        assertFalse(GgufMemory.tooLarge(50 * gib, DeviceMemory(0, null, 0, false)))
    }

    @Test
    fun theIndexRoundTrips() {
        val m = GgufImportCheck.record(header(), "gguf:q", "q.gguf", 1_100_000_000, phone(8), now = 5).copy(loadCrashed = true)
        assertEquals(m, GgufIndex.decode(GgufIndex.encode(m)))
    }
}
