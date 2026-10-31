package dev.locus.core.browser

import android.app.Activity
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * A slot that shows the agent browser's active tab. Chat's live panel and the full-screen browser
 * each have one, and a WebView can only be in one at a time: a host takes it when it's attached to
 * a window, and on [release] lets go only if it still holds it. (Removing the WebView from "whatever
 * parent it has" let the chat panel's cleanup pull it out of the full-screen browser that had just
 * taken it, leaving a black screen.) The host that attached last also follows tab switches.
 */
class WebViewHost(private val activity: Activity, private val browser: AgentBrowser) : FrameLayout(activity) {

    private val show: (WebView) -> Unit = { view ->
        if (view.parent !== this) {
            removeAllViews()
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Not during this layout pass: taking the WebView out of the other host makes Android move
        // focus, and if the WebView had it, Compose's focus search re-enters layout and crashes
        // ("performMeasureAndLayout called during measure layout"). So claim it just after.
        post { if (isAttachedToWindow) claim() }
    }

    /** Detaches the WebView if this host still has it, so the browser keeps working off screen. */
    fun release() {
        browser.detach(show)
        removeAllViews()
    }

    private fun claim() = show(browser.attach(activity, show))
}
