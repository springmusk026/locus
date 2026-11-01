package dev.locus.core.data.diagnostics

import android.content.Context
import android.os.Build
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.datastore.DiagnosticsLog
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The default uncaught-exception handler: writes a scrubbed crash report to the local [DiagnosticsLog],
 * then hands the exception to the previous handler (which shows the system dialog and ends the process).
 * Nothing is sent anywhere.
 *
 * The report is scrubbed with SecretScrubber's credential patterns only (`Bearer …`, `sk-…`,
 * `api_key=…`): reading the vault's values from a dying process isn't safe. Diagnostics scrubs again
 * with the vault's values before it shows or shares anything.
 */
class CrashRecorder internal constructor(
    private val log: DiagnosticsLog,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val environment: String,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            log.record(DiagnosticsLog.Kind.CRASH, "Crash: ${error::class.java.simpleName}", report(thread.name, error, environment))
        } catch (_: Throwable) {
            // Never let the recorder hide the original crash.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private val patterns = SecretScrubber()

        /** Installs the recorder in front of the current default handler. Call first thing in Application.onCreate. */
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashRecorder) return
            Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(DiagnosticsLog.forApp(context), current, environment(context)))
        }

        /** The app version, Android version and device, for the top of every report. */
        fun environment(context: Context): String {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            return "Locus ${version ?: "?"} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"
        }

        /** The report for [error] on [threadName]: environment, thread, and the full stack trace with causes, scrubbed. */
        internal fun report(threadName: String, error: Throwable, environment: String): String {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            return scrub("$environment\nThread: $threadName\n\n$trace")
        }

        /** Credential patterns removed; see [SecretScrubber]. */
        internal fun scrub(text: String): String = patterns.scrub(text)
    }
}
