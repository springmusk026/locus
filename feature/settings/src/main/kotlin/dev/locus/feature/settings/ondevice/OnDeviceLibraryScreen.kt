package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.gguf.GgufImport

/** What the library screen asks to open. */
class OnDeviceNavigation(
    val back: () -> Unit,
    val search: () -> Unit,
    val model: (ModelKey) -> Unit,
)

/** Settings → Models → On-device models: its own route (`OnDeviceModelsRoute` in the app). */
@Composable
fun OnDeviceModelsScreen(nav: OnDeviceNavigation, viewModel: OnDeviceLibraryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // GGUF has no registered MIME type, so any file can be picked; the import reads its header and refuses non-GGUF files.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::import) }
    OnDeviceLibrary(
        state = state,
        nav = nav,
        actions = LibraryActions(
            get = viewModel::get,
            pause = viewModel::pause,
            resume = { viewModel.resume(it) },
            cancel = viewModel::cancel,
            useMobileData = viewModel::useMobileData,
            dismiss = viewModel::dismiss,
            setFilters = viewModel::setFilters,
            pickFile = { picker.launch(arrayOf("*/*")) },
            cancelImport = viewModel::cancelImport,
            dismissImport = viewModel::dismissImport,
            checkUrl = viewModel::checkUrl,
            dismissCheck = viewModel::dismissCheck,
            downloadChecked = { viewModel.downloadChecked() },
        ),
    )
}

/** The library's actions, so the screen below is stateless and previewable. */
class LibraryActions(
    val get: (DiscoverItem) -> Unit = {},
    val pause: (ActiveDownload) -> Unit = {},
    val resume: (ActiveDownload) -> Unit = {},
    val cancel: (ActiveDownload) -> Unit = {},
    val useMobileData: (ActiveDownload) -> Unit = {},
    val dismiss: (ActiveDownload) -> Unit = {},
    val setFilters: (DiscoverFilters) -> Unit = {},
    val pickFile: () -> Unit = {},
    val cancelImport: () -> Unit = {},
    val dismissImport: () -> Unit = {},
    val checkUrl: (String) -> Unit = {},
    val dismissCheck: () -> Unit = {},
    val downloadChecked: () -> Unit = {},
)

private enum class LibrarySheet { Add, Url, LearnMore }

