package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
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
import dev.locus.core.designsystem.SearchField
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.compactCount
import dev.locus.core.designsystem.meterFraction
import dev.locus.core.ondevice.MemoryGate
import dev.locus.core.ondevice.gguf.GgufCatalogEntry
import dev.locus.core.ondevice.gguf.GgufDownload
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufDownloadText
import dev.locus.core.ondevice.gguf.GgufImport
import dev.locus.core.ondevice.gguf.GgufOffer
import dev.locus.core.ondevice.gguf.GgufSources
import dev.locus.core.ondevice.gguf.HfFile
import dev.locus.core.ondevice.gguf.HfFileKind
import dev.locus.core.ondevice.gguf.HfHub
import dev.locus.core.ondevice.gguf.HfRepoInfo

/**
 * "Get a GGUF model": four ways in, as segments. Recommended (a pinned, checked list), Search
 * (Hugging Face), URL (a pasted link) and File (the system file picker). Downloads from Search and
 * URL show above the segments, so they stay in view whichever segment is open.
 */
@Composable
internal fun GgufSources(
    import: GgufImport,
    onPickFile: () -> Unit,
    actions: GgufActions,
    onOpenModel: (String) -> Unit,
    viewModel: GgufSourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(GgufSourceTab.Recommended) }
    // An import in progress or failed is shown where it was started.
    LaunchedEffect(import is GgufImport.Idle) { if (import !is GgufImport.Idle) tab = GgufSourceTab.File }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (state.downloads.isNotEmpty()) {
            CardGroup {
                state.downloads.forEachIndexed { i, d ->
                    if (i > 0) GroupDivider()
                    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(d.offer.name, style = LocusType.rowTitle, maxLines = 1)
                        SupportingText(offerLine(d.offer), maxLines = 2)
                        GgufDownloadStatus(d, state.inUse, viewModel, onOpenModel)
                    }
                }
            }
        }
        Segmented(
            options = GgufSourceTab.entries.map { it.label },
            selected = tab.ordinal,
            onSelect = { tab = GgufSourceTab.entries[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        when (tab) {
            GgufSourceTab.Recommended -> RecommendedList(state, viewModel, onOpenModel)
            GgufSourceTab.Search -> SearchPane(state, viewModel)
            GgufSourceTab.Url -> UrlPane(viewModel)
            GgufSourceTab.File -> CardGroup {
                GgufImportRow(import, onPickFile, actions)
                if (import is GgufImport.Idle) {
                    SupportingText(
                        "A .gguf file already on the phone or in a cloud drive. It's copied into Locus, and its header is checked first.",
                        Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md),
                    )
                }
            }
        }
    }

    GgufRepoSheet(state.repo, viewModel)
    GgufCheckSheet(state.check, viewModel)
}

