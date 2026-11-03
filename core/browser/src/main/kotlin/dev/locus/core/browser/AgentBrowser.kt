package dev.locus.core.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.SendFile.Companion.readableSize
import dev.locus.core.agent.tools.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

data class BrowserState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    /** Set while the agent has handed control to the user (log in, solve a CAPTCHA, pay). */
    val handoffReason: String? = null,
    val hasPage: Boolean = false,
    val tabs: List<TabInfo> = emptyList(),
    val activeTab: Int = 0,
    /** A JavaScript confirm() or prompt() waiting for an answer, from the agent or the user. */
    val dialog: PendingDialog? = null,
)

data class TabInfo(val title: String, val url: String)

data class PendingDialog(val type: String, val message: String, val defaultText: String)

/**
 * The agent's own browser: WebViews (one per tab; pop-ups become tabs), created lazily, with their
 * own cookie and storage profile where WebView supports it, so the agent never sees the user's
 * other web sessions.
 *
 * **One session per conversation** (docs/design/browser.md, "One browser per chat"): each chat, and
 * each helper, has its own tabs, active tab, element refs, dialogs, events and download policy, keyed
 * by [ToolCallContext.conversationId]. Chat B's navigation can't move the page chat A is about to click
 * on. [BrowserSessions] holds the bookkeeping (refs, download policy, memory caps); this class holds
 * the WebViews.
 *
 * All WebView calls happen on the main thread; each session's lock serializes that session's actions
 * so two tool calls never interleave on the same page.
 */
