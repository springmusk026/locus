package dev.locus.core.ondevice.gguf

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * What Locus reads from a GGUF file before llama.cpp ever opens it: enough to show the model, estimate
 * its memory, pick a context size, decide whether its template takes tools, and refuse files that are
 * truncated, of an unknown version, or split into parts.
 *
 * Format: github.com/ggml-org/ggml/blob/master/docs/gguf.md. Little-endian. Magic "GGUF", uint32
 * version (2 and 3 are read; version 1 used 32-bit counts and predates every current model), uint64
 * tensor count, uint64 metadata count, then the metadata key/values, then the tensor infos (name,
 * dimensions, ggml type, offset into the data section), padded to `general.alignment` (32 by default).
 */
data class GgufHeader(
    val version: Int,
    val tensorCount: Long,
    /** Scalar and string metadata; arrays are skipped except the few kept below. */
    val metadata: Map<String, Any>,
    /** Where the tensor data starts in the file. */
    val dataOffset: Long,
    /** The file size the tensors need: [dataOffset] plus the end of the last tensor. Null when a tensor's type is unknown. */
    val requiredBytes: Long?,
    /** Per-layer KV head counts, for models that vary them by layer (`{arch}.attention.head_count_kv` as an array). */
    val headCountKvPerLayer: List<Long>? = null,
) {
    val architecture: String get() = string("general.architecture").orEmpty()
    val name: String? get() = string("general.name")?.takeIf { it.isNotBlank() }
    val sizeLabel: String? get() = string("general.size_label")?.takeIf { it.isNotBlank() }
    val license: String? get() = string("general.license")?.takeIf { it.isNotBlank() }
    val fileType: Int? get() = long("general.file_type")?.toInt()
    val chatTemplate: String? get() = string("tokenizer.chat_template")?.takeIf { it.isNotBlank() }
    val splitCount: Int get() = long("split.count")?.toInt() ?: 1

    val contextLength: Int? get() = archLong("context_length")?.toInt()
    val blockCount: Int? get() = archLong("block_count")?.toInt()
    val embeddingLength: Int? get() = archLong("embedding_length")?.toInt()
    val headCount: Int? get() = archLong("attention.head_count")?.toInt()
    val headCountKv: Int? get() = archLong("attention.head_count_kv")?.toInt() ?: headCountKvPerLayer?.maxOrNull()?.toInt() ?: headCount
    val keyLength: Int? get() = archLong("attention.key_length")?.toInt() ?: perHead()
    val valueLength: Int? get() = archLong("attention.value_length")?.toInt() ?: perHead()
    val slidingWindow: Int? get() = archLong("attention.sliding_window")?.toInt()

    private fun perHead(): Int? {
        val e = embeddingLength ?: return null
        val h = headCount?.takeIf { it > 0 } ?: return null
        return e / h
    }

    fun string(key: String): String? = metadata[key] as? String
    fun long(key: String): Long? = (metadata[key] as? Number)?.toLong()
    private fun archLong(suffix: String): Long? = long("$architecture.$suffix")
}

/** A file that isn't a usable GGUF. [corrupt] distinguishes a damaged file from one that's valid but unsupported. */
class GgufException(message: String, val corrupt: Boolean = true) : Exception(message)

object GgufReader {
    const val MAGIC = 0x46554747 // "GGUF" read as a little-endian uint32

    // The file is untrusted (any file the user picks): every length and count is checked before
    // anything is allocated, and what's kept in memory is bounded however large the file is.
    private const val MAX_STRING = 64L * 1024 * 1024 // a string longer than this is damage, not data
    private const val MAX_KEY = 64L * 1024 // keys are dotted names; tensor names are at most 64 bytes in ggml
    /**
     * Longer string values are skipped, not kept: chat templates are kilobytes, but some older
     * conversions embed a whole tokenizer.json (`tokenizer.huggingface.json`, megabytes) nobody reads here.
     */
    private const val MAX_KEPT_STRING = 1L * 1024 * 1024
    /** All kept metadata together; a real header keeps well under a megabyte. */
    private const val MAX_KEPT_TOTAL = 16L * 1024 * 1024
    private const val MAX_ARRAY = 64L * 1024 * 1024
    /** Arrays of arrays: none of llama.cpp's keys nest, so deeper nesting is damage (and would overflow the stack). */
    private const val MAX_ARRAY_DEPTH = 4
    private const val MAX_TENSORS = 1_000_000L
    private const val MAX_KV = 1_000_000L

