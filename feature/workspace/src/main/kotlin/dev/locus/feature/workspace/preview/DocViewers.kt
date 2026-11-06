package dev.locus.feature.workspace.preview

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.markdown.FenceKind
import dev.locus.core.markdown.MathBlock
import dev.locus.core.markdown.artifact.ArtifactSandbox
import dev.locus.core.markdown.MermaidDiagram
import dev.locus.core.markdown.documentTypography
import dev.locus.core.markdown.fenceKind
import dev.locus.core.markdown.rememberMathText
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Above this, Markdown opens as source: the renderer lays out the whole document at once. */
internal const val MAX_RENDERED_MARKDOWN = 300_000

/**
 * Rendered Markdown with the chat's typography (chat-sized headings, monospace code), highlighted code
 * fences, typeset maths and Mermaid diagrams, as in chat.
 */
@Composable
internal fun MarkdownViewer(text: String, modifier: Modifier = Modifier, notices: List<String> = emptyList()) {
    val math = rememberMathText(text, LocusType.body, MaterialTheme.colorScheme.onBackground)
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
                        // A Markdown file's artifact fences stay source here; artifacts run in chat.
                        FenceKind.Artifact, FenceKind.Code -> codeCard()
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
                typography = documentTypography(),
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
    val isDark = Locus.isDark
    val syntax = remember(language) { language?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { syntaxFor("x.$it") ?: SyntaxLanguage.getByName(it) } }
    val highlighted by produceState(AnnotatedString(code), code, syntax, isDark) {
        if (syntax != null) value = withContext(Dispatchers.Default) { runCatching { highlightCode(code, syntax, SyntaxThemes.atom(darkMode = isDark)) }.getOrDefault(AnnotatedString(code)) }
    }
    CodeSurface(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Column {
            if (!language.isNullOrBlank()) {
                SupportingText(language.trim(), style = LocusType.mono, modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm))
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
 *
 * The page sits on the background a browser gives it ([ArtifactSandbox.canvasColor]), never on the app's dark
 * surface: white unless it declares dark support and the phone is dark.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebPreview(text: String, isSvg: Boolean, modifier: Modifier = Modifier) {
    val background = ArtifactSandbox.canvasColor(if (isSvg) "light" else ArtifactSandbox.pageColorScheme(text), Locus.isDark)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(background)
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

                    // The page itself arrives as a data: (or about:) load; answering it with a 403 made the whole
                    // preview "Webpage not available". Local schemes pass, everything else stays blocked.
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (previewAllows(request.url.scheme)) null
                        else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
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

/** What the offline HTML/SVG preview may load: only the document it was handed and inline data, never the network. */
internal fun previewAllows(scheme: String?): Boolean = scheme == "data" || scheme == "about"
