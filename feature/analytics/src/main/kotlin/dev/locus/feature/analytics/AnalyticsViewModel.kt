package dev.locus.feature.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.AnalyticsPeriod
import dev.locus.core.data.repository.AnalyticsReport
import dev.locus.core.data.repository.AnalyticsRepository
import dev.locus.core.data.repository.ModelDirectory
import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.ModelCatalogIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** The models.dev catalogue, when it's on, for models' display names (as the model picker shows them). */
    val catalog: ModelCatalogIndex? = null,
    /** Each provider's model list, for the names providers give their models (as the model picker shows them). */
    val listed: Map<String, ModelList> = emptyMap(),
    /** Where "Turn on prices" is: fetching the models.dev prices, or failed to. */
    val prices: PricesSetup = PricesSetup.Idle,
)

/** The spend card's "Turn on prices" action. */
enum class PricesSetup {
    /** Nothing asked, or it worked (the report then has prices). */
    Idle,

    /** Turning prices on and fetching them. */
    Fetching,

    /** They couldn't be fetched (offline, models.dev down). Prices stay on; Try again fetches again. */
    Failed,
}

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analytics: AnalyticsRepository,
    private val saved: SavedStateHandle,
    private val models: ModelDirectory,
) : ViewModel() {

    init {
        // Model names, as the picker shows them: the providers' own lists (where stale) and the catalogue.
        models.prepare()
    }

    private val period = saved.getStateFlow(KEY_PERIOD, AnalyticsPeriod.Week.name)
        .map { runCatching { AnalyticsPeriod.valueOf(it) }.getOrDefault(AnalyticsPeriod.Week) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val reports = period.flatMapLatest { analytics.report(it) }.onStart<AnalyticsReport?> { emit(null) }

    private val prices = MutableStateFlow(PricesSetup.Idle)

    /** The selection moves at once; the previous report stays on screen until the new one is ready. */
    val uiState: StateFlow<AnalyticsUiState> = combine(period, reports, models.catalog, models.lists, prices) { p, r, c, l, pr -> AnalyticsUiState(p, r, c, l, pr) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun setPeriod(period: AnalyticsPeriod) {
        saved[KEY_PERIOD] = period.name
    }

    /**
     * The spend card's "Turn on prices" (and its Try again): turns on the models.dev catalogue, the same
     * setting as Settings → Models → Model details, and fetches it. The report re-prices itself once it
     * arrives; until then the card shows that it's fetching, and afterwards whether it failed.
     */
    fun turnOnPrices(): Job? {
        if (prices.value == PricesSetup.Fetching) return null
        prices.value = PricesSetup.Fetching
        return viewModelScope.launch {
            val ok = try {
                models.turnOnCatalog()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            prices.value = if (ok) PricesSetup.Idle else PricesSetup.Failed
        }
    }

    private companion object {
        const val KEY_PERIOD = "period"
    }
}
