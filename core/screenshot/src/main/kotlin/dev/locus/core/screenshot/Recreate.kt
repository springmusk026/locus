package dev.locus.core.screenshot

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.designsystem.LocusTheme
import org.robolectric.Shadows.shadowOf

/**
 * What a screen shows, read from its semantics: every text, field and content description. Fields read
 * as their semantics give them, so a masked field reads as dots.
 */
class ScreenText internal constructor(private val nodes: List<SemanticsNode>) {
    /** Every text on screen: labels, content descriptions and what's in the fields. */
    val texts: List<String> = nodes.flatMap { it.labels() + listOfNotNull(it.config.getOrNull(SemanticsProperties.EditableText)?.text) }

    /** Whether something on screen says [text] (whole, or as part of a longer text). */
    fun shows(text: String): Boolean = texts.any { it == text || text in it }
}

/**
 * The activity a rotation test runs in: it shows [content] in the theme, as MainActivity shows a screen.
 * A rotation recreates it, and the new one shows the same [content] again, as the app does with its
 * view models (which outlive the activity); whatever the composables kept only in `remember` is lost,
 * and what they saved comes back from the Bundle.
 */
class RecreateHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocusTheme { content() } }
    }

    internal companion object {
        var content: @Composable () -> Unit = {}
    }
}

/**
 * Shows [content], takes [steps] on it (typing, taps), then recreates the activity the way a rotation
 * or a theme change does: the saved instance state is written to a Bundle and the new activity is
 * created from it. Returns the screen before and after, so a test can say a draft was kept.
 */
fun recreateAfter(steps: List<Step>, content: @Composable () -> Unit): Pair<ScreenText, ScreenText> = onScreen(steps, recreate = true, content)

/** Shows [content], takes [steps] on it and returns what the screen shows then: for a tap's outcome. */
fun screenAfter(steps: List<Step>, content: @Composable () -> Unit): ScreenText = onScreen(steps, recreate = false, content).first

private fun onScreen(steps: List<Step>, recreate: Boolean, content: @Composable () -> Unit): Pair<ScreenText, ScreenText> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    // As for screenshots: animations land at once, so a sheet's entrance doesn't keep the looper busy.
    Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    val info = android.content.pm.ActivityInfo().apply {
        name = RecreateHostActivity::class.java.name
        packageName = context.packageName
        theme = android.R.style.Theme_Material_Light_NoActionBar
    }
    shadowOf(context.packageManager).addOrUpdateActivity(info)
    RecreateHostActivity.content = content
    ActivityScenario.launch(RecreateHostActivity::class.java).use { scenario ->
        settleScreen()
        steps.forEach { step ->
            takeStep(step)
            settleScreen()
        }
        val before = ScreenText(screenNodes())
        if (!recreate) return before to before
        scenario.recreate()
        settleScreen()
        val after = ScreenText(screenNodes())
        return before to after
    }
}
