package dev.locus.feature.settings.ondevice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.ondevice.gguf.HfHub
import dev.locus.core.ondevice.gguf.HfRepo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchResults {
    data object Idle : SearchResults
    data class Loading(val query: String) : SearchResults
    data class Results(val query: String, val repos: List<HfRepo>) : SearchResults
    data class Failed(val query: String, val problem: Problem) : SearchResults
}

data class SearchUiState(val query: String = "", val sort: HfHub.Sort = HfHub.Sort.Downloads, val results: SearchResults = SearchResults.Idle)

/** Search Hugging Face for GGUF repos; opening one goes to its detail ([OnDeviceDetailViewModel]). */
@HiltViewModel
class OnDeviceSearchViewModel @Inject constructor(private val actions: OnDeviceActions) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** Opens with [query] searched, once (a later recomposition doesn't search again). */
    fun start(query: String) {
        if (_state.value.query == query && _state.value.results !is SearchResults.Idle) return
        setQuery(query)
        search()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        if (query.isBlank()) {
            job?.cancel()
            _state.value = _state.value.copy(results = SearchResults.Idle)
        }
    }

    fun setSort(sort: HfHub.Sort) {
        if (sort == _state.value.sort) return
        _state.value = _state.value.copy(sort = sort)
        if (_state.value.results !is SearchResults.Idle) search()
    }

    fun search() {
        val q = _state.value.query.trim()
        job?.cancel()
        if (q.isEmpty()) return
        _state.value = _state.value.copy(results = SearchResults.Loading(q))
        val sort = _state.value.sort
        job = viewModelScope.launch {
            val results = try {
                SearchResults.Results(q, actions.downloads.sources.search(q, sort))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SearchResults.Failed(q, Problem.of(e))
            }
            _state.value = _state.value.copy(results = results)
        }
    }
}
