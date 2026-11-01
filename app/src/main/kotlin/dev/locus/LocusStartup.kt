package dev.locus

import android.content.Context
import android.util.Log
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.data.backup.PendingRestore
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import dev.locus.core.data.integrity.PolicyIntegrity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What Locus does as its process starts, in two parts: before anything opens its files, and once the
 * Hilt graph exists. [LocusApplication] runs both; the instrumentation tests' application runs the
 * same steps (app/src/androidTest), so a test process starts the way the app does.
 */
object LocusStartup {
    /**
     * From `Application.attachBaseContext`, before any provider, Room or DataStore opens the files: a
     * confirmed restore is swapped in. It's all-or-nothing (the vault included): a failure is rolled
     * back and recorded for RestoreNoticeViewModel, not retried. Returns whether a restore cancelled
     * scheduled tasks that must be scheduled again (pass it to [afterInjection]).
     */
    fun beforeProviders(base: Context): Boolean {
        val pending = PendingRestore.forApp(base)
        runCatching { pending.apply() }.onFailure { Log.e("Locus", "Restoring a backup failed", it) }
        // A restore cancelled scheduled tasks; they come back from whichever database is now in place.
        return runCatching { pending.rescheduleOwed() }.getOrDefault(false)
    }

    /** Once the Hilt graph exists (`Application.onCreate` in the app). Lazy, so nothing is built that isn't needed. */
    fun afterInjection(
        rescheduleTasks: Boolean,
        backup: dagger.Lazy<BackupManager>,
        integrity: dagger.Lazy<PolicyIntegrity>,
        diagnostics: dagger.Lazy<DiagnosticsRepository>,
        scope: CoroutineScope,
    ) {
        if (rescheduleTasks) backup.get().rescheduleTasks()
        // Sealed permissions and connector approvals, checked off the main thread (docs/design/linux-isolation.md).
        integrity.get().checkAtStart()
        // ANRs, native crashes and kills Android recorded since the last start, into Diagnostics.
        scope.launch(Dispatchers.IO) { diagnostics.get().recordExitsSinceLastStart() }
    }
}
