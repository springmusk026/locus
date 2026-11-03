package dev.locus.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.meterFraction
import androidx.compose.foundation.layout.padding
import dev.locus.core.llm.Fit
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.OnDeviceFormat
import dev.locus.core.ondevice.gguf.GgufImport
import dev.locus.core.ondevice.gguf.GgufModel

/** What the GGUF rows and sheet ask the screen to do. */
internal class GgufActions(
    /** Imports the file the user picked in the system file picker. */
    val import: (android.net.Uri) -> Unit,
    val cancelImport: () -> Unit,
    val dismissImport: () -> Unit,
    val delete: (id: String) -> Unit,
    val unload: () -> Unit,
) {
    companion object {
        val None = GgufActions({}, {}, {}, {}, {})
    }
}

/**
 * The imported GGUF models inside the "On-device models" card, then the import: a progress row while
 * copying, the reason when it failed, or the row that opens the file picker.
 */
@Composable
internal fun GgufRows(rows: List<GgufRow>, engine: EngineStatus, import: GgufImport, onOpen: (String) -> Unit, onPick: () -> Unit, actions: GgufActions) {
    rows.forEachIndexed { i, row ->
        if (i > 0) GroupDivider(inset = Insets.iconRow)
        ListRow(
            title = row.model.name,
            subtitle = ggufSubtitle(row, engine),
            subtitleMaxLines = 2,
            onClick = { onOpen(row.model.id) },
            leading = { IconTile(LocusIcons.File) },
            trailing = { GgufPill(row) },
        )
    }
    if (rows.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
    when (import) {
        GgufImport.Idle -> AddRow("Import a GGUF model", icon = LocusIcons.Import, onClick = onPick)
        is GgufImport.Reading -> ListRow(
            title = "Reading ${import.fileName}",
            subtitle = "Checking the model's header",
            leading = { IconTile(LocusIcons.Import) },
            trailing = { InlineSpinner(contentDescription = "Reading") },
        )
        is GgufImport.Copying -> Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Text("Copying ${import.fileName}", style = LocusType.rowSupporting, maxLines = 1)
            Meter(import.total?.let { meterFraction(import.bytes, it) }, tone = Tone.Live, contentDescription = "Copied")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportingText(
                    import.total?.let { "${import.bytes / 1_000_000} / ${it / 1_000_000} MB" } ?: "${import.bytes / 1_000_000} MB",
                    Modifier.weight(1f),
                    style = LocusType.mono,
                )
                TextButton(onClick = actions.cancelImport) { Text("Cancel") }
            }
        }
        is GgufImport.Failed -> Column(Modifier.padding(Spacing.md)) {
            Banner(
                text = import.message,
                tone = Tone.Critical,
                title = "Couldn't import ${import.fileName}",
                actionsBelow = true,
            ) {
                TextButton(onClick = actions.dismissImport) { Text("Dismiss") }
                TextButton(onClick = { actions.dismissImport(); onPick() }) { Text("Pick another file") }
            }
        }
    }
}

@Composable
private fun GgufPill(row: GgufRow) = when {
    row.tooLarge -> LocusChip("Too large", tone = Tone.Critical)
    row.model.loadCrashed -> LocusChip("Ran out of memory", tone = Tone.Critical)
    !row.model.tools -> LocusChip("Chat only", tone = Tone.Caution)
    else -> LocusChip("Tools", tone = Tone.Brand)
}

/** "1.1 GB · Q4_K_M · ≈1.6 GB memory", then the engine's state when this model is the loaded one. */
internal fun ggufSubtitle(row: GgufRow, engine: EngineStatus): String = listOfNotNull(
    "${gb(row.model.sizeBytes)} GB",
    row.model.quant,
    "≈${MemoryGate.gb(row.model.ramBytes / MemoryGate.GIB)} GB memory",
    OnDeviceFormat.backendLine(engine, row.model.id).takeIf { engine.modelId == row.model.id },
).joinToString(" · ")

