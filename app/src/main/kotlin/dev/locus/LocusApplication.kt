package dev.locus

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.data.backup.PendingRestore
import dev.locus.core.data.integrity.PolicyIntegrity
import dev.locus.core.linux.ProcIsolation
import javax.inject.Inject

@HiltAndroidApp
class LocusApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var backup: dagger.Lazy<BackupManager>
    @Inject lateinit var integrity: dagger.Lazy<PolicyIntegrity>

    private var rescheduleTasks = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First, before any file is opened: Linux programs run as this app's uid, and without this
        // they could reach Locus's open files and memory through /proc/<pid> (docs/design/linux-isolation.md).
        if (!ProcIsolation.makeNotDumpable()) Log.w("Locus", "Couldn't make the process non-dumpable")
        // A confirmed restore is swapped in here, before any provider, Room or DataStore opens the files.
        // It's all-or-nothing (the vault included): a failure is rolled back and recorded for
        // RestoreNoticeViewModel, not retried.
        val pending = PendingRestore.forApp(base)
        runCatching { pending.apply() }.onFailure { Log.e("Locus", "Restoring a backup failed", it) }
        // A restore cancelled scheduled tasks; they come back from whichever database is now in place.
        rescheduleTasks = runCatching { pending.rescheduleOwed() }.getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        if (rescheduleTasks) backup.get().rescheduleTasks()
        // Sealed permissions and connector approvals, checked off the main thread (docs/design/linux-isolation.md).
        integrity.get().checkAtStart()
    }

    // Scheduled-task workers get their dependencies from Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
