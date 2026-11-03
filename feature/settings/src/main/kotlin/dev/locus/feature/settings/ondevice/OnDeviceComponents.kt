package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.ondevice.MemoryGate

/** The runtime badge: "LiteRT" or "llama.cpp". */
@Composable
internal fun EngineBadge(engine: Engine) = LocusChip(engine.label)

@Composable
internal fun FitChip(fit: Fit) = LocusChip(
    fit.label,
    tone = when (fit) {
        Fit.Fits -> Tone.Live
        Fit.Tight -> Tone.Caution
        Fit.TooLarge -> Tone.Critical
        Fit.Unknown -> Tone.Neutral
    },
)

@Composable
internal fun ToolsChip(tools: Boolean) = LocusChip(if (tools) "Tools" else "Chat only", tone = if (tools) Tone.Brand else Tone.Caution)

/** The chips under a model's name. They wrap at large font sizes rather than clipping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelChips(engine: Engine, fit: Fit?, tools: Boolean?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        EngineBadge(engine)
        fit?.let { FitChip(it) }
        tools?.let { ToolsChip(it) }
    }
}

/**
 * A download's progress and what can be done about it. The progress is announced to TalkBack every
 * 10 % ([announceStep]), not on every byte.
 */
@Composable
internal fun DownloadControls(
    name: String,
    ui: DownloadUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onUseMobileData: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val step = announceStep(ui.fraction)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        when (ui) {
            is DownloadUi.Waiting, is DownloadUi.Running, is DownloadUi.Paused -> {
                Box(
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = when (ui) {
                            is DownloadUi.Paused -> "$name paused" + (step?.let { " at $it percent" } ?: "")
                            is DownloadUi.Waiting -> "$name waiting" + if (ui.wifiOnly) " for Wi-Fi" else ""
                            else -> "$name downloading" + (step?.let { ", $it percent" } ?: "")
                        }
                    },
                ) {
                    Meter(ui.fraction, tone = if (ui is DownloadUi.Paused) Tone.Neutral else Tone.Live)
                }
                SupportingText(
                    when (ui) {
                        is DownloadUi.Running -> ui.line
                        is DownloadUi.Waiting -> if (ui.wifiOnly) "Waiting for Wi-Fi" else "Waiting to start"
                        else -> "Paused at " + (ui.total?.let { "${ui.bytes / 1_000_000} of ${it / 1_000_000} MB" } ?: "${ui.bytes / 1_000_000} MB")
                    },
                    style = LocusType.mono,
                )
                ButtonRow {
                    if (ui is DownloadUi.Waiting && ui.wifiOnly) TextButton(onClick = onUseMobileData) { Text("Use mobile data") }
                    if (ui is DownloadUi.Paused) TextButton(onClick = onResume) { Text("Resume") } else TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = { confirmCancel = true }) { Text("Cancel") }
                }
            }
            is DownloadUi.Verifying -> Box(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                ListRow(title = "Checking $name", subtitle = "SHA-256 and the model's header", trailing = { InlineSpinner() })
            }
            is DownloadUi.Failed -> Banner(text = ui.message, tone = Tone.Critical, title = "Download failed", actionsBelow = true) {
                TextButton(onClick = onCancel) { Text("Remove") }
                if (ui.retryable) TextButton(onClick = onResume) { Text(if (ui.bytes > 0) "Resume" else "Retry") }
            }
            is DownloadUi.Done -> {
                Text(
                    when {
                        ui.existing -> "Already on this phone: nothing was downloaded again."
                        ui.verified -> "Installed. SHA-256 matches Hugging Face's."
                        else -> "Installed. Unverified source; check its SHA-256."
                    },
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = LocusType.supporting,
                    color = if (ui.verified) Locus.colors.live else Locus.colors.caution,
                )
                ui.sha256?.let { SupportingText(it, style = LocusType.mono) }
                ButtonRow {
                    TextButton(onClick = onDismiss) { Text("Done") }
                    onOpen?.let { TextButton(onClick = it) { Text("Open") } }
                }
            }
        }
    }
    if (confirmCancel) {
        ConfirmSheet(
            title = "Cancel this download?",
            message = "Deletes what's downloaded of $name. Pause keeps it to resume later.",
            confirmLabel = "Cancel download",
            dismissLabel = "Keep",
            tone = Tone.Critical,
            onConfirm = { confirmCancel = false; onCancel() },
            onDismiss = { confirmCancel = false },
        )
    }
}

