package dev.locus.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.browser.BrowserState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(val browser: AgentBrowser) : ViewModel() {
    val uiState: StateFlow<BrowserState> = browser.state

    fun done() = browser.completeHandoff()

    fun clearData() = viewModelScope.launch { browser.clearData() }
}
