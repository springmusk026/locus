package dev.locus.core.designsystem

import java.io.File
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Every public composable in core/designsystem has a screenshot test: it is called somewhere in
 * [ComponentScreenshots] (directly, or through the preview that shows it). A new component without one
 * fails here, so it can't ship unseen.
 */
class ComponentCoverageTest {

    /** Composables that draw nothing of their own: theme and plumbing. */
    private val noPicture = setOf(
        "LocusTheme", "ProvideLocusFeedback", "ProvideSettingsLinks", "LocusFeedbackHost", "FloatingLayer",
        // Draw a SettingsLink only when a message names a page, and need a LocalNetworkGate from the app.
        "SettingsLinkIn", "SettingsActionFor", "LocalNetworkNote",
        // A full-screen dialog over the app's own pictures; a render can't draw a dialog's window.
        "ImageGallery",
    )

    @Test
    fun everyComponentHasAGolden() {
        val main = File("src/main/kotlin")
        val test = File("src/test/kotlin/dev/locus/core/designsystem/ComponentScreenshots.kt").readText()
        val shown = main.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
            .let { all -> previewBodies(all) + test }
        val components = main.walkTopDown().filter { it.extension == "kt" }
            .flatMap { publicComposables(it.readText()) }.toSortedSet() - noPicture
        val missing = components.filter { name -> !Regex("""\b$name\s*[({]""").containsMatchIn(shown) }
        assertTrue(
            missing.isEmpty(),
            "No screenshot shows: ${missing.joinToString()}. Add them to ComponentScreenshots (or a preview it renders) " +
                "and render it: ./gradlew :core:designsystem:recordRoborazziDebug",
        )
    }

    private fun publicComposables(text: String): List<String> =
        Regex("""@Composable\s*\n\s*fun ([A-Z]\w*)\(""").findAll(text).map { it.groupValues[1] }.toList()

    /** The bodies of the `…Preview()` functions, which ComponentScreenshots renders. */
    private fun previewBodies(text: String): String =
        Regex("""internal fun \w+Preview\(\)[\s\S]*?\n\}""").findAll(text).joinToString("\n") { it.value }
}