/** One imported model: what it is, what it can do, how it fits this phone, its engine, and Delete. */
@Composable
internal fun GgufModelSheet(row: GgufRow, engine: EngineStatus, actions: GgufActions, onDismiss: () -> Unit) {
    val m = row.model
    var confirmDelete by remember { mutableStateOf(false) }
    LocusSheet(title = m.name, subtitle = m.fileName, onDismiss = onDismiss) {
        if (row.tooLarge || row.ramWarning != null) {
            Banner(text = row.ramWarning.orEmpty(), tone = if (row.tooLarge) Tone.Critical else Tone.Caution, title = if (row.tooLarge) "Too large for this phone" else "Short on memory")
        }
        if (m.loadCrashed) {
            Banner(
                text = "Android stopped Locus while it was loading this model last time, most likely because memory ran out. Close other apps and try again, or import a smaller quantisation.",
                tone = Tone.Critical,
                title = "The last load didn't finish",
            )
        }
        Column {
            KeyValueRow("Architecture", m.architecture, mono = true, dense = true)
            m.quant?.let { KeyValueRow("Quantisation", it, mono = true, dense = true) }
            m.sizeLabel?.let { KeyValueRow("Parameters", it, dense = true) }
            KeyValueRow("File", "${gb(m.sizeBytes)} GB", dense = true)
            KeyValueRow(
                "Context",
                Fit.tokensLabel(m.contextTokens) + " tokens" + (m.trainedContext?.takeIf { it > m.contextTokens }?.let { " (trained for ${Fit.tokensLabel(it)})" } ?: ""),
                dense = true,
            )
            KeyValueRow("Memory", "≈${MemoryGate.gb(m.ramBytes / MemoryGate.GIB)} GB while running", dense = true)
            m.license?.let { KeyValueRow("Licence", it, dense = true) }
        }
        Text(if (m.tools) "Tool calling" else "Chat only", style = LocusType.label)
        SupportingText(
            if (m.tools) {
                "Its chat template declares tools, so the agent can read files, search and act with it. llama.cpp constrains each call to the tool's schema."
            } else {
                "Its chat template has no tool calling, so it can only chat. Locus warns before sending it a message that needs tools, and never picks it as a backup for one."
            },
        )
        if (!m.hasTemplate) SupportingText("The file has no chat template, so llama.cpp formats the chat in the common ChatML layout. Replies may be poor if the model was trained on another.")

        Text("Engine", style = LocusType.label)
        val mine = engine.modelId == m.id
        Text(OnDeviceFormat.backendLine(engine, m.id), style = LocusType.rowSupporting)
        if (mine) {
            OnDeviceFormat.loadedLine(engine)?.let { SupportingText(it) }
            engine.lastTurn?.let { turn -> OnDeviceFormat.turnLine(turn)?.let { SupportingText("Last reply: $it") } }
        }
        if (mine && engine.loaded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportingText(
                    if (engine.busy) "Replying now. Unload when it finishes." else "Frees its memory. The next message to it loads it again.",
                    Modifier.weight(1f),
                )
                TextButton(onClick = actions.unload, enabled = !engine.busy) { Text("Unload") }
            }
        } else if (!mine) {
            SupportingText("Loaded on its first message, on the CPU. Unloaded after a while unused, sooner when the phone is hot or short of memory.")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }, enabled = !(mine && engine.busy)) { Text("Delete", color = Locus.colors.critical) }
        }
    }
    if (confirmDelete) {
        ConfirmSheet(
            title = "Delete ${m.name}?",
            message = "Frees ${gb(m.sizeBytes)} GB on this phone. Chats that use it need another model; import the file again to use it.",
            confirmLabel = "Delete",
            tone = Tone.Critical,
            onConfirm = {
                confirmDelete = false
                actions.delete(m.id)
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun gb(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / 1e9)