@Composable
private fun RecommendedList(state: GgufSourcesUiState, vm: GgufSourcesViewModel, onOpenModel: (String) -> Unit) {
    var details by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<GgufCatalogRow?>(null) }
    CardGroup {
        state.catalog.forEachIndexed { i, row ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            ListRow(
                title = row.entry.name,
                subtitle = catalogLine(row),
                subtitleMaxLines = 2,
                onClick = { details = row.entry.id },
                leading = { IconTile(LocusIcons.Library) },
                trailing = {
                    when {
                        row.installedId != null && row.installedId == state.inUse -> LocusChip("In use", tone = Tone.Brand)
                        row.installedId != null -> TextButton(onClick = { vm.use(row.installedId) }) { Text("Use") }
                        row.download != null -> Unit
                        row.tooLarge -> LocusChip("Too large", tone = Tone.Critical)
                        else -> TextButton(onClick = { if (row.ramWarning != null) confirm = row else vm.downloadRecommended(row.entry) }) { Text("Get") }
                    }
                },
            )
            row.download?.let { d ->
                Column(Modifier.padding(start = Insets.iconRow, end = Spacing.lg, bottom = Spacing.md)) {
                    GgufDownloadStatus(d, state.inUse, vm, onOpenModel)
                }
            }
        }
    }
    SupportingText(
        "Each file is pinned to one commit on Hugging Face and checked against its published SHA-256. Memory is estimated for this phone " +
            "(${MemoryGate.gb(state.memory.totalBytes / MemoryGate.GIB)} GB).",
        Modifier.padding(horizontal = Spacing.xs),
    )

    details?.let { id ->
        val row = state.catalog.firstOrNull { it.entry.id == id }
        if (row == null) details = null else CatalogSheet(row, state.inUse, vm, onOpenModel, onDismiss = { details = null }, onDownload = {
            if (row.ramWarning != null) confirm = row else vm.downloadRecommended(row.entry)
        })
    }
    confirm?.let { row ->
        ConfirmSheet(
            title = "Download ${row.entry.name} anyway?",
            message = row.ramWarning,
            confirmLabel = "Download anyway",
            onConfirm = { confirm = null; vm.downloadRecommended(row.entry) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun CatalogSheet(row: GgufCatalogRow, inUse: String?, vm: GgufSourcesViewModel, onOpenModel: (String) -> Unit, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val e = row.entry
    val canGet = row.installedId == null && row.download == null && !row.tooLarge
    LocusSheet(
        title = e.name,
        subtitle = e.description,
        onDismiss = onDismiss,
        action = if (canGet) "Download" else null,
        onAction = if (canGet) onDownload else null,
    ) {
        if (row.tooLarge || row.ramWarning != null) {
            Banner(text = row.ramWarning.orEmpty(), tone = if (row.tooLarge) Tone.Critical else Tone.Caution, title = if (row.tooLarge) "Too large for this phone" else "Short on memory")
        }
        EntryDetails(e, row.ramBytes)
        row.download?.let { GgufDownloadStatus(it, inUse, vm, onOpenModel) }
        row.installedId?.let { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportingText("Downloaded and verified.", Modifier.weight(1f))
                TextButton(onClick = { onDismiss(); onOpenModel(id) }) { Text("Details") }
                if (id != inUse) TextButton(onClick = { vm.use(id) }) { Text("Use") } else LocusChip("In use", tone = Tone.Brand)
            }
        }
    }
}

@Composable
private fun EntryDetails(e: GgufCatalogEntry, ramBytes: Long) {
    Column {
        KeyValueRow("Download", "${gb(e.sizeBytes)} GB", dense = true)
        KeyValueRow("Memory", "≈${MemoryGate.gb(ramBytes / MemoryGate.GIB)} GB while running", dense = true)
        e.sizeLabel?.let { KeyValueRow("Parameters", it, dense = true) }
        e.quant?.let { KeyValueRow("Quantisation", it, mono = true, dense = true) }
        KeyValueRow("Tools", if (e.tools) "Yes: its chat template declares tools" else "No: chat only", dense = true)
        KeyValueRow("Licence", e.licenseName, dense = true)
        KeyValueRow("Source", "${e.repo} at ${e.revision.take(7)}", dense = true)
        KeyValueRow("File", e.file, mono = true, dense = true)
        KeyValueRow("SHA-256", e.sha256, mono = true, dense = true)
    }
    e.licenseUrl?.let { SupportingText("Licence text: $it") }
}

@Composable
private fun SearchPane(state: GgufSourcesUiState, vm: GgufSourcesViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SearchField(query, onQueryChange = { query = it; if (it.isBlank()) vm.search("") }, placeholder = "Search Hugging Face, e.g. qwen 2b", onSearch = { vm.search(query) })
        Segmented(
            options = HfHub.Sort.entries.map { it.label },
            selected = state.sort.ordinal,
            onSelect = { vm.setSort(HfHub.Sort.entries[it]) },
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state.search) {
            GgufSearch.Idle -> SupportingText(
                "Type a model name and press search. Only repositories with GGUF files are listed; open one to pick a file and quantisation.",
                Modifier.padding(horizontal = Spacing.xs),
            )
            is GgufSearch.Loading -> CardGroup {
                ListRow(title = "Searching for \"${s.query}\"", leading = { IconTile(LocusIcons.Search) }, trailing = { InlineSpinner(contentDescription = "Searching") })
            }
            is GgufSearch.Failed -> ProblemBanner(s.problem, title = "Search didn't work") { vm.search(s.query) }
            is GgufSearch.Results -> if (s.repos.isEmpty()) {
                CardGroup {
                    ListRow(
                        title = "No GGUF models match \"${s.query}\"",
                        subtitle = "Try fewer words, or the model's family name (qwen, llama, gemma, smollm).",
                        subtitleMaxLines = 2,
                        leading = { IconTile(LocusIcons.Search) },
                    )
                }
            } else {
                CardGroup {
                    s.repos.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        ListRow(
                            title = r.id.substringAfter('/'),
                            subtitle = listOfNotNull(r.id.substringBefore('/'), "${compactCount(r.downloads)} downloads", r.license).joinToString(" · "),
                            onClick = { vm.openRepo(r.id) },
                            leading = { IconTile(LocusIcons.Globe) },
                            trailing = { if (r.gated) LocusChip("Needs login", tone = Tone.Caution) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlPane(vm: GgufSourcesViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    val warning = urlWarning(url)
    CardGroup {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("File address (https://…)") },
                singleLine = true,
                isError = warning != null,
                supportingText = warning?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank() && warning == null) vm.checkUrl(url) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportingText(
                    "A huggingface.co file link (…/resolve/… or …/blob/…) is pinned to its commit and checked against Hugging Face's SHA-256. Any other https address downloads unverified.",
                    Modifier.weight(1f),
                )
                TextButton(onClick = { vm.checkUrl(url) }, enabled = url.isNotBlank() && warning == null) { Text("Check") }
            }
        }
    }
}

/** What's wrong with a pasted address before it's checked online; null when it can be tried. */
internal fun urlWarning(url: String): String? {
    val u = url.trim()
    if (u.isEmpty()) return null
    return when {
        u.startsWith("http://", ignoreCase = true) -> "Only https:// addresses: a plain http download could be changed on the way."
        !u.startsWith("https://", ignoreCase = true) -> "Paste a full address starting with https://."
        else -> null
    }
}

@Composable
private fun GgufRepoSheet(view: GgufRepoView?, vm: GgufSourcesViewModel) {
    view ?: return
    val info = (view as? GgufRepoView.Files)?.info
    LocusSheet(
        title = view.repo,
        subtitle = info?.let { listOfNotNull(it.license?.let { l -> "Licence: $l" }, it.sha?.let { s -> "commit ${s.take(7)}" }).joinToString(" · ") },
        onDismiss = vm::closeRepo,
    ) {
        when (view) {
            is GgufRepoView.Loading -> ListRow(title = "Listing its files", leading = { IconTile(LocusIcons.Folder) }, trailing = { InlineSpinner(contentDescription = "Loading") })
            is GgufRepoView.Failed -> ProblemBanner(view.problem, title = "Couldn't open ${view.repo}") { vm.openRepo(view.repo) }
            is GgufRepoView.Files -> if (view.files.isEmpty()) {
                SupportingText("This repository has no .gguf files.")
            } else {
                SupportingText("Pick a file. Q4_K_M is a good balance of size and quality; smaller files run faster and need less memory.")
                Column {
                    view.files.forEachIndexed { i, f ->
                        if (i > 0) GroupDivider()
                        FileRow(view.info, f, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(info: HfRepoInfo, f: HfFile, vm: GgufSourcesViewModel) {
    val problem = GgufSources.kindProblem(f.kind)
    ListRow(
        title = f.fileName,
        subtitle = listOfNotNull("${gb(f.sizeBytes)} GB", f.quant, if (f.sha256 == null && problem == null) "no published checksum" else null).joinToString(" · "),
        onClick = { vm.checkFile(info, f) },
        enabled = f.usable,
        trailing = {
            when (val k = f.kind) {
                is HfFileKind.Split -> LocusChip("Part ${k.part} of ${k.count}", tone = Tone.Caution)
                HfFileKind.Projector -> LocusChip("Vision projector")
                HfFileKind.Draft -> LocusChip("Draft head")
                HfFileKind.Other -> LocusChip("Not a model")
                HfFileKind.Model -> Unit
            }
        },
    )
}

/** The step before a Search or URL download: what the file is, where it's from, and whether it fits. */
@Composable
private fun GgufCheckSheet(check: GgufCheck, vm: GgufSourcesViewModel) {
    when (check) {
        GgufCheck.Idle -> Unit
        is GgufCheck.Checking -> LocusSheet(title = "Checking the file", subtitle = check.label, onDismiss = vm::dismissCheck) {
            ListRow(title = "Reading the model's header", subtitle = "Its first few megabytes: what it is, its tools and the memory it needs.", subtitleMaxLines = 2, trailing = { InlineSpinner(contentDescription = "Checking") })
        }
        is GgufCheck.Failed -> LocusSheet(title = "Can't download this", subtitle = check.label, onDismiss = vm::dismissCheck) {
            ProblemBanner(check.problem, title = null, onRetry = null)
        }
        is GgufCheck.Ready -> {
            val o = check.offer
            val blocked = o.problem != null || check.tooLarge
            LocusSheet(
                title = o.name,
                subtitle = o.source,
                onDismiss = vm::dismissCheck,
                action = if (blocked) null else if (o.verified) "Download" else "Download anyway",
                actionTone = if (o.verified) Tone.Brand else Tone.Critical,
                onAction = if (blocked) null else ({ vm.downloadChecked() }),
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
                if (check.tooLarge || check.ramWarning != null) {
                    Banner(text = check.ramWarning.orEmpty(), tone = if (check.tooLarge) Tone.Critical else Tone.Caution, title = if (check.tooLarge) "Too large for this phone" else "Short on memory")
                }
                Column {
                    KeyValueRow("Download", o.sizeBytes?.let { "${gb(it)} GB" } ?: "Size not given", dense = true)
                    o.ramBytes?.let { KeyValueRow("Memory", "≈${MemoryGate.gb(it / MemoryGate.GIB)} GB while running", dense = true) }
                    o.architecture?.let { KeyValueRow("Architecture", it, mono = true, dense = true) }
                    o.sizeLabel?.let { KeyValueRow("Parameters", it, dense = true) }
                    o.quant?.let { KeyValueRow("Quantisation", it, mono = true, dense = true) }
                    o.tools?.let { KeyValueRow("Tools", if (it) "Yes: its chat template declares tools" else "No: chat only", dense = true) }
                    KeyValueRow("Licence", o.license ?: "Not stated: check the source", dense = true)
                    KeyValueRow("File", o.fileName, mono = true, dense = true)
                    KeyValueRow("SHA-256", o.sha256 ?: "Unknown until downloaded", mono = o.sha256 != null, dense = true)
                }
            }
        }
    }
}

/** A download's state with what can be done about it. */
@Composable
private fun GgufDownloadStatus(d: GgufDownload, inUse: String?, vm: GgufSourcesViewModel, onOpenModel: (String) -> Unit) {
    var confirmCancel by remember { mutableStateOf(false) }
    when (val s = d.state) {
        is GgufDownloadState.Queued -> Column {
            Meter(s.total?.let { meterFraction(s.bytes, it) }, tone = Tone.Live, contentDescription = "Downloaded")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportingText(if (s.wifiOnly) "Waiting for Wi-Fi" else "Waiting to start", Modifier.weight(1f))
                if (s.wifiOnly) TextButton(onClick = { vm.useAnyNetwork(d.id) }) { Text("Use mobile data") }
                TextButton(onClick = { vm.pause(d.id) }) { Text("Pause") }
            }
        }
        is GgufDownloadState.Running -> Column {
            Meter(s.total?.let { meterFraction(s.bytes, it) }, tone = Tone.Live, contentDescription = "Downloaded")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportingText(GgufDownloadText.progress(s), Modifier.weight(1f), style = LocusType.mono)
                TextButton(onClick = { vm.pause(d.id) }) { Text("Pause") }
                TextButton(onClick = { confirmCancel = true }) { Text("Cancel") }
            }
        }
        is GgufDownloadState.Paused -> Column {
            Meter(s.total?.let { meterFraction(s.bytes, it) }, tone = Tone.Neutral, contentDescription = "Downloaded")
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportingText(
                    "Paused at " + (s.total?.let { "${s.bytes / 1_000_000} / ${it / 1_000_000} MB" } ?: "${s.bytes / 1_000_000} MB"),
                    Modifier.weight(1f),
                    style = LocusType.mono,
                )
                TextButton(onClick = { vm.resume(d.id) }) { Text("Resume") }
                TextButton(onClick = { confirmCancel = true }) { Text("Cancel") }
            }
        }
        GgufDownloadState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            InlineSpinner(contentDescription = "Checking")
            SupportingText("Checking the SHA-256 and the model's header…")
        }
        is GgufDownloadState.Failed -> Banner(text = s.message, tone = Tone.Critical, title = "Download failed", actionsBelow = true) {
            TextButton(onClick = { vm.cancel(d.id) }) { Text("Remove") }
            if (s.retryable) TextButton(onClick = { vm.resume(d.id) }) { Text(if (s.bytes > 0) "Resume" else "Retry") }
        }
        is GgufDownloadState.Installed -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                if (s.verified) "Installed. SHA-256 matches Hugging Face's." else "Installed. Unverified source; its SHA-256 is below.",
                style = LocusType.supporting,
                color = if (s.verified) Locus.colors.live else Locus.colors.caution,
            )
            SupportingText(s.sha256, style = LocusType.mono)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                TextButton(onClick = { vm.dismiss(d.id) }) { Text("Done") }
                TextButton(onClick = { onOpenModel(s.modelId) }) { Text("Details") }
                if (s.modelId == inUse) LocusChip("In use", tone = Tone.Brand) else TextButton(onClick = { vm.use(s.modelId) }) { Text("Use") }
            }
        }
    }
    if (confirmCancel) {
        ConfirmSheet(
            title = "Cancel this download?",
            message = "Deletes what's downloaded of ${d.offer.name}. Pause keeps it to resume later.",
            confirmLabel = "Cancel download",
            dismissLabel = "Keep",
            tone = Tone.Critical,
            onConfirm = { confirmCancel = false; vm.cancel(d.id) },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun ProblemBanner(problem: GgufProblem, title: String?, onRetry: (() -> Unit)?) {
    val tone = when (problem.kind) {
        GgufProblem.Kind.Offline, GgufProblem.Kind.RateLimited, GgufProblem.Kind.Gated -> Tone.Caution
        GgufProblem.Kind.Other -> Tone.Critical
    }
    val heading = title ?: when (problem.kind) {
        GgufProblem.Kind.Offline -> "Offline"
        GgufProblem.Kind.RateLimited -> "Too many requests"
        GgufProblem.Kind.Gated -> "Needs a Hugging Face login"
        GgufProblem.Kind.Other -> null
    }
    Banner(text = problem.message, tone = tone, title = heading, actionsBelow = true) {
        if (onRetry != null && problem.kind != GgufProblem.Kind.Gated) TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/** "1.3 GB · Q4_K_M · 2B · ≈1.9 GB memory · Apache 2.0 · tools" */
private fun catalogLine(row: GgufCatalogRow): String = listOfNotNull(
    "${gb(row.entry.sizeBytes)} GB",
    row.entry.quant,
    row.entry.sizeLabel,
    "≈${MemoryGate.gb(row.ramBytes / MemoryGate.GIB)} GB memory",
    row.entry.licenseName.substringBefore(" ("),
    if (row.entry.tools) "tools" else "chat only",
).joinToString(" · ")

private fun offerLine(o: GgufOffer): String = listOfNotNull(
    o.sizeBytes?.let { "${gb(it)} GB" },
    o.quant,
    o.source,
    if (o.verified) "checked against Hugging Face" else "unverified source",
).joinToString(" · ")

private fun gb(bytes: Long) = "%.1f".format(java.util.Locale.ROOT, bytes / 1e9)
