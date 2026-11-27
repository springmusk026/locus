package dev.locus

import android.app.Application
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import dev.locus.core.model.SettingsDestination
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * MainActivity's back stack (rememberNavBackStack) is saved into the activity's state with
 * NavBackStackSerializer and NavKeySerializer, and read back after Android kills and restores Locus. A
 * route that can't make that trip crashes the restore, so every route, with every field set, goes
 * through the same serializers into a Bundle and back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoutesSavedStateTest {
    @Test
    fun `every route survives the trip through saved state`() {
        val routes: List<NavKey> = listOf(
            ConversationsRoute,
            ChatRoute("c1", draft = "shared text\nwith a line break", focusMessageId = "m9", voice = true),
            SettingsRoute(),
            SettingsRoute(SettingsDestination.About),
            OnDeviceModelsRoute,
            OnDeviceSearchRoute("qwen"),
            OnDeviceModelRoute("repo:owner/name"),
            WorkspaceRoute(openPath = "inbox/report.pdf"),
            TerminalRoute,
            LinuxHealthRoute,
            LinuxPackagesRoute,
            ActivityRoute("c1", taskSince = 10L, taskUntil = 20L, taskDecisions = listOf("ALLOWED", "DENIED"), taskLabel = "Weekly report"),
            BrowserRoute(standalone = true),
            LibraryRoute(tab = 2, template = "github", suggestMemories = true),
            TasksRoute,
            OnboardingRoute,
            PersonaRoute,
            AnalyticsRoute,
            ProjectsRoute,
            ProjectRoute("p1"),
            ProjectEditRoute(),
            ProjectEditRoute("p1"),
            ProjectRulesRoute("p1"),
        )
        val serializer = NavBackStackSerializer(NavKeySerializer<NavKey>())
        val saved = encodeToSavedState(serializer, NavBackStack(*routes.toTypedArray()))
        val restored = decodeFromSavedState(serializer, saved)
        assertEquals(routes, restored.toList())
    }
}