/** Buttons that wrap onto a second line at large font sizes instead of clipping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.Center, content = content)
}

@Composable
internal fun ProblemBanner(problem: Problem, title: String? = null, onRetry: (() -> Unit)?) {
    val tone = if (problem.kind == Problem.Kind.Other) Tone.Critical else Tone.Caution
    Banner(
        text = listOfNotNull(problem.message, problem.advice).joinToString("\n\n"),
        tone = tone,
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
                        text = "This file isn't from Hugging Face, so there's no published checksum to check it against. Locus shows its SHA-256 after downloading and checks it's a GGUF model, but can't tell whether it's the file its author published. Download only from a source you trust.",
                        tone = Tone.Caution,
                        actionsBelow = true,
                    )
                }
                o.problem?.let { Banner(text = it, tone = Tone.Critical, title = "Locus can't run this file") }
                check.warning?.let { Banner(text = it, tone = if (check.fit == Fit.TooLarge) Tone.Critical else Tone.Caution, title = check.fit.label) }
                ModelChips(Engine.LlamaCpp, check.fit, o.tools)
                Column {
                    KeyValueRow("Download", o.sizeBytes?.let(::gb) ?: "Size not given", dense = true)
                    o.ramBytes?.let { KeyValueRow("Memory", "≈${MemoryGate.gb(it / MemoryGate.GIB)} GB while running", dense = true) }
                    o.architecture?.let { KeyValueRow("Architecture", it, mono = true, dense = true) }
                    o.sizeLabel?.let { KeyValueRow("Parameters", it, dense = true) }
                    o.quant?.let { KeyValueRow("Quantisation", it, mono = true, dense = true) }
                    KeyValueRow("Licence", o.license ?: "Not stated: check the source", dense = true)
                    KeyValueRow("File", o.fileName, mono = true, dense = true)
                    KeyValueRow("SHA-256", o.sha256 ?: "Unknown until downloaded", mono = o.sha256 != null, dense = true)
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
            ListRow(title = "Search Hugging Face", subtitle = "GGUF models, checked against their published SHA-256", subtitleMaxLines = 2, onClick = onSearch, leading = { IconTile(LocusIcons.Search) })
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
            supportingText = { Text(warning ?: "A huggingface.co file link is pinned to its commit and checked against Hugging Face's SHA-256. Other https addresses download unverified.") },
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
        SupportingText("Recommended models and Hugging Face links are pinned to one commit and checked against the SHA-256 Hugging Face publishes. Search and downloads contact only huggingface.co and the download server it sends Locus to, with no account, key or anything about you. Downloads wait for Wi-Fi unless you allow mobile data in Settings → Battery & heat.")
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Large font", widthDp = 360, fontScale = 2f)
@Composable
private fun DownloadControlsPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                ModelChips(Engine.LlamaCpp, Fit.Fits, tools = true)
                DownloadControls("Qwen3.5 2B", DownloadUi.Running(512_000_000, 1_280_000_000, "512 / 1280 MB · 4.2 MB/s · 3 min left"), {}, {}, {}, {}, {}, null)
                DownloadControls("Qwen3.5 2B", DownloadUi.Waiting(0, 1_280_000_000, wifiOnly = true), {}, {}, {}, {}, {}, null)
                DownloadControls("Qwen3.5 2B", DownloadUi.Failed(0, 1_280_000_000, "No connection to huggingface.co.", retryable = true), {}, {}, {}, {}, {}, null)
            }
        }
    }
}
