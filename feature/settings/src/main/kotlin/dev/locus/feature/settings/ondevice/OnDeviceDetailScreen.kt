package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
import dev.locus.feature.settings.R
import dev.locus.feature.settings.summaryJoin
import kotlinx.coroutines.launch
import dev.locus.core.designsystem.ChipFlow
import dev.locus.core.designsystem.ErrorState
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.LocusIcons
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.llm.Fit as ContextFit
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.OnDeviceFormat

/** One model: what it is, its files, and what can be done with it. Its own route (`OnDeviceModelRoute`). */
@Composable
fun OnDeviceModelScreen(
    key: ModelKey,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onSearch: (String) -> Unit = {},
    viewModel: OnDeviceDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(key) { viewModel.load(key) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feedback = Locus.feedback
    val resources = LocalResources.current
    LaunchedEffect(state.notice) { state.notice?.let { feedback.show(it.asString(resources)); viewModel.clearNotice() } }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    OnDeviceModelDetail(
        state,
        onBack,
        DetailActions(
            select = viewModel::select,
            download = { viewModel.download() },
            retry = viewModel::retry,
            pause = viewModel::pause,
            resume = { viewModel.resume(it) },
            cancel = viewModel::cancel,
            useMobileData = viewModel::useMobileData,
            dismiss = viewModel::dismiss,
            newChat = { viewModel.newChat(onOpenChat) },
            setDefault = { viewModel.setDefault() },
            unload = { viewModel.unload() },
            benchmark = { viewModel.benchmark() },
            delete = { viewModel.delete() },
            dismissCheck = viewModel::dismissCheck,
            downloadChecked = { viewModel.downloadChecked() },
            search = onSearch,
        ),
    )
}

class DetailActions(
    val select: (String) -> Unit = {},
    val download: () -> Unit = {},
    val retry: () -> Unit = {},
    val pause: (ActiveDownload) -> Unit = {},
    val resume: (ActiveDownload) -> Unit = {},
    val cancel: (ActiveDownload) -> Unit = {},
    val useMobileData: (ActiveDownload) -> Unit = {},
    val dismiss: (ActiveDownload) -> Unit = {},
    val newChat: () -> Unit = {},
    val setDefault: () -> Unit = {},
    val unload: () -> Unit = {},
    val benchmark: () -> Unit = {},
    val delete: () -> Unit = {},
    val dismissCheck: () -> Unit = {},
    val downloadChecked: () -> Unit = {},
    /** Opens Search with this text (a gated model's name, to find an ungated copy). */
    val search: (String) -> Unit = {},
)

/** The verdict sentence under the chips. */
@Composable
internal fun fitSentence(fit: Fit, ramBytes: Long?, memory: DeviceMemory): String {
    // The figures, in GB, when both the model's need and the phone's memory are known.
    val need = ramBytes?.let { MemoryGate.gb(it / MemoryGate.GIB) }
    val have = if (memory.totalBytes > 0) MemoryGate.gb(memory.totalBytes / MemoryGate.GIB) else null
    return when (fit) {
        Fit.Fits -> if (need != null && have != null) stringResource(R.string.settings_ondevice_fit_fits_figures, need, have) else stringResource(R.string.settings_ondevice_fit_fits)
        Fit.Tight -> if (need != null && have != null) stringResource(R.string.settings_ondevice_fit_tight_figures, need, have) else stringResource(R.string.settings_ondevice_fit_tight)
        Fit.TooLarge -> if (need != null && have != null) stringResource(R.string.settings_ondevice_fit_too_large_figures, need, have) else stringResource(R.string.settings_ondevice_fit_too_large)
        Fit.Unknown -> stringResource(R.string.settings_ondevice_fit_unknown)
    }
}

@Composable
internal fun OnDeviceModelDetail(state: DetailUiState, onBack: () -> Unit, actions: DetailActions) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmTight by rememberSaveable { mutableStateOf(false) }
    val info = state.info
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LocusTopBar(info?.name ?: stringResource(R.string.settings_ondevice_detail_model), onBack = onBack) },
    ) { padding ->
        val problem = state.problem
        when {
            // The model couldn't be looked up at all (offline, rate-limited, gated, gone): the screen's own error.
            problem != null && problem.kind == Problem.Kind.Gated -> {
                // A login wall, not a failure: caution, the model's name first, and a way on.
                val name = info?.name?.let(dev.locus.core.ondevice.gguf.GgufNames::display) ?: stringResource(R.string.settings_ondevice_detail_this_model)
                ErrorState(
                    title = stringResource(R.string.settings_ondevice_detail_gated_title),
                    message = stringResource(R.string.settings_ondevice_detail_gated_message),
                    onRetry = null,
                    modifier = Modifier.padding(padding),
                    icon = LocusIcons.Key,
                    tone = Tone.Caution,
                    action = { Button(onClick = { actions.search(name.removeSuffix(" GGUF")) }) { Text(stringResource(R.string.settings_ondevice_detail_gated_search, name), textAlign = TextAlign.Center) } },
                )
                return@Scaffold
            }
            problem != null -> {
                ErrorState(
                    title = problem.title ?: stringResource(R.string.settings_ondevice_detail_error),
                    message = listOfNotNull(problem.message, problem.advice).joinToString("\n\n"),
                    onRetry = actions.retry,
                    modifier = Modifier.padding(padding),
                    tone = if (problem.kind == Problem.Kind.Other) Tone.Critical else Tone.Caution,
                )
                return@Scaffold
            }
            state.loading || info == null -> {
                LoadingState(Modifier.padding(padding))
                return@Scaffold
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            item { Header(state, info) }
            if (state.fit == Fit.Tight || state.fit == Fit.TooLarge) {
                item {
                    Banner(
                        text = fitSentence(state.fit, state.installed?.ramBytes ?: state.selectedOption?.ramBytes, state.memory),
                        tone = if (state.fit == Fit.TooLarge) Tone.Critical else Tone.Caution,
                        title = state.fit.label,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                }
            }
            val installed = state.installed
            if (installed != null) {
                if (installed.loadCrashed) item { LoadCrashedBanner() }
                item { SectionLabel(stringResource(R.string.settings_ondevice_detail_on_phone)) }
                item { InstalledActions(state, installed, actions, onDelete = { confirmDelete = true }) }
                item { SectionLabel(stringResource(R.string.settings_ondevice_detail_engine)) }
                item { EngineFacts(state, installed) }
            } else {
                item { SectionLabel(stringResource(R.string.settings_ondevice_detail_files)) }
                item { Files(state, actions.select) }
                val hidden = state.options.count { !it.usable }
                if (hidden > 0) {
                    item { SectionNote(hiddenFilesNote(hidden), Modifier.padding(top = Spacing.sm)) }
                }
                val d = state.download
                if (d != null) {
                    item {
                        CardGroup(Modifier.padding(top = Spacing.md)) {
                            DownloadRows(
                                d.copy(name = info.name),
                                onPause = { actions.pause(d) }, onResume = { actions.resume(d) }, onCancel = { actions.cancel(d) },
                                onUseMobileData = { actions.useMobileData(d) }, onOpen = null,
                            )
                        }
                    }
                } else {
                    item { DownloadButton(state) { if (state.fit == Fit.Tight) confirmTight = true else actions.download() } }
                    downloadNote(state)?.let { note -> item { SectionNote(stringResource(note), Modifier.padding(top = Spacing.sm)) } }
                }
            }
            item { SectionLabel(stringResource(R.string.settings_ondevice_detail_details)) }
            item { Facts(info, state) }
            if (installed != null && !installed.hasTemplate) {
                item { SectionNote(stringResource(R.string.settings_ondevice_detail_no_template), Modifier.padding(top = Spacing.sm)) }
            }
        }
    }
    OfferSheet(state.check, onDownload = actions.downloadChecked, onDismiss = actions.dismissCheck)
    if (confirmDelete) {
        ConfirmSheet(
            title = stringResource(R.string.settings_ondevice_detail_delete_title, info?.name.toString()),
            message = stringResource(R.string.settings_ondevice_detail_delete_message, state.installed?.sizeBytes?.let(::gb).toString()),
            confirmLabel = stringResource(R.string.settings_ondevice_detail_delete),
            tone = Tone.Critical,
            onConfirm = { confirmDelete = false; actions.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmTight) {
        ConfirmSheet(
            title = stringResource(R.string.settings_ondevice_detail_download_anyway_title),
            message = fitSentence(state.fit, state.selectedOption?.ramBytes, state.memory),
            confirmLabel = stringResource(R.string.settings_ondevice_download_anyway),
            onConfirm = { confirmTight = false; actions.download() },
            onDismiss = { confirmTight = false },
        )
    }
}

/** What the model is (its description, else its fit) and its chips; the name is the top bar's. */
@Composable
private fun Header(state: DetailUiState, info: ModelInfo) {
    LocusCard(Modifier.padding(top = Spacing.sm)) {
        SupportingText(
            info.description ?: fitSentence(state.fit, state.installed?.ramBytes ?: state.selectedOption?.ramBytes, state.memory),
            style = LocusType.rowSupporting,
        )
        ModelChips(info.engine, state.fit, info.tools)
    }
}

@Composable
private fun Files(state: DetailUiState, onSelect: (String) -> Unit) {
    CardGroup {
        state.options.filter { it.usable }.forEachIndexed { i, o ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            val chosen = o.path == state.selected
            val spoken = listOfNotNull(o.quant ?: o.fileName, gb(o.sizeBytes), o.fit.spoken, if (o.recommended) stringResource(R.string.settings_ondevice_detail_spoken_recommended) else null, o.problem).joinToString(", ")
            ListRow(
                title = o.quant ?: o.fileName,
                subtitle = summaryJoin(
                    listOfNotNull(
                        gb(o.sizeBytes),
                        o.ramBytes?.let { stringResource(R.string.settings_ondevice_detail_file_memory, MemoryGate.gb(it / MemoryGate.GIB)) },
                        if (o.recommended) stringResource(R.string.settings_ondevice_detail_recommended) else null,
                        o.problem,
                    ),
                ),
                subtitleMaxLines = 3,
                enabled = o.usable,
                modifier = Modifier
                    .selectable(selected = chosen, enabled = o.usable, role = Role.RadioButton) { onSelect(o.path) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                    },
                leading = { RadioButton(selected = chosen, onClick = null, enabled = o.usable) },
                meta = if (o.usable) ({ ChipFlow { FitChip(o.fit) } }) else null,
            )
        }
        if (state.optionsLoading) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = stringResource(R.string.settings_ondevice_detail_listing),
                leading = { IconTile(LocusIcons.Layers) },
                trailing = { InlineSpinner(contentDescription = stringResource(R.string.settings_ondevice_detail_loading)) },
            )
        }
        state.optionsProblem?.let {
            GroupDivider(inset = Insets.iconRow)
            ListRow(title = stringResource(R.string.settings_ondevice_detail_listing_failed), subtitle = it.message, subtitleMaxLines = 3, leading = { IconTile(LocusIcons.Alert) })
        }
        if (state.options.none { it.usable } && !state.optionsLoading) ListRow(title = stringResource(R.string.settings_ondevice_detail_no_file), leading = { IconTile(LocusIcons.Layers) })
    }
}

@Composable
private fun DownloadButton(state: DetailUiState, onClick: () -> Unit) {
    val o = state.selectedOption
    val blocked = o == null || !o.usable || o.fit == Fit.TooLarge
    Button(onClick = onClick, enabled = !blocked, modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        Text(if (o == null) stringResource(R.string.settings_ondevice_detail_pick_file) else stringResource(R.string.settings_ondevice_detail_download_size, gb(o.sizeBytes)))
    }
}

/** "1 more file isn't listed: a vision projector or a split part, which Locus can't run on its own." */
@Composable
internal fun hiddenFilesNote(count: Int): String = pluralStringResource(R.plurals.settings_ondevice_detail_hidden_files, count, count)

/** The line under Download: why it can't, or what happens to the file. */
@StringRes
private fun downloadNote(state: DetailUiState): Int? {
    val o = state.selectedOption ?: return null
    return when {
        o.fit == Fit.TooLarge -> R.string.settings_ondevice_detail_note_too_large
        o.sha256 != null -> R.string.settings_ondevice_detail_note_checked
        else -> null
    }
}

@Composable
private fun LoadCrashedBanner() = Banner(
    text = stringResource(R.string.settings_ondevice_detail_crashed_text),
    tone = Tone.Critical,
    title = stringResource(R.string.settings_ondevice_detail_crashed_title),
    actionsBelow = true,
    modifier = Modifier.padding(top = Spacing.md),
)

/** What can be done with the installed model: one card of ListRows with tiles. */
@Composable
private fun InstalledActions(state: DetailUiState, m: InstalledInfo, actions: DetailActions, onDelete: () -> Unit) {
    val engine = state.engine
    val mine = engine.modelId == m.modelId
    CardGroup {
        ListRow(title = stringResource(R.string.settings_ondevice_detail_new_chat), onClick = actions.newChat, leading = { IconTile(LocusIcons.Chats) })
        GroupDivider(inset = Insets.iconRow)
        if (state.isDefault) {
            ListRow(title = stringResource(R.string.settings_ondevice_detail_is_default), subtitle = stringResource(R.string.settings_ondevice_detail_is_default_subtitle), subtitleMaxLines = 2, leading = { IconTile(LocusIcons.StarFilled) })
        } else {
            ListRow(title = stringResource(R.string.settings_ondevice_detail_set_default), subtitle = stringResource(R.string.settings_ondevice_detail_set_default_subtitle), subtitleMaxLines = 2, onClick = actions.setDefault, leading = { IconTile(LocusIcons.Star) })
        }
        if (mine && engine.loaded) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = stringResource(R.string.settings_ondevice_detail_unload),
                subtitle = stringResource(if (engine.busy) R.string.settings_ondevice_detail_unload_busy else R.string.settings_ondevice_detail_unload_subtitle),
                subtitleMaxLines = 2,
                onClick = actions.unload,
                enabled = !engine.busy,
                leading = { IconTile(LocusIcons.Stop) },
            )
        }
        if (state.info?.engine == Engine.LiteRt) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = stringResource(if (engine.benchmarking) R.string.settings_ondevice_detail_benchmark_running else R.string.settings_ondevice_detail_benchmark),
                subtitle = stringResource(R.string.settings_ondevice_detail_benchmark_subtitle),
                subtitleMaxLines = 2,
                onClick = actions.benchmark,
                enabled = !engine.benchmarking && !(mine && engine.busy),
                leading = { IconTile(LocusIcons.Gauge) },
                trailing = { if (engine.benchmarking) InlineSpinner(contentDescription = stringResource(R.string.settings_ondevice_detail_benchmark_description)) },
            )
        }
        GroupDivider(inset = Insets.iconRow)
        ListRow(title = stringResource(R.string.settings_ondevice_detail_delete), subtitle = stringResource(R.string.settings_ondevice_detail_frees, gb(m.sizeBytes)), onClick = onDelete, enabled = !(mine && engine.busy), leading = { IconTile(LocusIcons.Trash, tint = Locus.colors.critical) })
    }
}

