package dev.locus.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.data.repository.MemoryTidyRepository
import dev.locus.core.data.repository.TidyAvailability
import dev.locus.core.data.repository.TidyFailure
import dev.locus.core.data.repository.TidyOutcome
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.SettingsDestination
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a tidy-up is (docs/design/memory-approval.md). */
sealed interface TidyUiState {
    /** No sheet. */
    data object Closed : TidyUiState

    /** The helper model is reading the notes. */
    data object Working : TidyUiState

    /**
     * The proposals to review, none accepted until the user ticks them. [read] notes were sent, [leftOut]
     * weren't (too much at once), and [dropped] items of the answer matched no note. [applying] while saving.
     */
    data class Review(
        val proposals: List<MemoryTidy.Proposal>,
        val accepted: Set<String> = emptySet(),
        val read: Int = 0,
        val leftOut: Int = 0,
        val dropped: Int = 0,
        val applying: Boolean = false,
    ) : TidyUiState

    data class Failed(val reason: TidyFailure) : TidyUiState
}

/**
 * Library → Memory → Tidy up. Runs only when the user taps it; closing the sheet cancels a request still
 * running and applies nothing. Only the proposals the user accepted are applied.
 */
@HiltViewModel
class MemoryTidyViewModel @Inject constructor(private val tidy: MemoryTidyRepository) : ViewModel() {
    private val _availability = MutableStateFlow<TidyAvailability?>(null)

    /** Whether Tidy up can run; null while it's being checked. */
    val availability: StateFlow<TidyAvailability?> = _availability.asStateFlow()

    private val _state = MutableStateFlow<TidyUiState>(TidyUiState.Closed)
    val state: StateFlow<TidyUiState> = _state.asStateFlow()

    private val _applied = MutableStateFlow<MemoryTidy.Applied?>(null)

    /** The last apply's result, until the screen has said so ([consumeApplied]). */
    val applied: StateFlow<MemoryTidy.Applied?> = _applied.asStateFlow()

    private var job: Job? = null

    /** Checks whether the helper model can be used (the settings may have changed since). */
    fun checkAvailability() {
        viewModelScope.launch { _availability.value = runCatching { tidy.availability() }.getOrDefault(TidyAvailability.HELPER_UNAVAILABLE) }
    }

    /** Asks the helper model for proposals; changes nothing. */
    fun start() {
        job?.cancel()
        _state.value = TidyUiState.Working
        job = viewModelScope.launch {
            _state.value = when (val outcome = tidy.propose()) {
                is TidyOutcome.Failed -> TidyUiState.Failed(outcome.reason)
                is TidyOutcome.Proposals -> TidyUiState.Review(outcome.proposals, read = outcome.snapshot.entries.size, leftOut = outcome.snapshot.leftOut, dropped = outcome.dropped)
            }
        }
    }

    fun toggle(id: String) {
        val review = _state.value as? TidyUiState.Review ?: return
        if (review.applying) return
        _state.value = review.copy(accepted = if (id in review.accepted) review.accepted - id else review.accepted + id)
    }

    /** Closes the sheet: a request still running is cancelled, and nothing is applied. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = TidyUiState.Closed
    }

    /** Applies the accepted proposals, and only those. */
    fun apply() {
        val review = _state.value as? TidyUiState.Review ?: return
        val accepted = review.proposals.filter { it.id in review.accepted }
        if (accepted.isEmpty() || review.applying) return
        _state.value = review.copy(applying = true)
        job = viewModelScope.launch {
            _applied.value = tidy.apply(accepted)
            _state.value = TidyUiState.Closed
        }
    }

    fun consumeApplied() {
        _applied.value = null
    }
}

