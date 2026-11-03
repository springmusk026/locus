package dev.locus.core.ondevice.gguf

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GGUF header parsing against files built byte by byte to the spec (ggml docs/gguf.md). */
class GgufHeaderTest {

    /** Writes a GGUF header: metadata, then tensor infos; no tensor data unless [dataBytes] is set. */
    private class Writer(private val version: Int = 3) {
        private val kvs = mutableListOf<(ByteArrayOutputStream) -> Unit>()
        private val tensors = mutableListOf<(ByteArrayOutputStream) -> Unit>()

        fun string(key: String, value: String) = apply { kvs += { o -> str(o, key); u32(o, 8); str(o, value) } }
        fun u32(key: String, value: Long) = apply { kvs += { o -> str(o, key); u32(o, 4); u32(o, value) } }
        fun f32(key: String, value: Float) = apply { kvs += { o -> str(o, key); u32(o, 6); u32(o, value.toRawBits().toLong() and 0xFFFFFFFFL) } }
        fun bool(key: String, value: Boolean) = apply { kvs += { o -> str(o, key); u32(o, 7); o.write(if (value) 1 else 0) } }
        fun stringArray(key: String, values: List<String>) = apply {
            kvs += { o -> str(o, key); u32(o, 9); u32(o, 8); u64(o, values.size.toLong()); values.forEach { str(o, it) } }
        }
        fun i32Array(key: String, values: List<Int>) = apply {
            kvs += { o -> str(o, key); u32(o, 9); u32(o, 5); u64(o, values.size.toLong()); values.forEach { u32(o, it.toLong() and 0xFFFFFFFFL) } }
        }
        fun tensor(name: String, dims: List<Long>, type: Int, offset: Long) = apply {
            tensors += { o -> str(o, name); u32(o, dims.size.toLong()); dims.forEach { u64(o, it) }; u32(o, type.toLong()); u64(o, offset) }
        }

        fun bytes(): ByteArray {
            val o = ByteArrayOutputStream()
            o.write("GGUF".toByteArray())
            u32(o, version.toLong())
            u64(o, tensors.size.toLong())
            u64(o, kvs.size.toLong())
            kvs.forEach { it(o) }
            tensors.forEach { it(o) }
            return o.toByteArray()
        }

