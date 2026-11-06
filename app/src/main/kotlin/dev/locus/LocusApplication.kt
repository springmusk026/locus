package dev.locus

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.data.integrity.PolicyIntegrity
import dev.locus.core.linux.ProcIsolation
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.diagnostics.CrashRecorder
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LocusApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var backup: dagger.Lazy<BackupManager>
    @Inject lateinit var integrity: dagger.Lazy<PolicyIntegrity>
    @Inject lateinit var diagnostics: dagger.Lazy<DiagnosticsRepository>
    @Inject @ApplicationScope lateinit var scope: CoroutineScope
    @Inject lateinit var shortcuts: dagger.Lazy<dev.locus.core.data.shortcuts.ChatShortcuts>
    @Inject lateinit var telegramRemote: dagger.Lazy<dev.locus.core.data.integrations.TelegramRemoteHub>
    @Inject lateinit var widget: dagger.Lazy<dev.locus.widget.LocusWidgetUpdater>
    @Inject lateinit var agentHome: dagger.Lazy<dev.locus.core.agent.home.AgentHome>
    @Inject lateinit var updates: dagger.Lazy<dev.locus.core.data.updates.Updates>
    @Inject lateinit var linuxAutostart: dagger.Lazy<dev.locus.core.data.linux.LinuxAutostart>
    @Inject lateinit var temporaryChats: dagger.Lazy<dev.locus.core.data.repository.TemporaryChats>
    @Inject lateinit var reminders: dagger.Lazy<dev.locus.core.data.device.Reminders>

    private var rescheduleTasks = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First, before any file is opened: Linux programs run as this app's uid, and without this
        // they could reach Locus's open files and memory through /proc/<pid> (docs/design/linux-isolation.md).
        if (!ProcIsolation.makeNotDumpable()) Log.w("Locus", "Couldn't make the process non-dumpable")
        // Workspace writes open with O_NOFOLLOW and check where the descriptor really is (/proc/self/fd),
        // so a folder a Linux program swaps for a link can't redirect them (docs/design/linux-isolation.md).
        dev.locus.core.model.NoFollowFiles.opener = dev.locus.core.linux.AndroidNoFollowOpener
        // Next, so a crash anywhere after this (a restore, Hilt, the first screen) is written to the
        // local diagnostics log before the process ends. Nothing is sent anywhere.
        CrashRecorder.install(base)
        // A confirmed restore is swapped in here, before any provider, Room or DataStore opens the files.
        rescheduleTasks = LocusStartup.beforeProviders(base)
    }

    override fun onCreate() {
        super.onCreate()
        LocusStartup.afterInjection(rescheduleTasks, backup, integrity, diagnostics, scope)
        // Pinned and recent chats as launcher and Direct Share shortcuts (docs/design/android-bridge.md).
        shortcuts.get().start()
        // Approvals by Telegram button and remote mode, when the user turned them on (docs/design/telegram-remote.md).
        telegramRemote.get().start()
        // The home-screen widget follows the agent's status and the latest task result.
        widget.get().start()
        // Memory search: open the index now, rebuilding it from the notes if it's missing or stale.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { agentHome.get().openMemoryIndex() }
        // The opt-in update check's daily schedule follows its setting; nothing is fetched here (docs/design/updates.md).
        updates.get().start()
        // Linux services the user set to start when Locus opens, once it's on screen (docs/design/linux-health.md).
        linuxAutostart.get().start()
        // Reminder alarms again, in case Android dropped them (a force stop clears alarms; docs/design/phone-integrations.md).
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { reminders.get().rescheduleAll() } }
        // Temporary chats left from the last run are deleted (docs/design/temporary-chats.md). A moment
        // later, so a chat screen Android restores after the process was killed registers first and keeps its chat.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(TEMPORARY_SWEEP_DELAY_MS)
            runCatching { temporaryChats.get().sweep() }
        }
    }

    private companion object {
        const val TEMPORARY_SWEEP_DELAY_MS = 10_000L
    }

    // Scheduled-task workers get their dependencies from Hilt.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
