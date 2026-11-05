package dev.locus.core.screenshot

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeActivityScenarioCreatorOption
import com.github.takahirom.roborazzi.RoborazziComposeActivityScenarioOption
import com.github.takahirom.roborazzi.RoborazziComposeCaptureOption
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziComposeSetupOption
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import dev.locus.core.designsystem.Spacing
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import java.util.TimeZone

/**
 * Helpers for screenshots of states a composable keeps to itself: a sheet only a tap opens, or a
 * private sheet called directly.
 */

/** A step taken on the rendered screen before it's captured. */
sealed interface Step {
    /** Taps the [index]th clickable node whose text or content description is [label]. */
    data class Tap(val label: String, val index: Int = 0) : Step

    /** Types [text] into the [index]th editable field. */
    data class Type(val text: String, val index: Int = 0) : Step

    /** Runs [block] on the main thread: a change to the state the content reads (search results arriving). */
    class Act(val block: () -> Unit) : Step
}

@OptIn(ExperimentalRoborazziApi::class)
internal class StepsOption(private val steps: List<Step>) : RoborazziComposeActivityScenarioOption, RoborazziComposeCaptureOption {
    private var scenario: ActivityScenario<out Activity>? = null

    override fun configureWithActivityScenario(scenario: ActivityScenario<out Activity>) {
        this.scenario = scenario
        // Roborazzi captures the whole screen (every window) only when there's more than one window
        // before the capture starts; otherwise just the activity's view. A sheet a step opens is a window
        // of its own, so an empty 1×1 window makes sure the screen is what gets captured.
        if (steps.any { it !is Step.Type }) scenario.onActivity { activity ->
            val params = android.view.WindowManager.LayoutParams(
                1, 1, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            )
            activity.windowManager.addView(View(activity), params)
        }
    }

    override fun beforeCapture() {
        settle()
        for (step in steps) {
            // Every window's Compose root, the activity's first: a sheet is a window of its own.
            val roots = windows().mapNotNull(::composeRoot)
            check(roots.isNotEmpty()) { "No Compose root to take $step on" }
            val nodes = buildList { roots.forEach { collect(it.semanticsOwner.rootSemanticsNode, this) } }
            when (step) {
                is Step.Act -> step.block()
                is Step.Tap -> {
                    val matches = nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null && it.labels().any { l -> l == step.label } }
                        .ifEmpty { nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null && it.labels().any { l -> l.contains(step.label) } } }
                    val node = matches.getOrNull(step.index)
                        ?: error("Nothing clickable says \"${step.label}\"; found: ${nodes.flatMap { it.labels() }.distinct().take(60)}")
                    node.config[SemanticsActions.OnClick].action!!.invoke()
                }
                is Step.Type -> {
                    val node = nodes.filter { it.config.getOrNull(SemanticsActions.SetText) != null }.getOrNull(step.index)
                        ?: error("No text field #${step.index}")
                    node.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(step.text))
                }
            }
            settle()
        }
    }

    override fun afterCapture() = Unit

    private fun settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        return views.filter { it.isAttachedToWindow }
    }

    private fun SemanticsNode.labels(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun collect(node: SemanticsNode, out: MutableList<SemanticsNode>) {
        out += node
        node.children.forEach { collect(it, out) }
    }

    private fun composeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoot(view.getChildAt(i))?.let { return it }
        return null
    }
}

/** Launches [activity] for the capture instead of Roborazzi's activity, registering it with Robolectric first. */
@OptIn(ExperimentalRoborazziApi::class)
internal class ActivityOption(private val activity: Class<out ComponentActivity>) : RoborazziComposeActivityScenarioCreatorOption {
    override fun createScenario(chain: () -> ActivityScenario<out ComponentActivity>): ActivityScenario<out ComponentActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = android.content.pm.ActivityInfo().apply {
            name = activity.name
            packageName = context.packageName
            theme = android.R.style.Theme_Material_Light_NoActionBar
        }
        shadowOf(context.packageManager).addOrUpdateActivity(info)
        return ActivityScenario.launch(activity)
    }
}

/**
 * An activity Hilt accepts as a component holder, for routes that call `hiltViewModel()`. It knows no
 * view models: the test puts the one the route asks for in the store first, so Hilt's factory is made
 * but never used.
 */
class HiltHostActivity : ComponentActivity(), dagger.hilt.internal.GeneratedComponentManager<Any> {
    override fun generatedComponent(): Any {
        val entryPoint = Class.forName("dagger.hilt.android.internal.lifecycle.HiltViewModelFactory\$ActivityCreatorEntryPoint")
        return java.lang.reflect.Proxy.newProxyInstance(entryPoint.classLoader, arrayOf(dagger.hilt.internal.GeneratedComponent::class.java, entryPoint)) { _, method, _ ->
            when (method.name) {
                "getViewModelKeys" -> emptyMap<Class<*>, Boolean>()
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "HiltHostActivity component"
                else -> null
            }
        }
    }
}

/**
 * Calls a private composable: [fileClass] is its file's class (`dev.locus.feature.chat.ChangesCardKt`),
 * [args] every parameter in order, defaults included. The Compose compiler adds the composer and its
 * change and default masks after them; zero masks mean "every argument given".
 */
@Composable
fun PrivateComposable(fileClass: String, function: String, vararg args: Any?) {
    val method = Class.forName(fileClass).declaredMethods.single {
        it.name == function && it.parameterTypes.getOrNull(args.size) == Composer::class.java
    }
    method.isAccessible = true
    val masks = arrayOfNulls<Any>(method.parameterCount - args.size - 1).also { it.fill(0) }
    method.invoke(null, *args, currentComposer, *masks)
}

/** A component as it sits on a screen: on the screen's background, with its padding. */
@Composable
fun OnScreen(content: @Composable ColumnScope.() -> Unit) {
    // A Surface, as the screen's Scaffold is: it gives the text its colour.
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
    }
}

/**
 * Pins the default time zone to UTC, so clock times and dates a sheet formats from a fixed epoch read
 * the same on a laptop in Kathmandu and on CI.
 */
class UtcRule : TestWatcher() {
    private var saved: TimeZone? = null

    override fun starting(description: Description) {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    override fun finished(description: Description) {
        saved?.let(TimeZone::setDefault)
    }
}
