package dev.locus.core.browser

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * A window for a WebView that has none, so it can draw a fresh frame: a [Presentation] on a private
 * [VirtualDisplay] the size of the view. Used only around a capture, then the view goes back where
 * it was.
 *
 * Why it's needed: Chromium's WebView stops producing frames once it has been attached to a window
 * and is then detached, or while its window is hidden. `BrowserViewRenderer::IsClientVisible()` is
 * `!was_attached_ || (attached_to_window_ && window_visible_)`, and without it the begin-frame source
 * is cut off, so `View.draw` repaints the last frame it made. The agent's WebView is attached
 * whenever the chat's browser panel or the full-screen browser shows it, so after the user closes
 * either one, the page on screen stops being redrawn, and a later resize (see
 * `AgentBrowser.layoutOffscreen`) leaves everything outside the old frame blank.
 *
 * A private virtual display needs no permission: only this app can show windows on it, and a
 * Presentation on it gets the TYPE_PRIVATE_PRESENTATION window type, from a window context since
 * Android 12. Nothing on it is ever seen; the [ImageReader] only gives the display a surface and
 * drops each frame.
 */
internal class OffscreenWindow private constructor(
    private val view: WebView,
    private val frame: FrameLayout,
    private val presentation: Presentation,
    private val display: VirtualDisplay,
    private val reader: ImageReader,
    private val home: Home?,
) : AutoCloseable {

    /** Where the view was before, to put it back. */
    private class Home(val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)

    /** Gives the view back to its previous parent, unless someone else (a browser screen) took it meanwhile. */
    override fun close() {
        val ours = view.parent === frame
        if (ours) frame.removeView(view)
        runCatching { presentation.dismiss() }
        display.release()
        reader.close()
        if (ours && home != null && view.parent == null) {
            home.parent.addView(view, home.index.coerceAtMost(home.parent.childCount), home.params)
        }
    }

    companion object {
        /** Moves [view] into a new off-screen window of its current size. Main thread only. */
        fun open(context: Context, view: WebView): OffscreenWindow {
            val width = view.width
            val height = view.height
            require(width > 0 && height > 0)
            val dpi = context.resources.displayMetrics.densityDpi
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, Handler(Looper.getMainLooper()))
            var display: VirtualDisplay? = null
            var presentation: Presentation? = null
            var made: OffscreenWindow? = null
            try {
                // No flags: a private display that mirrors nothing and shows only this app's windows.
                display = context.getSystemService(DisplayManager::class.java)
                    .createVirtualDisplay("locus-browser-capture", width, height, dpi, reader.surface, 0)
                    ?: error("no virtual display")
                presentation = Presentation(context, display.display).apply {
                    window?.addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    )
                }
                val frame = FrameLayout(presentation.context)
                presentation.setContentView(frame)
                val parent = view.parent as? ViewGroup
                val home = parent?.let { Home(it, it.indexOfChild(view), view.layoutParams) }
                parent?.removeView(view)
                frame.addView(view, FrameLayout.LayoutParams(width, height))
                made = OffscreenWindow(view, frame, presentation, display, reader, home)
                presentation.show()
                return made
            } catch (e: Exception) {
                // close() puts the view back and releases everything made so far.
                if (made != null) {
                    made.close()
                } else {
                    runCatching { presentation?.dismiss() }
                    display?.release()
                    reader.close()
                }
                throw e
            }
        }
    }
}
