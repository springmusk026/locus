package dev.locus.core.ondevice.gguf

import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.MemoryGate

/**
 * How much memory a GGUF model needs at a given context, and the context Locus gives it.
 *
 * - **Weights**: the file. llama.cpp memory-maps it, so the pages are file-backed (Android can drop and
 *   re-read them under pressure instead of killing the app), but every page is touched each token, so
 *   in practice the whole file is resident while it runs.
 * - **KV cache**: `layers × context × kv_heads × (key_length + value_length) × 2 bytes` (F16, llama.cpp's
 *   default cache type). Models with per-layer head counts or a sliding window use less; this overcounts.
 * - **Compute buffers and runtime**: a fixed [OVERHEAD_BYTES] plus [OVERHEAD_SHARE] of the weights, a
 *   deliberately round figure: llama.cpp's CPU compute buffer for a 512-token batch is a few hundred MB
 *   on 1–8B models (it logs the exact size at load).
 *
 * The context is the largest of [CONTEXTS] the model was trained for whose total stays within
 * [BUDGET_SHARE] of the phone's memory. Agent chats on small windows get a compact system prompt
 * (PromptBudget), so 16K is the target, as for LiteRT-LM.
 */
object GgufMemory {
    val CONTEXTS = listOf(16_384, 8_192, 4_096)
    const val MIN_CONTEXT = 2_048
    const val OVERHEAD_BYTES = 300L * 1024 * 1024
    const val OVERHEAD_SHARE = 0.05
    /** Android, the launcher and other apps keep running: a model should leave a third of the memory to them. */
    const val BUDGET_SHARE = 0.65

    /** Bytes of KV cache per token of context, or null when the header doesn't give the shape. */
    fun kvBytesPerToken(layers: Int?, kvHeads: Int?, keyLength: Int?, valueLength: Int?): Long? {
        if (layers == null || kvHeads == null || keyLength == null || valueLength == null) return null
        if (layers <= 0 || kvHeads <= 0 || keyLength <= 0 || valueLength <= 0) return null
        return layers.toLong() * kvHeads * (keyLength + valueLength) * 2
    }

    fun estimate(fileBytes: Long, kvPerToken: Long?, context: Int): Long =
        fileBytes + (kvPerToken ?: 0) * context + OVERHEAD_BYTES + (fileBytes * OVERHEAD_SHARE).toLong()

    /**
     * The context for a model trained on [trained] tokens (null: unknown, taken as 4K), on a phone with
     * [memory]. Unknown memory (0) never shrinks it.
     */
    fun chooseContext(trained: Int?, fileBytes: Long, kvPerToken: Long?, memory: DeviceMemory): Int {
        val cap = (trained ?: 4_096).coerceAtLeast(MIN_CONTEXT)
        val candidates = CONTEXTS.map { minOf(it, cap) }.distinct()
        val total = memory.totalBytes
        if (total <= 0) return candidates.first()
        return candidates.firstOrNull { estimate(fileBytes, kvPerToken, it) <= total * BUDGET_SHARE } ?: candidates.last()
    }

    /** What the phone can't give this model, as the sentence shown before loading; null when it fits. */
    fun warning(name: String, needBytes: Long, memory: DeviceMemory): String? = when {
        memory.totalBytes <= 0 -> null
        needBytes > memory.totalBytes ->
            "$name needs about ${MemoryGate.gb(needBytes / MemoryGate.GIB)} GB of memory and this phone has " +
                "${MemoryGate.gb(memory.totalBytes / MemoryGate.GIB)} GB. Android would stop Locus while loading it. Pick a smaller model or quantisation."
        needBytes > memory.totalBytes * BUDGET_SHARE || memory.lowMemory ->
            "$name needs about ${MemoryGate.gb(needBytes / MemoryGate.GIB)} GB of this phone's ${MemoryGate.gb(memory.totalBytes / MemoryGate.GIB)} GB. " +
                "It may be slow, or Android may stop Locus to free memory. Close other apps first."
        else -> null
    }

    /** Whether the model can't possibly fit: loading would only get Locus killed. */
    fun tooLarge(needBytes: Long, memory: DeviceMemory): Boolean = memory.totalBytes > 0 && needBytes > memory.totalBytes
}
