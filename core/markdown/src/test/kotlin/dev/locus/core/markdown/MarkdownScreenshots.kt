package dev.locus.core.markdown

import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.markdown.artifact.Artifact
import dev.locus.core.markdown.artifact.ArtifactCard
import dev.locus.core.markdown.artifact.ArtifactKind
import dev.locus.core.markdown.artifact.MAX_ARTIFACT_BYTES
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Step
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentHashMap

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

    /** Where the screen can publish (docs/design/publish.md), the card's row gains Publish as a web page. */
    @Test
    fun artifactPublish() = snap("artifact-card-publish", heightDp = 500) {
        val rows = (1..70_000).joinToString("") { "<tr><td>Order $it</td><td>€${it % 97}.50</td></tr>" }
        val big = Artifact(ArtifactKind.Html, "<table>$rows</table>", title = "Orders 2025 (full export)")
        androidx.compose.runtime.CompositionLocalProvider(
            dev.locus.core.markdown.artifact.LocalArtifactHost provides dev.locus.core.markdown.artifact.ArtifactHost(saveToWorkspace = { n, _ -> n }, publish = {}),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.padding(Spacing.lg)) { ArtifactCard(big) } }
        }
    }

    /**
     * The laid-out heights of the formulas the parser typesets off the main thread (the library parses on
     * Dispatchers.Default), and [emptyHeight], a formula box's height before its formula has landed: its padding.
     */
    private class Formulas(val emptyHeight: Int) {
        val heights = ConcurrentHashMap<Int, Int>()
        val typeset get() = heights.size == 2 && heights.values.all { it > emptyHeight }
    }

    /** The variant being drawn: each one composes the screen afresh. */
    @Volatile
    private var formulas: Formulas? = null

    @Test
    fun math() = snap("math-blocks", heightDp = 600, steps = listOf(Step.Act(::awaitFormulas))) {
        val empty = with(LocalDensity.current) { (Spacing.sm * 2).roundToPx() }
        val drawn = remember(empty) { Formulas(empty) }
        SideEffect { formulas = drawn }
        fun Modifier.typeset(i: Int) = onSizeChanged { drawn.heights[i] = it.height }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val color = MaterialTheme.colorScheme.onSurface
            Text("Compound interest after n years:", style = LocusType.body, color = color)
            Box(Modifier.typeset(0)) { MathBlock("A = P\\left(1 + \\frac{r}{n}\\right)^{nt}", LocusType.body, color) { Text("fallback", color = color) } }
            Text("Sample variance:", style = LocusType.body, color = color)
            Box(Modifier.typeset(1)) { MathBlock("s^2 = \\frac{1}{n-1}\\sum_{i=1}^{n}(x_i - \\bar{x})^2", LocusType.body, color) { Text("fallback", color = color) } }
            Text("A formula that doesn't parse falls back to its source:", style = LocusType.body, color = color)
            MathBlock("\\frac{a}{", LocusType.body, color) { Text("\\frac{a}{", style = LocusType.code, color = color) }
        } }
    }

    /**
     * Before the capture, on the main thread: lets the main looper run until both formulas have been laid
     * out with their content. Nothing signals the end of the library's background parse, so this checks
     * the laid-out result after each pass instead of sleeping for a guessed time; the 10 s only bound a
     * run where the formulas never land.
     */
    private fun awaitFormulas() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (formulas?.typeset != true) {
            check(System.nanoTime() < deadline) { "The formulas were never typeset: heights ${formulas?.heights}, empty ${formulas?.emptyHeight}" }
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
    }
}
