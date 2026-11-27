package dev.locus

import android.content.Intent
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.locus.core.data.automation.AutomationBridge
import dev.locus.core.data.automation.AutomationContract
import dev.locus.core.data.backup.BackupManager
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import dev.locus.core.data.integrity.PolicyIntegrity
import dev.locus.core.data.lock.AppLock
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.shortcuts.ChatShortcuts
import dev.locus.core.data.updates.UpdateBuild
import dev.locus.core.terminal.TerminalSessions
import dev.locus.widget.LocusWidgetUpdater
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The app's Hilt graph, as the app module wires it: every @InstallIn module on the app's classpath,
 * plus UpdateBuildModule. Hilt checks the whole graph when it generates this test's components, so a
 * binding the app's entry points need (LocusApplication's fields, MainActivity's, AutomationActivity's,
 * every @HiltViewModel) that nothing provides fails the build of this test. The providers below are
 * the ones LocusApplication and the activities inject; most aren't built, only proven to exist.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = HiltTestApplication::class)
class AppGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var updateBuild: UpdateBuild
    @Inject lateinit var settings: Provider<SettingsRepository>
    @Inject lateinit var appLock: Provider<AppLock>
    @Inject lateinit var shortcuts: Provider<ChatShortcuts>
    @Inject lateinit var bridge: Provider<AutomationBridge>
    @Inject lateinit var workerFactory: Provider<HiltWorkerFactory>
    @Inject lateinit var backup: Provider<BackupManager>
    @Inject lateinit var integrity: Provider<PolicyIntegrity>
    @Inject lateinit var diagnostics: Provider<DiagnosticsRepository>
    @Inject lateinit var widget: Provider<LocusWidgetUpdater>
    @Inject lateinit var terminals: Provider<TerminalSessions>

    @Before
    fun inject() {
        // Robolectric's app has no native library directory; Linux then reports "not installed", as
        // TestContext arranges for the other modules' tests.
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app.applicationInfo.nativeLibraryDir = File(app.cacheDir, "native").apply { mkdirs() }.path
        hilt.inject()
    }

    @Test
    fun `the update check is told what this build is`() {
        assertEquals(UpdateBuild(enabled = BuildConfig.UPDATE_CHECK_ENABLED, debug = BuildConfig.DEBUG, versionName = BuildConfig.VERSION_NAME), updateBuild)
        // Unit tests run the debug build: dev.locus.debug, which a release APK installs beside.
        assertTrue(updateBuild.debug)
    }

    @Test
    fun `the lock and the shortcuts are one per app`() {
        // Two locks would let one screen unlock while another stays locked (docs/design/app-lock.md).
        assertSame(appLock.get(), appLock.get())
        assertSame(settings.get(), settings.get())
        assertSame(shortcuts.get(), shortcuts.get())
    }

    @Test
    fun `an automation request that doesn't parse is refused at once`() {
        // START_CHAT without its prompt: refused before anything is shown or run.
        val intent = Intent(AutomationContract.ACTION_START_CHAT)
        val activity = Robolectric.buildActivity(AutomationActivity::class.java, intent).setup().get()
        assertTrue(activity.isFinishing)
    }
}
