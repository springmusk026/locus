package dev.locus.core.markdown.artifact

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the screen around an artifact card can do with it. Null actions hide their buttons.
 * [saveToWorkspace] writes the source and returns its workspace path; [shareImage] shares a PNG.
 */
class ArtifactHost(
    val saveToWorkspace: (suspend (fileName: String, content: String) -> String)? = null,
    val shareImage: (suspend (fileName: String, png: ByteArray) -> Unit)? = null,
)

val LocalArtifactHost = staticCompositionLocalOf { ArtifactHost() }

/** The live WebView of one card (inline or full screen) and what it reported. */
private class LiveView {
    var view: WebView? = null
    var loaded = false
}

/**
 * An artifact in the chat: a fixed-height preview running in the sandbox ([createArtifactWebView]),
 * with Full screen, Save to workspace ([canSave], for fenced artifacts; a sent file is already there),
 * Share as image and View source.
 *
 * Only one preview is live at a time ([ArtifactPreviews.slot]). A card that has never been drawn takes
 * the slot when it's free; any other shows its last snapshot, or a placeholder, until tapped. Leaving
 * the screen or losing the slot takes a snapshot first.
 */
@Composable
fun ArtifactCard(artifact: Artifact, modifier: Modifier = Modifier, canSave: Boolean = true) {
    val context = LocalContext.current
    val host = LocalArtifactHost.current
    val scope = rememberCoroutineScope()
    val feedback = Locus.feedback
    val surface = MaterialTheme.colorScheme.surface
    val dark = Locus.isDark
    val snapKey = remember(artifact.key, dark) { snapshotKey(artifact.key, dark) }
    val instance = remember { UUID.randomUUID().toString() }
    val inlineId = "inline:$instance"
    val fullId = "full:$instance"
    val slot = ArtifactPreviews.slot
    val fits = remember(artifact.key) { withinArtifactCap(artifact.bytes.toLong()) }

    var snapshot by remember(snapKey) { mutableStateOf(ArtifactPreviews.snapshots.get(snapKey)) }
    var blocked by remember(artifact.key) { mutableIntStateOf(0) }
    var link by remember { mutableStateOf<String?>(null) }
    var crashed by remember(artifact.key) { mutableStateOf(false) }
    var showSource by remember(artifact.key) { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    val inline = remember { LiveView() }
    val full = remember { LiveView() }

    fun capture(live: LiveView): Bitmap? {
        val view = live.view?.takeIf { live.loaded } ?: return null
        return captureArtifact(view)?.also {
            ArtifactPreviews.remember(snapKey, it)
            snapshot = it.asImageBitmap()
        }
    }
    val evictInline = { capture(inline); Unit }
    val events = { live: LiveView ->
        object : ArtifactEvents {
            override fun loaded() {
                live.loaded = true
                // A first picture once scripts have had a moment, in case the card leaves without an eviction.
                scope.launch {
                    kotlinx.coroutines.delay(SNAPSHOT_DELAY_MS)
                    capture(live)
                }
            }
            override fun blocked() {
                blocked++
            }
            override fun link(url: String) {
                link = url
            }
            override fun crashed() {
                live.loaded = false
                crashed = true
                slot.release(inlineId)
                slot.release(fullId)
            }
        }
    }

    if (fits) {
        // Draw once on arrival if nothing else is live, so the card has a picture to fall back to.
        LaunchedEffect(snapKey) {
            snapshotFlow { slot.owner }.collect { owner ->
                if (owner == null && snapshot == null && !crashed) slot.claimIfFree(inlineId, evictInline)
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                if (slot.isLive(inlineId)) {
                    capture(inline)
                    slot.release(inlineId)
                }
            }
        }
    }
    val live = slot.owner == inlineId
    val background = surface.toArgb()
    val backgroundCss = "#%06X".format(background and 0xFFFFFF)

    CodeSurface(modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    artifact.title ?: "${artifact.kind.label} preview",
                    style = LocusType.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (blocked > 0) {
                    SupportingText(if (blocked == 1) "1 request blocked" else "$blocked requests blocked", style = LocusType.mono)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).padding(horizontal = Spacing.sm)
                    .background(surface, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                val claim = { crashed = false; slot.claim(inlineId, evictInline); Unit }
                when {
                    showSource -> SourceText(artifact.content, Modifier.fillMaxSize())
                    !fits -> Note("Too large to preview (${readable(artifact.bytes.toLong())}; the limit is ${readable(MAX_ARTIFACT_BYTES.toLong())}). View the source instead.")
                    crashed -> Note("The preview stopped. Tap to run it again.", Modifier.clickable(onClickLabel = "Run the preview", onClick = claim))
                    live -> AndroidView(
                        factory = { ctx -> createArtifactWebView(ctx, artifact, background, backgroundCss, events(inline)).also { inline.view = it } },
                        onRelease = { view ->
                            inline.view = null
                            inline.loaded = false
                            view.stopLoading()
                            view.destroy()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    snapshot != null -> Box(Modifier.fillMaxSize().clickable(onClickLabel = "Interact with the preview", onClick = claim)) {
                        Image(
                            snapshot!!,
                            contentDescription = "Snapshot of ${artifact.title ?: "the preview"}",
                            contentScale = ContentScale.FillWidth,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            "Tap to interact",
                            style = LocusType.mono,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.sm)
                                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        )
                    }
                    else -> Note("Tap to run the preview", Modifier.clickable(onClickLabel = "Run the preview", onClick = claim))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                if (fits) ActionIcon(LocusIcons.Expand, "Open full screen") { fullScreen = true }
                val save = host.saveToWorkspace
                if (canSave && save != null) {
                    ActionIcon(LocusIcons.Folder, "Save to workspace") {
                        scope.launch {
                            runCatching { save(artifact.fileName(), artifact.content) }
                                .onSuccess { feedback.show("Saved to the workspace as $it", Tone.Live) }
                                .onFailure { feedback.show("Couldn't save to the workspace.", Tone.Critical) }
                        }
                    }
                }
                val share = host.shareImage
                if (fits && share != null) {
                    ActionIcon(LocusIcons.Image, "Share as image") {
                        val bitmap = capture(inline) ?: snapshot?.asAndroidBitmap()
                        if (bitmap == null) {
                            feedback.show("Run the preview first.", Tone.Caution)
                        } else {
                            scope.launch { runCatching { share(artifact.fileName().substringBeforeLast('.') + ".png", png(bitmap)) } }
                        }
                    }
                }
                ActionIcon(LocusIcons.Code, if (showSource) "Show the preview" else "View source", selected = showSource) { showSource = !showSource }
                SupportingText(
                    "Sandboxed · no network",
                    Modifier.weight(1f).padding(end = Spacing.sm),
                    style = LocusType.mono,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }

    if (fullScreen) {
        FullScreenArtifact(
            artifact = artifact,
            ownerId = fullId,
            live = full,
            events = events(full),
            background = background,
            backgroundCss = backgroundCss,
            onDismiss = {
                capture(full)
                slot.release(fullId)
                fullScreen = false
            },
        )
    }
    link?.let { url ->
        ConfirmSheet(
            title = "Open in browser?",
            message = "The preview links to:\n$url",
            confirmLabel = "Open",
            onConfirm = {
                link = null
                val open = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                try {
                    context.startActivity(open)
                } catch (_: ActivityNotFoundException) {
                    feedback.show("No browser here can open it.", Tone.Caution)
                }
            },
            onDismiss = { link = null },
        )
    }
}

/** The preview filling the screen. It takes the live slot, so the inline card shows its snapshot meanwhile. */
@Composable
private fun FullScreenArtifact(
    artifact: Artifact,
    ownerId: String,
    live: LiveView,
    events: ArtifactEvents,
    background: Int,
    backgroundCss: String,
    onDismiss: () -> Unit,
) {
    val slot = ArtifactPreviews.slot
    var claimed by remember { mutableStateOf(false) }
    LaunchedEffect(ownerId) {
        slot.claim(ownerId) { }
        claimed = true
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(LocusIcons.Close, contentDescription = "Close") }
                Text(
                    artifact.title ?: "${artifact.kind.label} preview",
                    style = LocusType.screenTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (slot.owner == ownerId) {
                    AndroidView(
                        factory = { ctx -> createArtifactWebView(ctx, artifact, background, backgroundCss, events).also { live.view = it } },
                        onRelease = { view ->
                            live.view = null
                            live.loaded = false
                            view.stopLoading()
                            view.destroy()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (claimed) {
                    Note("The preview stopped.", Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun SourceText(source: String, modifier: Modifier = Modifier) {
    Text(
        source,
        style = LocusType.code,
        color = MaterialTheme.colorScheme.onSurface,
        softWrap = false,
        modifier = modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(Spacing.sm),
    )
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        SupportingText(text, style = LocusType.rowSupporting)
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(IconSize.medium),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun png(bitmap: Bitmap): ByteArray = withContext(Dispatchers.Default) {
    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

private fun readable(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
}

// design-exception: the inline preview's fixed height, so a message doesn't resize as its page loads
private val PREVIEW_HEIGHT = 280.dp
private const val SNAPSHOT_DELAY_MS = 1_500L
