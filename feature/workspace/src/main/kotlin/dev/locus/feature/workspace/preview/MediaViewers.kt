package dev.locus.feature.workspace.preview

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.locus.core.designsystem.FileTypeIcon
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Spacing
import kotlinx.coroutines.delay
import java.io.File

// ---------------------------------------------------------------------------------------------
// Sharing and opening in other apps
// ---------------------------------------------------------------------------------------------

/**
 * The file as a content URI from the workspace DocumentsProvider (`<package>.workspace`, which has
 * grantUriPermissions; its document id is the workspace-relative path), so other apps get a one-off
 * read grant and no FileProvider is needed. Same approach as chat's SentFileCard.
 */
internal fun workspaceUri(context: Context, relativePath: String): Uri =
    DocumentsContract.buildDocumentUri("${context.packageName}.workspace", relativePath)

internal fun openWith(context: Context, file: OpenFile) {
    val uri = workspaceUri(context, file.path)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeTypeFor(file.name, file.kind)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, "Open ${file.name} with"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can open ${file.name}", Toast.LENGTH_SHORT).show()
    }
}

internal fun share(context: Context, file: OpenFile) {
    val uri = workspaceUri(context, file.path)
    val send = Intent(Intent.ACTION_SEND).setType(mimeTypeFor(file.name, file.kind))
        .putExtra(Intent.EXTRA_STREAM, uri)
        // ClipData carries the read grant through the chooser to the target app.
        .also { it.clipData = ClipData.newRawUri(file.name, uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app here can share ${file.name}", Toast.LENGTH_SHORT).show()
    }
}

internal fun sizeAndDate(context: Context, file: OpenFile): String =
    "${Formatter.formatShortFileSize(context, file.size)} · ${DateUtils.formatDateTime(context, file.modified, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)}"

// ---------------------------------------------------------------------------------------------
// Info card for files with no built-in viewer
// ---------------------------------------------------------------------------------------------

@Composable
internal fun FileInfoCard(file: OpenFile, onExport: () -> Unit, modifier: Modifier = Modifier, note: String? = null) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FileTypeIcon(file.name, isDirectory = false, size = 72.dp)
        Text(file.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.lg))
        Text(
            listOf(kindLabel(file), sizeAndDate(context, file)).joinToString(" · "),
            style = MonoCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Text(
            note ?: "Locus can't preview this type of file. Open it in another app, or share or export it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.md).widthIn(max = 320.dp),
        )
        Row(Modifier.padding(top = Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilledTonalButton(onClick = { openWith(context, file) }) { Text("Open with…") }
            OutlinedButton(onClick = { share(context, file) }) { Text("Share") }
            OutlinedButton(onClick = onExport) { Text("Export") }
        }
    }
}

private fun kindLabel(file: OpenFile): String {
    val ext = extensionOf(file.name)
    return when (ext) {
        "apk", "aab", "apks", "xapk" -> "Android app package"
        "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "lz4", "jar", "aar" -> "${ext.uppercase()} archive"
        "doc", "docx", "odt", "rtf" -> "Document"
        "xls", "xlsx", "ods" -> "Spreadsheet"
        "ppt", "pptx", "odp" -> "Presentation"
        "ttf", "otf", "woff", "woff2" -> "Font"
        "" -> "File"
        else -> ext.uppercase()
    }
}

// ---------------------------------------------------------------------------------------------
// Video and audio
// ---------------------------------------------------------------------------------------------

/** A VideoView with the platform MediaController (play/pause/seek); starts paused on the first frame. */
@Composable
internal fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    var failed by remember(file) { mutableStateOf(false) }
    if (failed) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This video can't be played here. Try Open with.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { seekTo(1); controller.show(0) }
                    setOnErrorListener { _, _, _ -> failed = true; true }
                    setVideoPath(file.absolutePath)
                }
            },
            onRelease = { it.stopPlayback() },
        )
    }
}

/** A MediaPlayer with play/pause and a seek bar. Released when the screen goes away. */
@Composable
internal fun AudioPlayer(file: File, modifier: Modifier = Modifier) {
    var prepared by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableFloatStateOf(0f) }
    var dragging by remember(file) { mutableStateOf(false) }
    val player = remember(file) {
        MediaPlayer().apply {
            setOnPreparedListener { duration = it.duration; prepared = true }
            setOnCompletionListener { playing = false; position = 0f; it.seekTo(0) }
            setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
            runCatching {
                setDataSource(file.absolutePath)
                prepareAsync()
            }.onFailure { failed = true }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, playing) {
        while (playing) {
            if (!dragging) position = runCatching { player.currentPosition.toFloat() }.getOrDefault(position)
            delay(250)
        }
    }
    Column(
        modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FileTypeIcon(file.name, isDirectory = false, size = 72.dp)
        Text(file.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.lg))
        if (failed) {
            Text("This audio can't be played here. Try Open with.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.md))
            return@Column
        }
        Slider(
            value = position,
            onValueChange = { dragging = true; position = it },
            onValueChangeFinished = { player.seekTo(position.toInt()); dragging = false },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = prepared,
            modifier = Modifier.widthIn(max = 420.dp).padding(top = Spacing.xl),
        )
        Text(
            "${DateUtils.formatElapsedTime(position.toLong() / 1000)} / ${DateUtils.formatElapsedTime(duration.toLong() / 1000)}",
            style = MonoCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = {
                if (playing) player.pause() else player.start()
                playing = !playing
            },
            enabled = prepared,
            modifier = Modifier.padding(top = Spacing.lg),
        ) {
            if (!playing) Icon(LocusIcons.Play, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs))
            Text(if (playing) "Pause" else "Play")
        }
    }
}
