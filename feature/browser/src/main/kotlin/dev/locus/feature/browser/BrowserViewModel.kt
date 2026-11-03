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

    /** Clears the browser's cookies, storage and history; [onDone] says whether it worked. */
    fun clearData(onDone: (cleared: Boolean) -> Unit) = viewModelScope.launch {
        onDone(runCatching { browser.clearData() }.isSuccess)
    }

    fun selectTab(index: Int) = viewModelScope.launch { runCatching { browser.selectTab(index) } }

    fun closeTab(index: Int) = viewModelScope.launch { runCatching { browser.closeTab(index) } }

    /** The user answers the page's confirm() or prompt() themselves (in the browser of the chat on screen). */
    fun answerDialog(accept: Boolean, text: String?) = viewModelScope.launch {
        browser.answerDialog(accept, text)
    }
}