        companion object {
            fun u32(o: ByteArrayOutputStream, v: Long) = o.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array())
            fun u64(o: ByteArrayOutputStream, v: Long) = o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
            fun str(o: ByteArrayOutputStream, s: String) {
                val b = s.toByteArray(Charsets.UTF_8)
                u64(o, b.size.toLong())
                o.write(b)
            }
        }
    }

    private fun qwenLike() = Writer()
        .string("general.architecture", "qwen2")
        .string("general.name", "Qwen2.5 1.5B Instruct")
        .string("general.size_label", "1.5B")
        .string("general.license", "apache-2.0")
        .u32("general.file_type", 15)
        .u32("qwen2.context_length", 32_768)
        .u32("qwen2.block_count", 28)
        .u32("qwen2.embedding_length", 1_536)
        .u32("qwen2.attention.head_count", 12)
        .u32("qwen2.attention.head_count_kv", 2)
        .f32("qwen2.rope.freq_base", 1_000_000f)
        .bool("tokenizer.ggml.add_bos_token", false)
        // The vocabulary: skipped without being kept.
        .stringArray("tokenizer.ggml.tokens", List(5_000) { "tok$it" })
        .string("tokenizer.chat_template", "{%- if tools %}{{ tools | tojson }}{% endif %}")

    private fun read(bytes: ByteArray) = GgufReader.read(ByteArrayInputStream(bytes))

    @Test
    fun readsWhatLocusShows() {
        val h = read(qwenLike().tensor("token_embd.weight", listOf(1_536, 151_936), 12, 0).bytes())
        assertEquals(3, h.version)
        assertEquals("qwen2", h.architecture)
        assertEquals("Qwen2.5 1.5B Instruct", h.name)
        assertEquals("1.5B", h.sizeLabel)
        assertEquals("apache-2.0", h.license)
        assertEquals("Q4_K_M", GgufQuant.name(h.fileType))
        assertEquals(32_768, h.contextLength)
        assertEquals(28, h.blockCount)
        assertEquals(2, h.headCountKv)
        // No key/value length in the file: embedding / heads.
        assertEquals(128, h.keyLength)
        assertEquals(128, h.valueLength)
        assertTrue(GgufTemplate.supportsTools(h.chatTemplate))
        assertEquals(1, h.splitCount)
        assertEquals(false, h.metadata["tokenizer.ggml.add_bos_token"])
        assertFalse("tokenizer.ggml.tokens" in h.metadata)
    }

    @Test
    fun computesTheSizeTheTensorsNeed() {
        // Q4_K: 256 elements per 144-byte block. 1536 × 151936 elements = 911,616 blocks.
        val bytes = qwenLike()
            .tensor("token_embd.weight", listOf(1_536, 151_936), 12, 0)
            .tensor("output_norm.weight", listOf(1_536), 0, 131_272_704)
            .bytes()
        val h = read(bytes)
        val dataOffset = (bytes.size + 31L) / 32 * 32
        assertEquals(dataOffset, h.dataOffset)
        assertEquals(dataOffset + 131_272_704 + 1_536 * 4, h.requiredBytes)
    }

    @Test
    fun anUnknownTensorTypeSkipsTheSizeCheck() {
        assertNull(read(qwenLike().tensor("x", listOf(32), 99, 0).bytes()).requiredBytes)
    }

    @Test
    fun perLayerKvHeadsAreKept() {
        val h = read(
            Writer().string("general.architecture", "llama")
                .u32("llama.attention.head_count", 32)
                .u32("llama.embedding_length", 4_096)
                .i32Array("llama.attention.head_count_kv", listOf(8, 8, 0, 4))
                .bytes(),
        )
        assertEquals(listOf(8L, 8L, 0L, 4L), h.headCountKvPerLayer)
        assertEquals(8, h.headCountKv)
    }

    @Test
    fun refusesWhatIsNotAGguf() {
        val e = assertFailsWith<GgufException> { read("PK\u0003\u0004 a zip file, not a model".toByteArray()) }
        assertTrue(e.corrupt)
        assertTrue("Not a GGUF" in e.message.orEmpty())
    }

    @Test
    fun aTruncatedHeaderIsCorrupt() {
        val full = qwenLike().bytes()
        val e = assertFailsWith<GgufException> { read(full.copyOf(full.size / 2)) }
        assertTrue(e.corrupt)
        assertTrue("truncated" in e.message.orEmpty())
    }

    @Test
    fun anOldOrFutureVersionIsRefused() {
        val v1 = assertFailsWith<GgufException> { read(Writer(version = 1).bytes()) }
        assertFalse(v1.corrupt)
        assertFailsWith<GgufException> { read(Writer(version = 9).bytes()) }
    }

    @Test
    fun impossibleCountsAreCorrupt() {
        val o = ByteArrayOutputStream()
        o.write("GGUF".toByteArray())
        Writer.u32(o, 3)
        Writer.u64(o, Long.MAX_VALUE)
        Writer.u64(o, 0)
        assertTrue(assertFailsWith<GgufException> { read(o.toByteArray()) }.corrupt)
    }

    /** A header with [kvCount] key/values written by [body]: the raw bytes of a hostile file. */
    private fun raw(kvCount: Long, tensorCount: Long = 0, body: (ByteArrayOutputStream) -> Unit): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("GGUF".toByteArray())
        Writer.u32(o, 3)
        Writer.u64(o, tensorCount)
        Writer.u64(o, kvCount)
        body(o)
        return o.toByteArray()
    }

    @Test
    fun deeplyNestedArraysAreRefusedNotAStackOverflow() {
        // 100,000 arrays each holding one array: 1.2 MB that would recurse once per level.
        val bytes = raw(1) { o ->
            Writer.str(o, "x")
            Writer.u32(o, 9)
            repeat(100_000) { Writer.u32(o, 9); Writer.u64(o, 1) }
            Writer.u32(o, 0); Writer.u64(o, 0)
        }
        assertTrue(assertFailsWith<GgufException> { read(bytes) }.corrupt)
    }

    @Test
    fun aHugeStringValueIsSkippedNotKept() {
        val big = "x".repeat(2 * 1024 * 1024)
        val h = read(Writer().string("general.architecture", "llama").string("tokenizer.huggingface.json", big).string("general.name", "After").bytes())
        assertFalse("tokenizer.huggingface.json" in h.metadata)
        assertEquals("After", h.name)
    }

    @Test
    fun keptMetadataIsBounded() {
        // 40 values of 512 KB each: each is kept-sized, together past the budget.
        val value = "y".repeat(512 * 1024)
        val w = Writer()
        repeat(40) { w.string("k$it", value) }
        assertTrue(assertFailsWith<GgufException> { read(w.bytes()) }.corrupt)
    }

    @Test
    fun aLongKeyIsRefusedBeforeItIsAllocated() {
        // Claims a 60 MB key and ends: refused on the length, not read.
        val bytes = raw(1) { o -> Writer.u64(o, 60L * 1024 * 1024) }
        assertTrue(assertFailsWith<GgufException> { read(bytes) }.corrupt)
    }

    @Test
    fun nonIntegerKvHeadsAreSkippedNotACrash() {
        val bytes = raw(2) { o ->
            Writer.str(o, "general.architecture"); Writer.u32(o, 8); Writer.str(o, "llama")
            Writer.str(o, "llama.attention.head_count_kv"); Writer.u32(o, 9); Writer.u32(o, 7); Writer.u64(o, 2); o.write(1); o.write(0)
        }
        assertNull(read(bytes).headCountKvPerLayer)
    }

    @Test
    fun anOverflowingTensorOffsetIsCorrupt() {
        val bytes = Writer().string("general.architecture", "llama").tensor("t", listOf(32), 0, Long.MAX_VALUE - 10).bytes()
        assertTrue(assertFailsWith<GgufException> { read(bytes) }.corrupt)
    }

    @Test
    fun quantisationNames() {
        assertEquals("Q8_0", GgufQuant.name(7))
        assertEquals("IQ4_XS", GgufQuant.name(30))
        assertEquals("BF16", GgufQuant.name(32))
        assertNull(GgufQuant.name(4)) // removed from llama.cpp
        assertNull(GgufQuant.name(null))
        assertEquals("Q4_K_M", GgufQuant.fromFileName("qwen2.5-1.5b-instruct-q4_k_m.gguf"))
        assertEquals("Q8_0", GgufQuant.fromFileName("SmolLM2-135M-Instruct-Q8_0.gguf"))
        assertEquals("IQ4_XS", GgufQuant.fromFileName("Llama-3.2-3B-Instruct-IQ4_XS.gguf"))
        assertNull(GgufQuant.fromFileName("model.gguf"))
    }

    @Test
    fun ggmlBlockSizes() {
        assertEquals(18, GgmlTypes.bytes(2, 32)) // Q4_0
        assertEquals(34, GgmlTypes.bytes(8, 32)) // Q8_0
        assertEquals(210, GgmlTypes.bytes(14, 256)) // Q6_K
        assertEquals(8, GgmlTypes.bytes(1, 4)) // F16
        assertNull(GgmlTypes.bytes(4, 32)) // Q4_2, removed
    }
}
