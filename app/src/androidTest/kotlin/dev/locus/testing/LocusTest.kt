package dev.locus.testing

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dev.locus.LocusStartup
import dev.locus.MainActivity
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.data.agent.AgentService
import dev.locus.core.data.agent.OpenChatLink
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.data.backup.PendingRestore
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import dev.locus.core.data.integrity.PolicyIntegrity
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.database.DatabaseBackup
import dev.locus.core.datastore.DataStoreFiles
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import javax.inject.Inject

/** On a test method: don't wipe Locus's data before it (it's the second phase of a two-process test). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class KeepDataBefore

/** On a test method: leave Locus's data for the next test (the first phase of a two-process test). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class KeepDataAfter

/**
 * The base of every instrumentation test: Hilt, a clean copy of Locus's data, the scripted model,
 * WorkManager's test driver, and the rest of startup. Subclasses are `@HiltAndroidTest`.
 *
 * Data: the tests run in the debug app (dev.locus.debug), whose data they wipe before each test and
 * clean after it. The orchestrator runs each test in its own process, so nothing has Locus's files
 * open when the wipe runs. The release app (dev.locus) is a different app and is never touched.
 */
abstract class LocusTest {
    @get:Rule(order = 0)
    val data: TestRule = AppDataRule()

    @get:Rule(order = 1)
    val hilt = HiltAndroidRule(this)

    /** Approvals post notifications; granted up front so no permission dialog covers the app. */
    @get:Rule(order = 2)
    val notifications: TestRule = if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else object : TestWatcher() {}

    @Inject lateinit var settingsStore: DataStore<AppSettings>
    @Inject lateinit var runner: AgentRunner
    @Inject lateinit var conversations: ConversationRepository
    @Inject lateinit var workspace: Workspace
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var backup: dagger.Lazy<BackupManager>
    @Inject lateinit var integrity: dagger.Lazy<PolicyIntegrity>
    @Inject lateinit var diagnostics: dagger.Lazy<DiagnosticsRepository>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    val context: Context get() = ApplicationProvider.getApplicationContext()
    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Tool permissions for this test's settings; write_file and edit_file run without asking unless a test says otherwise. */
    open val toolPolicies: Map<String, ToolPolicy> = mapOf("write_file" to ToolPolicy.ALLOW, "edit_file" to ToolPolicy.ALLOW)

    /** False for a test that starts on first-run setup. */
    open val seedProvider: Boolean = true

    private var keepAfter = false
    private val scenarios = mutableListOf<ActivityScenario<*>>()

