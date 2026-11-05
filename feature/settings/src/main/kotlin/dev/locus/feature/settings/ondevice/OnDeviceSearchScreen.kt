package dev.locus.feature.settings.ondevice

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipFlow
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.SearchField
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.compactCount
import dev.locus.core.ondevice.gguf.HfHub
import dev.locus.core.ondevice.gguf.HfRepo

/** Search Hugging Face for GGUF models: its own route (`OnDeviceSearchRoute` in the app). */
@Composable
fun OnDeviceSearchScreen(onBack: () -> Unit, onOpen: (ModelKey) -> Unit, query: String? = null, viewModel: OnDeviceSearchViewModel = hiltViewModel()) {
    // Opened with words (a gated model's "Search for …"): search for them straight away.
    LaunchedEffect(query) { if (!query.isNullOrBlank()) viewModel.start(query) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnDeviceSearch(state, onBack, onOpen, viewModel::setQuery, viewModel::search, viewModel::setSort)
}

@Composable
internal fun OnDeviceSearch(
    state: SearchUiState,
    onBack: () -> Unit,
    onOpen: (ModelKey) -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onSort: (HfHub.Sort) -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { LocusTopBar("Search Hugging Face", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xxl),
        ) {
            item {
                SearchField(state.query, onQueryChange = onQuery, placeholder = "Model name, e.g. qwen 2b", requestFocus = state.results is SearchResults.Idle, onSearch = onSearch)
            }
            item {
                // One choice of two: the segmented control, not filter chips.
                Segmented(
                    options = HfHub.Sort.entries.map { it.label },
                    selected = state.sort.ordinal,
                    onSelect = { onSort(HfHub.Sort.entries[it]) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    compact = true,
                )
            }
            item {
                when (val r = state.results) {
                    SearchResults.Idle -> SectionNote(
                        modifier = Modifier.padding(top = Spacing.md),
                        text =
                        "Only models with files Locus can run are listed. Open one to pick a size. Searching sends only your words to huggingface.co.",
                    )
                    is SearchResults.Loading -> CardGroup(Modifier.padding(top = Spacing.md)) {
                        ListRow(title = "Searching for \"${r.query}\"", leading = { IconTile(LocusIcons.Search) }, trailing = { InlineSpinner(contentDescription = "Searching") })
                    }
                    is SearchResults.Failed -> ProblemBanner(r.problem, title = r.problem.title ?: "Search didn't work", onRetry = onSearch, modifier = Modifier.padding(top = Spacing.md))
                    is SearchResults.Results -> if (r.repos.isEmpty()) {
                        CardGroup(Modifier.padding(top = Spacing.md)) {
                            ListRow(
                                title = "No GGUF models match \"${r.query}\"",
                                subtitle = "Try fewer words, or the model's family name: qwen, llama, gemma, smollm",
                                subtitleMaxLines = 3,
                                leading = { IconTile(LocusIcons.Search) },
                            )
                        }
                    } else {
                        CardGroup(Modifier.padding(top = Spacing.md)) {
                            r.repos.forEachIndexed { i, repo ->
                                if (i > 0) GroupDivider(inset = Insets.iconRow)
                                RepoRow(repo) { onOpen(ModelKey.Repo(repo.id)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: HfRepo, onOpen: () -> Unit) {
    val publisher = repo.id.substringBefore('/')
    // "Llama-3.2-1B-Instruct-GGUF" reads as "Llama 3.2 1B Instruct".
    val name = repoDisplayName(repo.id)
    val licence = dev.locus.core.ondevice.gguf.Licences.name(repo.license)
    ListRow(
        title = name,
        // The figure stays with its word at 200% font.
        subtitle = "$publisher\u00A0· ${compactCount(repo.downloads)}\u00A0downloads",
        subtitleMaxLines = 2,
        // A display name has spaces, so a second line breaks between words, never inside one.
        titleMaxLines = 2,
        onClick = onOpen,
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(name, "by $publisher", "${compactCount(repo.downloads)} downloads", licence, if (repo.gated) "needs a Hugging Face login" else null).joinToString(", ")
        },
        leading = { IconTile(LocusIcons.Layers) },
        meta = if (licence == null && !repo.gated) null else ({
            ChipFlow {
                if (repo.gated) LocusChip("Needs login", tone = Tone.Caution)
                licence?.let { LocusChip(it) }
            }
        }),
    )
}

/** A repo's name as people read it: separators as spaces, without the "GGUF" every result has. */
internal fun repoDisplayName(id: String): String =
    dev.locus.core.ondevice.gguf.GgufNames.display(id.substringAfter('/').replace(Regex("[-_.]?gguf$", RegexOption.IGNORE_CASE), ""))

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Large font", widthDp = 360, heightDp = 1000, fontScale = 2f)
@Composable
private fun SearchPreview() {
    LocusTheme {
        OnDeviceSearch(
            SearchUiState(
                "qwen 2b",
                results = SearchResults.Results(
                    "qwen 2b",
                    listOf(
                        HfRepo("unsloth/Qwen3.5-2B-GGUF", 438_879, 169, false, "apache-2.0", null),
                        HfRepo("meta-llama/Llama-3.2-1B-Instruct", 1_000_000, 900, true, "llama3.2", null),
                    ),
                ),
            ),
            {}, {}, {}, {}, {},
        )
    }
}
