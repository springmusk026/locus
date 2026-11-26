package dev.locus.feature.onboarding

import dev.locus.core.screenshot.PrivateComposable
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.screenAfter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Onboarding's key sheet: Remove key asks before the saved key goes, as Settings → Integrations does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntegrationKeyRemoveTest {

    @Test
    fun `remove key asks first and removes only when confirmed`() {
        val saved = mutableListOf<String?>()
        val sheet = @androidx.compose.runtime.Composable {
            PrivateComposable("dev.locus.feature.onboarding.SetupStepsKt", "IntegrationKeySheet", Integrations.ntfy, true, { key: String? -> saved += key }, {})
        }
        val asking = screenAfter(listOf(Step.Tap("Remove key"))) { sheet() }
        assertTrue(asking.shows("Remove the ntfy key?"), asking.texts.toString())
        assertEquals(emptyList(), saved)

        // The confirmation's own Remove key (the second on screen) removes it.
        screenAfter(listOf(Step.Tap("Remove key"), Step.Tap("Remove key", index = 1))) { sheet() }
        assertEquals(listOf<String?>(null), saved)
    }
}
