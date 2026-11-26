package dev.locus.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings → Automation: Forget is at once, and its Undo puts the app's answer back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutomationViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    @Test
    fun `forgetting an app's answer can be undone`() = runTest {
        try {
            val g = TestGraph(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            g.settingsRepository.setAutomationCaller("org.example.tasker", true)
            g.settingsRepository.setAutomationCaller("org.example.spam", false)
            val vm = host.add(AutomationViewModel(g.settingsRepository, ApplicationProvider.getApplicationContext<Context>()))
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val tasker = vm.state.first { it.apps.size == 2 }.apps.first { it.packageName == "org.example.tasker" }

            vm.forget(tasker.packageName)
            assertTrue(vm.state.first { it.apps.size == 1 }.apps.none { it.packageName == tasker.packageName })

            vm.restore(tasker)
            val back = vm.state.first { it.apps.size == 2 }.apps.first { it.packageName == tasker.packageName }
            assertEquals(true, back.allowed)
        } finally {
            host.clear()
        }
    }
}
