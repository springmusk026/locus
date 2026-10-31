package dev.locus.feature.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.AnalyticsPeriod
import dev.locus.core.data.repository.AnalyticsReport
import dev.locus.core.data.repository.AnalyticsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AnalyticsUiState(
    val period: AnalyticsPeriod = AnalyticsPeriod.Week,
    /** Null until the first report is computed; briefly the previous period's while switching. */
    val report: AnalyticsReport? = null,
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private val period = saved.getStateFlow(KEY_PERIOD, AnalyticsPeriod.Week.name)
        .map { runCatching { AnalyticsPeriod.valueOf(it) }.getOrDefault(AnalyticsPeriod.Week) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reports = period.flatMapLatest { analytics.report(it) }.onStart<AnalyticsReport?> { emit(null) }

    /** The selection moves at once; the previous report stays on screen until the new one is ready. */
    val uiState: StateFlow<AnalyticsUiState> = combine(period, reports) { p, r -> AnalyticsUiState(p, r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun setPeriod(period: AnalyticsPeriod) {
        saved[KEY_PERIOD] = period.name
    }

    private companion object {
        const val KEY_PERIOD = "period"
    }
}
