package dev.locus.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.rememberCopyFeedback
import dev.locus.core.markdown.FenceKind
import dev.locus.core.markdown.MathBlock
import dev.locus.core.markdown.MermaidDiagram
import dev.locus.core.markdown.fenceKind
import dev.locus.core.markdown.isFenceClosed
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Markdown components for chat: code fences and indented code render as [LocusCodeCard], except
 * mermaid fences (a diagram) and math fences (a display formula); the checkbox keeps the m3
 * module's default. [streaming] marks the live reply, whose last code block may still be growing.
 */
internal fun chatMarkdownComponents(streaming: Boolean): MarkdownComponents = markdownComponents(
    codeFence = { m ->
        // A saved reply's fences are complete even if the model never closed the last one.
        val closed = !streaming || isFenceClosed(m.node)
        val paragraph = m.typography.paragraph
        MarkdownCodeFence(m.content, m.node, m.typography.code) { code, language, style ->
            ChatFence(code, language, style, streaming, closed, paragraph)
        }
    },
    codeBlock = { m ->
        MarkdownCodeBlock(m.content, m.node, m.typography.code) { code, language, style -> LocusCodeCard(code, language, style, streaming) }
    },
    checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
)

internal val SavedChatComponents = chatMarkdownComponents(streaming = false)
internal val StreamingChatComponents = chatMarkdownComponents(streaming = true)

/**
 * A fenced block: a Mermaid diagram or a display formula once its fence has closed, else code. Until
 * then, and whenever drawing fails, the source shows as a normal code card.
 */
@Composable
private fun ChatFence(code: String, language: String?, style: TextStyle, streaming: Boolean, closed: Boolean, textStyle: TextStyle) {
    val codeCard = @Composable { LocusCodeCard(code, language, style, streaming) }
    when (fenceKind(language)) {
        FenceKind.Mermaid -> MermaidDiagram(code, closed, codeCard)
        FenceKind.Math -> if (closed) MathBlock(code, textStyle, LocalMarkdownColors.current.text, codeCard) else codeCard()
        FenceKind.Code -> codeCard()
    }
}

/** A code block: language and a copy button on top, highlighted code below that scrolls sideways. */
@Composable
internal fun LocusCodeCard(code: String, language: String?, style: TextStyle, streaming: Boolean = false) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val syntax = remember(language) { syntaxLanguageFor(language) }
    Surface(
        shape = RoundedCornerShape(Radius.control),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        border = BorderStroke(0.5.dp, Locus.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    languageDisplayName(language),
                    style = MonoCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CopyCodeButton(code)
            }
            // A theme switch re-highlights from scratch rather than briefly showing the old colours.
            key(isDark) {
                val text = rememberHighlighted(code, syntax, isDark, streaming)
                Text(
                    text,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                )
            }
        }
    }
}

