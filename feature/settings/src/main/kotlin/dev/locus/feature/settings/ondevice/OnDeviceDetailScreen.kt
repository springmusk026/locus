package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.llm.Fit as ContextFit
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.EngineStatus
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.OnDeviceFormat

/** One model: what it is, its files, and what can be done with it. Its own route (`OnDeviceModelRoute`). */
@Composable
fun OnDeviceModelScreen(key: ModelKey, onBack: () -> Unit, onOpenChat: (String) -> Unit, viewModel: OnDeviceDetailViewModel = hiltViewModel()) {
    LaunchedEffect(key) { viewModel.load(key) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feedback = Locus.feedback
    LaunchedEffect(state.notice) { state.notice?.let { feedback.show(it); viewModel.clearNotice() } }
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
)

/** The verdict sentence under the chips. */
internal fun fitSentence(fit: Fit, ramBytes: Long?, memory: DeviceMemory): String {
    val need = ramBytes?.let { "about ${MemoryGate.gb(it / MemoryGate.GIB)} GB" }
    val have = if (memory.totalBytes > 0) "${MemoryGate.gb(memory.totalBytes / MemoryGate.GIB)} GB" else null
    return when (fit) {
        Fit.Fits -> "Fits this phone" + (if (need != null && have != null) ": needs $need of its $have." else ".")
        Fit.Tight -> "A tight fit" + (if (need != null && have != null) ": needs $need of this phone's $have" else "") + ". It may be slow, or Android may stop Locus to free memory."
        Fit.TooLarge -> "Too large for this phone" + (if (need != null && have != null) ": needs $need and it has $have" else "") + ". Pick a smaller file."
        Fit.Unknown -> "Locus couldn't read this phone's memory, so it can't say whether this fits."
    }
}

@Composable
internal fun OnDeviceModelDetail(state: DetailUiState, onBack: () -> Unit, actions: DetailActions) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmTight by rememberSaveable { mutableStateOf(false) }
    val info = state.info
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LocusTopBar(info?.name ?: "Model", onBack = onBack, subtitle = info?.publisher) },
    ) { padding ->
        if (state.loading || info == null) {
            if (state.problem != null) {
                Column(Modifier.padding(padding).padding(Spacing.lg)) { ProblemBanner(state.problem, onRetry = actions.retry) }
            } else {
                LoadingState(Modifier.padding(padding))
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Header(state, info) }
            state.problem?.let { p -> item { ProblemBanner(p, onRetry = actions.retry) } }
            val installed = state.installed
            if (installed != null) {
                item { SectionLabel("On this phone") }
                item { InstalledCard(state, installed, actions, onDelete = { confirmDelete = true }) }
            } else if (state.problem == null) {
                item { SectionLabel("Files") }
                item { Files(state, actions.select) }
                item {
                    val d = state.download
                    if (d != null) {
                        LocusCard {
                            DownloadControls(
                                info.name, d.ui,
                                onPause = { actions.pause(d) }, onResume = { actions.resume(d) }, onCancel = { actions.cancel(d) },
                                onUseMobileData = { actions.useMobileData(d) }, onDismiss = { actions.dismiss(d) }, onOpen = null,
                            )
                        }
                    } else {
                        DownloadButton(state) { if (state.fit == Fit.Tight) confirmTight = true else actions.download() }
                    }
                }
            }
            item { Facts(info, state) }
        }
    }
    OfferSheet(state.check, onDownload = actions.downloadChecked, onDismiss = actions.dismissCheck)
    if (confirmDelete) {
        ConfirmSheet(
            title = "Delete ${info?.name}?",
            message = "Frees ${state.installed?.sizeBytes?.let(::gb)} on this phone. Chats that use it need another model; download it again to use it.",
            confirmLabel = "Delete",
            tone = Tone.Critical,
            onConfirm = { confirmDelete = false; actions.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmTight) {
        ConfirmSheet(
            title = "Download anyway?",
            message = fitSentence(state.fit, state.selectedOption?.ramBytes, state.memory),
            confirmLabel = "Download anyway",
            onConfirm = { confirmTight = false; actions.download() },
            onDismiss = { confirmTight = false },
        )
    }
}

@Composable
private fun Header(state: DetailUiState, info: ModelInfo) {
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ModelChips(info.engine, state.fit.takeIf { state.problem == null }, info.tools)
            info.description?.let { SupportingText(it, style = LocusType.rowSupporting) }
            if (state.problem == null) {
                Text(
                    fitSentence(state.fit, state.installed?.ramBytes ?: state.selectedOption?.ramBytes, state.memory),
                    style = LocusType.supporting,
                    color = when (state.fit) {
                        Fit.TooLarge -> Locus.colors.critical
                        Fit.Tight -> Locus.colors.caution
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun Files(state: DetailUiState, onSelect: (String) -> Unit) {
    CardGroup {
        state.options.forEachIndexed { i, o ->
            if (i > 0) GroupDivider()
            val chosen = o.path == state.selected
            ListRow(
                title = o.quant ?: o.fileName,
                subtitle = listOfNotNull(
                    gb(o.sizeBytes),
                    o.ramBytes?.let { "≈${MemoryGate.gb(it / MemoryGate.GIB)} GB memory" },
                    if (o.recommended) "Recommended" else null,
                    o.problem,
                ).joinToString(" · "),
                subtitleMaxLines = 3,
                enabled = o.usable,
                modifier = Modifier
                    .selectable(selected = chosen, enabled = o.usable, role = Role.RadioButton) { onSelect(o.path) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(o.quant ?: o.fileName, gb(o.sizeBytes), o.fit.spoken, if (o.recommended) "recommended" else null, o.problem).joinToString(", ")
                    },
                leading = { RadioButton(selected = chosen, onClick = null, enabled = o.usable) },
                trailing = { if (o.usable) FitChip(o.fit) },
            )
        }
        if (state.optionsLoading) {
            GroupDivider()
            ListRow(title = "Listing other quantisations", trailing = { InlineSpinner(contentDescription = "Loading") })
        }
        state.optionsProblem?.let {
            GroupDivider()
            ListRow(title = "Other quantisations couldn't be listed", subtitle = it.message, subtitleMaxLines = 3)
        }
        if (state.options.isEmpty() && !state.optionsLoading) ListRow(title = "This repository has no .gguf files")
    }
}

@Composable
private fun DownloadButton(state: DetailUiState, onClick: () -> Unit) {
    val o = state.selectedOption
    val blocked = o == null || !o.usable || o.fit == Fit.TooLarge
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Button(onClick = onClick, enabled = !blocked, modifier = Modifier.fillMaxWidth()) {
            Text(if (o == null) "Pick a file" else "Download ${gb(o.sizeBytes)}")
        }
        when {
            o == null -> Unit
            o.fit == Fit.TooLarge -> SupportingText("Too large for this phone. Pick a smaller file.")
            o.sha256 != null -> SupportingText("Checked against the SHA-256 Hugging Face publishes, then against the model's header, before it can be used.")
        }
    }
}

@Composable
private fun InstalledCard(state: DetailUiState, m: InstalledInfo, actions: DetailActions, onDelete: () -> Unit) {
    val engine = state.engine
    val mine = engine.modelId == m.modelId
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (m.loadCrashed) {
                Banner(
                    text = "Android stopped Locus while it was loading this model last time, most likely because memory ran out. Close other apps and try again, or pick a smaller quantisation.",
                    tone = Tone.Critical,
                    title = "The last load didn't finish",
                    actionsBelow = true,
                )
            }
            Text(OnDeviceFormat.backendLine(engine, m.modelId), style = LocusType.rowSupporting)
            if (mine) {
                OnDeviceFormat.loadedLine(engine)?.let { SupportingText(it) }
                engine.lastTurn?.let { turn -> OnDeviceFormat.turnLine(turn)?.let { SupportingText("Last reply: $it") } }
            }
            engine.benchmark?.takeIf { it.modelId == m.modelId }?.let { r -> OnDeviceFormat.benchmark(r).forEach { Text(it, style = LocusType.mono) } }
            if (engine.benchmarking) SupportingText("Benchmark running… this can take a minute.")
            if (state.isDefault) LocusChip("Default for new chats", tone = Tone.Brand)
            ButtonRow {
                TextButton(onClick = actions.newChat) { Text("Use in a new chat") }
                if (!state.isDefault) TextButton(onClick = actions.setDefault) { Text("Set as default") }
                if (mine && engine.loaded) TextButton(onClick = actions.unload, enabled = !engine.busy) { Text("Unload") }
                if (state.info?.engine == Engine.LiteRt) TextButton(onClick = actions.benchmark, enabled = !engine.benchmarking && !(mine && engine.busy)) { Text("Benchmark") }
                TextButton(onClick = onDelete, enabled = !(mine && engine.busy)) { Text("Delete", color = Locus.colors.critical) }
            }
        }
    }
}

@Composable
private fun Facts(info: ModelInfo, state: DetailUiState) {
    val uri = LocalUriHandler.current
    val m = state.installed
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SectionLabel("Details")
        CardGroup {
            Column(Modifier.padding(vertical = Spacing.xs)) {
                KeyValueRow("Publisher", info.publisher, dense = true)
                KeyValueRow("Licence", info.license ?: "Not stated: check the model card", dense = true)
                info.params?.let { KeyValueRow("Parameters", it, dense = true) }
                (m?.contextTokens ?: info.contextTokens)?.let { ctx ->
                    KeyValueRow("Context", ContextFit.tokensLabel(ctx) + " tokens" + (m?.trainedContext?.takeIf { it > ctx }?.let { " (trained for ${ContextFit.tokensLabel(it)})" } ?: ""), dense = true)
                }
                info.tools?.let { KeyValueRow("Tools", if (it) "Yes: its chat template declares tools" else "No: chat only", dense = true) }
                m?.let {
                    KeyValueRow("Size", gb(it.sizeBytes), dense = true)
                    it.ramBytes?.let { r -> KeyValueRow("Memory", "≈${MemoryGate.gb(r / MemoryGate.GIB)} GB while running", dense = true) }
                    it.architecture?.let { a -> KeyValueRow("Architecture", a, mono = true, dense = true) }
                    it.quant?.let { q -> KeyValueRow("Quantisation", q, mono = true, dense = true) }
                    it.sha256?.let { sha -> KeyValueRow(if (it.verified) "SHA-256 (verified)" else "SHA-256 (unverified)", sha, mono = true, dense = true) }
                }
                (m?.source ?: info.source)?.let { KeyValueRow("Source", it, dense = true) }
            }
            info.licenseUrl?.let { url ->
                GroupDivider()
                ButtonRow { TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(if (info.engine == Engine.LiteRt || info.source != null) "Licence and model card" else "Model card") } }
            }
        }
        if (m != null && !m.hasTemplate) SectionNote("The file has no chat template, so llama.cpp formats the chat in the common ChatML layout. Replies may be poor if the model was trained on another.")
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