@Singleton
class AgentBrowser @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val workspace: Workspace,
) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionsBook = BrowserSessions()

    /** Per-conversation state for the UI; kept apart from [Session] so reading it never creates WebViews. */
    private val states = ConcurrentHashMap<String, MutableStateFlow<BrowserState>>()
    private fun stateFlow(key: String) = states.getOrPut(key) { MutableStateFlow(BrowserState()) }

    /** The chat on screen: what the full-screen browser and UI-driven actions use. */
    private val focused = MutableStateFlow<String?>(null)

    /** The state of the chat [conversationId]'s browser, for its chat screen. */
    fun stateOf(conversationId: String): StateFlow<BrowserState> = stateFlow(conversationId).asStateFlow()

    /** The browser of the chat on screen ([focus]), for the full-screen browser. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<BrowserState> = focused
        .flatMapLatest { stateFlow(it ?: DEFAULT_KEY) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), SharingStarted.Eagerly, BrowserState())

    /** [conversationId]'s chat is on screen: the full-screen browser shows its tabs. */
    fun focus(conversationId: String) {
        focused.value = conversationId
    }

    private val context = MutableContextWrapper(appContext)
    private val agentSource: String by lazy { appContext.assets.open("locus-agent.js").bufferedReader().use { it.readText() } }

    /** The helper as injected after the page's scripts (fallback): it doesn't know any tab's secret. */
    private val fallbackJs: String by lazy { agentSource.replace(PageReply.SECRET_PLACEHOLDER, "") }

    private val sessions = ConcurrentHashMap<String, Session>()
    private fun session(key: String): Session = sessions.getOrPut(key) { Session(key) }

    // Main thread only.
    private var nextTabId = 1L

    private class Tab(val session: Session, val opener: Tab?, val id: Long) {
        lateinit var view: WebView

        /** Known only to the helper copy registered at document start; see [PageReply]. */
        val secret = PageReply.newSecret()
        var hasPage = false
        var pageLoad: CompletableDeferred<Unit>? = null

        /** Why the last main-frame load failed, set by the WebViewClient during a navigation. */
        var loadError: String? = null

        /** The last snapshot's element lines and URL, for describing what an action changed. */
        var lastBody: List<String>? = null
        var lastUrl: String? = null

        val logs = PageLogs()
    }

    private class Dialog(val tab: Tab, val info: PendingDialog, val result: JsResult)

    /** One conversation's browser. Fields are main-thread only unless marked otherwise. */
    private inner class Session(val key: String) {
        val lock = Mutex()
        val state = stateFlow(key)
        val tabs = mutableListOf<Tab>()
        var active: Tab? = null

        /** [active]'s id, readable off the main thread (ref checks before a call). */
        @Volatile var activeId: Long? = null
        var dialog: Dialog? = null
        var handoff: CompletableDeferred<Unit>? = null
        val events = mutableListOf<String>()
        var lastAgentGesture = 0L
        var downloadsInFlight = 0
        var downloadsStarted = 0

        /** Workspace files for the next file chooser the page opens (browser_upload through a button). */
        var pendingChooser: List<File>? = null
        var chooserAnswered: CompletableDeferred<Unit>? = null

        fun event(text: String) = synchronized(events) {
            events += text
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    /** Shows a WebView in the screen that currently displays the browser (see [attach]), for session [displayKey]. */
    private var display: ((WebView) -> Unit)? = null
    private var displayKey: String? = null

    /**
     * The active tab's WebView of chat [conversationId]'s browser (the focused chat's when null, as in
     * the full-screen browser), for display in [activity]. [show] is called with the new view when that
     * session's active tab changes while this display is the latest to attach.
     */
    fun attach(activity: Activity, conversationId: String?, show: (WebView) -> Unit): WebView {
        context.baseContext = activity
        if (conversationId != null) focused.value = conversationId
        val s = session(conversationId ?: focused.value ?: DEFAULT_KEY)
        display = show
        displayKey = s.key
        // The screen needs something to show even when every other chat is busy: don't refuse here.
        val view = (s.active ?: newTab(s, null, force = true).also(::activate)).view
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun detach(show: (WebView) -> Unit) {
        if (display === show) {
            display = null
            displayKey = null
        }
    }

    /** User finished the handoff on the browser screen (or dismissed it); the focused chat's waiting call continues. */
    fun completeHandoff() {
        sessions[focused.value ?: DEFAULT_KEY]?.handoff?.complete(Unit)
    }

    /** The calling conversation's session key: its id in a tool call, else the chat on screen. */
    private suspend fun key(): String = currentCoroutineContext()[ToolCallContext]?.conversationId ?: focused.value ?: DEFAULT_KEY

    /** The calling conversation's browser state (its URL, tabs, dialog), for tool results and checks. */
    suspend fun current(): BrowserState = stateFlow(key()).value

    /**
     * Records the calling conversation's run policy. Kept after the call: a download its page starts
     * later is judged by it ([BrowserSessions.setReadOnly]).
     */
    suspend fun applyRunPolicy(readOnly: Boolean) = sessionsBook.setReadOnly(key(), readOnly)

    /**
     * Why refs from the caller's latest snapshot can't be used now (another tab is active, or there was
     * no snapshot), or null. Tools check it before asking the user to approve a call that would fail.
     */
    suspend fun refsProblem(): String? {
        val k = key()
        return (sessionsBook.checkRefs(k, sessions[k]?.activeId) as? BrowserSessions.RefCheck.Stale)?.message
    }

    /** Loads [url], in a new tab when [newTab]; throws with an explanation when it can't be loaded. */
    suspend fun open(url: String, newTab: Boolean = false) = action {
        if (newTab && active?.hasPage == true) {
            if (tabs.size >= MAX_TABS) throw ToolException("$MAX_TABS tabs are open. Close one with browser_tabs first.")
            activate(newTab(this, null))
        }
        navigate { it.loadUrl(url) }
    }

    suspend fun back(): Boolean = action {
        val view = ensure()
        if (!view.canGoBack()) return@action false
        navigate { it.goBack() }
        true
    }

    private suspend fun Session.navigate(start: (WebView) -> Unit) {
        val tab = activeTab()
        tab.loadError = null
        val load = CompletableDeferred<Unit>().also { tab.pageLoad = it }
        val downloads = downloadsStarted
        start(ensure())
        // A dialog shown while loading holds the page, and a file download never finishes loading a
        // page, so stop waiting for the load when either happens.
        withTimeoutOrNull(NAVIGATION_TIMEOUT_MS) { while (!load.isCompleted && dialog == null && downloadsStarted == downloads) delay(50) }
        tab.loadError?.let { throw ToolException(it) }
        if (tab.hasPage) orDialog { settle() }
        awaitDownloads()
    }

    /**
     * The page as text for the model. With [diff], a page that mostly didn't change since the last
     * snapshot is described by its changes only (see [SnapshotFormat.afterAction]). The refs in it
     * belong to this tab and document from now on ([BrowserSessions.snapshotTaken]).
     */
    suspend fun describe(all: Boolean = false, diff: Boolean = false): String = action {
        ensurePage()
        dialog?.let { return@action dialogNote(it.info) }
        val tab = activeTab()
        val snap = pageSnapshot(all)
        val context = context()
        val text = if (diff) {
            SnapshotFormat.afterAction(tab.lastBody, tab.lastUrl, snap, context)
        } else {
            SnapshotFormat.lines(snap, context).joinToString("\n")
        }
        tab.lastBody = SnapshotFormat.body(snap)
        tab.lastUrl = snap.url
        text
    }

    /** Elements and text passages matching [query] anywhere on the page. */
    suspend fun find(query: String): String = action {
        ensurePage()
        val snap = pageSnapshot(all = true)
        val passages = (js("__locus.findText(${quote(query)}, 10)") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        SnapshotFormat.find(snap, query, passages)
    }

    private suspend fun Session.pageSnapshot(all: Boolean): PageSnapshot {
        val reply = reply("__locus.snapshot($all)")
        val obj = reply.value as? JsonObject
            ?: throw ToolException("The page did not return a snapshot (it may still be loading). Try browser_wait, then browser_snapshot.")
        sessionsBook.snapshotTaken(key, activeTab().id, reply.doc)
        return PageSnapshot.parse(obj)
    }

    private fun Session.context(): BrowserContext {
        val view = ensure()
        return BrowserContext(
            backSteps = view.copyBackForwardList().currentIndex.coerceAtLeast(0),
            tabCount = tabs.size,
            activeTab = tabs.indexOf(active).coerceAtLeast(0),
            dialog = dialog?.info?.let { "${it.type} \"${SnapshotFormat.clip(it.message, 120)}\"" },
        )
    }

    /**
     * The document refs from the latest snapshot belong to, after checking they are this tab's; throws
     * with what to do when they aren't. Actions pass it to [sameDocument], so a page that navigated since
     * the snapshot refuses in the same script call. Null on a page whose helper isn't verified.
     */
    private fun Session.refDoc(): String? = when (val check = sessionsBook.checkRefs(key, activeId)) {
        is BrowserSessions.RefCheck.Ok -> check.doc
        is BrowserSessions.RefCheck.Stale -> throw ToolException(check.message)
    }

    /** Facts about [ref] for deciding whether acting on it must be confirmed; null when it's gone or stale. */
    suspend fun inspect(ref: String): ElementFacts? = action {
        ensurePage()
        if (dialog != null) return@action null
        val doc = runCatching { refDoc() }.getOrElse { return@action null }
        val reply = reply(sameDocument(doc, "__locus.inspect(${quote(ref)})"))
        val obj = reply.value as? JsonObject ?: return@action null
        if (obj["error"] != null) null else ElementFacts.parse(obj, reply)
    }

    /** Facts about the focused element, for key presses. */
    suspend fun inspectFocused(): ElementFacts? = action {
        ensurePage()
        if (dialog != null) return@action null
        val reply = reply("__locus.inspectFocused()")
        (reply.value as? JsonObject)?.let { ElementFacts.parse(it, reply) }
    }

    /**
     * Clicks an element. It is scrolled into view and must hold still, be enabled and not be covered.
     * When the browser is on screen a real touch is sent (pages treat it as a trusted event);
     * otherwise the click is dispatched from script.
     */
    suspend fun click(ref: String): String? = action {
        ensurePage()
        clickNow(ref, refDoc())
    }

    private suspend fun Session.clickNow(ref: String, doc: String?): String? {
        val view = ensure()
        val rect = stableRect(ref, doc) ?: return "Could not locate $ref. Take a new snapshot."
        rect.string("error")?.let { return it }
        rect.string("covered")?.let {
            return "$ref is covered by $it. Close or answer that first (often a cookie banner, menu or dialog), or scroll, then try again."
        }
        val onScreen = view.isAttachedToWindow && view.isShown && view.width > 0 && rect.string("outside") != "true"
        markAgentGesture()
        return orDialog {
            if (onScreen) {
                // CSS pixels to view pixels; also right for zoomed-out desktop pages.
                val ratio = view.width / (rect.string("vw")?.toFloatOrNull()?.takeIf { it > 0 } ?: (view.width / view.resources.displayMetrics.density))
                tap(view, (rect.string("x")?.toFloatOrNull() ?: 0f) * ratio, (rect.string("y")?.toFloatOrNull() ?: 0f) * ratio)
            } else {
                (js(sameDocument(doc, "__locus.click(${quote(ref)})")) as? JsonObject)?.string("error")?.let { return@orDialog it }
            }
            afterAction()
            null
        }
    }

    /** The element's position once it stops moving (animations, lazy layout), up to a few tries. */
    private suspend fun Session.stableRect(ref: String, doc: String?): JsonObject? {
        var last: JsonObject? = null
        repeat(5) {
            val now = js(sameDocument(doc, "__locus.rect(${quote(ref)})")) as? JsonObject ?: return last
            if (now["error"] != null) return now
            val prev = last
            if (prev != null && near(prev, now)) return now
            last = now
            delay(80)
        }
        return last
    }

    private fun near(a: JsonObject, b: JsonObject): Boolean = listOf("x", "y").all { k ->
        val p = a.string(k)?.toFloatOrNull() ?: return false
        val q = b.string(k)?.toFloatOrNull() ?: return false
        kotlin.math.abs(p - q) < 1f
    }

    /**
     * Types into [ref]. The page refuses when it is no longer the document [doc] (the one its facts were
     * checked on), or the latest snapshot's, in the same script call that types.
     */
    suspend fun type(ref: String, text: String, submit: Boolean, doc: String? = null): String? = action {
        ensurePage()
        val expected = refDoc() ?: doc
        if (submit) markAgentGesture()
        orDialog {
            val result = js(sameDocument(expected, "__locus.type(${quote(ref)}, ${quote(text)}, $submit)")) as? JsonObject
            result?.string("error")?.let { return@orDialog it }
            afterAction()
            null
        }
    }

    suspend fun select(ref: String, value: String): String? = action {
        ensurePage()
        val doc = refDoc()
        orDialog {
            (js(sameDocument(doc, "__locus.select(${quote(ref)}, ${quote(value)})")) as? JsonObject)?.string("error")?.let { return@orDialog it }
            afterAction()
            null
        }
    }

    /** Fills several fields; returns one line per field that failed (empty when all worked). [doc]: see [type]. */
    suspend fun fill(fields: List<Pair<String, String>>, doc: String? = null): List<String> = action {
        ensurePage()
        val expected = refDoc() ?: doc
        val failed = mutableListOf<String>()
        orDialog {
            fields.forEach { (ref, value) ->
                (js(sameDocument(expected, "__locus.fill(${quote(ref)}, ${quote(value)})")) as? JsonObject)?.string("error")?.let(failed::add)
            }
            afterAction()
        }
        failed
    }

    suspend fun pressKey(key: String) = action {
        ensurePage()
        markAgentGesture()
        orDialog {
            js("__locus.key(${quote(key)})")
            afterAction()
        }
    }

    /** Returns a note for the model (already at the bottom, …) or throws with what went wrong. */
    suspend fun scroll(direction: String, ref: String?): String? = action {
        ensurePage()
        val expression = "__locus.scroll(${quote(direction)}, ${ref?.let(::quote) ?: "null"})"
        val result = js(if (ref != null) sameDocument(refDoc(), expression) else expression) as? JsonObject
        result?.string("error")?.let { throw ToolException(it) }
        delay(400)
        result?.string("note")
    }

    /**
     * Waits until [text] appears (or, with [gone], disappears), or simply pauses when both are null.
     * Returns whether the condition was met within [seconds].
     */
    suspend fun waitFor(text: String?, gone: String?, seconds: Int): Boolean {
        val target = text ?: gone
        if (target == null) {
            delay(seconds * 1000L)
            return true
        }
        return withTimeoutOrNull(seconds * 1000L) {
            while (true) {
                val present = action {
                    ensurePage()
                    (js("__locus.hasText(${quote(target)})") as? JsonPrimitive)?.contentOrNull == "true"
                }
                if (present == (text != null)) break
                delay(300)
            }
            true
        } ?: false
    }

    /** The current page's HTML, for readable-text extraction. */
    suspend fun html(): String = action {
        ensurePage()
        js("document.documentElement.outerHTML").stringOrEmpty()
    }

    /** The caller's active tab's console messages and failed requests since its page loaded. */
    suspend fun logs(kind: String, errorsOnly: Boolean): String {
        val tab = sessions[key()]?.active ?: throw ToolException("No page is open. Use browser_open first.")
        val (entries, dropped) = tab.logs.snapshot()
        return PageLogs.format(entries, dropped, kind, errorsOnly)
    }

    /** Saves the page as an MHTML web archive at [file]. */
    suspend fun saveArchive(file: File) = action {
        ensurePage()
        val saved = CompletableDeferred<String?>()
        ensure().saveWebArchive(file.absolutePath, false) { saved.complete(it) }
        withTimeoutOrNull(SAVE_TIMEOUT_MS) { saved.await() } ?: throw ToolException("The page could not be saved as an archive.")
    }

    /**
     * Saves the page as a PNG at [file]: the viewport (what the browser shows), or with [fullPage] the
     * page from the top, scrolled through and stitched, within [CapturePlan]'s caps. Returns what the
     * image shows, for the model.
     */
    suspend fun screenshot(file: File, fullPage: Boolean = false): String = action {
        ensurePage()
        val view = ensure()
        if (view.width <= 0 || view.height <= 0) throw ToolException("The browser has no size yet; open a page first.")
        val (bitmap, summary) = drawable(view) {
            try {
                if (fullPage) captureFullPage(view) else captureViewport(view)
            } catch (_: OutOfMemoryError) {
                throw ToolException("Not enough memory for this screenshot. Try again without full_page.")
            }
        }
        try {
            withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        } finally {
            bitmap.recycle()
        }
        summary
    }

    /**
     * Runs [block] once the view can draw a current frame. A view on a visible screen draws as it is.
     * Otherwise it's lent an [OffscreenWindow] for the capture: once detached, a WebView only repeats
     * its last frame (and, laid out larger since, leaves the rest blank). A WebView that was never
     * attached does draw off screen, but nothing confirms when a frame is current
     * (postVisualStateCallback answers after a draw in a window), so it goes the same way.
     * Refuses rather than save a stale or blank picture.
     */
    private suspend fun <T> drawable(view: WebView, block: suspend () -> T): T {
        val live = view.isAttachedToWindow && view.isShown && view.windowVisibility == android.view.View.VISIBLE
        if (live) {
            painted(view)
            return block()
        }
        val window = try {
            OffscreenWindow.open(appContext, view)
        } catch (e: Exception) {
            throw ToolException(
                "The browser can't draw while it's off screen on this phone (${e.javaClass.simpleName}). " +
                    "Ask the user to open the browser panel in the chat, then try again.",
            )
        }
        try {
            if (!painted(view, PAINT_TIMEOUT_OFFSCREEN_MS)) {
                throw ToolException("The browser didn't draw the page off screen in time. Ask the user to open the browser panel in the chat, then try again.")
            }
            return block()
        } finally {
            window.close()
        }
    }

    /** Waits until the page's current state has been drawn (WebView.postVisualStateCallback). */
    private suspend fun painted(view: WebView, timeoutMs: Long = PAINT_TIMEOUT_MS): Boolean {
        val done = CompletableDeferred<Unit>()
        view.postVisualStateCallback(
            SystemClock.uptimeMillis(),
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    done.complete(Unit)
                }
            },
        )
        return withTimeoutOrNull(timeoutMs) { done.await() } != null
    }

    private fun captureViewport(view: WebView): Pair<Bitmap, String> {
        val plan = CapturePlan.viewport(view.width, view.height)
        val bitmap = Bitmap.createBitmap(plan.outWidth, plan.outHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(plan.scale, plan.scale)
        drawViewport(view, canvas)
        return bitmap to plan.describe(view.scrollY)
    }

    /**
     * Scrolls the window through the page a viewport at a time and draws each one into a single
     * bitmap. Fixed and sticky bars (headers, cookie banners) are hidden after the first viewport so
     * they don't repeat down the image; the page's scroll position and styles are restored after.
     */
    private suspend fun Session.captureFullPage(view: WebView): Pair<Bitmap, String> {
        val w = view.width
        val h = view.height
        val metrics = js(
            "({sh: Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0), " +
                "ih: window.innerHeight, x: window.scrollX, y: window.scrollY})",
        ) as? JsonObject ?: throw ToolException("The page didn't report its size.")
        val innerHeight = metrics.string("ih")?.toDoubleOrNull()?.takeIf { it > 0 } ?: throw ToolException("The page didn't report its size.")
        val cssScrollHeight = metrics.string("sh")?.toDoubleOrNull() ?: innerHeight
        // Physical pixels per CSS pixel, page zoom included.
        val ratio = h / innerHeight
        val plan = CapturePlan.fullPage(w, h, (cssScrollHeight * ratio).toInt())
        val startX = metrics.string("x")?.toDoubleOrNull() ?: 0.0
        val startY = metrics.string("y")?.toDoubleOrNull() ?: 0.0
        val out = Bitmap.createBitmap(plan.outWidth, plan.outHeight, Bitmap.Config.ARGB_8888)
        val viewport = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            out.recycle()
            throw e
        }
        try {
            val canvas = android.graphics.Canvas(out)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.scale(plan.scale, plan.scale)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            val tileCanvas = android.graphics.Canvas(viewport)
            plan.tiles.forEachIndexed { i, target ->
                if (i == 1) js(HIDE_FIXED_JS)
                val at = scrollForCapture(view, target / ratio, ratio)
                if (!painted(view)) throw ToolException("The page didn't redraw while scrolling. Try again, or save without full_page.")
                drawViewport(view, tileCanvas)
                // This viewport covers [at, at + h) of the page; a later one overwrites any overlap.
                val rows = minOf(h, plan.sourceHeight - at)
                if (rows > 0) {
                    canvas.drawBitmap(
                        viewport,
                        android.graphics.Rect(0, 0, w, rows),
                        android.graphics.RectF(0f, at.toFloat(), w.toFloat(), (at + rows).toFloat()),
                        paint,
                    )
                }
            }
        } catch (e: Throwable) {
            out.recycle()
            throw e
        } finally {
            viewport.recycle()
            runCatching { js(RESTORE_FIXED_JS) }
            runCatching { js("(window.scrollTo({left: $startX, top: $startY, behavior: 'instant'}), true)") }
        }
        return out to plan.describe()
    }

    /**
     * Scrolls the window to [cssY] and waits until the view's own scroll offset (what [drawViewport]
     * draws at) has caught up. Returns that offset in physical pixels.
     */
    private suspend fun Session.scrollForCapture(view: WebView, cssY: Double, ratio: Double): Int {
        val reached = js("(window.scrollTo({left: window.scrollX, top: $cssY, behavior: 'instant'}), window.scrollY)")
            .stringOrEmpty().toDoubleOrNull() ?: return view.scrollY
        val expected = (reached * ratio).roundToInt()
        withTimeoutOrNull(SCROLL_SYNC_TIMEOUT_MS) {
            while (abs(view.scrollY - expected) > 2) delay(50)
        }
        delay(TILE_SETTLE_MS)
        return view.scrollY
    }

    /**
     * Draws the viewport at the canvas origin, on white (what a browser shows behind a transparent
     * page; transparent pixels look like an empty area in an image viewer, especially in dark mode).
     *
     * The translate matters: WebView's software draw adds the scroll offset back
     * (SoftwareCanvasHolder translates by it) because the framework translates a child by -scroll
     * before drawing it. Drawing a scrolled WebView without it moves the page down by the scroll
     * offset, leaving the top blank and the rest cut off.
     */
    private fun drawViewport(view: WebView, canvas: android.graphics.Canvas) {
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.save()
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        canvas.restore()
    }

    // ---- Files ------------------------------------------------------------------------------------

    /**
     * Gives workspace [files] to the page. A file input (the element, one inside or labelling it, or
     * the form's) gets them directly, which needs no file picker; otherwise [ref] is clicked and the
     * file picker it opens is answered with them. Returns what happened.
     */
    suspend fun upload(ref: String, files: List<File>): String = action {
        ensurePage()
        val doc = refDoc()
        val target = js(sameDocument(doc, "__locus.uploadTarget(${quote(ref)})")) as? JsonObject ?: throw ToolException("Could not reach $ref. Take a new snapshot.")
        target.string("error")?.let { throw ToolException(it) }
        if (target.string("none") == "true") return@action uploadThroughChooser(ref, doc, files)
        val input = target.string("ref") ?: throw ToolException("Could not reach the file input.")
        if (files.size > 1 && target.string("multiple") != "true") {
            throw ToolException("This file input takes one file. Upload them one at a time, or look for another input.")
        }
        val specs = files.mapIndexed { i, file ->
            val id = "up${SystemClock.uptimeMillis()}x$i"
            js("__locus.fileStart(${quote(id)})")
            val data = withContext(Dispatchers.IO) { android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP) }
            data.chunked(UPLOAD_CHUNK_CHARS).forEach { js("__locus.fileChunk(${quote(id)}, ${quote(it)})") }
            "{\"id\":${quote(id)},\"name\":${quote(file.name)},\"type\":${quote(mimeOf(file))}}"
        }
        // Same document as the snapshot: a page that navigated meanwhile gets nothing.
        val result = js(sameDocument(doc, "__locus.fileSet(${quote(input)}, [${specs.joinToString(",")}])")) as? JsonObject
        result?.string("error")?.let { throw ToolException(it) }
        orDialog { afterAction() }
        "Attached ${files.joinToString { it.name }} to the file input [$input]."
    }

    private suspend fun Session.uploadThroughChooser(ref: String, doc: String?, files: List<File>): String {
        val answered = CompletableDeferred<Unit>()
        pendingChooser = files
        chooserAnswered = answered
        try {
            clickNow(ref, doc)?.let { throw ToolException(it) }
            withTimeoutOrNull(CHOOSER_WAIT_MS) { answered.await() }
                ?: throw ToolException(
                    "$ref didn't open a file picker. Look for the page's file input (role file-upload in a full snapshot) or an upload button, and use that ref.",
                )
        } finally {
            pendingChooser = null
            chooserAnswered = null
        }
        orDialog { afterAction() }
        return "Picked ${files.joinToString { it.name }} in the file picker that $ref opened."
    }

    private fun mimeOf(file: File): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    /**
     * A page started a download (a link, a button, or browser_open of a file): save it into the
     * workspace, if the conversation that owns the tab may write ([BrowserSessions.downloadsAllowed]),
     * judged now, when it starts, not when some tool call began or ended.
     */
    private fun download(tab: Tab, url: String, userAgent: String?, disposition: String?, mime: String?, length: Long) {
        val s = tab.session
        // Counted even when refused: the navigation that led here stops waiting for a page either way.
        s.downloadsStarted++
        if (!sessionsBook.downloadsAllowed(s.key)) {
            s.event("Didn't download ${SnapshotFormat.clip(url, 120)}: this chat's run is read-only.")
            return
        }
        if (length > Transfers.MAX_DOWNLOAD_BYTES) {
            s.event("Didn't download ${SnapshotFormat.clip(url, 120)}: it is ${readableSize(length)}, over the ${readableSize(Transfers.MAX_DOWNLOAD_BYTES)} limit.")
            return
        }
        var name = Transfers.sanitize(android.webkit.URLUtil.guessFileName(url, disposition, mime))
        if ('.' !in name) name += Transfers.extensionFor(mime.orEmpty())
        val referer = tab.view.url
        val cookies = cookies().getCookie(url)
        s.downloadsInFlight++
        io.launch {
            val result = runCatching {
                val dir = workspace.resolve(Transfers.DOWNLOAD_DIR).apply { mkdirs() }
                val file = File(dir, Transfers.unique(name) { File(dir, it).exists() })
                try {
                    when {
                        url.startsWith("data:") -> file.writeBytes(Transfers.decodeDataUrl(url)?.first ?: throw IOException("it isn't valid data"))
                        url.startsWith("blob:") -> readBlob(tab, url, file)
                        else -> fetchTo(url, userAgent, cookies, referer, file)
                    }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
                file
            }
            withContext(Dispatchers.Main) {
                s.downloadsInFlight--
                result
                    .onSuccess { s.event("Downloaded ${workspace.relative(it)} (${readableSize(it.length())}) into the workspace. send_file gives it to the user.") }
                    .onFailure { s.event("The download of $name failed: ${it.message}") }
            }
        }
    }

    private fun fetchTo(url: String, userAgent: String?, cookies: String?, referer: String?, file: File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            userAgent?.let { connection.setRequestProperty("User-Agent", it) }
            cookies?.let { connection.setRequestProperty("Cookie", it) }
            referer?.let { connection.setRequestProperty("Referer", it) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("the server answered HTTP $code")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > Transfers.MAX_DOWNLOAD_BYTES) throw IOException("it is over the ${readableSize(Transfers.MAX_DOWNLOAD_BYTES)} limit")
                        output.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** blob: URLs only exist inside the page that made them, so the page reads them for us. */
    private suspend fun readBlob(tab: Tab, url: String, file: File) {
        val s = tab.session
        val id = "dl${SystemClock.uptimeMillis()}"
        withContext(Dispatchers.Main) { s.js("__locus.readBlob(${quote(url)}, ${quote(id)})", tab) }
        var length = -1
        withTimeoutOrNull(BLOB_WAIT_MS) {
            while (length < 0) {
                val job = withContext(Dispatchers.Main) { s.js("__locus.job(${quote(id)})", tab) } as? JsonObject
                when (job?.string("state")) {
                    "done" -> length = job.string("length")?.toIntOrNull() ?: 0
                    "running" -> delay(200)
                    else -> throw IOException("the page could not read it (${job?.string("error") ?: "it is gone"}); try clicking the download again")
                }
            }
        } ?: throw IOException("the page took too long to hand it over")
        if (length.toLong() * 3 / 4 > Transfers.MAX_DOWNLOAD_BYTES) throw IOException("it is over the ${readableSize(Transfers.MAX_DOWNLOAD_BYTES)} limit")
        file.outputStream().use { out ->
            var start = 0
            while (start < length) {
                val chunk = withContext(Dispatchers.Main) { s.js("__locus.readChunk(${quote(id)}, $start, $BLOB_CHUNK_CHARS)", tab) }
                val text = (chunk as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (text.isEmpty()) throw IOException("the page stopped handing it over")
                out.write(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
                start += text.length
            }
        }
        withContext(Dispatchers.Main) { runCatching { s.js("__locus.endJob(${quote(id)})", tab) } }
    }

    /** Lets an action's downloads finish, so its result can report them. */
    private suspend fun Session.awaitDownloads() {
        withTimeoutOrNull(DOWNLOAD_WAIT_MS) { while (downloadsInFlight > 0) delay(200) }
    }

    private fun cookies(): CookieManager =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager
        } else {
            CookieManager.getInstance()
        }

    // ---- Tabs -------------------------------------------------------------------------------------

    /** Makes tab [index] (0-based) of the caller's browser the active one. */
    suspend fun selectTab(index: Int) = action {
        val tab = tabs.getOrNull(index) ?: throw ToolException("There is no tab ${index + 1}; ${tabs.size} open.")
        activate(tab)
    }

    /** Closes tab [index] (0-based) of the caller's browser; the opener or the last tab becomes active. */
    suspend fun closeTab(index: Int) = action {
        val tab = tabs.getOrNull(index) ?: throw ToolException("There is no tab ${index + 1}; ${tabs.size} open.")
        close(tab)
    }

    private fun activate(tab: Tab) {
        val s = tab.session
        s.active = tab
        s.activeId = tab.id
        val show = display
        if (show != null && displayKey == s.key) {
            (tab.view.parent as? ViewGroup)?.removeView(tab.view)
            show(tab.view)
        }
        s.refreshState()
    }

    private fun close(tab: Tab) {
        val s = tab.session
        if (s.dialog?.tab === tab) s.answer(false, null)
        if (!s.tabs.remove(tab)) return
        sessionsBook.tabClosed(s.key)
        tab.pageLoad?.complete(Unit)
        (tab.view.parent as? ViewGroup)?.removeView(tab.view)
        tab.view.destroy()
        if (s.active === tab) {
            val next = tab.opener?.takeIf { it in s.tabs } ?: s.tabs.lastOrNull()
            if (next != null) {
                activate(next)
            } else {
                s.active = null
                s.activeId = null
                s.refreshState()
            }
        } else {
            s.refreshState()
        }
    }

    /**
     * Closes every tab of the sessions [BrowserSessions.evictFor] picks so [s] can open one more, or
     * throws when the other chats' tabs are all in use. Bounds memory: each tab is a WebView.
     */
    private fun makeRoom(s: Session) {
        val evict = sessionsBook.evictFor(s.key, 1, focused.value)
            ?: throw ToolException(
                "Locus's browser already has ${BrowserSessions.MAX_LIVE_TABS} tabs open across chats, all in use. " +
                    "Close tabs you no longer need with browser_tabs, or try again when another chat's task has finished.",
            )
        evict.forEach(::closeSession)
    }

    /** Closes [key]'s tabs to free memory; its chat is told on its next browser call. */
    private fun closeSession(key: String) {
        val s = sessions[key] ?: return
        if (s.tabs.isEmpty()) return
        s.tabs.toList().forEach(::close)
        sessionsBook.closed(key)
        s.event("This chat's browser tabs were closed to free memory while it wasn't using them. Open the page again with browser_open.")
    }

    private fun Session.refreshState() {
        val tab = active
        val view = tab?.view
        state.update {
            it.copy(
                url = view?.url.orEmpty(),
                title = view?.title.orEmpty(),
                canGoBack = view?.canGoBack() == true,
                hasPage = tab?.hasPage == true,
                loading = if (tab == null) false else it.loading,
                tabs = tabs.map { t -> TabInfo(t.view.title.orEmpty(), t.view.url.orEmpty()) },
                activeTab = tabs.indexOf(tab).coerceAtLeast(0),
                dialog = dialog?.info,
            )
        }
    }

    // ---- Dialogs ----------------------------------------------------------------------------------

    /**
     * Answers the caller's waiting confirm() or prompt(); [text] is the prompt's answer (its default when
     * null). Called by the agent's browser_dialog and by the user from the browser screen (the focused chat's).
     */
    suspend fun answerDialog(accept: Boolean, text: String?): Boolean {
        val s = sessions[key()] ?: return false
        return withContext(Dispatchers.Main) {
            if (s.dialog == null) return@withContext false
            s.answer(accept, text)
            true
        }
    }

    private fun Session.answer(accept: Boolean, text: String?) {
        val d = dialog ?: return
        dialog = null
        when {
            !accept -> d.result.cancel()
            d.result is JsPromptResult -> d.result.confirm(text ?: d.info.defaultText)
            else -> d.result.confirm()
        }
        refreshState()
    }

    private fun holdDialog(tab: Tab, type: String, message: String, default: String, result: JsResult): Boolean {
        val s = tab.session
        // One at a time; a page that stacks dialogs gets "Cancel" for the extra ones.
        if (s.dialog != null) {
            result.cancel()
            return true
        }
        val info = PendingDialog(type, message, default)
        s.dialog = Dialog(tab, info, result)
        s.event("The page opened a $type dialog: “${SnapshotFormat.clip(message, 200)}”. The page waits until it is answered with browser_dialog.")
        s.refreshState()
        return true
    }

    private fun dialogNote(info: PendingDialog) =
        "A JavaScript ${info.type} dialog is open: “${SnapshotFormat.clip(info.message, 300)}”" +
            (if (info.type == "prompt") " (default answer: “${info.defaultText}”)" else "") +
            ". The page is paused until it is answered: use browser_dialog."

    /** Thrown by [js] while a dialog blocks the page. */
    private class DialogOpen : Exception()

    /**
     * Runs an action that may make the page open a dialog. A dialog is a normal outcome, reported in
     * the snapshot that follows, so the action counts as done.
     */
    private suspend fun <T> orDialog(block: suspend () -> T?): T? = try {
        block()
    } catch (_: DialogOpen) {
        null
    }

    // ---- Handoff and data -------------------------------------------------------------------------

    /** Suspends until the user taps Done on the browser screen. The caller's session stays busy meanwhile. */
    suspend fun handOff(reason: String) {
        val s = session(key())
        val done = CompletableDeferred<Unit>().also { s.handoff = it }
        sessionsBook.begin(s.key)
        s.state.update { it.copy(handoffReason = reason) }
        try {
            done.await()
        } finally {
            s.handoff = null
            sessionsBook.end(s.key)
            s.state.update { it.copy(handoffReason = null) }
        }
    }

    /** Forgets cookies, storage, history and tabs of the agent's browser, in every chat. */
    suspend fun clearData() = withContext(Dispatchers.Main) {
        sessions.values.forEach { s -> s.tabs.toList().forEach { it.view.clearCache(true); close(it) } }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val profile = androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        } else {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        states.values.forEach { it.value = BrowserState() }
    }

    /** What happened in the caller's browser outside its view since it last asked: load errors, dialogs, downloads, new tabs. */
    suspend fun takeEvents(): List<String> {
        val s = sessions[key()] ?: return emptyList()
        return synchronized(s.events) { s.events.toList().also { s.events.clear() } }
    }

    /** Page script actions carry no user gesture; pop-ups opened right after one are let through. */
    private fun Session.markAgentGesture() {
        lastAgentGesture = SystemClock.uptimeMillis()
    }

    // ---- Plumbing ---------------------------------------------------------------------------------

    private suspend fun <T> action(block: suspend Session.() -> T): T {
        val s = session(key())
        return s.lock.withLock {
            withContext(Dispatchers.Main) {
                sessionsBook.begin(s.key)
                try {
                    s.block()
                } catch (_: DialogOpen) {
                    throw ToolException(s.dialog?.let { dialogNote(it.info) } ?: "The page is paused by a dialog. Use browser_dialog.")
                } finally {
                    sessionsBook.end(s.key)
                }
            }
        }
    }

    private fun Session.activeTab(): Tab = active ?: newTab(this, null).also(::activate)

    private fun Session.ensure(): WebView = activeTab().view.also(::layoutOffscreen)

    /**
     * When the browser isn't on screen it has no parent to size it. Lay it out at the display size
     * so pages render at a normal phone viewport and element positions stay meaningful.
     */
    private fun layoutOffscreen(view: WebView) {
        if (view.isAttachedToWindow) return
        val metrics = appContext.resources.displayMetrics
        if (view.width == metrics.widthPixels && view.height > 0) return
        val w = android.view.View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, android.view.View.MeasureSpec.EXACTLY)
        val h = android.view.View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, android.view.View.MeasureSpec.EXACTLY)
        view.measure(w, h)
        view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun Session.ensurePage() {
        if (active?.hasPage != true) throw ToolException("No page is open. Use browser_open first.")
    }

    /** A new tab in [s]; first closes idle or least recently used other chats' tabs if the cap needs it ([makeRoom]). */
    private fun newTab(s: Session, opener: Tab?, force: Boolean = false): Tab {
        if (force) runCatching { makeRoom(s) } else makeRoom(s)
        val tab = Tab(s, opener, nextTabId++)
        tab.view = create(tab)
        s.tabs += tab
        sessionsBook.tabOpened(s.key)
        layoutOffscreen(tab.view)
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(tab: Tab): WebView {
        val s = tab.session
        val view = WebView(context)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(view, PROFILE)
        }
        // The helper goes in before any of the page's own scripts run, on every document this view loads,
        // and then locks window.__locus (see the end of locus-agent.js). Injected after load instead, a page
        // could define its own __locus first and answer the sensitive-action check (inspect) with harmless
        // facts. androidx.webkit: WebViewCompat.addDocumentStartJavaScript, guarded by
        // WebViewFeature.DOCUMENT_START_SCRIPT. The onPageFinished injection below stays as a fallback for
        // WebViews without the feature; the script is idempotent.
        //
        // Whether it did is checked on every answer, not assumed: the document-start copy carries the tab's
        // secret and the fallback copy doesn't, so answers from a page without the early helper (old
        // WebView, failed registration, or a page that got its own __locus in first) are unverified and the
        // sensitive-action gates ask (PageReply, SensitiveActions.untrusted).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(view, agentSource.replace(PageReply.SECRET_PLACEHOLDER, tab.secret), setOf("*"))
            }.onFailure { s.event("Locus's page helper couldn't be set up to run first in this tab (${it.javaClass.simpleName}); clicks and typing will ask the user.") }
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            // Pop-ups (OAuth windows, target=_blank) become tabs; see onCreateWindow.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Only web pages; intent://, tel:, market: and the like never leave the agent browser.
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                val description = error.description?.toString().orEmpty()
                tab.logs.add(PageLogs.Entry(PageLogs.Kind.NETWORK, "error", "${request.method} ${request.url} failed: $description"))
                if (request.isForMainFrame) tab.loadError = LoadErrors.explain(request.url.toString(), description)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: android.webkit.WebResourceResponse) {
                if (response.statusCode < 400) return
                tab.logs.add(PageLogs.Entry(PageLogs.Kind.NETWORK, "error", "${request.method} ${request.url} → ${response.statusCode} ${response.reasonPhrase.orEmpty()}".trim()))
                if (request.isForMainFrame) s.event(LoadErrors.httpStatus(request.url.toString(), response.statusCode, response.reasonPhrase.orEmpty()))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.logs.clear()
                tab.hasPage = true
                if (tab === s.active) s.state.update { it.copy(loading = true) }
                s.refreshState()
            }

            /** The page's renderer crashed or was killed for memory: drop this tab, keep the app alive. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                s.event("The page in tab ${s.tabs.indexOf(tab) + 1} crashed and its tab was closed. Open the page again.")
                close(tab)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(fallbackJs, null)
                if (tab === s.active) s.state.update { it.copy(loading = false) }
                s.refreshState()
                tab.pageLoad?.complete(Unit)
            }
        }
        view.setDownloadListener { url, userAgent, disposition, mime, length -> download(tab, url, userAgent, disposition, mime, length) }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab === s.active) s.state.update { it.copy(progress = newProgress) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) = s.refreshState()

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                val level = when (message.messageLevel()) {
                    android.webkit.ConsoleMessage.MessageLevel.ERROR -> "error"
                    android.webkit.ConsoleMessage.MessageLevel.WARNING -> "warning"
                    android.webkit.ConsoleMessage.MessageLevel.DEBUG -> "debug"
                    else -> "log"
                }
                val source = message.sourceId()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { " ($it:${message.lineNumber()})" }.orEmpty()
                tab.logs.add(PageLogs.Entry(PageLogs.Kind.CONSOLE, level, message.message() + source))
                return false
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // Per the WebView docs, refuse windows nobody asked for. Agent clicks are synthetic and
                // never carry a gesture, so a pop-up right after one counts as asked for.
                val agentAsked = SystemClock.uptimeMillis() - s.lastAgentGesture < POPUP_GRACE_MS
                if (!isUserGesture && !agentAsked) {
                    s.event("Blocked a pop-up window the page tried to open on its own.")
                    return false
                }
                if (s.tabs.size >= MAX_TABS) {
                    s.event("Blocked a new tab: $MAX_TABS tabs are open. Close one with browser_tabs.")
                    return false
                }
                val child = try {
                    newTab(s, tab)
                } catch (e: ToolException) {
                    s.event("Blocked a new tab: ${e.message}")
                    return false
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = child.view
                resultMsg.sendToTarget()
                activate(child)
                s.event("The page opened a new tab (tab ${s.tabs.size} of ${s.tabs.size}); it is now the active tab. browser_tabs lists and switches tabs.")
                return true
            }

            override fun onCloseWindow(window: WebView) {
                s.tabs.firstOrNull { it.view === window }?.let {
                    s.event("The page closed its tab (tab ${s.tabs.indexOf(it) + 1}).")
                    close(it)
                }
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm()
                s.event("The page showed an alert: “${SnapshotFormat.clip(message.orEmpty(), 200)}” (dismissed).")
                return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
                holdDialog(tab, "confirm", message.orEmpty(), "", result)

            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
                holdDialog(tab, "prompt", message.orEmpty(), defaultValue.orEmpty(), result)

            // "Leave site? Changes may not be saved": the agent or user already chose to navigate.
            override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm()
                return true
            }

            // Only files browser_upload armed in this chat (workspace files the user approved); anything else is cancelled.
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                val files = s.pendingChooser
                if (files == null) {
                    s.event("The page asked for a file. browser_upload can give it files from the workspace.")
                    return false
                }
                val chosen = if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) files else files.take(1)
                callback.onReceiveValue(chosen.map { android.net.Uri.fromFile(it) }.toTypedArray())
                s.pendingChooser = null
                s.chooserAnswered?.complete(Unit)
                return true
            }

            // Camera, microphone, location and the like are never granted to pages in the agent's browser.
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
        }
        return view
    }

    /** Waits until the page stops changing and has no requests in flight, up to a cap. */
    private suspend fun Session.settle() {
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            while (true) {
                val settled = js("(window.__locus && __locus.settled()) === true")
                if ((settled as? JsonPrimitive)?.contentOrNull == "true") break
                delay(250)
            }
        }
        refreshState()
    }

    private suspend fun Session.afterAction() {
        delay(300)
        settle()
        awaitDownloads()
    }

    /**
     * Runs [expression] after making sure the agent script is present, in [target] (default: the active
     * tab). Returns the parsed JSON result. A busy page, or one blocked by a JavaScript dialog, never
     * answers, so this never waits forever.
     */
    private suspend fun Session.js(expression: String, target: Tab? = null): JsonElement = reply(expression, target).value

    /** [js] with whether the answer came from the trusted helper, and from which document ([PageReply]). */
    private suspend fun Session.reply(expression: String, target: Tab? = null): PageReply {
        if (dialog != null) throw DialogOpen()
        val tab = target ?: activeTab()
        val view = if (target != null) target.view else ensure()
        val result = CompletableDeferred<String?>()
        view.evaluateJavascript("$fallbackJs\n;${PageReply.wrap(expression)}") { result.complete(it) }
        val raw = withTimeoutOrNull(JS_TIMEOUT_MS) {
            while (!result.isCompleted) {
                if (dialog != null) throw DialogOpen()
                delay(50)
            }
            result.await()
        } ?: throw ToolException("The page did not respond in time; it may be busy. Try browser_wait, then browser_snapshot.")
        // evaluateJavascript returns the JSON encoding of our JSON string.
        return PageReply.open(raw, tab.secret)
    }

    /** [expression], run only if the page is still document [doc] (when given); otherwise an error for the model. */
    private fun sameDocument(doc: String?, expression: String): String =
        if (doc == null) expression else "((window.__locus && __locus.doc === ${quote(doc)}) ? ($expression) : {error: ${quote(PAGE_CHANGED)}})"

    private fun tap(view: WebView, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }

    private fun quote(s: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))

    private fun JsonElement.stringOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val PROFILE = "locus-agent"

        /** The session for UI actions before any chat was on screen, and calls without a conversation. */
        const val DEFAULT_KEY = ""
        const val PAGE_CHANGED = "The page changed since the last snapshot (it navigated or reloaded), so its refs no longer apply. Take a new snapshot."
        const val NAVIGATION_TIMEOUT_MS = 20_000L
        const val SETTLE_TIMEOUT_MS = 8_000L
        const val JS_TIMEOUT_MS = 10_000L
        const val MAX_EVENTS = 20
        const val MAX_TABS = 6
        const val POPUP_GRACE_MS = 2_000L
        const val DOWNLOAD_WAIT_MS = 30_000L
        const val BLOB_WAIT_MS = 30_000L
        const val CHOOSER_WAIT_MS = 3_000L
        const val SAVE_TIMEOUT_MS = 20_000L
        const val PAINT_TIMEOUT_MS = 1_500L
        const val PAINT_TIMEOUT_OFFSCREEN_MS = 5_000L
        const val SCROLL_SYNC_TIMEOUT_MS = 1_000L

        // After a scroll, a moment for lazy images and scroll-triggered content to appear.
        const val TILE_SETTLE_MS = 250L

        // Fixed and sticky elements shorter than 40% of the viewport (headers, banners, chat
        // buttons) are hidden for full-page tiles after the first; taller ones are page layout.
        const val HIDE_FIXED_JS = "(function(){var n=0,lim=window.innerHeight*0.4;" +
            "document.querySelectorAll('body *').forEach(function(e){var p=getComputedStyle(e).position;" +
            "if((p==='fixed'||p==='sticky')&&e.getBoundingClientRect().height<lim){" +
            "e.setAttribute('data-locus-capture',e.style.getPropertyValue('visibility')+'|'+e.style.getPropertyPriority('visibility'));" +
            "e.style.setProperty('visibility','hidden','important');n++;}});return n;})()"
        const val RESTORE_FIXED_JS = "(function(){document.querySelectorAll('[data-locus-capture]').forEach(function(e){" +
            "var v=e.getAttribute('data-locus-capture').split('|');e.removeAttribute('data-locus-capture');" +
            "if(v[0])e.style.setProperty('visibility',v[0],v[1]);else e.style.removeProperty('visibility');});return true;})()"

        // Base64 text per script call; multiples of 4 so each chunk decodes on its own.
        const val BLOB_CHUNK_CHARS = 1_048_576
        const val UPLOAD_CHUNK_CHARS = 524_288
    }
}
