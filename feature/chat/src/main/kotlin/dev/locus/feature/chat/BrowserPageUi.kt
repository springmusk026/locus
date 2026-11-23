package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.locus.core.browser.PageRecord
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.GridImage
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.ImageGallery
import dev.locus.core.designsystem.ImageGrid
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.relativeTime
import dev.locus.core.model.Envelopes
import dev.locus.core.model.Part
import java.io.File

/** What "See the page" found for a past browser step (docs/design/browser.md, "The page as it was"). */
sealed interface PageView {
    /** Locus kept the page at that call: its snapshot text, and a picture when the browser was on screen. */
    data class Kept(val record: PageRecord, val picture: File?) : PageView

    /**
     * Nothing kept (an older chat, a restored backup, a fork, or a call past the store's limits): the page's
     * [title] and [url] as the step's own result gave them, when it did.
     */
    data class NotKept(val title: String?, val url: String?) : PageView

    /** The record couldn't be read. */
    data object Failed : PageView
}

/** Loads a past browser step's page for this chat; null where there's no chat (previews, the home screen). */
internal val LocalBrowserPages = staticCompositionLocalOf<(suspend (callId: String, result: String?) -> PageView)?> { null }

/** The element the user picked in the browser, under their message (sent or queued): the crosshair and what it is. */
@Composable
internal fun PickedElementChip(label: String, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.chat_picked_element, label)
    LocusChip(
        label,
        modifier.widthIn(max = Sizes.readable).semantics { contentDescription = spoken },
        icon = LocusIcons.Target,
    )
}

/** "See the page" under a browser step that ran: opens [PageAtStepSheet]. Only where the chat can load pages. */
@Composable
internal fun SeePageAction(callId: String, result: Part.ToolResult, modifier: Modifier = Modifier) {
    val load = LocalBrowserPages.current ?: return
    var open by rememberSaveable(callId) { mutableStateOf(false) }
    LocusChip(
        stringResource(R.string.chat_page_see),
        modifier,
        icon = LocusIcons.Globe,
        onClick = { open = true },
    )
    if (open) PageAtStepSheet(load = { load(callId, result.content) }, onDismiss = { open = false })
}

/**
 * The page as it was at one browser step: its title and address, the picture Locus kept when the browser
 * was on screen, and the page as the agent read it. Loading, not kept and couldn't-read states included.
 */
@Composable
internal fun PageAtStepSheet(load: suspend () -> PageView, onDismiss: () -> Unit, initial: PageView? = null) {
    var attempt by remember { mutableIntStateOf(0) }
    val view by produceState<PageView?>(initial, attempt) {
        if (initial != null && attempt == 0) return@produceState
        value = null
        value = load()
    }
    val current = view
    val subtitle = (current as? PageView.Kept)?.record?.url?.let(::hostOf)
        ?: (current as? PageView.NotKept)?.url?.let(::hostOf)
    LocusSheet(title = stringResource(R.string.chat_page_title), subtitle = subtitle?.takeIf { it.isNotBlank() }, onDismiss = onDismiss) {
        when (current) {
            null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                InlineSpinner(contentDescription = stringResource(R.string.chat_page_loading))
            }
            is PageView.Kept -> KeptPage(current)
            is PageView.NotKept -> NotKeptPage(current)
            PageView.Failed -> Banner(
                stringResource(R.string.chat_page_failed),
                Tone.Critical,
                Modifier.fillMaxWidth(),
            ) { TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.chat_page_retry)) } }
        }
    }
}

@Composable
private fun KeptPage(page: PageView.Kept) {
    val record = page.record
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        PageHeading(record.title, record.url, relativeTime(record.at))
        val picture = page.picture
        if (picture != null) {
            PagePicture(picture, record)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LocusIcons.Image, contentDescription = null, modifier = Modifier.size(IconSize.small), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                SupportingText(stringResource(R.string.chat_page_no_picture), Modifier.padding(start = Spacing.sm))
            }
        }
        SupportingText(stringResource(R.string.chat_page_text_label), style = LocusType.chip)
        val text = Envelopes.strip(record.text)
        CodeSurface(Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    if (text.length > SHOWN_CHARS) text.take(SHOWN_CHARS) + "\n…" else text,
                    Modifier.padding(Spacing.sm),
                    style = LocusType.code,
                )
            }
        }
        SupportingText(stringResource(R.string.chat_page_private), style = LocusType.chip)
    }
}

@Composable
private fun NotKeptPage(page: PageView.NotKept) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (page.url != null) PageHeading(page.title.orEmpty(), page.url, null)
        Banner(stringResource(R.string.chat_page_not_kept_message), Tone.Neutral, Modifier.fillMaxWidth(), title = stringResource(R.string.chat_page_not_kept_title))
    }
}

@Composable
private fun PageHeading(title: String, url: String, at: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (title.isNotBlank()) Text(title, style = LocusType.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        SupportingText(url, style = LocusType.mono, maxLines = 3)
        if (at != null) SupportingText(stringResource(R.string.chat_page_when, at), style = LocusType.chip)
    }
}

/** The kept picture, tap to open it full screen (pinch to zoom). */
@Composable
private fun PagePicture(file: File, record: PageRecord) {
    val name = stringResource(R.string.chat_page_picture)
    val attachment = remember(file) {
        Part.Attachment(name, "image/jpeg", file.absolutePath, "", file.length(), record.pictureWidth.takeIf { it > 0 }, record.pictureHeight.takeIf { it > 0 })
    }
    val resources = LocalResources.current
    var gallery by rememberSaveable { mutableStateOf(false) }
    val thumb = rememberAttachmentThumbnail(attachment)
    ImageGrid(listOf(GridImage(thumb.bitmap, missing = thumb.missing, label = name)), onOpen = { gallery = true })
    if (gallery) {
        ImageGallery(1, 0, label = { resources.getString(R.string.chat_page_picture) }, onDismiss = { gallery = false }) { FullImage(attachment) }
    }
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty()

/** The page text shown in the sheet; the rest stays in the record. */
private const val SHOWN_CHARS = 12_000
