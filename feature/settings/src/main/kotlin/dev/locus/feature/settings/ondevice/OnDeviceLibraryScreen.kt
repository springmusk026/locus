package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipRow
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.TransferProgress
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.ondevice.DeviceMemory
import dev.locus.core.ondevice.gguf.GgufImport
import dev.locus.feature.settings.R
import dev.locus.feature.settings.summaryJoin

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
    val feedback = Locus.feedback
    val openLabel = stringResource(R.string.settings_ondevice_library_open)
    // A finished download is a transient outcome: one message, with Open.
    LaunchedEffect(viewModel) {
        viewModel.finished.collect { f ->
            feedback.show(f.message, if (f.existing || f.verified) Tone.Live else Tone.Caution, openLabel) { nav.model(ModelKey.Installed(f.modelId)) }
        }
    }
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
    val setFilters: (DiscoverFilters) -> Unit = {},
    val pickFile: () -> Unit = {},
    val cancelImport: () -> Unit = {},
    val dismissImport: () -> Unit = {},
    val checkUrl: (String) -> Unit = {},
    val dismissCheck: () -> Unit = {},
    val downloadChecked: () -> Unit = {},
)

private enum class LibrarySheet { Add, Url, LearnMore }

/**
 * The library, laid out as Settings pages are: a LocusTopBar, then SectionLabel + CardGroup of
 * ListRows per section, in a LazyColumn with the screen-edge padding and the navigation bar's inset.
 */
@Composable
internal fun OnDeviceLibrary(state: LibraryUiState, nav: OnDeviceNavigation, actions: LibraryActions) {
    var sheet by rememberSaveable { mutableStateOf<LibrarySheet?>(null) }
    var confirm by remember { mutableStateOf<DiscoverItem?>(null) }
    val get = { item: DiscoverItem -> if (item.fit == Fit.Tight) confirm = item else actions.get(item) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LocusTopBar(
                stringResource(R.string.settings_ondevice_library_title),
                onBack = nav.back,
                actions = {
                    IconButton(onClick = nav.search) { Icon(LocusIcons.Search, contentDescription = stringResource(R.string.settings_ondevice_library_search)) }
                    IconButton(onClick = { sheet = LibrarySheet.Add }) { Icon(LocusIcons.Add, contentDescription = stringResource(R.string.settings_ondevice_library_add)) }
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
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            if (state.import !is GgufImport.Idle) {
                item { SectionLabel(stringResource(R.string.settings_ondevice_library_importing)) }
                item { ImportProgress(state.import, actions) }
            }
            if (state.downloads.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.settings_ondevice_library_downloads)) }
                item {
                    CardGroup {
                        state.downloads.forEachIndexed { i, d ->
                            if (i > 0) GroupDivider(inset = Insets.iconRow)
                            DownloadRows(
                                d,
                                onPause = { actions.pause(d) }, onResume = { actions.resume(d) }, onCancel = { actions.cancel(d) },
                                onUseMobileData = { actions.useMobileData(d) }, onOpen = d.key?.let { k -> { nav.model(k) } },
                            )
                        }
                    }
                }
            }
            if (state.empty) {
                item { SectionLabel(stringResource(R.string.settings_ondevice_library_get_started), action = stringResource(R.string.settings_ondevice_library_learn_more), onAction = { sheet = LibrarySheet.LearnMore }) }
                item { SectionNote(stringResource(R.string.settings_ondevice_library_empty)) }
                item { DiscoverCard(state.picks, nav, get) }
            } else {
                item { SectionLabel(stringResource(R.string.settings_ondevice_library_installed), action = stringResource(R.string.settings_ondevice_library_learn_more), onAction = { sheet = LibrarySheet.LearnMore }) }
                item {
                    CardGroup {
                        state.installed.forEachIndexed { i, m ->
                            if (i > 0) GroupDivider(inset = Insets.iconRow)
                            InstalledRow(m) { nav.model(m.key) }
                        }
                    }
                }
                item { SectionNote(stringResource(R.string.settings_ondevice_library_on_phone, state.installed.size, gb(state.totalBytes)), Modifier.padding(top = Spacing.sm)) }
            }
            item { SectionLabel(stringResource(R.string.settings_ondevice_library_discover)) }
            item { Filters(state.filters, actions.setFilters) }
            // With nothing installed the picks are shown above; Discover doesn't repeat them.
            val discover = if (state.empty) state.discover - state.picks.toSet() else state.discover
            item {
                if (state.discover.isEmpty()) {
                    CardGroup {
                        if (state.discoverAll.isEmpty()) {
                            ListRow(title = stringResource(R.string.settings_ondevice_library_all_title), subtitle = stringResource(R.string.settings_ondevice_library_all_subtitle), onClick = nav.search, leading = { IconTile(LocusIcons.Search) })
                        } else {
                            ListRow(
                                title = stringResource(R.string.settings_ondevice_library_none_title),
                                subtitle = stringResource(R.string.settings_ondevice_library_none_subtitle, state.discoverAll.size),
                                subtitleMaxLines = 2,
                                onClick = { actions.setFilters(DiscoverFilters(fitsOnly = false)) },
                                leading = { IconTile(LocusIcons.Search) },
                            )
                        }
                    }
                } else if (discover.isNotEmpty()) {
                    DiscoverCard(discover, nav, get)
                }
            }
            item { SectionNote(memoryNote(state.memory), Modifier.padding(top = Spacing.sm)) }
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
            title = stringResource(R.string.settings_ondevice_library_download_anyway_title, item.name),
            message = item.warning,
            confirmLabel = stringResource(R.string.settings_ondevice_download_anyway),
            onConfirm = { confirm = null; actions.get(item) },
            onDismiss = { confirm = null },
        )
    }
}

