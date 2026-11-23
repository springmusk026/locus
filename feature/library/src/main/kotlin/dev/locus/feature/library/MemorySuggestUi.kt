package dev.locus.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.home.MemorySuggest
import dev.locus.core.agent.home.MemoryTidy
import dev.locus.core.data.repository.MemorySuggestRepository
import dev.locus.core.data.repository.SuggestOutcome
import dev.locus.core.data.repository.SuggestPeriod
import dev.locus.core.data.repository.SuggestScope
import dev.locus.core.data.repository.TidyAvailability
import dev.locus.core.data.repository.TidyFailure
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipRow
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ChatImportSource
import dev.locus.core.model.SettingsDestination
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Where "Suggest memories from imported chats" is (docs/design/import-chats.md). */
sealed interface SuggestUiState {
    /** No sheet. */
    data object Closed : SuggestUiState

    /**
     * Choosing which chats to read: [scope] out of [counts] (imported chats by app), and what that reads
     * ([plan], null while it's being worked out). Nothing is sent yet.
     */
    data class Choosing(val counts: Map<ChatImportSource, Int>, val scope: SuggestScope, val plan: MemorySuggest.Plan? = null) : SuggestUiState

    /** Request [request] of [of] is with the helper model. */
    data class Working(val request: Int, val of: Int) : SuggestUiState

    /**
     * The proposals to review in Tidy up's list ([review], nothing ticked), what was read ([plan]), and how
     * many requests got no usable answer.
     */
    data class Review(val review: TidyUiState.Review, val plan: MemorySuggest.Plan, val failedRequests: Int = 0, val dropped: Int = 0) : SuggestUiState

    data class Failed(val reason: TidyFailure) : SuggestUiState
}

/**
 * Library → Memory → Suggest memories from imported chats. Runs only on the user's tap: open, choose the
 * chats, see the estimate, Suggest. Closing the sheet cancels a request still running and saves nothing;
 * only the proposals the user ticks are saved.
 */
@HiltViewModel
class MemorySuggestViewModel @Inject constructor(private val suggest: MemorySuggestRepository) : ViewModel() {
    private val _availability = MutableStateFlow<TidyAvailability?>(null)

    /** Whether the helper model can be used; null while it's being checked. */
    val availability: StateFlow<TidyAvailability?> = _availability.asStateFlow()

    private val _counts = MutableStateFlow<Map<ChatImportSource, Int>?>(null)

    /** Imported chats by app; null while counting. Empty: the entry isn't shown. */
    val counts: StateFlow<Map<ChatImportSource, Int>?> = _counts.asStateFlow()

    private val _state = MutableStateFlow<SuggestUiState>(SuggestUiState.Closed)
    val state: StateFlow<SuggestUiState> = _state.asStateFlow()

    private val _applied = MutableStateFlow<MemoryTidy.Applied?>(null)

    /** The last save's result, until the screen has said so ([consumeApplied]). */
    val applied: StateFlow<MemoryTidy.Applied?> = _applied.asStateFlow()

    private var job: Job? = null

    /** Checks the helper model and counts the imported chats (both may have changed since). */
    fun refresh() {
        viewModelScope.launch {
            _availability.value = runCatching { suggest.availability() }.getOrDefault(TidyAvailability.HELPER_UNAVAILABLE)
            _counts.value = runCatching { suggest.importedCounts() }.getOrDefault(emptyMap())
        }
    }

    /** Opens the sheet on all imported chats and works out the estimate. Sends nothing. */
    fun open() {
        job?.cancel()
        job = viewModelScope.launch {
            val counts = suggest.importedCounts()
            _counts.value = counts
            choose(SuggestScope(), counts)
        }
    }

    /** Another scope: the estimate is worked out again. */
    fun choose(scope: SuggestScope) {
        val choosing = _state.value as? SuggestUiState.Choosing ?: return
        job?.cancel()
        job = viewModelScope.launch { choose(scope, choosing.counts) }
    }

    private suspend fun choose(scope: SuggestScope, counts: Map<ChatImportSource, Int>) {
        _state.value = SuggestUiState.Choosing(counts, scope)
        val plan = suggest.plan(scope)
        _state.value = SuggestUiState.Choosing(counts, scope, plan)
    }

