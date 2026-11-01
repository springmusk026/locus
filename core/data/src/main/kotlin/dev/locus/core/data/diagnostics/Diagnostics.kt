// ApplicationExitInfo's REASON_* constants are inlined ints; they only classify records read on API 30+.
@file:SuppressLint("InlinedApi")

package dev.locus.core.data.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.datastore.DiagnosticsLog
import dev.locus.core.datastore.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** What a diagnostics entry is about. */
enum class DiagnosticKind { CRASH, EXIT, ERROR, STORAGE }

/** One entry under Settings → About & privacy → Diagnostics, already scrubbed. */
data class DiagnosticEntry(val id: String, val kind: DiagnosticKind, val at: Long, val title: String, val text: String)

/** A process exit Android recorded (ApplicationExitInfo), reduced to what Diagnostics shows. */
data class ExitRecord(val timestamp: Long, val reason: Int, val description: String?, val importance: Int, val trace: String?)

/**
 * The exits worth showing that happened after [since]: ANRs, native crashes, and kills for memory or
 * resource use. Java crashes aren't repeated (CrashRecorder already wrote them, with the stack), nor
 * are exits the user or Locus asked for.
 */
internal fun exitsToRecord(all: List<ExitRecord>, since: Long): List<ExitRecord> =
    all.filter { it.timestamp > since && it.reason in SHOWN_EXITS }.sortedBy { it.timestamp }

private val SHOWN_EXITS = setOf(
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
)

internal fun exitTitle(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android: low memory"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by Android: too much CPU or memory"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
    else -> "Exited (reason $reason)"
}

internal fun exitText(exit: ExitRecord): String = buildString {
    append("When: ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(exit.timestamp))).append('\n')
    exit.description?.takeIf { it.isNotBlank() }?.let { append("Android says: ").append(it).append('\n') }
    append("Running in the foreground: ").append(if (exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) "yes" else "no").append('\n')
    exit.trace?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
}

/**
 * Locus's local crash and exit history and its one-time notices (docs/design/diagnostics.md): crash
 * reports from [CrashRecorder], exits Android recorded since the last start, errors caught on the app's
 * coroutine scope, and storage problems DataStore recovered from. Everything shown or shared is
 * scrubbed with the vault's values and the credential patterns.
 */
@Singleton
class DiagnosticsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val log: DiagnosticsLog,
    private val secrets: SecretStore,
) {
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    /** Re-reads the log, scrubbed. */
    suspend fun refresh() {
        val scrubber = scrubber()
        _entries.value = log.entries().map { DiagnosticEntry(it.id, DiagnosticKind.valueOf(it.kind.name), it.at, scrubber.scrub(it.title), scrubber.scrub(it.text)) }
    }

    /** All entries as one text, for Share (the user chooses where it goes). Scrubbed like [entries]. */
    suspend fun shareText(): String {
        refresh()
        val header = runCatching { CrashRecorder.environment(context) }.getOrDefault("Locus")
        return buildString {
            append("Locus diagnostics (secrets removed)\n").append(header).append("\n")
            for (e in _entries.value) {
                append("\n--- ").append(e.title).append(" · ")
                append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(e.at))).append(" ---\n")
                append(e.text.trimEnd()).append('\n')
            }
        }
    }

    fun clear() {
        log.clear()
        _entries.value = emptyList()
    }

    /** Messages the user must see once (saved keys reset, settings reset). */
    fun notices(): List<String> = log.notices()

    fun dismissNotices() = log.dismissNotices()

    /** Records an error the app's coroutine scope caught (it would otherwise have ended the process). */
    fun recordError(error: Throwable) {
        log.record(DiagnosticsLog.Kind.ERROR, "Background error: ${error::class.java.simpleName}", CrashRecorder.report(Thread.currentThread().name, error, ""))
    }

    /** Reads Android's exit history (API 30+) and records the exits since the last check. Call once per start, off the main thread. */
    fun recordExitsSinceLastStart() {
        if (Build.VERSION.SDK_INT < 30) return
        val exits = runCatching { readExits() }.getOrElse { return }
        val since = log.readMark(EXIT_MARK) ?: 0
        for (exit in exitsToRecord(exits, since)) {
            log.record(DiagnosticsLog.Kind.EXIT, exitTitle(exit.reason), CrashRecorder.scrub(exitText(exit)))
        }
        exits.maxOfOrNull { it.timestamp }?.let { if (it > since) log.writeMark(EXIT_MARK, it) }
    }

    @RequiresApi(30)
    private fun readExits(): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS).map { info ->
            val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { info.traceInputStream?.use { it.readNBytesCompat(MAX_TRACE_BYTES).decodeToString() } }.getOrNull()
            } else {
                null
            }
            ExitRecord(info.timestamp, info.reason, info.description, info.importance, trace)
        }
    }

    private suspend fun scrubber(): SecretScrubber = SecretScrubber(runCatching { secrets.valuesForScrubbing() }.getOrDefault(emptyList()))

    private companion object {
        const val EXIT_MARK = "exits-checked"
        const val MAX_EXITS = 16
        /** The start of an ANR trace: the main thread comes first, and that's what matters. */
        const val MAX_TRACE_BYTES = 24_000
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