/** "Memory is estimated for this phone's 8 GB. Files are checked before they're used; …" */
@Composable
internal fun memoryNote(memory: DeviceMemory): String =
    if (memory.totalBytes > 0) {
        stringResource(R.string.settings_ondevice_library_memory_note, dev.locus.core.ondevice.MemoryGate.gb(memory.totalBytes / dev.locus.core.ondevice.MemoryGate.GIB))
    } else {
        stringResource(R.string.settings_ondevice_library_memory_unknown_note)
    }

@Composable
private fun DiscoverCard(items: List<DiscoverItem>, nav: OnDeviceNavigation, get: (DiscoverItem) -> Unit) {
    CardGroup {
        items.forEachIndexed { i, item ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            DiscoverRow(item, onOpen = { nav.model(item.key) }, onGet = { get(item) })
        }
    }
}

@Composable
internal fun InstalledRow(m: InstalledItem, onOpen: () -> Unit) {
    ListRow(
        title = m.name,
        subtitle = summaryJoin(gb(m.sizeBytes), m.state),
        subtitleMaxLines = 2,
        titleMaxLines = 3,
        onClick = onOpen,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = spokenRow(m.name + if (m.inUse) ", default" else "", m.sizeBytes, m.fit, m.tools, m.state)
        },
        leading = { EngineTile(m.engine) },
        meta = { ModelChips(m.engine, m.fit, m.tools, isDefault = m.inUse) },
    )
}

@Composable
internal fun DiscoverRow(item: DiscoverItem, onOpen: () -> Unit, onGet: () -> Unit) {
    val canGet = item.fit != Fit.TooLarge
    val getLabel = stringResource(R.string.settings_ondevice_get)
    ListRow(
        title = item.name,
        subtitle = summaryJoin(listOfNotNull(gb(item.sizeBytes), item.quant, item.params, item.license)),
        subtitleMaxLines = 2,
        titleMaxLines = 3,
        onClick = onOpen,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = spokenRow(item.name, item.sizeBytes, item.fit, item.tools, if (canGet) getLabel else null)
        },
        leading = { EngineTile(item.engine) },
        meta = { ModelChips(item.engine, item.fit, item.tools, onGet = if (canGet) onGet else null, getDescription = stringResource(R.string.settings_ondevice_get_name, item.name)) },
    )
}

