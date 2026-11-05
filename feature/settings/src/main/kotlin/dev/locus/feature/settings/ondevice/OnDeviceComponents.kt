package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import dev.locus.core.designsystem.ActionRow
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipFlow
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.TransferProgress
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.gguf.GgufDownloadText

/*
 * The on-device screens' shared pieces, all built from core/designsystem: an engine's icon tile,
 * the chips under a model's name, a download's rows inside a CardGroup, and the sheets.
 */

/** One icon per engine, the same everywhere: a chip for LiteRT, stacked layers (a weights file) for llama.cpp. */
internal val Engine.icon: ImageVector get() = when (this) {
    Engine.LiteRt -> LocusIcons.Chip
    Engine.LlamaCpp -> LocusIcons.Layers
}

@Composable
internal fun EngineTile(engine: Engine) = IconTile(engine.icon)

/**
 * The chips under a model's name: its engine, Tools or Chat only, and its fit only when that's a
 * warning (Tight fit, Too large). "Fits" isn't repeated on every row; the Discover filter says it.
 */
@Composable
internal fun ModelChips(engine: Engine, fit: Fit?, tools: Boolean?, isDefault: Boolean = false, onGet: (() -> Unit)? = null, getDescription: String = "Get") {
    ChipFlow {
        onGet?.let { LocusChip("Get", tone = Tone.Brand, icon = LocusIcons.Import, onClick = it, contentDescription = getDescription) }
        if (isDefault) LocusChip("Default", tone = Tone.Brand)
        LocusChip(engine.label)
        // Neutral when it's the usual case; colour only for what needs attention (Chat only, a tight fit).
        tools?.let { LocusChip(if (it) "Tools" else "Chat only", tone = if (it) Tone.Neutral else Tone.Caution) }
        when (fit) {
            Fit.Tight -> LocusChip(fit.label, tone = Tone.Caution)
            Fit.TooLarge -> LocusChip(fit.label, tone = Tone.Critical)
            else -> Unit
        }
    }
}

/** A fit chip for a file in the detail's list, where every file's fit is the point. */
@Composable
internal fun FitChip(fit: Fit) = LocusChip(
    fit.label,
    tone = when (fit) {
        Fit.Fits -> Tone.Neutral
        Fit.Tight -> Tone.Caution
        Fit.TooLarge -> Tone.Critical
        Fit.Unknown -> Tone.Neutral
    },
)

/**
 * A download inside a CardGroup: a ListRow naming it, then its progress and actions
 * ([TransferProgress]), a spinner while it's checked, or why it failed with Retry and Remove.
 * Progress is announced every 10 % ([announceStep]).
 */
@Composable
internal fun DownloadRows(d: ActiveDownload, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onUseMobileData: () -> Unit, onOpen: (() -> Unit)?) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val ui = d.ui
    when (ui) {
        is DownloadUi.Failed -> {
            ListRow(
                title = d.name,
                subtitle = ui.message,
                subtitleMaxLines = 4,
                titleMaxLines = 2,
                leading = { IconTile(LocusIcons.Alert, tint = Locus.colors.critical) },
            )
            ActionRow {
                TextButton(onClick = onCancel) { Text("Remove") }
                if (ui.retryable) TextButton(onClick = onResume) { Text(if (ui.bytes > 0) "Resume" else "Retry") }
            }
        }
        is DownloadUi.Verifying -> ListRow(
            title = d.name,
            subtitle = "Checking the file before it's used",
            subtitleMaxLines = 2,
            titleMaxLines = 2,
            leading = { EngineTile(d.engine) },
            trailing = { InlineSpinner(contentDescription = "Checking ${d.name}") },
        )
        is DownloadUi.Done -> Unit // a transient message, never a row
        is DownloadUi.Waiting, is DownloadUi.Running, is DownloadUi.Paused -> {
            ListRow(
                title = d.name,
                subtitle = if (d.verified) null else "Unverified source",
                titleMaxLines = 2,
                onClick = onOpen,
                leading = { EngineTile(d.engine) },
            )
            val step = announceStep(ui.fraction)
            TransferProgress(
                fraction = ui.fraction,
                status = statusLines(ui),
                start = Insets.iconRow,
                announcement = when (ui) {
                    is DownloadUi.Paused -> "${d.name} paused" + (step?.let { " at $it percent" } ?: "")
                    is DownloadUi.Waiting -> "${d.name} waiting" + if (ui.wifiOnly) " for Wi-Fi" else ""
                    else -> "${d.name} downloading" + (step?.let { ", $it percent" } ?: "")
                },
                tone = if (ui is DownloadUi.Paused) Tone.Neutral else Tone.Live,
            ) {
                if (ui is DownloadUi.Waiting && ui.wifiOnly) TextButton(onClick = onUseMobileData) { Text("Use mobile data") }
                if (ui is DownloadUi.Paused) TextButton(onClick = onResume) { Text("Resume") } else TextButton(onClick = onPause) { Text("Pause") }
                TextButton(onClick = { confirmCancel = true }) { Text("Cancel") }
            }
        }
    }
    if (confirmCancel) {
        ConfirmSheet(
            title = "Cancel this download?",
            message = "Deletes what's downloaded of ${d.name}. Pause keeps it to resume later.",
            confirmLabel = "Cancel download",
            dismissLabel = "Keep",
            tone = Tone.Critical,
            onConfirm = { confirmCancel = false; onCancel() },
            onDismiss = { confirmCancel = false },
        )
    }
}

