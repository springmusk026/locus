package dev.locus.core.screenshot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeActivityScenarioOption
import com.github.takahirom.roborazzi.RoborazziComposeCaptureOption
import dev.locus.core.designsystem.RowTitle
import java.io.File

/**
 * Checks what a screenshot actually shows (docs/design/design-system.md, "Review gate"), on the rendered
 * pixels and the semantics tree, right after Roborazzi captures it:
 *
 * - `text-contrast`: every piece of text, in the colour it was drawn in, against the colour most of its
 *   box is painted, reaches WCAG AA (4.5:1, or 3:1 for large text). Disabled text is exempt. This is
 *   what catches text that fell back to black outside a Surface, on the dark theme.
 * - `raw-identifier`: no text shows a hash, a UUID, a tool-call id or a `gguf:`/`sha256:` key.
 * - `cut-title`: at 200 % font, no row title (marked [RowTitle]) that may wrap onto a few lines ends in "…":
 *   a cap that fits at 100 % has to give way at a large font. A one-line title (a file name) is exempt.
 *
 * Findings are counted per screenshot and variant and held to `src/test/render-guard-baseline.txt`, the
 * same ratchet as the source guard: a count may go down, never up, and a screenshot that isn't listed
 * must be clean. `LOCUS_RENDER_GUARD_UPDATE=1` rewrites a screenshot's lines when it is recorded. Every
 * finding, with its text, colours and ratio, goes to `build/reports/render-guard/findings.txt`.
 */
@OptIn(ExperimentalRoborazziApi::class)
internal class RenderGuardOption(private val shot: String) : RoborazziComposeActivityScenarioOption, RoborazziComposeCaptureOption {

    private var scenario: ActivityScenario<out Activity>? = null
    var findings: List<RenderGuard.Finding> = emptyList()
        private set

    /** Whether Roborazzi drew anything: it captures only when recording or verifying. */
    var ran = false
        private set

    override fun configureWithActivityScenario(scenario: ActivityScenario<out Activity>) {
        this.scenario = scenario
    }

    override fun beforeCapture() = Unit

    override fun afterCapture() {
        ran = true
        scenario?.onActivity { activity ->
            // Every window: a sheet a step opened is a window of its own.
            val roots = (listOf(activity.window.decorView) + windows()).distinct().filter { it.width > 1 && it.height > 1 }
            findings = roots.flatMap { root ->
                val compose = findComposeRoot(root) ?: return@flatMap emptyList()
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                RenderGuard.inspect(shot, compose.semanticsOwner.unmergedRootSemanticsNode, bitmap, compose.view)
            }.distinct()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>).filter { it.isAttachedToWindow }
    }.getOrDefault(emptyList())

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findComposeRoot(view.getChildAt(i))?.let { return it }
        return null
    }
}

object RenderGuard {

    data class Finding(val rule: String, val shot: String, val detail: String)

    const val AA = 4.5
    const val AA_LARGE = 3.0

    /** Hashes, UUIDs, provider call ids and file keys: things a person can't read. */
    private val rawId = Regex(
        """\b[0-9a-fA-F]{16,}\b""" +
            """|\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""" +
            """|\b(call|toolu|chatcmpl|msg|run|req)_[A-Za-z0-9]{8,}\b""" +
            """|\b(gguf|sha256|sha1|md5):""",
    )

    internal fun inspect(shot: String, root: SemanticsNode, bitmap: Bitmap, view: View): List<Finding> {
        val out = ArrayList<Finding>()
        val origin = IntArray(2).also { view.getLocationInWindow(it) }
        walk(root) { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: node.config.getOrNull(SemanticsProperties.EditableText)?.text
            if (text.isNullOrBlank()) return@walk
            rawId.find(text)?.let { out += Finding("raw-identifier", shot, "\"${text.take(80)}\" shows \"${it.value}\"") }
            val layout = layoutOf(node) ?: return@walk
            if (node.config.contains(RowTitle)) cut(layout)?.let { out += Finding("cut-title", shot, "row title \"${text.take(60)}\" is cut off after $it lines") }
            if (isDisabled(node)) return@walk
            val style = layout.layoutInput.style
            if (!style.color.isSpecified()) return@walk
            val box = node.boundsInRoot
            val left = (box.left + origin[0]).toInt().coerceIn(0, bitmap.width - 1)
            val top = (box.top + origin[1]).toInt().coerceIn(0, bitmap.height - 1)
            val right = (box.right + origin[0]).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (box.bottom + origin[1]).toInt().coerceIn(top + 1, bitmap.height)
            if (right - left < 2 || bottom - top < 2) return@walk
            val background = dominant(bitmap, left, top, right, bottom, style.color.copy(alpha = 1f)) ?: return@walk
            val ink = style.color.compositeOver(background)
            val ratio = contrast(ink, background)
            val large = style.fontSize.isSp && (style.fontSize.value >= 18f || (style.fontSize.value >= 14f && (style.fontWeight ?: FontWeight.Normal) >= FontWeight.Bold))
            val needed = if (large) AA_LARGE else AA
            if (ratio < needed) {
                out += Finding(
                    "text-contrast",
                    shot,
                    "\"${text.take(60)}\" ${hex(ink)} on ${hex(background)} is ${"%.2f".format(ratio)}:1 (needs $needed)",
                )
            }
        }
        return out
    }

