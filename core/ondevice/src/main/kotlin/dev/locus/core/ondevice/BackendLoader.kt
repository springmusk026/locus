package dev.locus.core.ondevice

/** The compute backend an engine was actually created with. */
enum class Accelerator { GPU, CPU }

/** An engine and the backend it really runs on. [gpuFailure] is set when GPU was tried first and failed. */
data class LoadedBackend<E>(val engine: E, val backend: Accelerator, val gpuFailure: String?)

/** Both backends failed. The message carries both reasons so the user sees why. */
class BackendLoadException(val gpuReason: String, val cpuReason: String, cause: Throwable) :
    Exception("GPU: $gpuReason; CPU: $cpuReason", cause)

/**
 * Tries GPU, then CPU, and reports which one worked. The backend is taken from the call that
 * succeeded, never assumed: if GPU creation throws, the result says CPU and keeps GPU's reason.
 * [create] must fully initialise the engine (and free it if initialisation fails) so a GPU that
 * fails late is still caught here.
 */
object BackendLoader {

    fun <E> load(create: (Accelerator) -> E): LoadedBackend<E> {
        val gpuError = try {
            return LoadedBackend(create(Accelerator.GPU), Accelerator.GPU, gpuFailure = null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e
        }
        val gpuReason = reason(gpuError)
        return try {
            LoadedBackend(create(Accelerator.CPU), Accelerator.CPU, gpuFailure = gpuReason)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw BackendLoadException(gpuReason, reason(e), e)
        }
    }

    /** A short, single-line reason from an exception: its first non-blank message line, trimmed. */
    fun reason(t: Throwable, max: Int = MAX_REASON): String {
        val line = t.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?: t::class.simpleName
            ?: "unknown error"
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
    }

    const val MAX_REASON = 120
}