    /** Sends the chosen chats to the helper model; saves nothing. */
    fun start() {
        val plan = (_state.value as? SuggestUiState.Choosing)?.plan ?: return
        if (plan.read == 0) return
        job?.cancel()
        _state.value = SuggestUiState.Working(1, plan.requests)
        job = viewModelScope.launch {
            val outcome = suggest.propose(plan, onRequest = { i -> _state.value = SuggestUiState.Working(i, plan.requests) })
            _state.value = when (outcome) {
                is SuggestOutcome.Failed -> SuggestUiState.Failed(outcome.reason)
                is SuggestOutcome.Proposals -> SuggestUiState.Review(TidyUiState.Review(outcome.proposals, read = plan.read), plan, outcome.failedRequests, outcome.dropped)
            }
        }
    }

    /** Try again after a failure: back to choosing, with the same chats. */
    fun retry() {
        job?.cancel()
        open()
    }

    fun toggle(id: String) {
        val s = _state.value as? SuggestUiState.Review ?: return
        val review = s.review
        if (review.applying) return
        _state.value = s.copy(review = review.copy(accepted = if (id in review.accepted) review.accepted - id else review.accepted + id))
    }

    /** Closes the sheet: a request still running is cancelled, and nothing is saved. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SuggestUiState.Closed
    }

    /** Saves the ticked proposals, and only those. */
    fun apply() {
        val s = _state.value as? SuggestUiState.Review ?: return
        val accepted = s.review.proposals.filter { it.id in s.review.accepted }
        if (accepted.isEmpty() || s.review.applying) return
        _state.value = s.copy(review = s.review.copy(applying = true))
        job = viewModelScope.launch {
            _applied.value = suggest.apply(accepted)
            _state.value = SuggestUiState.Closed
        }
    }

    fun consumeApplied() {
        _applied.value = null
    }
}

/**
 * The entry in Library → Memory, shown only when there are imported chats, its sheet, and what saving
 * said. [openNow] opens the sheet once (after an import, from its outcome), when the helper model can run.
 */
@Composable
internal fun MemorySuggestEntry(openNow: Boolean = false, modifier: Modifier = Modifier, viewModel: MemorySuggestViewModel = hiltViewModel()) {
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val applied by viewModel.applied.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    var opened by rememberSaveable { mutableStateOf(false) }
    val total = counts?.values?.sum() ?: 0
    LaunchedEffect(openNow, availability, total) {
        if (openNow && !opened && availability == TidyAvailability.READY && total > 0) {
            opened = true
            viewModel.open()
        }
    }
    if (total > 0) MemorySuggestRow(total, availability, onStart = viewModel::open, modifier = modifier)
    if (state != SuggestUiState.Closed) {
        MemorySuggestSheet(
            state,
            onScope = viewModel::choose,
            onSuggest = viewModel::start,
            onToggle = viewModel::toggle,
            onApply = viewModel::apply,
            onRetry = viewModel::retry,
            onDismiss = viewModel::cancel,
        )
    }
    val feedback = Locus.feedback
    val done = applied?.let { suggestSavedMessage(it) }
    LaunchedEffect(applied) {
        if (done != null) {
            feedback.show(done, if (applied?.skipped.isNullOrEmpty()) Tone.Live else Tone.Caution)
            viewModel.consumeApplied()
        }
    }
}

@Composable
private fun suggestSavedMessage(result: MemoryTidy.Applied): String {
    val saved = pluralStringResource(R.plurals.library_suggest_saved, result.applied, result.applied)
    if (result.skipped.isEmpty()) return saved
    return stringResource(R.string.library_tidy_applied_skipped, saved, pluralStringResource(R.plurals.library_suggest_skipped, result.skipped.size, result.skipped.size))
}

