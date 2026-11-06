package dev.locus.feature.chat

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import dev.locus.core.designsystem.FileChip
import dev.locus.core.designsystem.GridImage
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.ImageGallery
import dev.locus.core.designsystem.ImageGrid
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.model.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the user sent with a message, above its bubble at the end of the row: the pictures as one
 * compact [ImageGrid] (a tap opens them full screen, swipeable), then any other files as [FileChip]s.
 */
@Composable
internal fun SentAttachments(attachments: List<Part.Attachment>, modifier: Modifier = Modifier) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.isImage() }
    val files = attachments.filterNot { it.isImage() }
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (images.isNotEmpty()) {
            val grid = images.mapIndexed { i, a ->
                key(a.path) {
                    val thumb = rememberAttachmentThumbnail(a)
                    GridImage(thumb.bitmap, missing = thumb.missing, label = photoLabel(i, images.size, a.name))
                }
            }
            ImageGrid(grid, onOpen = { open = it })
        }
        if (files.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                files.forEach { FileChip(it.name, Formatter.formatShortFileSize(context, it.sizeBytes)) }
            }
        }
    }
    open?.let { start ->
        ImageGallery(images.size, start, label = { photoLabel(it, images.size, images[it].name) }, onDismiss = { open = null }) { i ->
            FullImage(images[i])
        }
    }
}

/** "Photo 1 of 2, IMG_1400.jpg": what TalkBack says for a picture in a message. */
internal fun photoLabel(index: Int, count: Int, name: String): String =
    if (count == 1) "Photo, $name" else "Photo ${index + 1} of $count, $name"

private fun Part.Attachment.isImage() = mimeType.startsWith("image/")

/** A thumbnail being decoded: the bitmap once ready, and whether the file turned out to be missing or unreadable. */
internal class Thumbnail(val bitmap: ImageBitmap?, val missing: Boolean)

/** [rememberThumbnail] that also says when there's nothing to show (the file was removed), so the tile can say so. */
@Composable
internal fun rememberAttachmentThumbnail(a: Part.Attachment): Thumbnail {
    if (LocalInspectionMode.current) return remember(a.path) { decodeThumbnail(a).let { Thumbnail(it, missing = it == null) } }
    val state by produceState<Thumbnail>(Thumbnail(null, missing = false), a.path) {
        val bitmap = withContext(Dispatchers.IO) { decodeThumbnail(a) }
        value = Thumbnail(bitmap, missing = bitmap == null)
    }
    return state
}

/** A picture full screen, decoded at up to [FULL_EDGE] px on its long edge. */
@Composable
private fun FullImage(a: Part.Attachment) {
    val decoded by produceState<Thumbnail>(Thumbnail(null, missing = false), a.path) {
        val bitmap = withContext(Dispatchers.IO) { decodeFull(a.path) }
        value = Thumbnail(bitmap, missing = bitmap == null)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bitmap = decoded.bitmap
        when {
            bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            decoded.missing -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(LocusIcons.Image, contentDescription = null, modifier = Modifier.size(IconSize.large))
                SupportingText("This picture isn't on the phone any more.")
            }
            else -> InlineSpinner()
        }
    }
}

private const val FULL_EDGE = 2048

private fun decodeFull(path: String): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > FULL_EDGE) sample *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
