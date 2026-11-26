package dev.locus.feature.settings

import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.recreateAfter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The backup password sheet keeps both fields when the phone turns (a recreated activity). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupPasswordRotationTest {

    @Test
    fun `both password fields survive a rotation`() {
        val (before, after) = recreateAfter(listOf(Step.Type("violet harbor lamp", 0), Step.Type("violet harbor lamp", 1))) {
            NewPasswordSheet(includesVault = false, onDismiss = {}, onDone = {})
        }
        // A masked field reads as one dot per character: both fields hold the password before the rotation…
        val dots = "•".repeat("violet harbor lamp".length)
        assertEquals(2, before.texts.count { it == dots }, before.texts.toString())
        assertTrue(before.shows("Strength: strong"))
        // …and after it, so neither came back empty.
        assertEquals(2, after.texts.count { it == dots }, after.texts.toString())
        assertTrue(after.shows("Strength: strong"), after.texts.toString())
        assertFalse(after.shows("The passwords don't match."))
    }
}
