package dev.locus.core.ondevice.gguf

/**
 * The JNI side of llama.cpp (core/ondevice/src/main/cpp/locus_llama.cpp, built by tools/build-llama.sh).
 * Only [LlamaSession] calls these; one session's calls never overlap except [nativeCancel].
 */
internal object LlamaNative {
    @Volatile private var systemInfo: String? = null

    /**
     * Loads the bridge and ggml's CPU backends from the app's native library folder (they're extracted
     * there: useLegacyPackaging in app/build.gradle.kts). Returns llama.cpp's system line: which CPU
     * features the chosen backend uses (NEON, DOTPROD, MATMUL_INT8, SVE, SME).
     */
    @Synchronized
    fun init(nativeLibraryDir: String): String {
        systemInfo?.let { return it }
        System.loadLibrary("locusllama")
        return nativeInit(nativeLibraryDir).also { systemInfo = it }
    }

    /** What [delta] reports: the kinds match DELTA_* in locus_llama.cpp. */
    fun interface Sink {
        fun onDelta(kind: Int, utf8: ByteArray)
    }

    const val DELTA_CONTENT = 0
    const val DELTA_REASONING = 1
    const val DELTA_TOOL = 2

    @JvmStatic external fun nativeInit(nativeLibraryDir: String): String
    @JvmStatic external fun nativeLoad(path: String, contextTokens: Int, threads: Int, batch: Int): Long
    @JvmStatic external fun nativeInfo(handle: Long): ByteArray
    @JvmStatic external fun nativeGenerate(handle: Long, request: ByteArray, maxTokens: Int, threads: Int, sink: Sink): ByteArray
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeFree(handle: Long)
}

/**
 * A failure inside llama.cpp. The native side puts its kind on the first line of the message
 * (`throw_java` in locus_llama.cpp) and llama.cpp's own log lines after it.
 */
class LlamaNativeException(raw: String) : Exception(raw.substringAfter('\n', raw).trim()) {
    val kind: Kind = Kind.entries.firstOrNull { it.wire == raw.substringBefore('\n') } ?: Kind.LOAD

    enum class Kind(val wire: String) {
        LOAD("load"),
        ARCH("arch"),
        CORRUPT("corrupt"),
        MEMORY("memory"),
        CONTEXT("context"),
        TEMPLATE("template"),
        GENERATE("generate"),
    }
}