    /** Metadata arrays worth keeping: everything else (the vocabulary, merges, scores) is skipped. */
    private const val KV_HEADS_SUFFIX = ".attention.head_count_kv"

    fun read(file: File): GgufHeader = file.inputStream().use { read(it) }

    /** Reads the header from the start of [input]; reads only as far as the tensor infos. */
    fun read(input: InputStream): GgufHeader {
        val r = LeReader(BufferedInputStream(input, 1 shl 16))
        try {
            val magic = r.u32()
            if (magic != MAGIC.toLong()) throw GgufException("Not a GGUF file.")
            val version = r.u32().toInt()
            if (version !in 2..3) throw GgufException("GGUF version $version isn't supported (2 or 3 are).", corrupt = version > 3)
            val tensors = r.u64()
            val kvs = r.u64()
            if (tensors !in 0..MAX_TENSORS || kvs !in 0..MAX_KV) throw GgufException("The header's counts are impossible; the file is damaged.")
            val meta = LinkedHashMap<String, Any>()
            var kvHeads: List<Long>? = null
            var kept = 0L
            repeat(kvs.toInt()) {
                val key = r.string(MAX_KEY)
                val type = r.u32().toInt()
                if (type == TYPE_ARRAY) {
                    val elemType = r.u32().toInt()
                    val count = r.u64()
                    if (count !in 0..MAX_ARRAY) throw GgufException("Metadata array ${key.take(80)} is too long; the file is damaged.")
                    if (key.endsWith(KV_HEADS_SUFFIX) && elemType in INTEGER_TYPES && count <= 4096) {
                        kvHeads = List(count.toInt()) { (r.value(elemType) as Number).toLong() }
                    } else {
                        r.skipArray(elemType, count, depth = 1)
                    }
                } else if (type == TYPE_STRING) {
                    val value = r.stringOrSkip(MAX_KEPT_STRING) ?: return@repeat
                    kept += key.length + value.length
                    if (kept > MAX_KEPT_TOTAL) throw GgufException("The header's metadata is impossibly large; the file is damaged.")
                    meta[key] = value
                } else {
                    kept += key.length
                    if (kept > MAX_KEPT_TOTAL) throw GgufException("The header's metadata is impossibly large; the file is damaged.")
                    meta[key] = r.value(type)
                }
            }
            val alignment = (meta["general.alignment"] as? Number)?.toLong()?.takeIf { it > 0 && it and (it - 1) == 0L } ?: 32L
            var end = 0L
            var known = true
            repeat(tensors.toInt()) {
                r.skipString(MAX_KEY) // name
                val dims = r.u32().toInt()
                if (dims !in 0..8) throw GgufException("A tensor has $dims dimensions; the file is damaged.")
                var elements = 1L
                repeat(dims) {
                    val d = r.u64()
                    if (d < 0) throw GgufException("A tensor dimension is negative; the file is damaged.")
                    elements = Math.multiplyExact(elements, d)
                }
                val type = r.u32().toInt()
                val offset = r.u64()
                if (offset < 0) throw GgufException("A tensor offset is negative; the file is damaged.")
                val bytes = GgmlTypes.bytes(type, elements)
                if (bytes == null) known = false else end = maxOf(end, Math.addExact(offset, bytes))
            }
            val dataOffset = (r.position + alignment - 1) / alignment * alignment
            return GgufHeader(version, tensors, meta, dataOffset, if (known) Math.addExact(dataOffset, end) else null, kvHeads)
        } catch (e: EOFException) {
            throw GgufException("The file ends inside its header; it's truncated or damaged.")
        } catch (e: ArithmeticException) {
            throw GgufException("A tensor's size overflows; the file is damaged.")
        }
    }

    internal const val TYPE_UINT8 = 0
    internal const val TYPE_INT8 = 1
    internal const val TYPE_UINT16 = 2
    internal const val TYPE_INT16 = 3
    internal const val TYPE_UINT32 = 4
    internal const val TYPE_INT32 = 5
    internal const val TYPE_FLOAT32 = 6
    internal const val TYPE_BOOL = 7
    internal const val TYPE_STRING = 8
    internal const val TYPE_ARRAY = 9
    internal const val TYPE_UINT64 = 10
    internal const val TYPE_INT64 = 11
    internal const val TYPE_FLOAT64 = 12