/** The Tidy up row at the top of Library → Memory, the sheet it opens, and what applying said. */
@Composable
internal fun MemoryTidyEntry(viewModel: MemoryTidyViewModel = hiltViewModel()) {
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val applied by viewModel.applied.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.checkAvailability() }
    MemoryTidyRow(availability, onStart = viewModel::start)
    if (state != TidyUiState.Closed) {
        MemoryTidySheet(state, onToggle = viewModel::toggle, onApply = viewModel::apply, onRetry = viewModel::start, onDismiss = viewModel::cancel)
    }
    val feedback = Locus.feedback
    val done = applied?.let { appliedMessage(it) }
    LaunchedEffect(applied) {
        if (done != null) {
            feedback.show(done, if (applied?.skipped.isNullOrEmpty()) Tone.Live else Tone.Caution)
            viewModel.consumeApplied()
        }
    }
}

@Composable
private fun appliedMessage(result: MemoryTidy.Applied): String {
    val applied = pluralStringResource(R.plurals.library_tidy_applied, result.applied, result.applied)
    if (result.skipped.isEmpty()) return applied
    return stringResource(R.string.library_tidy_applied_skipped, applied, pluralStringResource(R.plurals.library_tidy_skipped, result.skipped.size, result.skipped.size))
}

/** "Tidy up": runs on tap; when the helper model can't be used, says why and links to the page that fixes it. */
@Composable
internal fun MemoryTidyRow(availability: TidyAvailability?, onStart: () -> Unit) {
    val ready = availability == TidyAvailability.READY
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
        CardGroup {
            ListRow(
                title = stringResource(R.string.library_tidy_title),
                subtitle = stringResource(
                    when (availability) {
                        TidyAvailability.NO_MODEL -> R.string.library_tidy_no_model
                        TidyAvailability.HELPER_UNAVAILABLE -> R.string.library_tidy_helper_unavailable
                        else -> R.string.library_tidy_subtitle
                    },
                ),
                subtitleMaxLines = 4,
                onClick = onStart,
                enabled = ready,
                leading = { IconTile(LocusIcons.Sparkle) },
            )
        }
        when (availability) {
            TidyAvailability.NO_MODEL -> SettingsLink(SettingsDestination.Models, Modifier.padding(start = Spacing.lg, top = Spacing.xs))
            TidyAvailability.HELPER_UNAVAILABLE -> SettingsLink(SettingsDestination.Extras, Modifier.padding(start = Spacing.lg, top = Spacing.xs))
            else -> Unit
        }
    }
}

/** The review: each proposal with its reason and, for a merge, the note it would save; Apply makes only the ticked ones. */
@Composable
internal fun MemoryTidySheet(
    state: TidyUiState,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val review = state as? TidyUiState.Review
    val count = review?.accepted?.size ?: 0
    LocusSheet(
        title = stringResource(R.string.library_tidy_sheet_title),
        subtitle = stringResource(R.string.library_tidy_sheet_subtitle),
        onDismiss = onDismiss,
        action = review?.takeIf { it.proposals.isNotEmpty() }?.let {
            if (count == 0) stringResource(R.string.library_tidy_apply_none) else pluralStringResource(R.plurals.library_tidy_apply, count, count)
        },
        actionEnabled = count > 0 && review?.applying != true,
        onAction = onApply,
    ) {
        when (state) {
            TidyUiState.Closed -> Unit
            TidyUiState.Working -> Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                InlineSpinner()
                SupportingText(stringResource(R.string.library_tidy_working), modifier = Modifier.padding(start = Spacing.md))
            }
            is TidyUiState.Failed -> Banner(
                stringResource(
                    when (state.reason) {
                        TidyFailure.UNAVAILABLE -> R.string.library_tidy_failed_unavailable
                        TidyFailure.NO_ANSWER -> R.string.library_tidy_failed_no_answer
                        TidyFailure.MALFORMED -> R.string.library_tidy_failed_malformed
                        TidyFailure.NOTHING -> R.string.library_tidy_failed_nothing
                    },
                ),
                Tone.Critical,
                title = stringResource(R.string.library_tidy_failed_title),
            ) {
                if (state.reason != TidyFailure.NOTHING) TextButton(onClick = onRetry) { Text(stringResource(R.string.library_action_try_again)) }
            }
            is TidyUiState.Review -> TidyReview(state, onToggle)
        }
    }
}