@Composable
private fun CopyCodeButton(code: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Android 13+ confirms clipboard writes itself; older versions get our own short confirmation.
    val feedback = rememberCopyFeedback()
    if (feedback.shown) {
        Text("Copied", style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(Spacing.xs))
    }
    IconButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("Code", code)))
                feedback.copied()
            }
        },
        modifier = Modifier.minimumInteractiveComponentSize().size(32.dp),
    ) {
        Icon(
            if (feedback.shown) LocusIcons.Check else LocusIcons.Copy,
            contentDescription = "Copy code",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The highlighted code. Small finished blocks highlight during composition so a saved message never
 * flashes uncoloured; everything else highlights off the main thread. While a block streams, the
 * last result is kept for the part of the text it covers and new text shows plain until the next
 * pass. A long block that is still growing waits for appends to pause, so it isn't re-highlighted
 * on every tick; finished blocks don't change, so they get coloured once.
 */
@Composable
private fun rememberHighlighted(code: String, syntax: SyntaxLanguage?, isDark: Boolean, streaming: Boolean): AnnotatedString {
    if (syntax == null) return remember(code) { AnnotatedString(code) }
    val theme = remember(isDark) { SyntaxThemes.atom(darkMode = isDark) }
    if (!streaming && code.length <= IMMEDIATE_HIGHLIGHT_CHARS) {
        return remember(code, syntax, theme) { highlightCode(code, syntax, theme) }
    }
    val latest by produceState(AnnotatedString(""), code, syntax, theme) {
        if (streaming && code.length > STREAMING_HIGHLIGHT_CHARS) delay(STREAMING_SETTLE_MS)
        value = withContext(Dispatchers.Default) { highlightCode(code, syntax, theme) }
    }
    return remember(latest, code) {
        when {
            latest.text == code -> latest
            latest.text.isNotEmpty() && code.startsWith(latest.text) -> buildAnnotatedString {
                append(latest)
                append(code.substring(latest.length))
            }
            else -> AnnotatedString(code)
        }
    }
}

private const val IMMEDIATE_HIGHLIGHT_CHARS = 1_500
private const val STREAMING_HIGHLIGHT_CHARS = 4_000
private const val STREAMING_SETTLE_MS = 400L

/*
 * Adapted from buildHighlightedAnnotatedString in multiplatform-markdown-renderer-code 0.46.0
 * (MarkdownHighlightedCode.kt, Copyright Mike Penz, Apache License 2.0). Changed: a fresh
 * Highlights.Builder per call, because the builder is mutable and calls may run concurrently; spans
 * outside the text are skipped.
 */
internal fun highlightCode(code: String, language: SyntaxLanguage, theme: SyntaxTheme): AnnotatedString {
    val highlights = Highlights.Builder().code(code).language(language).theme(theme).build().getHighlights()
    return buildAnnotatedString {
        append(code)
        highlights.forEach {
            val start = it.location.start
            val end = it.location.end
            if (start < 0 || end > code.length || start >= end) return@forEach
            val style = when (it) {
                is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(style, start, end)
        }
    }
}

/** The fence's language word, lowercased: "```Kotlin title=x" gives "kotlin". */
private fun fenceWord(fence: String?): String? =
    fence?.trim()?.split(' ', '\t', '{', ',')?.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }

/**
 * The highlighter's language for a fence tag, or null to show the code plain: no tag, or a language
 * the highlighter doesn't know (JSON, YAML, HTML, SQL, diff…).
 */
internal fun syntaxLanguageFor(fence: String?): SyntaxLanguage? {
    val word = fenceWord(fence) ?: return null
    return when (word) {
        "js", "jsx", "mjs", "cjs", "node" -> SyntaxLanguage.JAVASCRIPT
        "ts", "tsx", "mts" -> SyntaxLanguage.TYPESCRIPT
        "py", "py3", "python3" -> SyntaxLanguage.PYTHON
        "sh", "bash", "zsh", "shell", "console", "shellsession" -> SyntaxLanguage.SHELL
        "kt", "kts" -> SyntaxLanguage.KOTLIN
        "c++", "cc", "cxx", "hpp", "hh" -> SyntaxLanguage.CPP
        "h" -> SyntaxLanguage.C
        "cs", "c#" -> SyntaxLanguage.CSHARP
        "rb" -> SyntaxLanguage.RUBY
        "golang" -> SyntaxLanguage.GO
        "rs" -> SyntaxLanguage.RUST
        "pl" -> SyntaxLanguage.PERL
        "coffee" -> SyntaxLanguage.COFFEESCRIPT
        else -> SyntaxLanguage.getByName(word)?.takeIf { it != SyntaxLanguage.DEFAULT }
    }
}

/** What the card's header calls the language: a proper name for known ones, else the tag as written. */
internal fun languageDisplayName(fence: String?): String {
    val word = fenceWord(fence) ?: return "Code"
    return when (syntaxLanguageFor(word)) {
        SyntaxLanguage.JAVASCRIPT -> "JavaScript"
        SyntaxLanguage.TYPESCRIPT -> "TypeScript"
        SyntaxLanguage.PYTHON -> "Python"
        SyntaxLanguage.SHELL -> if (word == "zsh" || word == "bash") word else "Shell"
        SyntaxLanguage.KOTLIN -> "Kotlin"
        SyntaxLanguage.CPP -> "C++"
        SyntaxLanguage.C -> "C"
        SyntaxLanguage.CSHARP -> "C#"
        SyntaxLanguage.RUBY -> "Ruby"
        SyntaxLanguage.GO -> "Go"
        SyntaxLanguage.RUST -> "Rust"
        SyntaxLanguage.PERL -> "Perl"
        SyntaxLanguage.COFFEESCRIPT -> "CoffeeScript"
        SyntaxLanguage.JAVA -> "Java"
        SyntaxLanguage.DART -> "Dart"
        SyntaxLanguage.SWIFT -> "Swift"
        SyntaxLanguage.PHP -> "PHP"
        else -> when (word) {
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "html" -> "HTML"
            "xml" -> "XML"
            "css" -> "CSS"
            "sql" -> "SQL"
            "toml" -> "TOML"
            "md", "markdown" -> "Markdown"
            "diff", "patch" -> "Diff"
            "text", "txt", "plaintext" -> "Text"
            "mermaid" -> "Mermaid"
            "math", "latex", "tex" -> "LaTeX"
            else -> fence!!.trim().split(' ', '\t', '{', ',').first()
        }
    }
}