    private val INTEGER_TYPES = setOf(TYPE_UINT8, TYPE_INT8, TYPE_UINT16, TYPE_INT16, TYPE_UINT32, TYPE_INT32, TYPE_UINT64, TYPE_INT64)

    private class LeReader(private val input: InputStream) {
        var position = 0L
            private set
        private val buf = ByteArray(8)

        private fun fill(n: Int) {
            var off = 0
            while (off < n) {
                val read = input.read(buf, off, n - off)
                if (read < 0) throw EOFException()
                off += read
            }
            position += n
        }

        private fun le(n: Int): Long {
            fill(n)
            var v = 0L
            for (i in n - 1 downTo 0) v = (v shl 8) or (buf[i].toLong() and 0xFF)
            return v
        }

        fun u32(): Long = le(4)
        fun u64(): Long = le(8)

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw EOFException()
                    left--
                    position++
                } else {
                    left -= skipped
                    position += skipped
                }
            }
        }

        /** A string of at most [max] bytes; a longer one is damage. */
        fun string(max: Long = MAX_KEPT_STRING): String {
            val len = u64()
            if (len !in 0..max) throw GgufException("A metadata string is too long; the file is damaged.")
            return readString(len)
        }

        /** A string value: read when it's at most [keep] bytes, skipped (null) when longer, refused past [MAX_STRING]. */
        fun stringOrSkip(keep: Long): String? {
            val len = u64()
            if (len !in 0..MAX_STRING) throw GgufException("A metadata string is too long; the file is damaged.")
            if (len > keep) {
                skip(len)
                return null
            }
            return readString(len)
        }

        fun skipString(max: Long) {
            val len = u64()
            if (len !in 0..max) throw GgufException("A string in the header is too long; the file is damaged.")
            skip(len)
        }

        private fun readString(len: Long): String {
            val bytes = ByteArray(len.toInt())
            var off = 0
            while (off < bytes.size) {
                val read = input.read(bytes, off, bytes.size - off)
                if (read < 0) throw EOFException()
                off += read
            }
            position += len
            return String(bytes, Charsets.UTF_8)
        }

        fun value(type: Int): Any = when (type) {
            TYPE_UINT8 -> le(1)
            TYPE_INT8 -> le(1).toByte().toLong()
            TYPE_UINT16 -> le(2)
            TYPE_INT16 -> le(2).toShort().toLong()
            TYPE_UINT32 -> le(4)
            TYPE_INT32 -> le(4).toInt().toLong()
            TYPE_FLOAT32 -> java.lang.Float.intBitsToFloat(le(4).toInt()).toDouble()
            TYPE_BOOL -> le(1) != 0L
            TYPE_STRING -> string()
            TYPE_UINT64, TYPE_INT64 -> le(8)
            TYPE_FLOAT64 -> java.lang.Double.longBitsToDouble(le(8))
            else -> throw GgufException("Unknown metadata type $type; the file is damaged.")
        }

        fun skipArray(type: Int, count: Long, depth: Int) {
            if (depth > MAX_ARRAY_DEPTH) throw GgufException("Metadata arrays are nested too deeply; the file is damaged.")
            when (type) {
                TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skip(count)
                TYPE_UINT16, TYPE_INT16 -> skip(count * 2)
                TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skip(count * 4)
                TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skip(count * 8)
                TYPE_STRING -> {
                    var i = 0L
                    while (i < count) {
                        val len = u64()
                        if (len !in 0..MAX_STRING) throw GgufException("A metadata string is too long; the file is damaged.")
                        skip(len)
                        i++
                    }
                }
                TYPE_ARRAY -> {
                    var i = 0L
                    while (i < count) {
                        val inner = u32().toInt()
                        val n = u64()
                        if (n !in 0..MAX_ARRAY) throw GgufException("Metadata array is too long; the file is damaged.")
                        skipArray(inner, n, depth + 1)
                        i++
                    }
                }
                else -> throw GgufException("Unknown metadata type $type; the file is damaged.")
            }
        }
    }
}

