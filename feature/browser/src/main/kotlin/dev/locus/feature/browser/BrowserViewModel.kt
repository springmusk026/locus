package dev.locus.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.browser.BrowserState
import dev.locus.core.browser.LoginOffer
import dev.locus.core.data.agent.AgentRunner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the full-screen browser shows: the page of the chat on screen ([page]), whether that chat's agent
 * is working ([agentRunning], which makes the page watch-only until the user takes over), and whether
 * this is the user's own browsing with no chat behind it ([standalone]).
 */
data class BrowserUi(
    val page: BrowserState = BrowserState(),
    val agentRunning: Boolean = false,
    val standalone: Boolean = false,
    /** "Save this login?" after the user signed in themselves; null when nothing is offered. */
    val loginOffer: LoginOffer? = null,
) {
    /** Locus is acting on this page and the user hasn't taken it: they watch, and Take over is offered. */
    val agentDriving: Boolean get() = agentRunning && !page.userControl && page.handoffReason == null

    /** The user may use the address bar, back, forward and reload. */
    val userDriving: Boolean get() = !agentDriving
}

@HiltViewModel
class BrowserViewModel @Inject constructor(val browser: AgentBrowser, private val runner: AgentRunner) : ViewModel() {

    /** Whose browser was on screen before the user's own browsing was shown; given back when this screen goes. */
    private var before: String? = null
    private var showingStandalone = false

    val uiState: StateFlow<BrowserUi> = combine(browser.state, browser.focusedKey, runner.states, browser.loginOffer) { page, key, runs, offer ->
        val standalone = key == AgentBrowser.STANDALONE
        BrowserUi(page, agentRunning = !standalone && key != null && runs[key]?.running == true, standalone = standalone, loginOffer = offer)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserUi(browser.state.value))

    /** Opened from the Workspace: the user's own browsing, which belongs to no chat. */
    fun showStandalone() {
        if (showingStandalone) return
        showingStandalone = true
        before = browser.focusStandalone()
    }

    fun takeOver() = browser.takeOver()

    /**
     * Gives the browser back to Locus, after a Take over or the agent's own hand-over ("Your turn"). A
     * [note] goes to the chat as the user's own message first (so it joins the run at its next step,
     * after the browser call that was waiting), never into a tool result, which is untrusted territory.
     */
    fun handBack(note: String) {
        val key = browser.focusedKey.value?.takeIf { it != AgentBrowser.STANDALONE }
        val text = note.trim()
        if (key != null && text.isNotEmpty()) runner.send(key, text)
        val page = browser.state.value
        when {
            page.handoffReason != null -> browser.completeHandoff()
            page.userControl -> browser.handBack(noted = key != null && text.isNotEmpty())
        }
    }

    /** Opens what the user typed in the address bar; [onError] gets a message when it can't. */
    fun go(input: String, onError: (String) -> Unit) = viewModelScope.launch {
        browser.userOpen(input)?.let(onError)
    }

    fun navigate(action: AgentBrowser.UserNavigation) = viewModelScope.launch { browser.userNavigate(action) }

    /** Answers to "Save this login?" ([BrowserUi.loginOffer]; docs/design/browser.md, "Saved logins"). */
    fun saveLogin(username: String) = viewModelScope.launch { runCatching { browser.saveOffer(username) } }

    fun neverSaveLogin() = viewModelScope.launch { runCatching { browser.neverOfferSite() } }

    fun dismissLogin() = browser.dismissOffer()

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

    override fun onCleared() {
        // Leaving the browser gives it back: nobody is left to tap Hand back, and the agent shouldn't wait out the timeout.
        if (browser.state.value.userControl) browser.handBack(noted = false)
        if (showingStandalone) browser.restoreFocus(before)
    }
}
