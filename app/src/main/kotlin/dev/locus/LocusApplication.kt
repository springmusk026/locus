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

    private var restored = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // A confirmed restore is swapped in here, before any provider, Room or DataStore opens the files.
        // It's all-or-nothing: a failure is rolled back and recorded for RestoreNoticeViewModel, not retried.
        restored = runCatching { PendingRestore.forApp(base).apply() }
            .onFailure { Log.e("Locus", "Restoring a backup failed", it) }
            .getOrDefault(false)
    }

    override fun onCreate() {
        super.onCreate()
        if (restored) backup.get().onRestored()
    }

    // Scheduled-task workers get their dependencies from Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