/** The engine's state for this model: one card of KeyValueRows. */
@Composable
private fun EngineFacts(state: DetailUiState, m: InstalledInfo) {
    val engine = state.engine
    val mine = engine.modelId == m.modelId
    CardGroup {
        KeyValueRow(stringResource(R.string.settings_ondevice_detail_engine), OnDeviceFormat.backendLine(engine, m.modelId))
        if (mine) {
            OnDeviceFormat.loadedLine(engine)?.let { KeyValueRow(stringResource(R.string.settings_ondevice_detail_loaded), it) }
            engine.lastTurn?.let { turn -> OnDeviceFormat.turnLine(turn)?.let { KeyValueRow(stringResource(R.string.settings_ondevice_detail_last_reply), it) } }
        }
        engine.benchmark?.takeIf { it.modelId == m.modelId }?.let { r -> KeyValueRow(stringResource(R.string.settings_ondevice_detail_benchmark_label), OnDeviceFormat.benchmark(r).joinToString("\n"), mono = true, valueMaxLines = 6) }
    }
}

/** Facts as KeyValueRows in a CardGroup; the SHA-256 copies on tap; the model card (with its licence) opens in the browser. */
@Composable
private fun Facts(info: ModelInfo, state: DetailUiState) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val feedback = Locus.feedback
    val copied = stringResource(R.string.settings_ondevice_detail_sha_copied)
    val m = state.installed
    CardGroup {
        KeyValueRow(stringResource(R.string.settings_ondevice_detail_publisher), info.publisher)
        KeyValueRow(stringResource(R.string.settings_ondevice_offer_licence), dev.locus.core.ondevice.gguf.Licences.name(info.license) ?: stringResource(R.string.settings_ondevice_detail_licence_unknown))
        info.params?.let { KeyValueRow(stringResource(R.string.settings_ondevice_offer_parameters), it) }
        (m?.contextTokens ?: info.contextTokens)?.let { ctx ->
            val trained = m?.trainedContext?.takeIf { it > ctx }
            KeyValueRow(
                stringResource(R.string.settings_ondevice_detail_context),
                if (trained != null) {
                    stringResource(R.string.settings_ondevice_detail_context_trained, ContextFit.tokensLabel(ctx), ContextFit.tokensLabel(trained))
                } else {
                    stringResource(R.string.settings_ondevice_detail_context_tokens, ContextFit.tokensLabel(ctx))
                },
            )
        }
        info.tools?.let {
            KeyValueRow(
                stringResource(R.string.settings_ondevice_tools),
                when {
                    it -> stringResource(R.string.settings_ondevice_detail_tools_yes)
                    dev.locus.core.model.SmallModels.tooSmallForTools(info.params) -> stringResource(R.string.settings_ondevice_detail_tools_too_small, dev.locus.core.model.SmallModels.TOO_SMALL)
                    else -> stringResource(R.string.settings_ondevice_detail_tools_no_template)
                },
            )
        }
        m?.let {
            KeyValueRow(stringResource(R.string.settings_ondevice_detail_size), gb(it.sizeBytes))
            it.ramBytes?.let { r -> KeyValueRow(stringResource(R.string.settings_ondevice_offer_memory), stringResource(R.string.settings_ondevice_detail_memory_value, MemoryGate.gb(r / MemoryGate.GIB))) }
            it.quant?.let { q -> KeyValueRow(stringResource(R.string.settings_ondevice_offer_quantisation), q, mono = true) }
            it.sha256?.let { sha ->
                KeyValueRow(
                    stringResource(if (it.verified) R.string.settings_ondevice_detail_sha_verified else R.string.settings_ondevice_detail_sha_unverified),
                    sha,
                    mono = true,
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("SHA-256", sha)))
                            feedback.show(copied)
                        }
                    },
                )
            }
        }
        (m?.source ?: info.source)?.let { KeyValueRow(stringResource(R.string.settings_ondevice_detail_source), it, mono = true) }
        // One row anatomy per card: the model card is a value that opens, like the SHA-256 above.
        info.licenseUrl?.let { url -> KeyValueRow(stringResource(R.string.settings_ondevice_detail_model_card), url.removePrefix("https://"), onClick = { runCatching { uri.openUri(url) } }) }
    }
}

