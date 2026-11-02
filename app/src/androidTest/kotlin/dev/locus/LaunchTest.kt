package dev.locus

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import dagger.hilt.android.testing.HiltAndroidTest
import dev.locus.testing.LocusTest
import org.junit.Rule
import org.junit.Test

/**
 * MainActivity starts on a real device: setup on a first run, home otherwise, with every view model
 * its root composes (the restore, integrity, storage and saved-keys notices), and home's doors open
 * through the real navigation.
 */
@HiltAndroidTest
class LaunchTest : LocusTest() {
    @get:Rule(order = 3)
    val compose = createEmptyComposeRule()

    /** First run: MainActivity opens on setup, with every view model the root composes (the notices). */
    @Test
    fun firstRunOpensSetup() {
        runBlockingReset()
        launchHome()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Step 1 of", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Home and each of its doors, through MainActivity's real navigation, and back. */
    @Test
    fun homeAndItsDoorsOpen() {
        launchHome()
        val doors = listOf("Library", "Tasks", "Workspace files and Linux", "Activity, everything it did", "Insights: tokens, speed and tools", "Settings")
        doors.forEach { door ->
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(door, substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasContentDescription(door, substring = true)).onFirst().performClick()
            compose.waitForIdle()
            Espresso.pressBack()
            compose.waitForIdle()
        }
        // Home again.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Locus")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** No provider and setup not done, as on a first run. */
    private fun runBlockingReset() = kotlinx.coroutines.runBlocking { settingsStore.updateData { dev.locus.core.model.AppSettings() } }
}