/** "Suggest memories from imported chats": on tap; without a usable helper model it says why and links to the fix. */
@Composable
internal fun MemorySuggestRow(chats: Int, availability: TidyAvailability?, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val n = count(chats)
    Column(modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
        CardGroup {
            ListRow(
                title = stringResource(R.string.library_suggest_title),
                subtitle = when (availability) {
                    TidyAvailability.NO_MODEL -> stringResource(R.string.library_tidy_no_model)
                    TidyAvailability.HELPER_UNAVAILABLE -> stringResource(R.string.library_tidy_helper_unavailable)
                    else -> pluralStringResource(R.plurals.library_suggest_subtitle, chats, n)
                },
                titleMaxLines = 3,
                subtitleMaxLines = 4,
                onClick = onStart,
                enabled = availability == TidyAvailability.READY,
                leading = { IconTile(LocusIcons.Chats) },
            )
        }
        when (availability) {
            TidyAvailability.NO_MODEL -> SettingsLink(SettingsDestination.Models, Modifier.padding(start = Spacing.lg, top = Spacing.xs))
            TidyAvailability.HELPER_UNAVAILABLE -> SettingsLink(SettingsDestination.Extras, Modifier.padding(start = Spacing.lg, top = Spacing.xs))
            else -> Unit
        }
    }
}

/** The whole flow in one sheet: choose the chats and see the estimate, the requests, the review. */
@Composable
internal fun MemorySuggestSheet(
    state: SuggestUiState,
    onScope: (SuggestScope) -> Unit,
    onSuggest: () -> Unit,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val review = (state as? SuggestUiState.Review)?.review
    val ticked = review?.accepted?.size ?: 0
    val (action, enabled, onAction) = when (state) {
        is SuggestUiState.Choosing -> Triple(stringResource(R.string.library_suggest_action), (state.plan?.read ?: 0) > 0, onSuggest)
        is SuggestUiState.Review -> if (review!!.proposals.isEmpty()) {
            Triple(null, false, onApply)
        } else {
            Triple(if (ticked == 0) stringResource(R.string.library_suggest_save_none) else pluralStringResource(R.plurals.library_suggest_save, ticked, ticked), ticked > 0 && !review.applying, onApply)
        }
        else -> Triple(null, false, onApply)
    }
    LocusSheet(
        title = stringResource(R.string.library_suggest_sheet_title),
        subtitle = stringResource(R.string.library_suggest_sheet_subtitle),
        onDismiss = onDismiss,
        action = action,
        actionEnabled = enabled,
        onAction = onAction,
    ) {
        when (state) {
            SuggestUiState.Closed -> Unit
            is SuggestUiState.Choosing -> SuggestScopePicker(state, onScope)
            is SuggestUiState.Working -> Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                InlineSpinner()
                SupportingText(
                    if (state.of > 1) stringResource(R.string.library_suggest_working_of, state.request, state.of) else stringResource(R.string.library_suggest_working),
                    modifier = Modifier.padding(start = Spacing.md),
                )
            }
            is SuggestUiState.Failed -> Banner(
                stringResource(
                    when (state.reason) {
                        TidyFailure.UNAVAILABLE -> R.string.library_tidy_failed_unavailable
                        TidyFailure.NO_ANSWER -> R.string.library_suggest_failed_no_answer
                        TidyFailure.MALFORMED -> R.string.library_suggest_failed_malformed
                        TidyFailure.NOTHING -> R.string.library_suggest_failed_nothing
                    },
                ),
                Tone.Critical,
                title = stringResource(R.string.library_suggest_failed_title),
            ) {
                if (state.reason != TidyFailure.NOTHING) TextButton(onClick = onRetry) { Text(stringResource(R.string.library_action_try_again)) }
            }
            is SuggestUiState.Review -> {
                TidyReview(
                    state.review,
                    onToggle,
                    emptyTitle = stringResource(R.string.library_suggest_none_title),
                    emptyBody = pluralStringResource(R.plurals.library_suggest_none_body, state.plan.read, count(state.plan.read)),
                    note = stringResource(R.string.library_suggest_review_note),
                )
                LeftOut(state.plan, Modifier.padding(top = Spacing.sm))
                if (state.failedRequests > 0) {
                    SupportingText(pluralStringResource(R.plurals.library_suggest_failed_requests, state.failedRequests, state.failedRequests), modifier = Modifier.padding(top = Spacing.xs))
                }
                if (state.dropped > 0) {
                    SupportingText(pluralStringResource(R.plurals.library_suggest_dropped, state.dropped, state.dropped), modifier = Modifier.padding(top = Spacing.xs))
                }
            }
        }
    }
}

