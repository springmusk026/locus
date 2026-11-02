package dev.locus.feature.workspace.preview

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.markdown.FenceKind
import dev.locus.core.markdown.MathBlock
import dev.locus.core.markdown.MermaidDiagram
import dev.locus.core.markdown.fenceKind
import dev.locus.core.markdown.rememberMathText
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Above this, Markdown opens as source: the renderer lays out the whole document at once. */
internal const val MAX_RENDERED_MARKDOWN = 300_000

/**
 * Rendered Markdown with the chat's typography (chat-sized headings, monospace code), highlighted code
 * fences, typeset maths and Mermaid diagrams, as in chat.
 */
@Composable
internal fun MarkdownViewer(text: String, modifier: Modifier = Modifier, notices: List<String> = emptyList()) {
    val math = rememberMathText(text, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onBackground)
    val state = rememberMarkdownState(math.markdown, retainState = true)
    val components = remember {
        markdownComponents(
            codeFence = { m ->
                val paragraph = m.typography.paragraph
                MarkdownCodeFence(m.content, m.node, m.typography.code) { code, language, style ->
                    val codeCard = @Composable { FenceCard(code, language, style) }
                    when (fenceKind(language)) {
                        FenceKind.Mermaid -> MermaidDiagram(code, closed = true, codeCard = codeCard)
                        FenceKind.Math -> MathBlock(code, paragraph, LocalMarkdownColors.current.text, codeCard)
                        FenceKind.Code -> codeCard()
                    }
                }
            },
            codeBlock = { m -> MarkdownCodeBlock(m.content, m.node, m.typography.code) { code, language, style -> FenceCard(code, language, style) } },
            checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
        )
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (notices.isNotEmpty()) NoticeBanner(notices)
        SelectionContainer {
            Markdown(
                state,
                typography = markdownTypography(
                    h1 = MaterialTheme.typography.titleLarge,
                    h2 = MaterialTheme.typography.titleMedium,
                    h3 = MaterialTheme.typography.titleSmall,
                    h4 = MaterialTheme.typography.titleSmall,
                    h5 = MaterialTheme.typography.labelLarge,
                    h6 = MaterialTheme.typography.labelLarge,
                    text = MaterialTheme.typography.bodyLarge,
                    paragraph = MaterialTheme.typography.bodyLarge,
                    code = CodeTextStyle,
                    inlineCode = CodeTextStyle,
                ),
                components = components,
                annotator = math.annotator,
                inlineContent = math.inlineContent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }
}

@Composable
private fun FenceCard(code: String, language: String?, style: TextStyle) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val syntax = remember(language) { language?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { syntaxFor("x.$it") ?: SyntaxLanguage.getByName(it) } }
    val highlighted by produceState(AnnotatedString(code), code, syntax, isDark) {
        if (syntax != null) value = withContext(Dispatchers.Default) { runCatching { highlightCode(code, syntax, SyntaxThemes.atom(darkMode = isDark)) }.getOrDefault(AnnotatedString(code)) }
    }
    Surface(
        shape = RoundedCornerShape(Radius.control),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        border = BorderStroke(0.5.dp, Locus.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
    ) {
        Column {
            if (!language.isNullOrBlank()) {
                Text(language.trim(), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm))
            }
            Text(
                highlighted,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Spacing.md),
            )
        }
    }
}

/**
 * HTML and SVG in a locked-down WebView: JavaScript off, network loads blocked, no file or content
 * URI access, and every sub-resource request answered with an empty response, so the page sees
 * nothing beyond its own text. Links don't navigate. The document is passed as data with an opaque
 * base URL, never as a file:// URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebPreview(text: String, isSvg: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    blockNetworkLoads = true
                    blockNetworkImage = true
                    domStorageEnabled = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = !isSvg
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    // A crashed or OOM-killed renderer must not take the app down; the view is left blank.
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.destroy()
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                val html = if (isSvg) {
                    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<style>html,body{margin:0;background:#fff}svg{max-width:100%;height:auto;display:block;margin:auto}</style></head><body>$text</body></html>"
                } else text
                loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}
