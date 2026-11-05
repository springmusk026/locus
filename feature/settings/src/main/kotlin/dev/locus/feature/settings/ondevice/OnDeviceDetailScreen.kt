package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
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
        topBar = { LocusTopBar(info?.name ?: "Model", onBack = onBack) },
    ) { padding ->
        val problem = state.problem
        when {
            // The model couldn't be looked up at all (offline, rate-limited, gated, gone): the screen's own error.
            problem != null && problem.kind == Problem.Kind.Gated -> {
                // A login wall, not a failure: caution, the model's name first, and a way on.
                val name = info?.name?.let(dev.locus.core.ondevice.gguf.GgufNames::display) ?: "This model"
                ErrorState(
                    title = "Needs a Hugging Face login",
                    message = "Only Hugging Face accounts that accepted its licence can download it, and Locus doesn't use an account. " +
                        "Another publisher often has a copy anyone can download.",
                    onRetry = null,
                    modifier = Modifier.padding(padding),
                    icon = LocusIcons.Key,
                    tone = Tone.Caution,
                    action = { Button(onClick = { actions.search(name.removeSuffix(" GGUF")) }) { Text("Search for $name", textAlign = TextAlign.Center) } },
                )
                return@Scaffold
            }
            problem != null -> {
                ErrorState(
                    title = problem.title ?: "Couldn't open this model",
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
                item { SectionLabel("On this phone") }
                item { InstalledActions(state, installed, actions, onDelete = { confirmDelete = true }) }
                item { SectionLabel("Engine") }
                item { EngineFacts(state, installed) }
            } else {
                item { SectionLabel("Files") }
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
                    downloadNote(state)?.let { note -> item { SectionNote(note, Modifier.padding(top = Spacing.sm)) } }
                }
            }
            item { SectionLabel("Details") }
            item { Facts(info, state) }
            if (installed != null && !installed.hasTemplate) {
                item { SectionNote("The file has no chat template, so llama.cpp formats the chat in the common ChatML layout. Replies may be poor if the model was trained on another.", Modifier.padding(top = Spacing.sm)) }
            }
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
                meta = if (o.usable) ({ ChipFlow { FitChip(o.fit) } }) else null,
            )
        }
        if (state.optionsLoading) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(title = "Listing other quantisations", leading = { IconTile(LocusIcons.Layers) }, trailing = { InlineSpinner(contentDescription = "Loading") })
        }
        state.optionsProblem?.let {
            GroupDivider(inset = Insets.iconRow)
            ListRow(title = "Other quantisations couldn't be listed", subtitle = it.message, subtitleMaxLines = 3, leading = { IconTile(LocusIcons.Alert) })
        }
        if (state.options.none { it.usable } && !state.optionsLoading) ListRow(title = "No file here that Locus can run", leading = { IconTile(LocusIcons.Layers) })
    }
}

@Composable
private fun DownloadButton(state: DetailUiState, onClick: () -> Unit) {
    val o = state.selectedOption
    val blocked = o == null || !o.usable || o.fit == Fit.TooLarge
    Button(onClick = onClick, enabled = !blocked, modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        Text(if (o == null) "Pick a file" else "Download ${gb(o.sizeBytes)}")
    }
}

/** "1 more file isn't listed: a vision projector or a split part, which Locus can't run on its own." */
internal fun hiddenFilesNote(count: Int): String =
    if (count == 1) "1 more file isn't listed: a vision projector or a split part, which Locus can't run on its own."
    else "$count more files aren't listed: vision projectors or split parts, which Locus can't run on their own."

/** The line under Download: why it can't, or what happens to the file. */
private fun downloadNote(state: DetailUiState): String? {
    val o = state.selectedOption ?: return null
    return when {
        o.fit == Fit.TooLarge -> "Too large for this phone. Pick a smaller file."
        o.sha256 != null -> "Locus checks the file is exactly the one the publisher released before it's used."
        else -> null
    }
}

@Composable
private fun LoadCrashedBanner() = Banner(
    text = "Android stopped Locus while it was loading this model last time, most likely because memory ran out. Close other apps and try again, or pick a smaller quantisation.",
    tone = Tone.Critical,
    title = "The last load didn't finish",
    actionsBelow = true,
    modifier = Modifier.padding(top = Spacing.md),
)