/** Discover's filters: one scrolling line of filter chips, never wrapping a chip onto its own line. */
@Composable
private fun Filters(filters: DiscoverFilters, onChange: (DiscoverFilters) -> Unit) {
    ChipRow(Modifier.padding(bottom = Spacing.sm)) {
        LocusChip(stringResource(R.string.settings_ondevice_library_filter_fits), variant = ChipVariant.Filter, selected = filters.fitsOnly, onClick = { onChange(filters.copy(fitsOnly = !filters.fitsOnly)) })
        LocusChip(stringResource(R.string.settings_ondevice_tools), variant = ChipVariant.Filter, selected = filters.toolsOnly, onClick = { onChange(filters.copy(toolsOnly = !filters.toolsOnly)) })
        LocusChip(stringResource(R.string.settings_ondevice_library_filter_small), variant = ChipVariant.Filter, selected = filters.smallOnly, onClick = { onChange(filters.copy(smallOnly = !filters.smallOnly)) })
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
            ListRow(
                title = stringResource(R.string.settings_ondevice_library_reading, import.fileName),
                subtitle = stringResource(R.string.settings_ondevice_library_reading_subtitle),
                leading = { IconTile(LocusIcons.Import) },
                trailing = { InlineSpinner(contentDescription = stringResource(R.string.settings_ondevice_library_reading_description)) },
            )
        }
        is GgufImport.Copying -> CardGroup {
            ListRow(title = stringResource(R.string.settings_ondevice_library_copying, import.fileName), titleMaxLines = 2, leading = { IconTile(LocusIcons.Import) })
            TransferProgress(
                fraction = import.total?.let { meterFraction(import.bytes, it) },
                status = dev.locus.core.ondevice.gguf.GgufDownloadText.amount(import.bytes, import.total),
                announcement = announceStep(import.total?.let { meterFraction(import.bytes, it) })
                    ?.let { stringResource(R.string.settings_ondevice_library_copying_at, import.fileName, it) }
                    ?: stringResource(R.string.settings_ondevice_library_copying, import.fileName),
            ) {
                TextButton(onClick = actions.cancelImport) { Text(stringResource(R.string.settings_ondevice_cancel)) }
            }
        }
        is GgufImport.Failed -> Banner(text = import.message, tone = Tone.Critical, title = stringResource(R.string.settings_ondevice_library_import_failed, import.fileName), actionsBelow = true) {
            TextButton(onClick = actions.dismissImport) { Text(stringResource(R.string.settings_ondevice_library_dismiss)) }
            TextButton(onClick = { actions.dismissImport(); actions.pickFile() }) { Text(stringResource(R.string.settings_ondevice_library_pick_another)) }
        }
    }
}

private val previewItems = listOf(
    DiscoverItem(ModelKey.Catalog("qwen3.5-2b"), "Qwen3.5 2B", "unsloth", "A good first model.", Engine.LlamaCpp, 1_280_835_840, 1_900_000_000, Fit.Fits, true, "Apache 2.0", "Q4_K_M", "2B", null),
    DiscoverItem(ModelKey.LiteRt("gemma-4-e2b"), "Gemma 4 E2B", "Google", "Fast.", Engine.LiteRt, 2_588_147_712, null, Fit.Fits, true, "Apache 2.0", null, null, null),
    DiscoverItem(ModelKey.LiteRt("gemma-4-e4b"), "Gemma 4 E4B", "Google", "Smarter but slower.", Engine.LiteRt, 3_659_530_240, null, Fit.Tight, true, "Apache 2.0", null, null, "Meant for 12 GB."),
)

private val previewState = LibraryUiState(
    loading = false,
    installed = listOf(InstalledItem(ModelKey.Installed("gguf:qwen"), "gguf:qwen", "Qwen3.5 0.8B", Engine.LlamaCpp, 532_517_120, true, "Idle", Fit.Fits, inUse = true)),
    downloads = listOf(ActiveDownload(ModelKey.Catalog("smollm3-3b"), "smollm3", "SmolLM3 3B", Engine.LlamaCpp, DownloadUi.Running(800_000_000, 1_915_305_312, 5_000_000, 223))),
    discover = previewItems,
    discoverAll = previewItems,
    memory = DeviceMemory(8L shl 30, null, 4L shl 30, false),
)

@Preview(name = "360 light", widthDp = 360, heightDp = 1100)
@Preview(name = "360 dark", widthDp = 360, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "411 light", widthDp = 411, heightDp = 1100)
@Preview(name = "360 large font", widthDp = 360, heightDp = 1800, fontScale = 2f)
@Composable
private fun LibraryPreview() {
    LocusTheme { OnDeviceLibrary(previewState, OnDeviceNavigation({}, {}, {}), LibraryActions()) }
}

@Preview(name = "Empty", widthDp = 360, heightDp = 900)
@Preview(name = "Empty dark", widthDp = 411, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun EmptyLibraryPreview() {
    LocusTheme {
        OnDeviceLibrary(LibraryUiState(loading = false, discover = previewItems, discoverAll = previewItems), OnDeviceNavigation({}, {}, {}), LibraryActions())
    }
}
