package dev.locus.core.browser

import android.app.Activity
import android.widget.FrameLayout

/**
 * A slot that shows the agent browser's single WebView. Chat's live panel and the full-screen
 * browser each have one, and the WebView can only be in one at a time: a host takes it when it's
 * attached to a window, and on [release] lets go only if it still holds it. (Removing the WebView
 * from "whatever parent it has" let the chat panel's cleanup pull it out of the full-screen
 * browser that had just taken it, leaving a black screen.)
 */
class WebViewHost(private val activity: Activity, private val browser: AgentBrowser) : FrameLayout(activity) {

    init { claim() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        claim()
    }

    /** Detaches the WebView if this host still has it, so the browser keeps working off screen. */
    fun release() {
        removeAllViews()
    }

    private fun claim() {
        val view = browser.viewFor(activity)
        if (view.parent !== this) addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