private val previewState = DetailUiState(
    loading = false,
    info = ModelInfo("Qwen3.5 2B", "unsloth", "A good first model: tool calling, long context.", Engine.LlamaCpp, "Apache 2.0", "https://huggingface.co/Qwen/Qwen3.5-2B", "2B", 262_144, true, "unsloth/Qwen3.5-2B-GGUF at f6d5376"),
    options = listOf(
        QuantOption("a", "Qwen3.5-2B-Q4_K_M.gguf", "Q4_K_M", 1_280_835_840, 1_900_000_000, Fit.Fits, "aa", null, recommended = true),
        QuantOption("b", "Qwen3.5-2B-Q8_0.gguf", "Q8_0", 2_010_000_000, 2_600_000_000, Fit.Tight, "bb", null),
        QuantOption("c", "mmproj-F16.gguf", "Vision projector", 670_000_000, null, Fit.Unknown, "cc", "This is a vision projector (mmproj), not a model."),
    ),
    selected = "a",
    memory = DeviceMemory(6L shl 30, null, 3L shl 30, false),
)

@Preview(name = "Light", widthDp = 360, heightDp = 1000)
@Preview(name = "Dark", widthDp = 360, heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Large font", widthDp = 360, heightDp = 1800, fontScale = 2f)
@Composable
private fun DetailPreview() {
    LocusTheme { OnDeviceModelDetail(previewState, {}, DetailActions()) }
}

@Preview(name = "Installed", widthDp = 360, heightDp = 1000)
@Composable
private fun InstalledPreview() {
    LocusTheme {
        OnDeviceModelDetail(
            previewState.copy(
                installed = InstalledInfo("gguf:q", 1_280_835_840, 1_900_000_000, "qwen35", "Q4_K_M", 16_384, 262_144, "aaf42c8b", true, "unsloth/Qwen3.5-2B-GGUF@f6d5376", false, true),
                engine = EngineStatus(),
            ),
            {},
            DetailActions(),
        )
    }
}

@Preview(name = "Gated", widthDp = 360)
@Composable
private fun GatedPreview() {
    LocusTheme {
        OnDeviceModelDetail(
            DetailUiState(
                loading = false,
                problem = Problem("meta-llama/Llama-3.2-1B-Instruct is gated.", Problem.Kind.Gated),
                info = ModelInfo("Llama-3.2-1B-Instruct", "meta-llama", null, Engine.LlamaCpp, "llama3.2", null, null, null, null, null),
            ),
            {},
            DetailActions(),
        )
    }
}
