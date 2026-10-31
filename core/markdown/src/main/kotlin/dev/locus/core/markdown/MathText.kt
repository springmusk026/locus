package dev.locus.core.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.DefaultMarkdownInlineContent
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/** Markdown with its inline maths ready for the renderer: pass all three to `Markdown(...)`. */
@Immutable
class MathText(
    val markdown: String,
    val annotator: MarkdownAnnotator,
    val inlineContent: MarkdownInlineContent,
)

/**
 * Prepares [text] for the Markdown renderer with typeset maths (see [prepareMath]). Inline formulas
 * are measured here, in composition, at [style]'s size and in [color]; a formula that can't be
 * measured shows as its source text. Copying a selection gives the LaTeX source back.
 */
@Composable
fun rememberMathText(text: String, style: TextStyle, color: Color): MathText {
    val prepared = remember(text) { prepareMath(text) }
    // Most replies have no maths: no measurer, no fonts loaded, the stock annotator.
    if (prepared.formulas.isEmpty()) {
        return remember(prepared) { MathText(prepared.markdown, markdownAnnotator(), DefaultMarkdownInlineContent(emptyMap())) }
    }
    val config = remember(style.fontSize, color) { latexConfig(style.fontSize, color) }
    val measurer = rememberLatexMeasurer(config)
    val contents: Map<String, InlineTextContent> = remember(prepared, config, measurer) {
        buildMap {
            prepared.formulas.forEachIndexed { index, formula ->
                val content = runCatching { measurer.inlineContent(formula.latex, config) }.getOrNull()
                if (content != null) put(inlineTag(index), content)
            }
        }
    }
    val inlineContent = remember(contents) { DefaultMarkdownInlineContent(contents) }
    val annotator = remember(prepared, contents) { mathAnnotator(prepared, contents.keys) }
    return remember(prepared, annotator, inlineContent) { MathText(prepared.markdown, annotator, inlineContent) }
}

/**
 * Replaces placeholder characters in text tokens with their formula: the measured inline content if
 * there is one, else the formula's source.
 */
internal fun mathAnnotator(prepared: PreparedMarkdown, typeset: Set<String>): MarkdownAnnotator {
    if (prepared.formulas.isEmpty()) return markdownAnnotator()
    return markdownAnnotator { content, child ->
        if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
        val raw = child.getTextInNode(content)
        if (raw.none { prepared.formulaAt(it) != null }) return@markdownAnnotator false
        raw.forEach { c ->
            val formula = prepared.formulaAt(c)
            if (formula == null) {
                append(c)
            } else {
                val tag = inlineTag(c.code - PLACEHOLDER_BASE)
                if (tag in typeset) appendInlineContent(tag, formula.source) else append(formula.source)
            }
        }
        true
    }
}

internal fun inlineTag(index: Int) = "locus-math-$index"

private fun latexConfig(fontSize: TextUnit, color: Color): LatexConfig {
    val size = if (fontSize.isSpecified) fontSize else 16.sp
    val theme = if (color.luminance() > 0.5f) LatexTheme.dark(color, Color.Transparent) else LatexTheme.light(color, Color.Transparent)
    return LatexConfig(fontSize = size, theme = theme)
}

/**
 * A display formula: centred, scrolling sideways when wider than the screen. A formula the parser
 * reports errors for shows [fallback] (its source) instead.
 */
@Composable
fun MathBlock(latex: String, style: TextStyle, color: Color, fallback: @Composable () -> Unit) {
    val ok = remember(latex) { runCatching { LatexParser().parseWithDiagnostics(latex).errors.isEmpty() }.getOrDefault(false) }
    if (!ok || latex.isBlank()) {
        fallback()
        return
    }
    val size = if (style.fontSize.isSpecified) style.fontSize * 1.1f else 18.sp
    val config = remember(size, color) { latexConfig(size, color) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val width = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = width), contentAlignment = Alignment.Center) {
                Latex(latex = latex, config = config, isDarkTheme = color.luminance() > 0.5f)
            }
        }
    }
}