/**
 * The review list, shared by Tidy up and "Suggest memories from imported chats": a checkbox per proposal,
 * nothing ticked at first. [emptyTitle], [emptyBody] and [note] word it for the pass that made it.
 */
@Composable
internal fun TidyReview(
    review: TidyUiState.Review,
    onToggle: (String) -> Unit,
    emptyTitle: String = stringResource(R.string.library_tidy_none_title),
    emptyBody: String = pluralStringResource(R.plurals.library_tidy_none_body, review.read, review.read),
    note: String = stringResource(R.string.library_tidy_review_note),
) {
    if (review.proposals.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(emptyTitle, style = LocusType.cardTitle)
            SupportingText(emptyBody)
        }
    } else {
        SectionNote(note)
        // Project names are said only when there is more than one memory (a new note always goes to general memory).
        val memories = review.proposals.map { it.sources.firstOrNull()?.projectId }
        val manyMemories = memories.distinct().size > 1 || memories.any { it != null }
        CardGroup {
            review.proposals.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(Spacing.sm + Sizes.touchTarget)
                TidyProposalRow(p, p.id in review.accepted, enabled = !review.applying, showMemory = manyMemories, onToggle = { onToggle(p.id) })
            }
        }
    }
    if (review.leftOut > 0) SupportingText(pluralStringResource(R.plurals.library_tidy_left_out, review.leftOut, review.leftOut), modifier = Modifier.padding(top = Spacing.sm))
    if (review.dropped > 0) SupportingText(pluralStringResource(R.plurals.library_tidy_dropped, review.dropped, review.dropped), modifier = Modifier.padding(top = Spacing.xs))
}

@Composable
private fun TidyProposalRow(p: MemoryTidy.Proposal, checked: Boolean, enabled: Boolean, showMemory: Boolean, onToggle: () -> Unit) {
    val names = p.sources.map { "“${it.topic.name.replace('-', ' ')}”" }
    val title = when (p) {
        is MemoryTidy.Proposal.Merge -> stringResource(R.string.library_tidy_merge_title, names.joinToString(stringResource(R.string.library_memory_taint_separator)), p.topic.replace('-', ' '))
        is MemoryTidy.Proposal.Delete -> stringResource(R.string.library_tidy_delete_title, names.single())
        is MemoryTidy.Proposal.New -> stringResource(R.string.library_suggest_new_title, p.topic.replace('-', ' '))
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle).padding(start = Spacing.sm, end = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(Spacing.md))
            Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                Text(title, style = LocusType.rowTitle)
                if (p.reason.isNotBlank()) SupportingText(p.reason, style = LocusType.rowSupporting)
                if (p is MemoryTidy.Proposal.New) SuggestedFrom(p.chat)
                val e = p.sources.firstOrNull()
                if (showMemory && e != null) {
                    SupportingText(
                        if (e.projectId == null) stringResource(R.string.library_tidy_in_general) else stringResource(R.string.library_tidy_in_project, e.projectName ?: stringResource(R.string.library_tidy_project_unnamed)),
                    )
                }
            }
        }
        val saved = when (p) {
            is MemoryTidy.Proposal.Merge -> p.summary to p.content
            is MemoryTidy.Proposal.New -> p.summary to p.content
            is MemoryTidy.Proposal.Delete -> null
        }
        if (saved != null) {
            // The note it saves, in full: what the user accepts is exactly what is written.
            Column(Modifier.fillMaxWidth().padding(start = Spacing.sm + Sizes.touchTarget, end = Spacing.lg, bottom = Spacing.md)) {
                KeyValueRow(stringResource(R.string.library_memory_summary_label), saved.first, dense = true, valueMaxLines = Int.MAX_VALUE)
                CodeSurface(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                    SelectionContainer { Text(saved.second, Modifier.padding(Spacing.sm), style = LocusType.rowSupporting) }
                }
            }
        }
    }
}
