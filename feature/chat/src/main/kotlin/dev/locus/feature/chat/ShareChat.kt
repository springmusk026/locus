package dev.locus.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class ExportFormat(val label: String, val extension: String, val mimeType: String, val note: String) {
    Markdown("Markdown", "md", "text/markdown", "Plain text with code kept as code. Good for notes apps and GitHub."),
    Pdf("PDF", "pdf", "application/pdf", "A readable document to print or send."),
}

/**
 * The chat's top-bar Share chat button (its only action, so no menu): Markdown or PDF of the branch
 * on screen, shared through the share sheet (from the app cache, via a FileProvider) or saved into
 * the workspace. [onSaveToWorkspace] copies a file into the workspace and returns its workspace path.
 */
@Composable
internal fun ShareChatButton(
    title: String,
    startedAt: Long,
    items: List<ChatItem>,
    onSaveToWorkspace: suspend (name: String, file: File) -> String,
) {
    var sheet by remember { mutableStateOf(false) }
    IconButton(onClick = { sheet = true }, enabled = items.isNotEmpty()) { Icon(LocusIcons.Share, contentDescription = "Share chat") }
    if (sheet) ShareChatSheet(title, startedAt, items, onSaveToWorkspace, onDismiss = { sheet = false })
}

@Composable
private fun ShareChatSheet(
    title: String,
    startedAt: Long,
    items: List<ChatItem>,
    onSaveToWorkspace: suspend (name: String, file: File) -> String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun run(format: ExportFormat, save: Boolean) {
        busy = true
        scope.launch {
            val file = runCatching { exportFile(context, title, startedAt, items, format) }.getOrNull()
            when {
                file == null -> toast(context, "Couldn't make the ${format.label} file.")
                save -> runCatching { onSaveToWorkspace(file.name, file) }
                    .onSuccess { toast(context, "Saved to the workspace as $it") }
                    .onFailure { toast(context, "Couldn't save to the workspace.") }
                else -> share(context, file, format, title)
            }
            busy = false
            if (file != null) onDismiss()
        }
    }
    LocusSheet(
        title = "Share chat",
        subtitle = "The branch on screen. Reasoning, tool details and helper transcripts are left out, and anything that looks like a key is removed.",
        onDismiss = onDismiss,
    ) {
        ExportFormat.entries.forEach { format ->
            Column {
                Text(format.label, style = MaterialTheme.typography.titleMedium)
                Text(format.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = { run(format, save = false) }, enabled = !busy) { Text("Share") }
                    TextButton(onClick = { run(format, save = true) }, enabled = !busy) { Text("Save to workspace") }
                }
            }
        }
    }
}

/** Writes the export to cache/exports (old exports there are cleared after an hour). */
private suspend fun exportFile(context: Context, title: String, startedAt: Long, items: List<ChatItem>, format: ExportFormat): File =
    withContext(Dispatchers.IO) {
        val markdown = ChatExport.markdown(title, startedAt, items)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.delete() }
        val file = File(dir, ChatExport.fileName(title, startedAt, format.extension))
        when (format) {
            ExportFormat.Markdown -> file.writeText(markdown)
            ExportFormat.Pdf -> file.outputStream().use { ChatPdf.write(markdown, it) }
        }
        file
    }

private fun share(context: Context, file: File, format: ExportFormat, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND).setType(format.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        // ClipData carries the read grant through the chooser to the target app.
        .also { it.clipData = ClipData.newRawUri(file.name, uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        toast(context, "No app here can take a ${format.label} file.")
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
