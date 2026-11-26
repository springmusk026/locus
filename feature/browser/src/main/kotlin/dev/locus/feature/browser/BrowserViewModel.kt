package dev.locus.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.browser.AgentBrowser
import dev.locus.core.browser.BrowserState
import dev.locus.core.browser.LoginOffer
import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.tools.QuestionAnswer
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.AgentRunner
import dev.locus.core.browser.PickFailure
import dev.locus.core.browser.PickOutcome
import dev.locus.core.browser.PickedElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    /**
     * The chat's approval requests waiting for the user, oldest first: the same list the chat shows
     * (`RunState.approvals`), so an answer anywhere removes it here. Always empty in the user's own browsing.
     */
    val approvals: List<ApprovalRequest> = emptyList(),
    /** The chat's `ask_user` questions waiting for an answer, oldest first (`RunState.questions`). */
    val questions: List<UserQuestion> = emptyList(),
    /** "Pick an element" is on: taps point at the page instead of reaching it. Null when it's off. */
    val pick: PickMode? = null,
) {
    /** Locus is paused on the user's answer to an approval or a question, shown at the bottom of the browser. */
    val waitingForAnswer: Boolean get() = approvals.isNotEmpty() || questions.isNotEmpty()

    /** Locus is acting on this page and the user hasn't taken it: they watch, and Take over is offered. */
    val agentDriving: Boolean get() = agentRunning && !page.userControl && page.handoffReason == null

    /** The user may use the address bar, back, forward and reload. */
    val userDriving: Boolean get() = !agentDriving

    /**
     * Whether "Pick an element" can start: a chat's browser (the user's own browsing has no message to attach
     * to), a page, nobody else acting on it, and no page dialog in the way.
     */
    val canPick: Boolean get() = !standalone && page.hasPage && userDriving && page.dialog == null
}

/**
 * Pick mode in the full-screen browser (docs/design/browser.md, "Element picker"): what the last tap found
 * ([selection]), or why it found nothing ([failure]), and whether the page is being asked ([busy]).
 * [stale] when the page scrolled since, so the highlight is hidden until the next tap.
 */
data class PickMode(
    val selection: PickedElement? = null,
    val failure: PickFailure? = null,
    val busy: Boolean = false,
    val stale: Boolean = false,
    /** Attaching it to the chat's next message failed (the saved logins couldn't be read to clean its text). */
    val attachFailed: Boolean = false,
)

@HiltViewModel
class BrowserViewModel @Inject constructor(val browser: AgentBrowser, private val runner: AgentRunner) : ViewModel() {

    /** Whose browser was on screen before the user's own browsing was shown; given back when this screen goes. */
    private var before: String? = null
    private var showingStandalone = false

    private val pick = MutableStateFlow<PickMode?>(null)

    val uiState: StateFlow<BrowserUi> = combine(browser.state, browser.focusedKey, runner.states, browser.loginOffer, pick) { page, key, runs, offer, picking ->
        val standalone = key == AgentBrowser.STANDALONE
        // The user's own browsing belongs to no chat, so nothing waits on it.
        val run = key?.takeIf { !standalone }?.let { runs[it] }
        val ui = BrowserUi(
            page,
            agentRunning = run?.running == true,
            standalone = standalone,
            loginOffer = offer,
            approvals = run?.approvals.orEmpty(),
            questions = run?.questions.orEmpty(),
        )
        // Pick mode ends by itself once picking isn't possible (Locus started browsing, the page closed, a dialog opened).
        ui.copy(pick = picking?.takeIf { ui.canPick })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserUi(browser.state.value))

    init {
        // Another page, or another tab: what was picked belongs to the old one.
        viewModelScope.launch {
            browser.state.map { it.url to it.activeTab }.distinctUntilChanged().drop(1).collect {
                pick.update { it?.copy(selection = null, failure = null, stale = false, attachFailed = false) }
            }
        }
        viewModelScope.launch {
            uiState.map { it.canPick }.distinctUntilChanged().collect { can -> if (!can) pick.value = null }
        }
    }

    /** "Pick an element" from the menu: the next tap on the page points at something instead of reaching it. */
    fun startPick() {
        if (uiState.value.canPick) pick.value = PickMode()
    }

    fun cancelPick() {
        pick.value = null
    }