@Composable
internal fun OnDeviceLibrary(state: LibraryUiState, nav: OnDeviceNavigation, actions: LibraryActions) {
    var sheet by rememberSaveable { mutableStateOf<LibrarySheet?>(null) }
    var confirm by remember { mutableStateOf<DiscoverItem?>(null) }
    val get = { item: DiscoverItem -> if (item.fit == Fit.Tight) confirm = item else actions.get(item) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                "On-device models",
                onBack = nav.back,
                actions = {
                    IconButton(onClick = nav.search) { Icon(LocusIcons.Search, contentDescription = "Search Hugging Face") }
                    IconButton(onClick = { sheet = LibrarySheet.Add }) { Icon(LocusIcons.Add, contentDescription = "Add a model from a URL or a file") }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                ButtonRow {
                    SupportingText("Models that run on this phone: offline, private, no key.", Modifier.align(Alignment.CenterVertically))
                    TextButton(onClick = { sheet = LibrarySheet.LearnMore }) { Text("Learn more") }
                }
            }
            if (state.import !is GgufImport.Idle) item { ImportProgress(state.import, actions) }
            if (state.downloads.isNotEmpty()) {
                item { SectionLabel("Downloads") }
                item {
                    CardGroup {
                        state.downloads.forEachIndexed { i, d ->
                            if (i > 0) GroupDivider()
                            Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(d.name, style = LocusType.rowTitle)
                                ModelChips(d.engine, null, null)
                                DownloadControls(
                                    d.name, d.ui,
                                    onPause = { actions.pause(d) }, onResume = { actions.resume(d) }, onCancel = { actions.cancel(d) },
                                    onUseMobileData = { actions.useMobileData(d) }, onDismiss = { actions.dismiss(d) },
                                    onOpen = d.key?.let { k -> { nav.model(k) } },
                                )
                            }
                        }
                    }
                }
            }
            if (state.empty) {
                item { EmptyLibrary(state.picks, onOpen = { nav.model(it.key) }, onGet = get) }
            } else if (state.installed.isNotEmpty()) {
                item { SectionLabel("Installed · ${state.installed.size} · ${gb(state.totalBytes)}") }
                item {
                    CardGroup {
                        state.installed.forEachIndexed { i, m ->
                            if (i > 0) GroupDivider(inset = Insets.iconRow)
                            InstalledRow(m) { nav.model(m.key) }
                        }
                    }
                }
            }
            item { SectionLabel("Discover") }
            item { Filters(state.filters, actions.setFilters) }
            item {
                if (state.discover.isEmpty()) {
                    CardGroup {
                        ListRow(
                            title = if (state.discoverAll.isEmpty()) "You have every recommended model" else "No recommended model matches these filters",
                            subtitle = if (state.discoverAll.isEmpty()) "Search Hugging Face for more." else "${state.discoverAll.size} hidden by the filters.",
                            subtitleMaxLines = 2,
                            leading = { IconTile(LocusIcons.Library) },
                        )
                        if (state.discoverAll.isNotEmpty()) ButtonRow { TextButton(onClick = { actions.setFilters(DiscoverFilters(fitsOnly = false)) }) { Text("Clear filters") } }
                        else ButtonRow { TextButton(onClick = nav.search) { Text("Search Hugging Face") } }
                    }
                } else {
                    CardGroup {
                        state.discover.forEachIndexed { i, item ->
                            if (i > 0) GroupDivider(inset = Insets.iconRow)
                            DiscoverRow(item, onOpen = { nav.model(item.key) }, onGet = { get(item) })
                        }
                    }
                }
            }
            item {
                SectionNote("Memory is estimated for this phone (${dev.locus.core.ondevice.MemoryGate.gb(state.memory.totalBytes / dev.locus.core.ondevice.MemoryGate.GIB)} GB). Recommended files are pinned to one commit and checked against their published SHA-256.")
            }
        }
    }

    when (sheet) {
        LibrarySheet.Add -> AddSheet(
            onUrl = { sheet = LibrarySheet.Url },
            onFile = { sheet = null; actions.pickFile() },
            onSearch = { sheet = null; nav.search() },
            onDismiss = { sheet = null },
        )
        LibrarySheet.Url -> UrlSheet(onCheck = { sheet = null; actions.checkUrl(it) }, onDismiss = { sheet = null })
        LibrarySheet.LearnMore -> LearnMoreSheet(onDismiss = { sheet = null })
        null -> Unit
    }
    OfferSheet(state.check, onDownload = actions.downloadChecked, onDismiss = actions.dismissCheck)
    confirm?.let { item ->
        ConfirmSheet(
            title = "Download ${item.name} anyway?",
            message = item.warning,
            confirmLabel = "Download anyway",
            onConfirm = { confirm = null; actions.get(item) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun EmptyLibrary(picks: List<DiscoverItem>, onOpen: (DiscoverItem) -> Unit, onGet: (DiscoverItem) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
    LocusCard {
        Text("No on-device models yet", style = LocusType.cardTitle)
        SupportingText(
            "Download one to chat without internet or a key. These are good first picks for this phone; Discover below has more.",
            Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
        )
    }
    if (picks.isNotEmpty()) {
        SectionLabel("Top picks")
        CardGroup {
            picks.forEachIndexed { i, item ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                DiscoverRow(item, onOpen = { onOpen(item) }, onGet = { onGet(item) })
            }
        }
    }
}

@Composable
internal fun InstalledRow(m: InstalledItem, onOpen: () -> Unit) {
    ListRow(
        title = m.name,
        subtitle = listOf(gb(m.sizeBytes), m.state).joinToString(" · "),
        subtitleMaxLines = 2,
        titleMaxLines = 2,
        onClick = onOpen,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = spokenRow(m.name + if (m.inUse) ", default" else "", m.sizeBytes, m.fit, m.tools, m.state)
        },
        leading = { IconTile(LocusIcons.Phone) },
        meta = { ModelChips(m.engine, m.fit.takeIf { it != Fit.Fits }, m.tools) },
        trailing = { if (m.inUse) LocusChip("Default", tone = Tone.Brand) },
    )
}

@Composable
internal fun DiscoverRow(item: DiscoverItem, onOpen: () -> Unit, onGet: () -> Unit) {
    val canGet = item.fit != Fit.TooLarge
    ListRow(
        title = item.name,
        subtitle = listOfNotNull(gb(item.sizeBytes), item.quant, item.params, item.license).joinToString(" · "),
        subtitleMaxLines = 2,
        titleMaxLines = 2,
        onClick = onOpen,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = spokenRow(item.name, item.sizeBytes, item.fit, item.tools, if (canGet) "Get" else null)
        },
        leading = { IconTile(LocusIcons.Library) },
        meta = { ModelChips(item.engine, item.fit, item.tools) },
        trailing = { if (canGet) TextButton(onClick = onGet) { Text("Get") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(filters: DiscoverFilters, onChange: (DiscoverFilters) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LocusChip("Fits this phone", variant = ChipVariant.Filter, selected = filters.fitsOnly, onClick = { onChange(filters.copy(fitsOnly = !filters.fitsOnly)) })
        LocusChip("Tools", variant = ChipVariant.Filter, selected = filters.toolsOnly, onClick = { onChange(filters.copy(toolsOnly = !filters.toolsOnly)) })
        LocusChip("Under 1 GB", variant = ChipVariant.Filter, selected = filters.smallOnly, onClick = { onChange(filters.copy(smallOnly = !filters.smallOnly)) })
        Engine.entries.forEach { e ->
            LocusChip(e.label, variant = ChipVariant.Filter, selected = filters.engine == e, onClick = { onChange(filters.copy(engine = if (filters.engine == e) null else e)) })
        }
    }
}

@Composable
private fun ImportProgress(import: GgufImport, actions: LibraryActions) {
    when (import) {
        GgufImport.Idle -> Unit
        is GgufImport.Reading -> CardGroup {
            ListRow(title = "Reading ${import.fileName}", subtitle = "Checking the model's header", leading = { IconTile(LocusIcons.Import) }, trailing = { InlineSpinner(contentDescription = "Reading") })
        }
        is GgufImport.Copying -> CardGroup {
            Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                Text("Copying ${import.fileName}", style = LocusType.rowSupporting)
                Meter(import.total?.let { meterFraction(import.bytes, it) }, tone = Tone.Live, contentDescription = "Copied")
                ButtonRow {
                    SupportingText(import.total?.let { "${import.bytes / 1_000_000} / ${it / 1_000_000} MB" } ?: "${import.bytes / 1_000_000} MB", style = LocusType.mono)
                    TextButton(onClick = actions.cancelImport) { Text("Cancel") }
                }
            }
        }
        is GgufImport.Failed -> Banner(text = import.message, tone = Tone.Critical, title = "Couldn't import ${import.fileName}", actionsBelow = true) {
            TextButton(onClick = actions.dismissImport) { Text("Dismiss") }
            TextButton(onClick = { actions.dismissImport(); actions.pickFile() }) { Text("Pick another file") }
        }
    }
}

private val previewItems = listOf(
    DiscoverItem(ModelKey.Catalog("qwen3.5-2b"), "Qwen3.5 2B", "unsloth", "A good first model.", Engine.LlamaCpp, 1_280_835_840, 1_900_000_000, Fit.Fits, true, "Apache 2.0", "Q4_K_M", "2B", null),
    DiscoverItem(ModelKey.LiteRt("gemma-4-e4b"), "Gemma 4 E4B", "Google", "Smarter but slower.", Engine.LiteRt, 3_659_530_240, null, Fit.Tight, true, "Apache 2.0", null, null, "Meant for 12 GB."),
)

@Preview(name = "Light", widthDp = 360, heightDp = 900)
@Preview(name = "Dark", widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Large font", widthDp = 360, heightDp = 1400, fontScale = 2f)
@Composable
private fun LibraryPreview() {
    LocusTheme {
        OnDeviceLibrary(
            LibraryUiState(
                loading = false,
                installed = listOf(InstalledItem(ModelKey.Installed("gguf:qwen"), "gguf:qwen", "Qwen3.5 0.8B", Engine.LlamaCpp, 532_517_120, true, "Loaded", Fit.Fits, inUse = true)),
                downloads = listOf(ActiveDownload(ModelKey.Catalog("smollm3-3b"), "smollm3", "SmolLM3 3B", Engine.LlamaCpp, DownloadUi.Running(800_000_000, 1_915_305_312, "800 / 1915 MB · 5.0 MB/s · 4 min left"))),
                discover = previewItems,
                discoverAll = previewItems,
                memory = DeviceMemory(8L shl 30, null, 4L shl 30, false),
            ),
            OnDeviceNavigation({}, {}, {}),
            LibraryActions(),
        )
    }
}

@Preview(name = "Empty", widthDp = 360, heightDp = 900)
@Composable
private fun EmptyLibraryPreview() {
    LocusTheme {
        OnDeviceLibrary(LibraryUiState(loading = false, discover = previewItems, discoverAll = previewItems), OnDeviceNavigation({}, {}, {}), LibraryActions())
    }
}
