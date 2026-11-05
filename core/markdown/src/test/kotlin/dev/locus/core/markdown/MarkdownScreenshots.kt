package dev.locus.core.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.markdown.artifact.Artifact
import dev.locus.core.markdown.artifact.ArtifactCard
import dev.locus.core.markdown.artifact.ArtifactKind
import dev.locus.core.markdown.artifact.MAX_ARTIFACT_BYTES
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/**
 * Rich blocks in replies that draw without a WebView: an artifact too large to run, and maths. A runnable
 * artifact's card hosts its live WebView preview, which doesn't compose under Robolectric, and Mermaid
 * diagrams and their zoom dialog draw through a WebView and a separate window, so they aren't here.
 */
class MarkdownScreenshots : ScreenshotTest() {

    @Test
    fun artifactTooLarge() = snap("artifact-card-too-large", heightDp = 500) {
        val rows = (1..70_000).joinToString("") { "<tr><td>Order $it</td><td>€${it % 97}.50</td></tr>" }
        val big = Artifact(ArtifactKind.Html, "<table>$rows</table>", title = "Orders 2025 (full export)")
        check(big.bytes > MAX_ARTIFACT_BYTES)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.padding(Spacing.lg)) { ArtifactCard(big) } }
    }

    @Test
    fun math() = snap("math-blocks", heightDp = 600) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val color = MaterialTheme.colorScheme.onSurface
            Text("Compound interest after n years:", style = LocusType.body, color = color)
            MathBlock("A = P\\left(1 + \\frac{r}{n}\\right)^{nt}", LocusType.body, color) { Text("fallback", color = color) }
            Text("Sample variance:", style = LocusType.body, color = color)
            MathBlock("s^2 = \\frac{1}{n-1}\\sum_{i=1}^{n}(x_i - \\bar{x})^2", LocusType.body, color) { Text("fallback", color = color) }
            Text("A formula that doesn't parse falls back to its source:", style = LocusType.body, color = color)
            MathBlock("\\frac{a}{", LocusType.body, color) { Text("\\frac{a}{", style = LocusType.code, color = color) }
        } }
        // The formulas are laid out off the main thread; give them a few frames to land.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            repeat(4) {
                androidx.compose.runtime.withFrameNanos { }
                Thread.sleep(150)
            }
        }
    }
}
