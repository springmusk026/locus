package dev.locus.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.tools.SendFile
import dev.locus.core.designsystem.ChatSizes
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusFeedback
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.markdown.artifact.Artifact
import dev.locus.core.markdown.artifact.ArtifactCard
import dev.locus.core.markdown.artifact.artifactKindForFile
import dev.locus.core.markdown.artifact.withinArtifactCap
import dev.locus.core.markdown.files.OpenTarget
import dev.locus.core.markdown.files.openTargetFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens a workspace file (its path relative to the workspace) in Locus's own viewer, the Workspace's;
 * back returns to the chat. Null where there's nowhere to go (previews); Open then uses another app.
 */
internal val LocalOpenWorkspaceFile = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** What Open on a sent file does. */
internal sealed interface SentFileOpen {
    /** Locus's viewer, on this workspace-relative path. */
    data class InApp(val relativePath: String) : SentFileOpen

    /** Android's "Open with" for the file: Locus has no viewer for it, or none is reachable here. */
    data object OtherApp : SentFileOpen
}

/**
 * Open goes to Locus's own viewer for every type it can show ([openTargetFor]), and to another app
 * only when nothing in Locus fits or [inAppAvailable] is false.
 */
internal fun sentFileOpen(info: SentFileInfo, inAppAvailable: Boolean): SentFileOpen =
    if (inAppAvailable && openTargetFor(info.name, info.mimeType) == OpenTarget.InApp) SentFileOpen.InApp(info.relativePath) else SentFileOpen.OtherApp

/**
 * A file the agent handed over (send_file): icon or thumbnail, name and size, and Open / Share / Save.
 * Open (and a tap on the thumbnail) shows the file in Locus's viewer when Locus can show it, with
 * "Open with another app" in the overflow; anything else opens in another app.
 * HTML and SVG files also get a sandboxed, interactive preview ([ArtifactCard]).
 * Other apps get the file through the workspace DocumentsProvider (`<package>.workspace`, which has
 * grantUriPermissions) with a one-off read grant, so no FileProvider is needed.
 */
@Composable
internal fun SentFileCard(item: ChatItem.SentFile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = Locus.feedback
    val openInApp = LocalOpenWorkspaceFile.current
    var menu by remember { mutableStateOf(false) }
    val info = item.file
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(info?.mimeType ?: "application/octet-stream")) { target ->
        if (target != null && info != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target)?.use { out -> File(info.absolutePath).inputStream().use { it.copyTo(out) } } != null
                }.getOrDefault(false)
            }
            if (ok) feedback.show("Saved ${info.name}") else feedback.show("Couldn't save ${info.name}", Tone.Critical)
        }
    }
    val thumb by produceState<ImageBitmap?>(null, info?.absolutePath, info?.size) {
        if (info != null && info.mimeType.startsWith("image/")) value = withContext(Dispatchers.IO) { decodeThumb(info.absolutePath) }
    }

    // An .html or .svg file runs as an artifact preview above the file row (docs/design/artifacts.md).
    val artifactKind = info?.let { artifactKindForFile(it.name) }
    val artifact by produceState<Artifact?>(null, info?.absolutePath, info?.size) {
        if (info != null && artifactKind != null && withinArtifactCap(info.size)) {
            value = withContext(Dispatchers.IO) { runCatching { Artifact(artifactKind, File(info.absolutePath).readText(), info.name) }.getOrNull() }
        }
    }

    LocusCard(modifier.fillMaxWidth().widthIn(max = ChatSizes.mediaCard), contentPadding = PaddingValues(0.dp)) {
        artifact?.let {
            ArtifactCard(
                it,
                Modifier.padding(horizontal = Spacing.sm).padding(top = Spacing.sm),
                canSave = false,
                // The page whole, with the network, is the user's call, in an app of their choosing.
                onOpenElsewhere = info?.let { file -> { openWith(context, file, feedback) } },
            )
        }
        val image = thumb
        if (image != null && info != null) {
            Image(
                bitmap = image,
                contentDescription = info.name,
                contentScale = ContentScale.Crop,
                // A tall image (a full-page screenshot) shows its top, not a slice from the middle.
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth().heightIn(max = Sizes.inlineScroll)
                    .clip(MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)))
                    .clickable(onClickLabel = "Open ${info.name}") { open(context, info, openInApp, feedback) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, top = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (image == null) FileTypeIcon(info?.name ?: item.path, isDirectory = false, size = IconSize.tile)
            Column(Modifier.weight(1f).padding(start = if (image == null) Spacing.md else 0.dp)) {
                Text(
                    info?.name ?: item.path.substringAfterLast('/'),
                    style = LocusType.rowTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SupportingText(
                    when {
                        info == null -> "No longer in the workspace"
                        item.note != null -> "${SendFile.readableSize(info.size)} · ${item.note}"
                        else -> "${SendFile.readableSize(info.size)} · ${info.relativePath}"
                    },
                    maxLines = 2,
                )
            }
        }
        if (info != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { open(context, info, openInApp, feedback) }) { Text("Open") }
                TextButton(onClick = { share(context, info, feedback) }) { Text("Share") }
                // "Save" (to the phone's storage, through the system picker): short enough to stay on one line at a large font.
                TextButton(onClick = { save.launch(info.name) }) { Text("Save") }
                // When Open stays in Locus, another app is one step further.
                if (sentFileOpen(info, openInApp != null) is SentFileOpen.InApp) {
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(LocusIcons.More, contentDescription = "More actions for ${info.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Open with another app") }, onClick = { menu = false; openWith(context, info, feedback) })
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.padding(bottom = Spacing.md))
        }
    }
}

/** The file as a content URI from the workspace DocumentsProvider; its document id is the relative path. */
private fun uriFor(context: Context, info: SentFileInfo): Uri =
    DocumentsContract.buildDocumentUri("${context.packageName}.workspace", info.relativePath)

private fun open(context: Context, info: SentFileInfo, openInApp: ((String) -> Unit)?, feedback: LocusFeedback) {
    when (val target = sentFileOpen(info, openInApp != null)) {
        is SentFileOpen.InApp -> openInApp?.invoke(target.relativePath)
        SentFileOpen.OtherApp -> openWith(context, info, feedback)
    }
}

/** Android's "Open with" for the file, with a one-off read grant. */
private fun openWith(context: Context, info: SentFileInfo, feedback: LocusFeedback) {
    val uri = uriFor(context, info)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        feedback.show("No app here can open ${info.name}", Tone.Caution)
    }
}

private fun share(context: Context, info: SentFileInfo, feedback: LocusFeedback) {
    val uri = uriFor(context, info)
    val send = Intent(Intent.ACTION_SEND).setType(info.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        // ClipData carries the read grant through the chooser to the target app.
        .also { it.clipData = ClipData.newRawUri(info.name, uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        feedback.show("No app here can share ${info.name}", Tone.Caution)
    }
}

/** A small decode for the card: bounds first, then a power-of-two sample to roughly 512 px. */
private fun decodeThumb(path: String): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
