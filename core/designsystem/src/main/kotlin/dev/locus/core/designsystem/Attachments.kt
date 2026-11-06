package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One picture in an [ImageGrid]: its thumbnail once decoded, whether the file is gone, and what TalkBack says. */
data class GridImage(val bitmap: ImageBitmap?, val missing: Boolean, val label: String)

/**
 * Pictures sent together, as one compact block (the way messaging apps group an album): one keeps its
 * shape within a [ImageGridSize] square; two sit side by side; three are one large and two small; four
 * or more are a 2×2 grid whose last tile says how many more there are ("+2"). Tiles share one rounded
 * outline with a hairline edge. A tap opens [onOpen] with the tile's index (the "+N" tile opens the
 * fourth picture, and the viewer swipes on to the rest). Before a thumbnail is decoded the tile is a
 * quiet fill; a missing file shows the image icon.
 */
@Composable
fun ImageGrid(images: List<GridImage>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (images.isEmpty()) return
    val shape = RoundedCornerShape(Radius.control)
    val outline = modifier.clip(shape).border(Stroke.hairline, Locus.colors.hairline, shape)
    val gap = Spacing.xxs
    when (images.size) {
        1 -> {
            val bitmap = images[0].bitmap
            // Its own shape, clamped so a panorama or a tall screenshot doesn't run away.
            val ratio = bitmap?.let { (it.width.toFloat() / it.height).coerceIn(MIN_RATIO, MAX_RATIO) } ?: 1f
            val size = if (ratio >= 1f) Modifier.width(ImageGridSize).aspectRatio(ratio) else Modifier.height(ImageGridSize).aspectRatio(ratio)
            Tile(images[0], size.then(outline), onClick = { onOpen(0) })
        }
        2 -> Row(outline.width(ImageGridSize), horizontalArrangement = Arrangement.spacedBy(gap)) {
            images.forEachIndexed { i, image -> Tile(image, Modifier.weight(1f).aspectRatio(1f), onClick = { onOpen(i) }) }
        }
        3 -> Row(outline.width(ImageGridSize).height(ImageGridSize * 2 / 3), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tile(images[0], Modifier.weight(2f).fillMaxSize(), onClick = { onOpen(0) })
            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Tile(images[1], Modifier.weight(1f).fillMaxWidth(), onClick = { onOpen(1) })
                Tile(images[2], Modifier.weight(1f).fillMaxWidth(), onClick = { onOpen(2) })
            }
        }
        else -> Column(outline.width(ImageGridSize), verticalArrangement = Arrangement.spacedBy(gap)) {
            val more = images.size - 4
            for (row in 0..1) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0..1) {
                        val i = row * 2 + col
                        val last = i == 3 && more > 0
                        Tile(
                            images[i],
                            Modifier.weight(1f).aspectRatio(1f),
                            onClick = { onOpen(i) },
                            // The fourth tile also stands for the ones it hides.
                            more = if (last) more + 1 else 0,
                            label = if (last) "${images[i].label}, and ${more} more" else images[i].label,
                        )
                    }
                }
            }
        }
    }
}

/** The widest (and tallest) an [ImageGrid] gets: a third of a phone screen, so the message stays in view. */
val ImageGridSize: Dp = 240.dp

private const val MIN_RATIO = 0.6f
private const val MAX_RATIO = 1.8f

@Composable
private fun Tile(image: GridImage, modifier: Modifier, onClick: () -> Unit, more: Int = 0, label: String = image.label) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Image, onClickLabel = "Open", onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when {
            image.bitmap != null -> Image(image.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            image.missing -> Icon(LocusIcons.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.large))
        }
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = Emphasis.muted)), contentAlignment = Alignment.Center) {
                // On the scrim in both themes, so always light.
                Text("+$more", style = LocusType.stateTitle, color = Color.White) // design-exception: on a dark scrim over a photo in both themes
            }
        }
    }
}

/**
 * A file sent with a message that isn't a picture: its type icon, name and size in one compact chip,
 * not a card per file. TalkBack reads "name, size".
 */
