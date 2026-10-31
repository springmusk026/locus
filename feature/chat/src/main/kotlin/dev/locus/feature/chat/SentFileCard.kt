package dev.locus.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A file the agent handed over (send_file): icon or thumbnail, name and size, and Open / Share / Save.
 * Other apps get the file through the workspace DocumentsProvider (`<package>.workspace`, which has
 * grantUriPermissions) with a one-off read grant, so no FileProvider is needed.
 */
@Composable
internal fun SentFileCard(item: ChatItem.SentFile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = item.file
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(info?.mimeType ?: "application/octet-stream")) { target ->
        if (target != null && info != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target)?.use { out -> File(info.absolutePath).inputStream().use { it.copyTo(out) } } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "Saved ${info.name}" else "Couldn't save ${info.name}", Toast.LENGTH_SHORT).show()
        }
    }
    val thumb by produceState<ImageBitmap?>(null, info?.absolutePath, info?.size) {
        if (info != null && info.mimeType.startsWith("image/")) value = withContext(Dispatchers.IO) { decodeThumb(info.absolutePath) }
    }

    LocusCard(modifier.fillMaxWidth().widthIn(max = 420.dp), contentPadding = PaddingValues(0.dp)) {
        val image = thumb
        if (image != null && info != null) {
            Image(
                bitmap = image,
                contentDescription = info.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card))
                    .clickable(onClickLabel = "Open ${info.name}") { open(context, info) },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, top = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (image == null) FileTypeIcon(info?.name ?: item.path, isDirectory = false, size = 40.dp)
            Column(Modifier.weight(1f).padding(start = if (image == null) Spacing.md else 0.dp)) {
                Text(
                    info?.name ?: item.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        info == null -> "No longer in the workspace"
                        item.note != null -> "${SendFile.readableSize(info.size)} · ${item.note}"
                        else -> "${SendFile.readableSize(info.size)} · ${info.relativePath}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (info != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                TextButton(onClick = { open(context, info) }) { Text("Open") }
                TextButton(onClick = { share(context, info) }) { Text("Share") }
                TextButton(onClick = { save.launch(info.name) }) { Text("Save to device") }
            }
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = Spacing.md))
        }
    }
}

/** The file as a content URI from the workspace DocumentsProvider; its document id is the relative path. */
private fun uriFor(context: Context, info: SentFileInfo): Uri =
    DocumentsContract.buildDocumentUri("${context.packageName}.workspace", info.relativePath)

private fun open(context: Context, info: SentFileInfo) {
    val uri = uriFor(context, info)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can open ${info.name}", Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, info: SentFileInfo) {
    val uri = uriFor(context, info)
    val send = Intent(Intent.ACTION_SEND).setType(info.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        // ClipData carries the read grant through the chooser to the target app.
        .also { it.clipData = ClipData.newRawUri(info.name, uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can share ${info.name}", Toast.LENGTH_SHORT).show()
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