/** A problem inline, with what to do: a Banner, and Try again unless trying again can't help. */
@Composable
internal fun ProblemBanner(problem: Problem, title: String? = null, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Banner(
        modifier = modifier,
        text = listOfNotNull(problem.message, problem.advice).joinToString("\n\n"),
        tone = if (problem.kind == Problem.Kind.Other) Tone.Critical else Tone.Caution,
        title = title ?: problem.title,
        actionsBelow = true,
    ) {
        if (onRetry != null && problem.kind != Problem.Kind.Gated) TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/**
 * The step before a URL or search download: what the file is, where it's from, whether it fits,
 * and an "Unverified source" warning for anything not checked against Hugging Face.
 */
@Composable
internal fun OfferSheet(check: OfferCheck, onDownload: () -> Unit, onDismiss: () -> Unit) {
    when (check) {
        OfferCheck.Idle -> Unit
        is OfferCheck.Checking -> LocusSheet(title = "Checking the file", subtitle = check.label, onDismiss = onDismiss) {
            ListRow(
                title = "Reading the model's header",
                subtitle = "Its first few megabytes: what it is, its tools and the memory it needs.",
                subtitleMaxLines = 3,
                trailing = { InlineSpinner(contentDescription = "Checking") },
            )
        }
        is OfferCheck.Failed -> LocusSheet(title = "Can't download this", subtitle = check.label, onDismiss = onDismiss) {
            ProblemBanner(check.problem, onRetry = null)
        }
        is OfferCheck.Ready -> {
            val o = check.offer
            val blocked = o.problem != null || check.fit == Fit.TooLarge
            LocusSheet(
                title = o.name,
                subtitle = o.source,
                onDismiss = onDismiss,
                action = if (blocked) null else if (o.verified) "Download" else "Download anyway",
                actionTone = if (o.verified) Tone.Brand else Tone.Critical,
                onAction = if (blocked) null else onDownload,
            ) {
                if (!o.verified) {
                    Banner(
                        title = "Unverified source",
                        text = "This file isn't from Hugging Face, so Locus can't check it's the file its author published, only that it's a GGUF model. Download only from a source you trust.",
                        tone = Tone.Caution,
                        actionsBelow = true,
                    )
                }
                o.problem?.let { Banner(text = it, tone = Tone.Critical, title = "Locus can't run this file") }
                check.warning?.let { Banner(text = it, tone = if (check.fit == Fit.TooLarge) Tone.Critical else Tone.Caution, title = check.fit.label) }
                // The fit is the banner's when it's a warning; the chips don't repeat it.
                ModelChips(Engine.LlamaCpp, if (check.warning != null) null else check.fit, o.tools)
                // The facts in a card, so they share the banners' and chips' left edge.
                CardGroup {
                    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                        KeyValueRow("Download", o.sizeBytes?.let(::gb) ?: "Size not given", dense = true)
                        o.ramBytes?.let { KeyValueRow("Memory", "≈${MemoryGate.gb(it / MemoryGate.GIB)}${GgufDownloadText.NBSP}GB while running", dense = true) }
                        o.sizeLabel?.let { KeyValueRow("Parameters", it, dense = true) }
                        o.quant?.let { KeyValueRow("Quantisation", it, mono = true, dense = true) }
                        KeyValueRow("Licence", dev.locus.core.ondevice.gguf.Licences.name(o.license) ?: "Not stated: check the source", dense = true)
                        KeyValueRow("File", o.fileName, mono = true, dense = true)
                        o.sha256?.let { KeyValueRow("SHA-256", it, mono = true, dense = true) }
                    }
                }
            }
        }
    }
}

/** Add: from a URL, a file on the phone, or a Hugging Face search. */
@Composable
internal fun AddSheet(onUrl: () -> Unit, onFile: () -> Unit, onSearch: () -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = "Add a model", onDismiss = onDismiss) {
        CardGroup {
            ListRow(title = "Search Hugging Face", subtitle = "GGUF models, checked before they're used", subtitleMaxLines = 2, onClick = onSearch, leading = { IconTile(LocusIcons.Search) })
            ListRow(title = "From a URL", subtitle = "A Hugging Face link, or any https address (unverified)", subtitleMaxLines = 2, onClick = onUrl, leading = { IconTile(LocusIcons.Globe) })
            ListRow(title = "Import a file", subtitle = "A .gguf file on the phone or in a cloud drive", subtitleMaxLines = 2, onClick = onFile, leading = { IconTile(LocusIcons.Import) })
        }
    }
}