/** What can be done with the installed model: one card of ListRows with tiles. */
@Composable
private fun InstalledActions(state: DetailUiState, m: InstalledInfo, actions: DetailActions, onDelete: () -> Unit) {
    val engine = state.engine
    val mine = engine.modelId == m.modelId
    CardGroup {
        ListRow(title = "Use in a new chat", onClick = actions.newChat, leading = { IconTile(LocusIcons.Chats) })
        GroupDivider(inset = Insets.iconRow)
        if (state.isDefault) {
            ListRow(title = "Default for new chats", subtitle = "New chats start with this model", subtitleMaxLines = 2, leading = { IconTile(LocusIcons.StarFilled) })
        } else {
            ListRow(title = "Set as default", subtitle = "New chats use this model", subtitleMaxLines = 2, onClick = actions.setDefault, leading = { IconTile(LocusIcons.Star) })
        }
        if (mine && engine.loaded) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = "Unload",
                subtitle = if (engine.busy) "Replying now. Unload when it finishes." else "Frees its memory; the next message loads it again",
                subtitleMaxLines = 2,
                onClick = actions.unload,
                enabled = !engine.busy,
                leading = { IconTile(LocusIcons.Stop) },
            )
        }
        if (state.info?.engine == Engine.LiteRt) {
            GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = if (engine.benchmarking) "Benchmark running…" else "Run benchmark",
                subtitle = "256 tokens in, 128 out; results stay on this phone",
                subtitleMaxLines = 2,
                onClick = actions.benchmark,
                enabled = !engine.benchmarking && !(mine && engine.busy),
                leading = { IconTile(LocusIcons.Gauge) },
                trailing = { if (engine.benchmarking) InlineSpinner(contentDescription = "Benchmark running") },
            )
        }
        GroupDivider(inset = Insets.iconRow)
        ListRow(title = "Delete", subtitle = "Frees ${gb(m.sizeBytes)}", onClick = onDelete, enabled = !(mine && engine.busy), leading = { IconTile(LocusIcons.Trash, tint = Locus.colors.critical) })
    }
}

/** The engine's state for this model: one card of KeyValueRows. */
@Composable
private fun EngineFacts(state: DetailUiState, m: InstalledInfo) {
    val engine = state.engine
    val mine = engine.modelId == m.modelId
    CardGroup {
        KeyValueRow("Engine", OnDeviceFormat.backendLine(engine, m.modelId))
        if (mine) {
            OnDeviceFormat.loadedLine(engine)?.let { KeyValueRow("Loaded", it) }
            engine.lastTurn?.let { turn -> OnDeviceFormat.turnLine(turn)?.let { KeyValueRow("Last reply", it) } }
        }
        engine.benchmark?.takeIf { it.modelId == m.modelId }?.let { r -> KeyValueRow("Benchmark", OnDeviceFormat.benchmark(r).joinToString("\n"), mono = true, valueMaxLines = 6) }
    }
}

/** Facts as KeyValueRows in a CardGroup; the SHA-256 copies on tap; the model card (with its licence) opens in the browser. */
@Composable
private fun Facts(info: ModelInfo, state: DetailUiState) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val feedback = Locus.feedback
    val m = state.installed
    CardGroup {
        KeyValueRow("Publisher", info.publisher)
        KeyValueRow("Licence", dev.locus.core.ondevice.gguf.Licences.name(info.license) ?: "Not stated: check the model card")
        info.params?.let { KeyValueRow("Parameters", it) }
        (m?.contextTokens ?: info.contextTokens)?.let { ctx ->
            KeyValueRow("Context", ContextFit.tokensLabel(ctx) + " tokens" + (m?.trainedContext?.takeIf { it > ctx }?.let { " (trained for ${ContextFit.tokensLabel(it)})" } ?: ""))
        }
        info.tools?.let {
            KeyValueRow(
                "Tools",
                when {
                    it -> "Yes"
                    dev.locus.core.model.SmallModels.tooSmallForTools(info.params) -> "No, chat only. ${dev.locus.core.model.SmallModels.TOO_SMALL}."
                    else -> "No, chat only. Its chat template has no tools."
                },
            )
        }
        m?.let {
            KeyValueRow("Size", gb(it.sizeBytes))
            it.ramBytes?.let { r -> KeyValueRow("Memory", "≈${MemoryGate.gb(r / MemoryGate.GIB)} GB while running") }
            it.quant?.let { q -> KeyValueRow("Quantisation", q, mono = true) }
            it.sha256?.let { sha ->
                KeyValueRow(
                    if (it.verified) "SHA-256, verified" else "SHA-256, unverified",
                    sha,
                    mono = true,
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("SHA-256", sha)))
                            feedback.show("SHA-256 copied")
                        }
                    },
                )
            }
        }
        (m?.source ?: info.source)?.let { KeyValueRow("Source", it, mono = true) }
        // One row anatomy per card: the model card is a value that opens, like the SHA-256 above.
        info.licenseUrl?.let { url -> KeyValueRow("Model card", url.removePrefix("https://"), onClick = { runCatching { uri.openUri(url) } }) }
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
