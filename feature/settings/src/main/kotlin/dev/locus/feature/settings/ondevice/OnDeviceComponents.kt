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
import androidx.compose.ui.res.stringResource
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
import dev.locus.feature.settings.R
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.designsystem.asString

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
internal fun ModelChips(engine: Engine, fit: Fit?, tools: Boolean?, isDefault: Boolean = false, onGet: (() -> Unit)? = null, getDescription: String = stringResource(R.string.settings_ondevice_get)) {
    ChipFlow {
        onGet?.let { LocusChip(stringResource(R.string.settings_ondevice_get), tone = Tone.Brand, icon = LocusIcons.Import, onClick = it, contentDescription = getDescription) }
        if (isDefault) LocusChip(stringResource(R.string.settings_ondevice_default), tone = Tone.Brand)
        LocusChip(engine.label)
        // Neutral when it's the usual case; colour only for what needs attention (Chat only, a tight fit).
        tools?.let { LocusChip(stringResource(if (it) R.string.settings_ondevice_tools else R.string.settings_ondevice_chat_only), tone = if (it) Tone.Neutral else Tone.Caution) }
        when (fit) {
            Fit.Tight -> LocusChip(stringResource(fit.labelRes), tone = Tone.Caution)
            Fit.TooLarge -> LocusChip(stringResource(fit.labelRes), tone = Tone.Critical)
            else -> Unit
        }
    }
}

/** A fit chip for a file in the detail's list, where every file's fit is the point. */
@Composable
internal fun FitChip(fit: Fit) = LocusChip(
    stringResource(fit.labelRes),
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
                TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_ondevice_remove)) }
                if (ui.retryable) TextButton(onClick = onResume) { Text(stringResource(if (ui.bytes > 0) R.string.settings_ondevice_resume else R.string.settings_ondevice_retry)) }
            }
        }
        is DownloadUi.Verifying -> ListRow(
            title = d.name,
            subtitle = stringResource(R.string.settings_ondevice_verifying),
            subtitleMaxLines = 2,
            titleMaxLines = 2,
            leading = { EngineTile(d.engine) },
            trailing = { InlineSpinner(contentDescription = stringResource(R.string.settings_ondevice_checking_name, d.name)) },
        )
        is DownloadUi.Done -> Unit // a transient message, never a row
        is DownloadUi.Waiting, is DownloadUi.Running, is DownloadUi.Paused -> {
            ListRow(
                title = d.name,
                subtitle = if (d.verified) null else stringResource(R.string.settings_ondevice_unverified),
                titleMaxLines = 2,
                onClick = onOpen,
                leading = { EngineTile(d.engine) },
            )
            val step = announceStep(ui.fraction)
            TransferProgress(
                fraction = ui.fraction,
                status = statusLines(LocalResources.current, ui),
                start = Insets.iconRow,
                announcement = when (ui) {
                    is DownloadUi.Paused ->
                        if (step != null) stringResource(R.string.settings_ondevice_announce_paused_at, d.name, step) else stringResource(R.string.settings_ondevice_announce_paused, d.name)
                    is DownloadUi.Waiting ->
                        stringResource(if (ui.wifiOnly) R.string.settings_ondevice_announce_waiting_wifi else R.string.settings_ondevice_announce_waiting, d.name)
                    else ->
                        if (step != null) stringResource(R.string.settings_ondevice_announce_downloading_at, d.name, step) else stringResource(R.string.settings_ondevice_announce_downloading, d.name)
                },
                tone = if (ui is DownloadUi.Paused) Tone.Neutral else Tone.Live,
            ) {
                if (ui is DownloadUi.Waiting && ui.wifiOnly) TextButton(onClick = onUseMobileData) { Text(stringResource(R.string.settings_ondevice_use_mobile_data)) }
                if (ui is DownloadUi.Paused) TextButton(onClick = onResume) { Text(stringResource(R.string.settings_ondevice_resume)) } else TextButton(onClick = onPause) { Text(stringResource(R.string.settings_ondevice_pause)) }
                TextButton(onClick = { confirmCancel = true }) { Text(stringResource(R.string.settings_ondevice_cancel)) }
            }
        }
    }
    if (confirmCancel) {
        ConfirmSheet(
            title = stringResource(R.string.settings_ondevice_cancel_title),
            message = stringResource(R.string.settings_ondevice_cancel_message, d.name),
            confirmLabel = stringResource(R.string.settings_ondevice_cancel_confirm),
            dismissLabel = stringResource(R.string.settings_ondevice_cancel_keep),
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
        text = listOfNotNull(problem.message.asString(), problem.adviceRes?.let { stringResource(it) }).joinToString("\n\n"),
        tone = if (problem.kind == Problem.Kind.Other) Tone.Critical else Tone.Caution,
        title = title ?: problem.titleRes?.let { stringResource(it) },
        actionsBelow = true,
    ) {
        if (onRetry != null && problem.kind != Problem.Kind.Gated) TextButton(onClick = onRetry) { Text(stringResource(R.string.settings_ondevice_try_again)) }
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
        is OfferCheck.Checking -> LocusSheet(title = stringResource(R.string.settings_ondevice_offer_checking), subtitle = check.label, onDismiss = onDismiss) {
            ListRow(
                title = stringResource(R.string.settings_ondevice_offer_reading),
                subtitle = stringResource(R.string.settings_ondevice_offer_reading_subtitle),
                subtitleMaxLines = 3,
                trailing = { InlineSpinner(contentDescription = stringResource(R.string.settings_ondevice_offer_spinner)) },
            )
        }
        is OfferCheck.Failed -> LocusSheet(title = stringResource(R.string.settings_ondevice_offer_failed), subtitle = check.label, onDismiss = onDismiss) {
            ProblemBanner(check.problem, onRetry = null)
        }
        is OfferCheck.Ready -> {
            val o = check.offer
            val blocked = o.problem != null || check.fit == Fit.TooLarge
            LocusSheet(
                title = o.name,
                subtitle = o.source,
                onDismiss = onDismiss,
                action = if (blocked) null else stringResource(if (o.verified) R.string.settings_ondevice_download else R.string.settings_ondevice_download_anyway),
                actionTone = if (o.verified) Tone.Brand else Tone.Critical,
                onAction = if (blocked) null else onDownload,
            ) {
                if (!o.verified) {
                    Banner(
                        title = stringResource(R.string.settings_ondevice_unverified),
                        text = stringResource(R.string.settings_ondevice_offer_unverified_text),
                        tone = Tone.Caution,
                        actionsBelow = true,
                    )
                }
                o.problem?.let { Banner(text = it, tone = Tone.Critical, title = stringResource(R.string.settings_ondevice_offer_cant_run)) }
                check.warning?.let { Banner(text = it, tone = if (check.fit == Fit.TooLarge) Tone.Critical else Tone.Caution, title = stringResource(check.fit.labelRes)) }
                // The fit is the banner's when it's a warning; the chips don't repeat it.
                ModelChips(Engine.LlamaCpp, if (check.warning != null) null else check.fit, o.callsTools)
                // The facts in a card, so they share the banners' and chips' left edge.
                CardGroup {
                    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                        KeyValueRow(stringResource(R.string.settings_ondevice_offer_download), o.sizeBytes?.let { gb(it) } ?: stringResource(R.string.settings_ondevice_offer_size_unknown), dense = true)
                        o.ramBytes?.let { KeyValueRow(stringResource(R.string.settings_ondevice_offer_memory), stringResource(R.string.settings_ondevice_offer_memory_value, MemoryGate.gb(it / MemoryGate.GIB)), dense = true) }
                        o.sizeLabel?.let { KeyValueRow(stringResource(R.string.settings_ondevice_offer_parameters), it, dense = true) }
                        o.quant?.let { KeyValueRow(stringResource(R.string.settings_ondevice_offer_quantisation), it, mono = true, dense = true) }
                        KeyValueRow(stringResource(R.string.settings_ondevice_offer_licence), dev.locus.core.ondevice.gguf.Licences.name(o.license) ?: stringResource(R.string.settings_ondevice_offer_licence_unknown), dense = true)
                        KeyValueRow(stringResource(R.string.settings_ondevice_offer_file), o.fileName, mono = true, dense = true)
                        o.sha256?.let { KeyValueRow(stringResource(R.string.settings_ondevice_offer_sha256), it, mono = true, dense = true) }
                    }
                }
            }
        }
    }
}