@Composable
fun FileChip(name: String, detail: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.control)
    Row(
        modifier
            .clip(shape)
            .background(Locus.colors.card)
            .border(Stroke.hairline, Locus.colors.hairline, shape)
            .padding(start = Spacing.xs, end = Spacing.md, top = Spacing.xs, bottom = Spacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FileTypeIcon(name, isDirectory = false, size = IconSize.tileSmall)
        Column(Modifier.widthIn(max = FileChipNameWidth)) {
            Text(name, style = LocusType.chip, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (detail != null) SupportingText(detail, style = LocusType.mono, maxLines = 1)
        }
    }
}

private val FileChipNameWidth = 180.dp

/**
 * Pictures full screen: swipe between them, pinch or double-tap to zoom, and drag when zoomed. Black in
 * both themes, as photo viewers are. [page] draws picture [index] fitted to the screen; [label] names it
 * for the counter and TalkBack ("Photo 2 of 3").
 */
@Composable
fun ImageGallery(count: Int, initial: Int, label: (Int) -> String, onDismiss: () -> Unit, page: @Composable (index: Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Photo viewers are black in either theme.
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) { // design-exception: a photo viewer is black in both themes
            val pager = rememberPagerState(initialPage = initial.coerceIn(0, count - 1)) { count }
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                    Zoomable(Modifier.semantics { contentDescription = label(index) }) { page(index) }
                }
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(LocusIcons.Close, contentDescription = "Close") }
                    if (count > 1) Text("${pager.currentPage + 1} / $count", style = LocusType.label, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

/**
 * Pinch to zoom (up to 5×), double-tap to zoom in or back, drag when zoomed. One finger at 1× is left
 * alone, so a pager around it still swipes.
 */
@Composable
private fun Zoomable(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = w * (s - 1f) / 2f
            val maxY = h * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (scale > 1.05f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val s = 2.5f
                            offset = clamp((Offset(w / 2f, h / 2f) - at) * (s - 1f), s)
                            scale = s
                        }
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers > 1 || scale > 1f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val old = scale
                                val new = (old * zoom).coerceIn(1f, MAX_ZOOM)
                                val center = Offset(w / 2f, h / 2f)
                                val o = if (centroid == Offset.Unspecified) offset + pan else (offset + center - centroid) * (new / old) + centroid - center + pan
                                scale = new
                                offset = if (new <= 1f) Offset.Zero else clamp(o, new)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

private const val MAX_ZOOM = 5f

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun ImageGridPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            val pictures = listOf(
                previewPicture(160, 120, MaterialTheme.colorScheme.primary),
                previewPicture(120, 160, Locus.colors.caution),
                previewPicture(140, 140, Locus.colors.info),
                previewPicture(160, 110, Locus.colors.live),
                previewPicture(120, 120, Locus.colors.critical),
            )
            fun grid(n: Int) = pictures.take(n).mapIndexed { i, b -> GridImage(b, missing = false, label = "Photo ${i + 1} of $n") }
            Column(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                ImageGrid(grid(2), onOpen = {})
                ImageGrid(grid(3), onOpen = {})
                ImageGrid(grid(5), onOpen = {})
                ImageGrid(listOf(GridImage(null, missing = true, label = "Photo 1 of 2"), GridImage(null, missing = false, label = "Photo 2 of 2")), onOpen = {})
                FileChip("invoice-garden-centre-march.pdf", "182 kB")
            }
        }
    }
}

/** A flat picture for the preview: [colour] with a lighter oval, as a stand-in photo. */
private fun previewPicture(w: Int, h: Int, colour: Color): ImageBitmap {
    val bitmap = ImageBitmap(w, h)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), androidx.compose.ui.graphics.Paint().apply { color = colour })
    canvas.drawOval(w * 0.25f, h * 0.3f, w * 0.75f, h * 0.7f, androidx.compose.ui.graphics.Paint().apply { color = Color.White.copy(alpha = Emphasis.toneOutline) }) // design-exception: a stand-in photo's highlight, not UI
    return bitmap
}
