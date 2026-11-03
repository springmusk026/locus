package dev.locus.core.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.rememberCopyFeedback
import kotlinx.coroutines.launch

/**
 * A ```` ```mermaid ```` block drawn as a diagram, with buttons to show its source (Source / Diagram) and to copy it.
 *
 * Until the fence is [closed] (the reply is still streaming it), and if the diagram can't be drawn,
 * [codeCard] shows the source instead. Drawing starts only when the card is composed, i.e. on screen
 * in the chat list, and a finished picture comes from [MermaidRenderer]'s cache without a flash.
 */
@Composable
fun MermaidDiagram(source: String, closed: Boolean, codeCard: @Composable () -> Unit) {
    if (!closed || source.isBlank()) {
        codeCard()
        return
    }
    val dark = Locus.isDark
    val key = remember(source, dark) { diagramCacheKey(source, dark) }
    val context = LocalContext.current
    var showSource by remember(key) { mutableStateOf(false) }
    val image by produceState(MermaidRenderer.cached(key), key) {
        if (value == null && MermaidRenderer.failure(key) == null) {
            value = MermaidRenderer.render(context, source, dark)
            if (value == null) showSource = true
        }
    }
    if (image == null && MermaidRenderer.failure(key) != null) {
        // Couldn't be drawn (an unsupported diagram type or a syntax error): the source it is.
        codeCard()
        return
    }
    CodeSurface(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                SupportingText("Mermaid", Modifier.weight(1f), style = LocusType.mono)
                TextButton(onClick = { showSource = !showSource }) {
                    Text(if (showSource) "Diagram" else "Source", style = LocusType.mono)
                }
                CopySourceButton(source)
            }
            val drawn = image
            when {
                // Plain source inside the card (not a second code card nested in this one).
                showSource -> Text(
                    source,
                    style = LocusType.code,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                )
                drawn == null -> Box(Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT), contentAlignment = Alignment.Center) {
                    SupportingText("Drawing diagram…", style = LocusType.mono)
                }
                else -> DiagramImage(drawn)
            }
        }
    }
}

/** The picture at its natural size, centred, scrolling sideways if wider than the card. Tap to zoom. */
@Composable
private fun DiagramImage(image: MermaidImage) {
    var zoomed by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md)) {
        val width = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = width), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = image.bitmap,
                    contentDescription = "Diagram. Tap to zoom.",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(image.widthDp.dp, image.heightDp.dp).clickable { zoomed = true },
                )
            }
        }
    }
    if (zoomed) ZoomDialog(image) { zoomed = false }
}

/** Full screen with pinch to zoom and drag to pan; tap outside the gesture (back) closes it. */
@Composable
private fun ZoomDialog(image: MermaidImage, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(0.5f, 8f)
            offset += pan
        }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).transformable(state),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = image.bitmap,
                contentDescription = "Diagram",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(Spacing.md).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm)) {
                Icon(LocusIcons.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun CopySourceButton(source: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val feedback = rememberCopyFeedback()
    IconButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("Mermaid", source)))
                feedback.copied()
            }
        },
    ) {
        Icon(
            if (feedback.shown) LocusIcons.Check else LocusIcons.Copy,
            contentDescription = "Copy diagram source",
            modifier = Modifier.size(IconSize.small),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// design-exception: the card's height while a diagram draws, so the message doesn't jump when the picture arrives
private val PLACEHOLDER_HEIGHT = 120.dp