/** Add: from a URL, a file on the phone, or a Hugging Face search. */
@Composable
internal fun AddSheet(onUrl: () -> Unit, onFile: () -> Unit, onSearch: () -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = stringResource(R.string.settings_ondevice_add_title), onDismiss = onDismiss) {
        CardGroup {
            ListRow(title = stringResource(R.string.settings_ondevice_add_search), subtitle = stringResource(R.string.settings_ondevice_add_search_subtitle), subtitleMaxLines = 2, onClick = onSearch, leading = { IconTile(LocusIcons.Search) })
            ListRow(title = stringResource(R.string.settings_ondevice_add_url), subtitle = stringResource(R.string.settings_ondevice_add_url_subtitle), subtitleMaxLines = 2, onClick = onUrl, leading = { IconTile(LocusIcons.Globe) })
            ListRow(title = stringResource(R.string.settings_ondevice_add_file), subtitle = stringResource(R.string.settings_ondevice_add_file_subtitle), subtitleMaxLines = 2, onClick = onFile, leading = { IconTile(LocusIcons.Import) })
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
        title = stringResource(R.string.settings_ondevice_url_title),
        subtitle = stringResource(R.string.settings_ondevice_url_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_ondevice_url_check),
        actionEnabled = ok,
        onAction = { onCheck(url.trim()) },
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_ondevice_url_label)) },
            isError = warning != null,
            supportingText = { Text(stringResource(warning ?: R.string.settings_ondevice_url_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ok) onCheck(url.trim()) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Learn more": what on-device models are, the two engines, and where files come from. */
@Composable
internal fun LearnMoreSheet(onDismiss: () -> Unit) {
    LocusSheet(title = stringResource(R.string.settings_ondevice_learn_title), onDismiss = onDismiss) {
        Text(stringResource(R.string.settings_ondevice_learn_offline_title), style = LocusType.label)
        SupportingText(stringResource(R.string.settings_ondevice_learn_offline_body))
        Text(stringResource(R.string.settings_ondevice_learn_engines_title), style = LocusType.label)
        SupportingText(stringResource(R.string.settings_ondevice_learn_engines_body))
        Text(stringResource(R.string.settings_ondevice_learn_tools_title), style = LocusType.label)
        SupportingText(stringResource(R.string.settings_ondevice_learn_tools_body))
        Text(stringResource(R.string.settings_ondevice_learn_sources_title), style = LocusType.label)
        SupportingText(stringResource(R.string.settings_ondevice_learn_sources_body))
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
