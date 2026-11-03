package dev.locus.core.designsystem

import androidx.compose.material3.SnackbarDuration
import androidx.compose.ui.unit.Dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignSystemTest {

    @Test
    fun `the spacing scale climbs and has no gaps a screen would fill by hand`() {
        val scale = listOf(Spacing.xxs, Spacing.xs, Spacing.sm, Spacing.md, Spacing.lg, Spacing.xl, Spacing.xxl)
        assertEquals(listOf(2f, 4f, 8f, 12f, 16f, 24f, 32f), scale.map(Dp::value))
    }

    @Test
    fun `icon sizes climb and a tappable thing is at least 48dp`() {
        val sizes = listOf(IconSize.small, IconSize.medium, IconSize.large, IconSize.tileSmall, IconSize.tile, IconSize.hero)
        assertEquals(sizes.sortedBy { it.value }, sizes)
        assertEquals(48f, Sizes.touchTarget.value)
        assertTrue(Sizes.row >= Sizes.touchTarget)
        assertTrue(Stroke.hairline < Stroke.regular && Stroke.regular < Stroke.bold)
    }

    @Test
    fun `emphasis levels are ordered from faint fill to readable content`() {
        val levels = listOf(Emphasis.subtleFill, Emphasis.hairline, Emphasis.neutralContainer, Emphasis.toneContainer, Emphasis.toneOutline, Emphasis.disabled, Emphasis.muted)
        assertEquals(levels.sorted(), levels)
        assertTrue(levels.all { it > 0f && it < 1f })
        // Material's own disabled alpha, so our controls fade like Material's.
        assertEquals(0.38f, Emphasis.disabled)
    }

    @Test
    fun `motion durations go quick, standard, slow`() {
        assertTrue(Motion.QUICK_MS < Motion.STANDARD_MS && Motion.STANDARD_MS < Motion.SLOW_MS)
    }

    @Test
    fun `meter fractions clamp and survive a zero total`() {
        assertEquals(0f, meterFraction(-0.2f))
        assertEquals(1f, meterFraction(1.7f))
        assertEquals(0f, meterFraction(Float.NaN))
        assertEquals(0f, meterFraction(Float.POSITIVE_INFINITY))
        assertEquals(0.25f, meterFraction(1, 4))
        assertEquals(0f, meterFraction(5, 0))
        assertEquals(1f, meterFraction(9, 4))
    }

    @Test
    fun `a meter reads as a whole percentage`() {
        assertEquals("42 percent", meterPercent(0.421f))
        assertEquals("100 percent", meterPercent(3f))
        assertEquals("0 percent", meterPercent(Float.NaN))
    }

    @Test
    fun `counts cap so a chip never grows past two digits and a plus`() {
        assertEquals("0", chipCount(-3))
        assertEquals("7", chipCount(7))
        assertEquals("99", chipCount(99))
        assertEquals("99+", chipCount(100))
        assertEquals("9+", chipCount(12, max = 9))
    }

    @Test
    fun `destinations are written with arrows and spoken without them`() {
        val path = destinationPath("Settings", "Models")
        assertEquals("Settings → Models", path)
        assertEquals("Settings, Models", destinationSpeech(path))
        assertEquals("Library, Connectors, GitHub", destinationSpeech("Library > Connectors → GitHub"))
        assertEquals("Settings", destinationSpeech("Settings"))
    }

    @Test
    fun `a settings link is written the way the hint text was`() {
        assertEquals("Settings → Models", settingsPath(dev.locus.core.model.SettingsDestination.Models))
        assertEquals("Settings → About & privacy → Diagnostics", settingsPath(dev.locus.core.model.SettingsDestination.Diagnostics))
        assertEquals("Settings, SSH machines", destinationSpeech(settingsPath(dev.locus.core.model.SettingsDestination.Ssh)))
        assertEquals("Open SSH machines", settingsActionLabel(dev.locus.core.model.SettingsDestination.Ssh))
        // A chat error that names a page gets a button; one that doesn't, doesn't.
        assertTrue(hasSettingsAction("OpenAI has no API key. Add it in Settings → Models."))
        assertTrue(!hasSettingsAction("The model didn't answer.") && !hasSettingsAction(null))
        // Every page's link text leads back to that page, so a message can't name a page the link doesn't open.
        dev.locus.core.model.SettingsDestination.entries.forEach {
            assertEquals(it, dev.locus.core.model.SettingsDestination.inText(settingsPath(it)), it.name)
        }
    }

    @Test
    fun `feedback with an action or a problem stays long enough to act on`() {
        assertEquals(SnackbarDuration.Short, feedbackDuration(Tone.Live, hasAction = false))
        assertEquals(SnackbarDuration.Short, feedbackDuration(Tone.Neutral, hasAction = false))
        assertEquals(SnackbarDuration.Long, feedbackDuration(Tone.Neutral, hasAction = true))
        assertEquals(SnackbarDuration.Long, feedbackDuration(Tone.Critical, hasAction = false))
        assertEquals(SnackbarDuration.Long, feedbackDuration(Tone.Caution, hasAction = false))
    }

    @Test
    fun `every tone has a feedback icon, and problems share the alert`() {
        assertEquals(LocusIcons.Alert, feedbackIcon(Tone.Critical))
        assertEquals(LocusIcons.Alert, feedbackIcon(Tone.Caution))
        assertEquals(LocusIcons.Check, feedbackIcon(Tone.Live))
        Tone.entries.forEach { feedbackIcon(it) }
    }

    @Test
    fun `a busy switch says it is updating`() {
        assertEquals("On", switchState(checked = true, busy = false))
        assertEquals("Off, updating", switchState(checked = false, busy = true))
    }
}