/** A URL to check, validated as it's typed. */
@Composable
internal fun UrlSheet(onCheck: (String) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val warning = urlWarning(url)
    val ok = url.isNotBlank() && warning == null
    LocusSheet(
        title = "Add from a URL",
        subtitle = "The file's header is read first; nothing downloads until you confirm.",
        onDismiss = onDismiss,
        action = "Check",
        actionEnabled = ok,
        onAction = { onCheck(url.trim()) },
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("File address (https://…)") },
            isError = warning != null,
            supportingText = { Text(warning ?: "A huggingface.co link is checked against the file Hugging Face lists before it's used. Other https addresses download unverified.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ok) onCheck(url.trim()) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Learn more": what on-device models are, the two engines, and where files come from. */
@Composable
internal fun LearnMoreSheet(onDismiss: () -> Unit) {
    LocusSheet(title = "On-device models", onDismiss = onDismiss) {
        Text("Run without internet or a key", style = LocusType.label)
        SupportingText("These models run on this phone. Chats with them stay here, work offline and cost nothing, but they're smaller and slower than cloud models.")
        Text("Two engines", style = LocusType.label)
        SupportingText("LiteRT runs Google's Gemma models and can use the GPU. llama.cpp runs GGUF files from Hugging Face on the CPU; a small instruct model with a Q4_K_M quantisation is a good start.")
        Text("Tools", style = LocusType.label)
        SupportingText("Models marked Tools can read files, search and act. Chat only models just reply, and Locus warns before giving them a task that needs tools.")
        Text("Where files come from", style = LocusType.label)
        SupportingText("Recommended models and Hugging Face links are checked against the exact file Hugging Face lists before they're used. Search and downloads contact only huggingface.co and the download server it sends Locus to, with no account, key or anything about you. Downloads wait for Wi-Fi unless you allow mobile data in Settings → Battery & heat.")
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Large font", widthDp = 360, fontScale = 2f)
@Composable
private fun DownloadRowsPreview() {
    LocusTheme {
        CardGroup {
            DownloadRows(ActiveDownload(null, "a", "SmolLM3 3B", Engine.LlamaCpp, DownloadUi.Running(800_000_000, 1_915_305_312, 5_000_000, 223)), {}, {}, {}, {}, null)
            DownloadRows(ActiveDownload(null, "b", "Gemma 4 E2B", Engine.LiteRt, DownloadUi.Waiting(0, 2_588_147_712, wifiOnly = true)), {}, {}, {}, {}, null)
            DownloadRows(ActiveDownload(null, "c", "Qwen3.5 2B", Engine.LlamaCpp, DownloadUi.Failed(0, 1_280_835_840, "No connection to huggingface.co.", retryable = true)), {}, {}, {}, {}, null)
        }
    }
}