    @Before
    fun startLocus() {
        hilt.inject()
        // HiltTestApplication isn't the app's Configuration.Provider, so WorkManager gets the test
        // version here, with Hilt's worker factory (https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing).
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(workerFactory).setExecutor(SynchronousExecutor()).build(),
        )
        // The rest of LocusApplication.onCreate, now that the graph exists.
        LocusStartup.afterInjection(LocusTestApplicationBase.rescheduleTasks, backup, integrity, diagnostics, appScope)
        FakeModel.reset()
        if (seedProvider && !keepBefore) seed()
    }

    /** Settings for a test: the scripted provider, setup done, and nothing that calls the model on its own. */
    fun seed(policies: Map<String, ToolPolicy> = toolPolicies) = runBlocking {
        settingsStore.updateData {
            it.copy(
                providers = listOf(FakeModel.provider),
                defaultProviderId = FakeModel.provider.id,
                onboardingDone = true,
                autoTitle = false,
                suggestFollowUps = false,
                reflectAfterTasks = false,
                toolPolicies = policies,
            )
        }
    }

    @After
    fun stopLocus() {
        scenarios.forEach { runCatching { it.close() } }
        if (keepAfter) return
        runCatching {
            runBlocking {
                runner.states.value.keys.forEach(runner::cancel)
                conversations.allOnce().forEach { conversations.delete(it.id) }
            }
        }.onFailure { Log.w(TAG, "Couldn't clear chats after the test", it) }
        // Files go now; the database and settings are deleted before the next test, when nothing has them open.
        wipeFiles(context, includeStores = false)
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    // --- Helpers ---

    /** A new chat on the scripted provider. */
    fun newChat(title: String? = null): String = runBlocking {
        val id = conversations.create(FakeModel.provider.id).id
        if (title != null) conversations.rename(id, title)
        id
    }

    /** Starts Locus on [chatId]'s chat, the way a notification opens it; closed after the test. */
    fun launchChat(chatId: String): ActivityScenario<MainActivity> =
        launch(OpenChatLink.attach(context, mainIntent(), chatId))

    /** Starts Locus at home (or setup on a first run); closed after the test. */
    fun launchHome(): ActivityScenario<MainActivity> = launch(mainIntent())

    private fun launch(intent: Intent): ActivityScenario<MainActivity> = ActivityScenario.launch<MainActivity>(intent).also { scenarios += it }

    fun mainIntent(): Intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    /**
     * Brings Locus back to the front after Home, as the launcher icon does. Through the shell: Android
     * 10+ doesn't let an app in the background start an activity itself.
     */
    fun reopen() {
        device.executeShellCommand("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${context.packageName}/${MainActivity::class.java.name}")
        waitUntil("Locus to be in the foreground") { appVisible() }
    }

    fun state(chatId: String): RunState = runner.states.value[chatId] ?: RunState()

    /** Polls [condition] in real time until it holds, or fails naming [what]. */
    fun waitUntil(what: String, timeoutMs: Long = 30_000, condition: suspend () -> Boolean) = runBlocking {
        try {
            withTimeout(timeoutMs) { while (!condition()) delay(50) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what", e)
        }
    }

    /** Waits until [chatId]'s run has ended. */
    fun awaitRunEnd(chatId: String, timeoutMs: Long = 60_000) = waitUntil("the run in $chatId to end", timeoutMs) {
        !state(chatId).running
    }

    fun appVisible(): Boolean = runOnMain { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }

    /** Whether AgentService is running as a foreground service (getRunningServices still lists the caller's own). */
    @Suppress("DEPRECATION")
    fun agentServiceInForeground(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AgentService::class.java.name && it.foreground }

    /** Locus's notifications now showing (the shade's view of them, without driving the shade). */
    fun activeNotifications() = context.getSystemService(NotificationManager::class.java).activeNotifications.toList()

    fun <T> runOnMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private val keepBefore: Boolean get() = (data as AppDataRule).keepBefore

    /** Wipes Locus's data before the test unless it's [KeepDataBefore]; reads [KeepDataAfter]. */
    private inner class AppDataRule : TestWatcher() {
        var keepBefore = false

        override fun starting(description: Description) {
            keepBefore = description.getAnnotation(KeepDataBefore::class.java) != null
            keepAfter = description.getAnnotation(KeepDataAfter::class.java) != null
            if (!keepBefore) wipeFiles(context, includeStores = true)
        }
    }

    companion object {
        private const val TAG = "LocusTest"

        /**
         * Deletes what Locus keeps: the workspace and its trash, the agent home, queued messages and any
         * restore stage; with [includeStores], the database and settings too (only safe before anything
         * opens them). The vault and its Keystore key are kept: the tests never put a secret in them,
         * and deleting the key would make Locus ask to reset saved keys.
         */
        fun wipeFiles(context: Context, includeStores: Boolean) {
            val files = context.filesDir
            val noBackup = context.noBackupFilesDir
            val doomed = buildList {
                add(File(files, PendingRestore.WORKSPACE_DIR))
                add(File(files, "workspace-trash"))
                add(File(files, PendingRestore.AGENT_HOME_DIR))
                add(File(noBackup, "queued"))
                add(File(noBackup, "restore"))
                add(File(noBackup, "restore-failed"))
                add(File(noBackup, "restore-reschedule"))
                if (includeStores) {
                    val db = context.getDatabasePath(DatabaseBackup.NAME)
                    listOf("", "-wal", "-shm", "-journal").forEach { add(File(db.path + it)) }
                    add(DataStoreFiles.file(context, DataStoreFiles.SETTINGS))
                }
            }
            doomed.forEach { file ->
                // A failed-restore test leaves read-only folders and unreadable files behind on purpose.
                file.walkTopDown().forEach { it.setReadable(true, true); it.setWritable(true, true); it.setExecutable(true, true) }
                file.deleteRecursively()
                File(file.path + PendingRestoreOld).deleteRecursively()
                if (file.exists()) Log.w(TAG, "Couldn't delete ${file.path}")
            }
        }

        /** The suffix PendingRestore gives what it moves aside during a swap. */
        private const val PendingRestoreOld = ".restore-old"
    }
}
