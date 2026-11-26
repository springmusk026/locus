package dev.locus.core.linux

/**
 * The pid of a [java.lang.Process], which Android's public API doesn't give (`Process.pid()` is
 * Java 9 and isn't in the Android SDK). Two ways, either of which may be closed off: the private
 * `pid` field of the platform's process class (ART's `UNIXProcess`), then the pid its `toString()`
 * reports (`Process[pid=1234, …]` on ART and on OpenJDK 9+, where the field is closed to reflection).
 */
internal object ProcessIds {

    fun of(process: java.lang.Process): Int? = fromField(process) ?: fromDescription(process.toString())

    private fun fromField(process: java.lang.Process): Int? = try {
        process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process).takeIf { it > 0 }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null // SecurityException, or InaccessibleObjectException on a JVM whose java.base isn't open
    }

    /** `Process[pid=1234, hasExited=false]` → 1234. */
    fun fromDescription(description: String): Int? =
        Regex("""\bpid=(\d+)""").find(description)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}