/**
 * ggml's tensor types: elements per block and bytes per block, from `type_traits` in ggml/src/ggml.c
 * and the block structs in ggml-common.h at the llama.cpp tag in tools/build-llama.sh (measured with
 * sizeof). Only used to check a file holds all its tensor data; a type missing here skips that check.
 */
object GgmlTypes {
    private val blocks: Map<Int, Pair<Int, Int>> = mapOf(
        0 to (1 to 4), // F32
        1 to (1 to 2), // F16
        2 to (32 to 18), // Q4_0
        3 to (32 to 20), // Q4_1
        6 to (32 to 22), // Q5_0
        7 to (32 to 24), // Q5_1
        8 to (32 to 34), // Q8_0
        9 to (32 to 36), // Q8_1
        10 to (256 to 84), // Q2_K
        11 to (256 to 110), // Q3_K
        12 to (256 to 144), // Q4_K
        13 to (256 to 176), // Q5_K
        14 to (256 to 210), // Q6_K
        15 to (256 to 292), // Q8_K
        16 to (256 to 66), // IQ2_XXS
        17 to (256 to 74), // IQ2_XS
        18 to (256 to 98), // IQ3_XXS
        19 to (256 to 50), // IQ1_S
        20 to (32 to 18), // IQ4_NL
        21 to (256 to 110), // IQ3_S
        22 to (256 to 82), // IQ2_S
        23 to (256 to 136), // IQ4_XS
        24 to (1 to 1), // I8
        25 to (1 to 2), // I16
        26 to (1 to 4), // I32
        27 to (1 to 8), // I64
        28 to (1 to 8), // F64
        29 to (256 to 56), // IQ1_M
        30 to (1 to 2), // BF16
        34 to (256 to 54), // TQ1_0
        35 to (256 to 66), // TQ2_0
        39 to (32 to 17), // MXFP4
        40 to (64 to 36), // NVFP4
        41 to (128 to 18), // Q1_0
        42 to (64 to 18), // Q2_0
        43 to (1 to 1), // F8_E4M3
    )

    /** Bytes a tensor of [elements] elements of [type] takes, or null for a type not listed. */
    fun bytes(type: Int, elements: Long): Long? {
        val (perBlock, size) = blocks[type] ?: return null
        return Math.multiplyExact(Math.addExact(elements, perBlock - 1L) / perBlock, size.toLong())
    }
}

/**
 * The quantisation a GGUF says it mostly uses (`general.file_type`, llama.h's `llama_ftype`), as people
 * name it on Hugging Face: "Q4_K_M". Values 4–6 and 33–35 were removed from llama.cpp.
 */
object GgufQuant {
    private val names = mapOf(
        0 to "F32", 1 to "F16", 2 to "Q4_0", 3 to "Q4_1", 7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1",
        10 to "Q2_K", 11 to "Q3_K_S", 12 to "Q3_K_M", 13 to "Q3_K_L", 14 to "Q4_K_S", 15 to "Q4_K_M",
        16 to "Q5_K_S", 17 to "Q5_K_M", 18 to "Q6_K", 19 to "IQ2_XXS", 20 to "IQ2_XS", 21 to "Q2_K_S",
        22 to "IQ3_XS", 23 to "IQ3_XXS", 24 to "IQ1_S", 25 to "IQ4_NL", 26 to "IQ3_S", 27 to "IQ3_M",
        28 to "IQ2_S", 29 to "IQ2_M", 30 to "IQ4_XS", 31 to "IQ1_M", 32 to "BF16", 36 to "TQ1_0",
        37 to "TQ2_0", 38 to "MXFP4_MOE", 39 to "NVFP4", 40 to "Q1_0", 41 to "Q2_0", 42 to "F8_E4M3",
    )

    /** "Q4_K_M", or null when the file doesn't say (or says a value this list doesn't know). */
    fun name(fileType: Int?): String? = fileType?.let { names[it] }

    /** The quantisation named in a file name ("qwen2.5-1.5b-instruct-q4_k_m.gguf" → "Q4_K_M"), the fallback when the header doesn't say. */
    fun fromFileName(name: String): String? = dev.locus.core.model.ModelNames.quantInFileName(name)
}
