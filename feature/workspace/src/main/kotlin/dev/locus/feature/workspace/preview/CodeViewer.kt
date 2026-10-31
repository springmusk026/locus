package dev.locus.feature.workspace.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Spacing
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lines longer than this are cut for display (minified bundles would otherwise lay out one huge line). */
private const val MAX_LINE_CHARS = 2_000

/** Above this the regex highlighter is too slow on a phone, so the file shows plain. */
private const val MAX_HIGHLIGHT_CHARS = 150_000

/** The text as displayed: tabs expanded, overlong lines cut, and where each line starts. */
private class CodeModel(val text: String, val lineStarts: IntArray, val lineEnds: IntArray, val widestColumns: Int, val cutLines: Int) {
    val lineCount get() = lineStarts.size
}

private fun prepare(source: String): CodeModel {
    val out = StringBuilder(source.length + 64)
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    var widest = 0
    var cut = 0
    source.removePrefix("\uFEFF").lineSequence().forEach { raw ->
        var line = raw.removeSuffix("\r").replace("\t", "    ")
        if (line.length > MAX_LINE_CHARS) {
            line = line.substring(0, MAX_LINE_CHARS) + " …"
            cut++
        }
        if (starts.isNotEmpty()) out.append('\n')
        starts += out.length
        out.append(line)
        ends += out.length
        // Wide (CJK, emoji) characters take about two monospace cells.
        var columns = 0
        for (c in line) columns += if (c.code >= 0x2E80) 2 else 1
        if (columns > widest) widest = columns
    }
    // A trailing newline is not an extra empty line worth numbering.
    if (starts.size > 1 && starts.last() == ends.last() && source.endsWith("\n")) {
        starts.removeAt(starts.lastIndex); ends.removeAt(ends.lastIndex)
        out.setLength(out.length - 1)
    }
    return CodeModel(out.toString(), starts.toIntArray(), ends.toIntArray(), widest, cut)
}

/**
 * A read-only code view: line-number gutter that stays put, monospace lines that scroll sideways
 * together, and syntax colours from dev.snipme highlights (via multiplatform-markdown-renderer-code,
 * the same engine and Atom theme as chat code blocks). Lines are lazy, so large files stay smooth;
 * highlighting runs off the main thread and swaps in when ready.
 */
@Composable
internal fun CodeViewer(text: String, syntax: SyntaxLanguage?, modifier: Modifier = Modifier, notices: List<String> = emptyList()) {
    val model by produceState<CodeModel?>(null, text) { value = withContext(Dispatchers.Default) { prepare(text) } }
    val m = model ?: return
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val highlighted by produceState<AnnotatedString?>(null, m, syntax, isDark) {
        value = if (syntax == null || m.text.length > MAX_HIGHLIGHT_CHARS) null
        else withContext(Dispatchers.Default) { runCatching { highlightCode(m.text, syntax, SyntaxThemes.atom(darkMode = isDark)) }.getOrNull() }
    }

    val style = CodeTextStyle
    val measurer = rememberTextMeasurer()
    val charWidth = remember(style) { measurer.measure(AnnotatedString("M".repeat(64)), style).size.width / 64f }
    val density = LocalDensity.current
    val gutterDigits = m.lineCount.toString().length.coerceAtLeast(2)
    val gutterWidth = with(density) { (charWidth * gutterDigits).toDp() } + Spacing.md * 2
    val contentPad = with(density) { Spacing.md.toPx() }

    var viewport by remember { mutableIntStateOf(0) }
    var hOffset by remember(m) { mutableFloatStateOf(0f) }
    val maxOffset = (m.widestColumns * charWidth + contentPad * 2 - viewport).coerceAtLeast(0f)
    val hState = rememberScrollableState { delta ->
        val old = hOffset
        hOffset = (hOffset - delta).coerceIn(0f, maxOffset)
        old - hOffset
    }

    val allNotices = buildList {
        addAll(notices)
        if (m.cutLines > 0) add("${m.cutLines} long line(s) are cut at ${"%,d".format(MAX_LINE_CHARS)} characters here.")
        if (syntax != null && m.text.length > MAX_HIGHLIGHT_CHARS) add("Too large to colour; shown plain.")
    }
    val codeColor = MaterialTheme.colorScheme.onSurface
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val gutterBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)

    LazyColumn(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it.width - with(density) { gutterWidth.roundToPx() } }
            .scrollable(hState, Orientation.Horizontal),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        if (allNotices.isNotEmpty()) item(key = "notice") { NoticeBanner(allNotices) }
        items(m.lineCount, key = { it }) { i ->
            val start = m.lineStarts[i]
            val end = m.lineEnds[i]
            val line = highlighted?.takeIf { it.length == m.text.length }?.subSequence(start, end) ?: AnnotatedString(m.text.substring(start, end))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    (i + 1).toString(),
                    style = style,
                    color = gutterColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(gutterWidth).background(gutterBg).padding(horizontal = Spacing.md),
                )
                Box(Modifier.weight(1f).clipToBounds()) {
                    Text(
                        line,
                        style = style,
                        color = codeColor,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .offset { IntOffset(-hOffset.toInt(), 0) }
                            .padding(horizontal = Spacing.md),
                    )
                }
            }
        }
    }
}

/** A quiet strip above the content explaining what isn't shown. */
@Composable
internal fun NoticeBanner(lines: List<String>, modifier: Modifier = Modifier) {
    Text(
        lines.joinToString("\n"),
        style = MonoCaption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

/*
 * Adapted from buildHighlightedAnnotatedString in multiplatform-markdown-renderer-code 0.46.0
 * (MarkdownHighlightedCode.kt, Copyright Mike Penz, Apache License 2.0), as in feature/chat ChatCode.kt.
 * Changed: a fresh Highlights.Builder per call (the builder is mutable); spans outside the text are skipped.
 */
internal fun highlightCode(code: String, language: SyntaxLanguage, theme: SyntaxTheme): AnnotatedString {
    val highlights = Highlights.Builder().code(code).language(language).theme(theme).build().getHighlights()
    return buildAnnotatedString {
        append(code)
        highlights.forEach {
            val start = it.location.start
            val end = it.location.end
            if (start < 0 || end > code.length || start >= end) return@forEach
            val span = when (it) {
                is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(span, start, end)
        }
    }
}
