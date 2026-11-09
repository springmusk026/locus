package dev.locus.feature.workspace.preview

import dev.locus.core.markdown.files.PreviewKind
import dev.locus.core.markdown.artifact.ArtifactSandbox
import dev.locus.core.markdown.files.extensionOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.feature.workspace.R

/** Whether [file] has a rendered view and a source view to switch between. */
internal fun hasSourceToggle(file: OpenFile): Boolean =
    file.text != null && (file.kind == PreviewKind.Markdown || file.kind == PreviewKind.Web || file.kind == PreviewKind.Table)

/** Markdown this large opens as source by default (see [MAX_RENDERED_MARKDOWN]). */
internal fun defaultShowsSource(file: OpenFile): Boolean =
    file.kind == PreviewKind.Markdown && (file.text?.length ?: 0) > MAX_RENDERED_MARKDOWN

/**
 * Picks the viewer for [file]. [showSource] switches Markdown, HTML/SVG and CSV to their text.
 * [onDetail] receives a short fact for the top bar (page count, image dimensions, language).
 */
@Composable
internal fun FilePreview(
    file: OpenFile,
    showSource: Boolean,
    onExport: () -> Unit,
    onDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = file.text
    val notices = textNotices(file)
    val resources = LocalResources.current
    when {
        file.kind == PreviewKind.Pdf -> PdfViewer(file.file, modifier) { onDetail(UiText.Plural(R.plurals.workspace_preview_pages, it).asString(resources)) }
        file.kind == PreviewKind.Image -> ImageViewer(file.file, modifier, onDetail)
        file.kind == PreviewKind.Video -> VideoPlayer(file.file, modifier)
        file.kind == PreviewKind.Audio -> AudioPlayer(file.file, modifier)
        text == null -> FileInfoCard(file, onExport, modifier)
        showSource || file.kind == PreviewKind.Code || file.kind == PreviewKind.Text || file.kind == PreviewKind.Json ->
            CodeViewer(text, syntaxFor(file.name), modifier, notices)
        file.kind == PreviewKind.Markdown -> MarkdownViewer(text, modifier, notices)
        file.kind == PreviewKind.Web -> Column(modifier.fillMaxSize()) {
            if (notices.isNotEmpty()) NoticeBanner(notices)
            webNotice(text)?.let { notice ->
                val context = LocalContext.current
                val feedback = Locus.feedback
                // Seeing it whole means the network: the user's call, in an app of their choosing.
                Banner(notice, Tone.Info, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                    TextButton(onClick = { openWith(context, file, feedback) }) { Text(stringResource(R.string.workspace_preview_open_with)) }
                }
            }
            Box(Modifier.weight(1f)) { WebPreview(text, isSvg = extensionOf(file.name) == "svg") }
        }
        file.kind == PreviewKind.Table -> TableViewer(text, if (extensionOf(file.name) == "tsv") '\t' else ',', modifier, notices)
        else -> CodeViewer(text, syntaxFor(file.name), modifier, notices)
    }
}

/**
 * For a page that loads styles, scripts or files from the web, which this viewer never fetches: what's
 * missing, in plain words. The banner showing it offers Open with… to see the page whole.
 */
internal fun webNotice(html: String): String? =
    ArtifactSandbox.blockedNotice(ArtifactSandbox.webResources(html))?.let { "$it, so the page may look plain." }

@Composable
internal fun textNotices(file: OpenFile): List<String> = buildList {
    if (file.truncated) add(stringResource(R.string.workspace_preview_truncated, "%,d".format(FileLoader.MAX_TEXT_BYTES / 1000)))
    if (file.prettyPrinted) add(stringResource(R.string.workspace_preview_pretty_printed))
}

/** The top bar's fact for text files: language and line count. */
@Composable
internal fun textDetail(file: OpenFile): String? {
    val text = file.text ?: return null
    if (file.kind != PreviewKind.Code && file.kind != PreviewKind.Text && file.kind != PreviewKind.Json) return null
    val lines = text.count { it == '\n' } + if (text.endsWith("\n") || text.isEmpty()) 0 else 1
    return pluralStringResource(R.plurals.workspace_preview_text_detail, lines, languageLabel(file.name), "%,d".format(lines), if (file.truncated) "+" else "")
}