/** Which imported chats: the app they came from (when there's more than one) and how recent; then the estimate. */
@Composable
private fun SuggestScopePicker(state: SuggestUiState.Choosing, onScope: (SuggestScope) -> Unit) {
    val scope = state.scope
    if (state.counts.size > 1) {
        SectionLabel(stringResource(R.string.library_suggest_from_label))
        ChipRow {
            LocusChip(
                stringResource(R.string.library_suggest_source_chip, stringResource(R.string.library_suggest_source_all), count(state.counts.values.sum())),
                variant = ChipVariant.Filter,
                selected = scope.source == null,
                onClick = { onScope(scope.copy(source = null)) },
            )
            state.counts.entries.sortedByDescending { it.value }.forEach { (source, n) ->
                LocusChip(
                    stringResource(R.string.library_suggest_source_chip, source.displayName, count(n)),
                    variant = ChipVariant.Filter,
                    selected = scope.source == source,
                    onClick = { onScope(scope.copy(source = source)) },
                )
            }
        }
    }
    SectionLabel(stringResource(R.string.library_suggest_period_label))
    val periods = SuggestPeriod.entries
    Segmented(
        periods.map { p ->
            stringResource(
                when (p) {
                    SuggestPeriod.ANY -> R.string.library_suggest_period_any
                    SuggestPeriod.YEAR -> R.string.library_suggest_period_year
                    SuggestPeriod.MONTHS_3 -> R.string.library_suggest_period_months
                },
            )
        },
        selected = periods.indexOf(scope.period),
        onSelect = { onScope(scope.copy(period = periods[it])) },
        modifier = Modifier.fillMaxWidth(),
    )
    SectionLabel(stringResource(R.string.library_suggest_estimate_label))
    SuggestEstimate(state.plan)
    SectionNote(stringResource(R.string.library_suggest_outside_note), Modifier.padding(top = Spacing.sm))
}

/** What Suggest will send: chats, requests and tokens, and what's left out; a spinner while it's worked out. */
@Composable
internal fun SuggestEstimate(plan: MemorySuggest.Plan?) {
    if (plan == null) {
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            InlineSpinner()
            SupportingText(stringResource(R.string.library_suggest_estimating), modifier = Modifier.padding(start = Spacing.md))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (plan.read == 0) {
            Text(stringResource(R.string.library_suggest_estimate_none), style = LocusType.rowTitle)
        } else {
            Text(
                pluralStringResource(R.plurals.library_suggest_estimate_chats, plan.read, count(plan.read), count(plan.inScope)),
                style = LocusType.rowTitle,
            )
            SupportingText(
                pluralStringResource(R.plurals.library_suggest_estimate_requests, plan.requests, plan.requests, count(roundTokens(plan.estimatedTokens))),
            )
        }
        LeftOut(plan)
    }
}

/** The chats a run doesn't read, and why. */
@Composable
private fun LeftOut(plan: MemorySuggest.Plan, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (plan.tooLong > 0) SupportingText(pluralStringResource(R.plurals.library_suggest_too_long, plan.tooLong, count(plan.tooLong)))
        if (plan.overLimit > 0) SupportingText(pluralStringResource(R.plurals.library_suggest_over_limit, plan.overLimit, count(plan.overLimit)))
        if (plan.empty > 0) SupportingText(pluralStringResource(R.plurals.library_suggest_empty, plan.empty, count(plan.empty)))
    }
}

/** Where a suggested note came from, and that it is outside content: "From “Trip to Kyoto” · ChatGPT · 3 Jan 2024". */
@Composable
internal fun SuggestedFrom(chat: MemorySuggest.SourceChat) {
    val locale = LocalResources.current.configuration.locales[0]
    val day = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
        .format(Instant.ofEpochMilli(chat.startedAt).atZone(ZoneId.systemDefault()))
    SupportingText(stringResource(R.string.library_suggest_from_chat, chat.title, chat.sourceName, day), maxLines = 2)
    LocusChip(stringResource(R.string.library_suggest_outside_chip), tone = Tone.Caution, modifier = Modifier.padding(top = Spacing.xs))
}

/** Tokens to two significant figures: an estimate shouldn't look exact. */
private fun roundTokens(n: Int): Int {
    if (n < 100) return n
    var unit = 1
    while (n / unit >= 100) unit *= 10
    return (n + unit / 2) / unit * unit
}

@Composable
private fun count(n: Int): String = NumberFormat.getIntegerInstance(LocalResources.current.configuration.locales[0]).format(n)
