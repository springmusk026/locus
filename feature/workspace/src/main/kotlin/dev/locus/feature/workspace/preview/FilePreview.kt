package dev.locus.feature.workspace.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
    when {
        file.kind == PreviewKind.Pdf -> PdfViewer(file.file, modifier) { onDetail(if (it == 1) "1 page" else "$it pages") }
        file.kind == PreviewKind.Image -> ImageViewer(file.file, modifier, onDetail)
        file.kind == PreviewKind.Video -> VideoPlayer(file.file, modifier)
        file.kind == PreviewKind.Audio -> AudioPlayer(file.file, modifier)
        text == null -> FileInfoCard(file, onExport, modifier)
        showSource || file.kind == PreviewKind.Code || file.kind == PreviewKind.Text || file.kind == PreviewKind.Json ->
            CodeViewer(text, syntaxFor(file.name), modifier, notices)
        file.kind == PreviewKind.Markdown -> MarkdownViewer(text, modifier, notices)
        file.kind == PreviewKind.Web -> Column(modifier.fillMaxSize()) {
            if (notices.isNotEmpty()) NoticeBanner(notices)
            Box(Modifier.weight(1f)) { WebPreview(text, isSvg = extensionOf(file.name) == "svg") }
        }
        file.kind == PreviewKind.Table -> TableViewer(text, if (extensionOf(file.name) == "tsv") '\t' else ',', modifier, notices)
        else -> CodeViewer(text, syntaxFor(file.name), modifier, notices)
    }
}

internal fun textNotices(file: OpenFile): List<String> = buildList {
    if (file.truncated) add("Showing the first ${"%,d".format(FileLoader.MAX_TEXT_BYTES / 1000)} KB of a larger file.")
    if (file.prettyPrinted) add("Re-indented for reading; the file is unchanged.")
}

/** The top bar's fact for text files: language and line count. */
internal fun textDetail(file: OpenFile): String? {
    val text = file.text ?: return null
    if (file.kind != PreviewKind.Code && file.kind != PreviewKind.Text && file.kind != PreviewKind.Json) return null
    val lines = text.count { it == '\n' } + if (text.endsWith("\n") || text.isEmpty()) 0 else 1
    return "${languageLabel(file.name)} · ${"%,d".format(lines)} ${if (lines == 1) "line" else "lines"}${if (file.truncated) "+" else ""}"
}
