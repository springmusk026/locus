package dev.locus

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.data.backup.PendingRestore
import javax.inject.Inject

@HiltAndroidApp
class LocusApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var backup: dagger.Lazy<BackupManager>

    private var rescheduleTasks = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
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
    }

    // Scheduled-task workers get their dependencies from Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
