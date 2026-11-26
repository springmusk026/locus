package dev.locus.feature.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import dev.locus.core.designsystem.DateSkeleton
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.formatDateTime
import dev.locus.core.designsystem.localizedToolLabel
import dev.locus.core.designsystem.settingsPath
import dev.locus.core.designsystem.titleRes
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Starts publishing the chat on screen as a web page (docs/design/publish.md); null hides the option. */
internal val LocalChatPublish = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

internal enum class ExportFormat(val label: String, val extension: String, val mimeType: String, @StringRes val note: Int) {
    Markdown("Markdown", "md", "text/markdown", R.string.chat_share_markdown_note),
    Pdf("PDF", "pdf", "application/pdf", R.string.chat_share_pdf_note),
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
    IconButton(onClick = { sheet = true }, enabled = items.isNotEmpty()) { Icon(LocusIcons.Share, contentDescription = stringResource(R.string.chat_share_title)) }
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
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    // The screen's host outlives the sheet, so a message still shows once the sheet has closed.
    val feedback = Locus.feedback
    var busy by remember { mutableStateOf(false) }
    fun run(format: ExportFormat, save: Boolean) {
        busy = true
        scope.launch {
            val file = runCatching { exportFile(context, title, startedAt, items, format) }.getOrNull()
            when {
                file == null -> feedback.show(resources.getString(R.string.chat_share_make_failed, format.label), Tone.Critical)
                save -> runCatching { onSaveToWorkspace(file.name, file) }
                    .onSuccess { feedback.show(resources.getString(R.string.chat_share_saved, it), Tone.Live) }
                    .onFailure { feedback.show(resources.getString(R.string.chat_share_save_failed), Tone.Critical) }
                else -> share(context, file, format, title, feedback)
            }
            busy = false
            // Closed either way, so the message isn't left under the sheet's scrim.
            onDismiss()
        }
    }
    LocusSheet(
        title = stringResource(R.string.chat_share_title),
        subtitle = stringResource(R.string.chat_share_subtitle),
        onDismiss = onDismiss,
    ) {
        ExportFormat.entries.forEach { format ->
            Column {
                Text(format.label, style = LocusType.stateTitle)
                SupportingText(stringResource(format.note))
                // Pulled back by the buttons' own padding, so their labels start on the text's edge.
                Row(Modifier.offset(x = -Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = { run(format, save = false) }, enabled = !busy) { Text(stringResource(R.string.chat_share_share)) }
                    TextButton(onClick = { run(format, save = true) }, enabled = !busy) { Text(stringResource(R.string.chat_share_save)) }
                }
            }
        }
        // Publish to your own host (docs/design/publish.md): the chat screen provides it.
        LocalChatPublish.current?.let { publish ->
            Column {
                Text(stringResource(R.string.chat_share_publish_title), style = LocusType.stateTitle)
                SupportingText(stringResource(R.string.chat_share_publish_note))
                Row(Modifier.offset(x = -Spacing.md)) {
                    TextButton(onClick = { onDismiss(); publish() }, enabled = !busy) { Text(stringResource(R.string.chat_share_publish)) }
                }
            }
        }
    }
}

/** Writes the export to cache/exports (old exports there are cleared after an hour). */
private suspend fun exportFile(context: Context, title: String, startedAt: Long, items: List<ChatItem>, format: ExportFormat): File =
    withContext(Dispatchers.IO) {
        val markdown = ChatExport.markdown(title, startedAt, items, words = exportWords(context))
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

/**
 * The export's words in the app's language ([context]'s resources), its date on the phone's 12/24-hour
 * clock: the same strings the chat and the published page use where they say the same thing.
 */
internal fun exportWords(context: Context): ExportWords {
    val res = context.resources
    val locale = res.configuration.locales[0]
    return ExportWords(
        untitled = res.getString(R.string.chat_export_untitled),
        dated = res.getString(R.string.chat_export_dated),
        undated = res.getString(R.string.chat_export_undated),
        speaker = res.getString(R.string.chat_export_speaker),
        you = res.getString(R.string.chat_publish_page_you),
        locus = res.getString(R.string.chat_publish_page_locus),
        attached = res.getString(R.string.chat_publish_page_attached),
        summarised = res.getString(R.string.chat_export_summarised),
        ranAnyway = res.getString(R.string.chat_export_ran_anyway),
        approved = res.getString(R.string.chat_export_approved),
        plan = res.getString(R.string.chat_publish_page_plan),
        sent = res.getString(R.string.chat_export_sent),
        providerDraft = res.getString(R.string.chat_export_provider_draft),
        modelsPage = settingsPath(res, SettingsDestination.Models),
        changed = res.getString(R.string.chat_export_changed),
        changedUndone = res.getString(R.string.chat_export_changed_undone),
        failed = res.getString(R.string.chat_export_step_failed),
        notRun = res.getString(R.string.chat_export_step_not_run),
        typed = res.getString(R.string.chat_export_typed),
        type = res.getString(R.string.chat_export_type),
        field = res.getString(R.string.chat_export_field),
        date = { millis, zone -> formatDateTime(context, millis, DateSkeleton.FULL_DATE_TIME, zone) },
        tool = { name, args, done -> localizedToolLabel(res, name, args, done) },
        check = { res.getString(it.titleRes()).lowercase(locale) },
        change = { changeSummary(res, it) },
        receipt = { receiptExportText(res, it) },
    )
}

private fun share(context: Context, file: File, format: ExportFormat, title: String, feedback: LocusFeedback) {
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
        feedback.show(context.getString(R.string.chat_share_no_app, format.label), Tone.Critical)
    }
}

/** Shares a picture of an artifact preview (docs/design/artifacts.md) from cache/exports. */
internal suspend fun shareArtifactImage(context: Context, name: String, png: ByteArray, feedback: LocusFeedback) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeBytes(png) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .also { it.clipData = ClipData.newRawUri(file.name, uri) }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (_: ActivityNotFoundException) {
        feedback.show(context.getString(R.string.chat_share_no_app_picture), Tone.Critical)
    }
}