    private fun Color.isSpecified() = this != Color.Unspecified

    /**
     * At a large font, a title allowed to wrap onto a few lines that still ends in "…": the line count its
     * caller picked for 100 % no longer holds it, while the subtitle under it wraps freely (a switch's title
     * cut to "Include workspace f…" above a three-line subtitle). One-line text is left alone: cutting a file
     * name or an id to one line is a choice. Returns the line count it was cut to, or null.
     */
    private fun cut(layout: TextLayoutResult): Int? {
        val input = layout.layoutInput
        if (input.density.fontScale < LARGE_FONT || input.maxLines !in 2 until Int.MAX_VALUE) return null
        val last = layout.lineCount - 1
        return if (last >= 0 && layout.isLineEllipsized(last)) layout.lineCount else null
    }

    /** From here up the 200 % variant counts as a large font (cut-title). */
    private const val LARGE_FONT = 1.5f

    private fun walk(node: SemanticsNode, visit: (SemanticsNode) -> Unit) {
        visit(node)
        node.children.forEach { walk(it, visit) }
    }

    private fun isDisabled(node: SemanticsNode): Boolean {
        var n: SemanticsNode? = node
        while (n != null) {
            if (n.config.contains(SemanticsProperties.Disabled)) return true
            n = n.parent
        }
        return false
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val results = ArrayList<TextLayoutResult>()
        return if (action(results)) results.firstOrNull() else null
    }

    /**
     * The colour most of the box is painted, leaving out the text's own colour (bold or large text can
     * cover more of its box than the background does): what the text sits on. Null when the box is all ink.
     */
    private fun dominant(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, ink: Color): Color? {
        val w = right - left
        val h = bottom - top
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        val counts = HashMap<Int, Int>()
        for (p in pixels) counts.merge(p or 0xFF000000.toInt(), 1, Int::plus)
        return counts.entries.sortedByDescending { it.value }.asSequence()
            .map { Color(it.key) }
            .firstOrNull { contrast(it, ink) >= INK_LIKE }
    }

    /** Below this, a pixel counts as the text's own colour (anti-aliased edges included). */
    private const val INK_LIKE = 1.25

    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    // ---- Baseline

    private val baselineFile = File("src/test/render-guard-baseline.txt")
    private val reportFile = File("build/reports/render-guard/findings.txt")

    private const val HEADER =
        "# Render guard baseline (docs/design/design-system.md, \"Review gate\"): findings a screenshot still has.\n" +
            "# rule<TAB>screenshot<TAB>count. A count may go down, never up; a screenshot not listed must be clean.\n" +
            "# Lower it when recording: LOCUS_RENDER_GUARD_UPDATE=1 ./gradlew <module>:testDebugUnitTest -Proborazzi.test.record=true\n"

    /** Records [findings] for [shot]; what's wrong when they go past the baseline, else null. */
    @Synchronized
    internal fun enforce(shot: String, findings: List<Finding>, reportOnly: Boolean = false): String? {
        if (findings.isNotEmpty()) {
            reportFile.parentFile.mkdirs()
            reportFile.appendText(findings.joinToString("") { "${it.rule}\t${it.shot}\t${it.detail}\n" })
        }
        if (reportOnly) return null
        val counts = findings.groupingBy { it.rule }.eachCount()
        val baseline = parse(if (baselineFile.exists()) baselineFile.readText() else "")
        if (System.getenv("LOCUS_RENDER_GUARD_UPDATE") == "1") {
            val kept = baseline.filterKeys { it.second != shot }
            val next = kept + counts.mapKeys { (rule, _) -> rule to shot }
            baselineFile.writeText(HEADER + next.entries.sortedWith(compareBy({ it.key.second }, { it.key.first }))
                .joinToString("") { "${it.key.first}\t${it.key.second}\t${it.value}\n" })
            return null
        }
        val over = counts.filter { (rule, n) -> n > (baseline[rule to shot] ?: 0) }
        if (over.isEmpty()) return null
        return (
                "Render guard: $shot got worse.\n" +
                    findings.filter { it.rule in over }.joinToString("\n") { "  ${it.rule}: ${it.detail}" } +
                    "\n\nText must be drawn in a theme text role on a Surface (LocusScreen, LocusSheet, LocusCard), " +
                    "so it reaches 4.5:1 in both themes; ids and hashes belong in a labelled details row, not in text; " +
                    "a row title keeps wrapping at a large font instead of being cut to its 100 % line count (rowTitleLines). " +
                    "Baseline: ${baselineFile.path}. See docs/design/design-system.md, \"Review gate\"."
            )
    }

    internal fun parse(text: String): Map<Pair<String, String>, Int> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val p = line.split('\t')
                if (p.size == 3) (p[0] to p[1]) to (p[2].toIntOrNull() ?: return@mapNotNull null) else null
            }.toMap()
}
