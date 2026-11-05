package dev.locus.core.screenshot

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziComposeSetupOption
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.background
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import dev.locus.core.designsystem.LocusDarkColors
import dev.locus.core.designsystem.LocusLightColors
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.ProvideSettingsLinks
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One way a screen is looked at. Every [snap] renders all of them: the two common phone widths in both
 * themes, and the narrow width at 200% font, where truncation, wrapping and overlap show first.
 */
enum class Variant(val id: String, val widthDp: Int, val dark: Boolean, val fontScale: Float) {
    Light360("360-light", 360, dark = false, fontScale = 1f),
    Dark360("360-dark", 360, dark = true, fontScale = 1f),
    Light411("411-light", 411, dark = false, fontScale = 1f),
    Dark411("411-dark", 411, dark = true, fontScale = 1f),
    Font200("360-light-font200", 360, dark = false, fontScale = 2f),
}

/**
 * Base class for a module's screenshot tests: Robolectric with native graphics (real Skia drawing on the
 * JVM), at SDK 36. Subclasses call [snap] from ordinary `@Test` methods.
 *
 * Images are only drawn when Roborazzi is asked to record (`recordRoborazziDebug`, as CI's screenshots
 * job does): each render goes to the module's `build/screenshots` and the render guard checks it. There
 * are no goldens: no screenshot is committed or uploaded (docs/design/design-system.md, "Review gate").
 * A plain `testDebugUnitTest` skips the drawing and stays fast. `tools/render-screens.sh` records every
 * screen to a local folder for a person to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "mdpi")
abstract class ScreenshotTest {

    /**
     * Renders [content] in every [Variant] (or [variants]) as `<module>/build/screenshots/<name>_<variant>.png`.
     * [content] gets the theme and nothing else: no Surface, no padding. A screen that doesn't paint its
     * own background, or text that relies on a Surface it isn't in, shows up as such.
     *
     * [heightDp] is the window's height: a phone (800) by default; a sheet or a component can ask for less.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    fun snap(
        name: String,
        heightDp: Int = PHONE_HEIGHT_DP,
        variants: List<Variant> = Variant.entries,
        steps: List<Step> = emptyList(),
        /** The activity to render in instead of Roborazzi's own: [HiltHostActivity] for a route that asks Hilt for its view model. */
        activity: Class<out ComponentActivity>? = null,
        content: @Composable () -> Unit,
    ) {
        require(NAME.matches(name)) { "Screenshot names are lower-case words joined by dashes: $name" }
        // "Remove animations": Compose reads this scale, so entrances land at once and looping motion
        // (spinners, the live dot, voice mode's breathing) stops on a still frame instead of keeping
        // the main looper busy forever.
        Settings.Global.putFloat(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        val problems = ArrayList<String>()
        for (v in variants) {
            val shot = "${name}_${v.id}"
            val guard = RenderGuardOption(shot)
            val options = RoborazziComposeOptions {
                size(widthDp = v.widthDp, heightDp = heightDp)
                uiMode(if (v.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                fontScale(v.fontScale)
                addOption(DensityOption(density))
                // Previews' mode: sheets draw in place and nothing reaches for real services.
                inspectionMode(true)
                // The window is the theme's background, as the app's is: a sheet or a component drawn on its
                // own then sits on the page colour, not on transparent pixels a viewer shows as white.
                background(showBackground = true, backgroundColor = (if (v.dark) LocusDarkColors else LocusLightColors).background.toArgb().toLong() and 0xFFFFFFFFL)
                if (steps.isNotEmpty()) addOption(StepsOption(steps))
                if (activity != null) addOption(ActivityOption(activity))
                addOption(guard)
            }
            captureRoboImage(
                file = File(outputDir, "$shot.png"),
                roborazziComposeOptions = options,
            ) {
                // Settings links as in the app (tappable, so drawn enabled); a tap goes nowhere.
                LocusTheme(darkTheme = v.dark) { ProvideSettingsLinks({}) { content() } }
            }
            // Only a drawn image is checked: without record or verify, nothing was rendered.
            // A review render (another folder, another density) only reports: the baseline counts 1x renders.
            if (guard.ran) RenderGuard.enforce(shot, guard.findings, reportOnly = reviewRender)?.let(problems::add)
        }
        // After every variant, so a finding in one doesn't leave the others unrecorded.
        if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n\n"))
    }

    /**
     * [snap] with [steps] taken on the screen before each capture (a tap, typing, a state change): for a
     * sheet or confirmation only a tap opens.
     */
    fun snapAfter(
        name: String,
        steps: List<Step>,
        heightDp: Int = PHONE_HEIGHT_DP,
        variants: List<Variant> = Variant.entries,
        activity: Class<out ComponentActivity>? = null,
        content: @Composable () -> Unit,
    ) = snap(name, heightDp, variants, steps, activity, content)

    /**
     * Renders an existing `@Preview` function, which is usually private: [fileClass] is its file's class
     * (`dev.locus.feature.x.FooScreenKt`) and [function] its name. The preview supplies its own theme
     * and fake state; [snap] still applies the width, theme and font scale of each variant.
     */
    fun snapPreview(name: String, fileClass: String, function: String, heightDp: Int = PHONE_HEIGHT_DP, variants: List<Variant> = Variant.entries) {
        val method = Class.forName(fileClass).getDeclaredMethod(function, Composer::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        snap(name, heightDp, variants) { method.invoke(null, currentComposer, 0) }
    }

    /** A Robolectric density qualifier: `mdpi` (1x) for CI and the render guard's baseline, higher for a human review render. */
    @OptIn(ExperimentalRoborazziApi::class)
    private class DensityOption(private val qualifier: String) : RoborazziComposeSetupOption {
        override fun configure(configBuilder: RoborazziComposeSetupOption.ConfigBuilder) {
            configBuilder.addRobolectricQualifier(qualifier)
        }
    }

    private companion object {
        const val PHONE_HEIGHT_DP = 800

        val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        /**
         * `locus.screenshots.dir` sends a review render elsewhere. Otherwise renders go to `build/`, which
         * git ignores: screenshots are never committed.
         */
        val outputDir: File get() = File(System.getProperty("locus.screenshots.dir")?.takeIf { it.isNotBlank() } ?: "build/screenshots")

        val reviewRender: Boolean get() = !System.getProperty("locus.screenshots.dir").isNullOrBlank()

        val density: String get() = System.getProperty("locus.screenshots.density")?.takeIf { it.isNotBlank() } ?: "mdpi"
    }
}