    /** A tap at ([fx], [fy]), fractions of the page's size, while picking: what's there becomes the selection. */
    fun pickAt(fx: Float, fy: Float) {
        if (pick.value?.busy != false) return
        pick.update { it?.copy(busy = true, attachFailed = false) }
        viewModelScope.launch {
            val outcome = runCatching { browser.pickAt(fx, fy) }.getOrElse { PickOutcome.Failed(PickFailure.NoAnswer) }
            pick.update { current ->
                current ?: return@update null
                when (outcome) {
                    is PickOutcome.Picked -> current.copy(selection = outcome.element, failure = null, busy = false, stale = false)
                    // A miss keeps an earlier pick, so a stray tap doesn't lose it.
                    is PickOutcome.Failed -> current.copy(failure = outcome.reason, busy = false)
                }
            }
        }
    }

    /** Scrolls the page while picking (a drag); the highlight is hidden, since the element moved. */
    fun pickScroll(dy: Int) {
        if (pick.value == null) return
        pick.update { it?.copy(stale = it.selection != null) }
        viewModelScope.launch { browser.userScroll(dy) }
    }

    /**
     * Attaches the selection to the chat's next message and ends pick mode; [onDone] gets true when it's
     * attached, so the screen can go back to the chat.
     */
    fun attachPick(onDone: (Boolean) -> Unit) {
        val selection = pick.value?.selection ?: return
        val chat = browser.pickChat() ?: return onDone(false)
        viewModelScope.launch {
            val ok = runCatching { browser.attachPick(chat, selection) }.getOrDefault(false)
            if (ok) pick.value = null else pick.update { it?.copy(attachFailed = true) }
            onDone(ok)
        }
    }

    /** Opened from the Workspace: the user's own browsing, which belongs to no chat. */
    fun showStandalone() {
        if (showingStandalone) return
        showingStandalone = true
        before = browser.focusStandalone()
    }

    /** The chat whose browser this screen took over; handed back even when another chat is focused by then (#340). */
    private var tookOver: String? = null

    /** The chat whose browser is on screen; null in the user's own browsing. */
    private fun shownChat(): String? = browser.focusedKey.value?.takeIf { it != AgentBrowser.STANDALONE }

    fun takeOver(): Boolean {
        val key = shownChat() ?: return false
        return browser.takeOver(key).also { if (it) tookOver = key }
    }

    /**
     * The approval card's answers, the same calls the chat's card and the notification make
     * ([AgentRunner.answer]): the request leaves the run state, and with it every place that shows it.
     */
    fun answer(callId: String, approved: Boolean) = runner.answer(callId, approved)

    fun answerRemembering(callId: String, answer: ApprovalAnswer) = runner.answer(callId, answer)

    /** Deny, with the user's reason for the agent (the card's "Deny with a reason"). */
    fun denyWithReason(callId: String, reason: String) = runner.denyWithReason(callId, reason)

    fun answerQuestion(callId: String, answer: QuestionAnswer) {
        runner.answerQuestion(callId, answer)
    }

    /** Scrolls the page while Locus waits for an answer; taps still need Take over. */
    fun scrollPage(dy: Int) {
        if (!uiState.value.waitingForAnswer) return
        viewModelScope.launch { browser.userScroll(dy) }
    }

    /**
     * Gives the browser back to Locus, after a Take over or the agent's own hand-over ("Your turn"). A
     * [note] goes to the chat as the user's own message first (so it joins the run at its next step,
     * after the browser call that was waiting), never into a tool result, which is untrusted territory.
     */
    fun handBack(note: String) {
        // The chat on screen when it shows the handoff or the takeover, else the one this screen took over (#340).
        val shown = shownChat()
        val page = shown?.let { browser.stateOf(it).value }
        val key = when {
            shown != null && page != null && (page.handoffReason != null || page.userControl) -> shown
            else -> tookOver?.takeIf { browser.stateOf(it).value.userControl }
        } ?: return
        val text = note.trim()
        if (text.isNotEmpty()) runner.send(key, text)
        if (browser.stateOf(key).value.handoffReason != null) {
            browser.completeHandoff(key)
        } else {
            browser.handBack(key, noted = text.isNotEmpty())
        }
        if (key == tookOver) tookOver = null
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
        listOfNotNull(shownChat(), tookOver).distinct()
            .filter { browser.stateOf(it).value.userControl }
            .forEach { browser.handBack(it, noted = false) }
        if (showingStandalone) browser.restoreFocus(before)
    }
}
