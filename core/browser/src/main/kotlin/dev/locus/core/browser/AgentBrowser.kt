package dev.locus.core.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.locus.core.agent.ToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class BrowserState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    /** Set while the agent has handed control to the user (log in, solve a CAPTCHA, pay). */
    val handoffReason: String? = null,
    val hasPage: Boolean = false,
)

/**
 * The agent's own browser: one WebView, created lazily, with its own cookie and storage profile
 * where WebView supports it, so the agent never sees the user's other web sessions.
 *
 * All WebView calls happen on the main thread; [lock] serializes agent actions so two tool calls
 * never interleave on the same page.
 */
@Singleton
class AgentBrowser @Inject constructor(@param:ApplicationContext private val appContext: Context) {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    private val lock = Mutex()
    private val context = MutableContextWrapper(appContext)
    private var webView: WebView? = null
    private var pageLoad: CompletableDeferred<Unit>? = null
    private var handoff: CompletableDeferred<Unit>? = null

    /** The last snapshot's element lines and URL, for describing what an action changed. */
    private var lastBody: List<String>? = null
    private var lastUrl: String? = null
    private val agentJs: String by lazy { appContext.assets.open("locus-agent.js").bufferedReader().use { it.readText() } }

    /** The live WebView for display. Re-parents it into [parent]'s window context. */
    fun viewFor(activity: Activity): WebView {
        val view = webView ?: create()
        context.baseContext = activity
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    /** User finished the handoff (or dismissed it); the waiting tool call continues. */
    fun completeHandoff() {
        handoff?.complete(Unit)
    }

    suspend fun open(url: String) = action {
        val view = ensure()
        val load = CompletableDeferred<Unit>().also { pageLoad = it }
        view.loadUrl(url)
        withTimeoutOrNull(NAVIGATION_TIMEOUT_MS) { load.await() }
        settle()
    }

    suspend fun back(): Boolean = action {
        val view = ensure()
        if (!view.canGoBack()) return@action false
        val load = CompletableDeferred<Unit>().also { pageLoad = it }
        view.goBack()
        withTimeoutOrNull(NAVIGATION_TIMEOUT_MS) { load.await() }
        settle()
        true
    }

    /**
     * The page as text for the model. With [diff], a page that mostly didn't change since the last
     * snapshot is described by its changes only (see [SnapshotFormat.afterAction]).
     */
    suspend fun describe(all: Boolean = false, diff: Boolean = false): String = action {
        ensurePage()
        val snap = pageSnapshot(all)
        val context = context()
        val text = if (diff) {
            SnapshotFormat.afterAction(lastBody, lastUrl, snap, context)
        } else {
            SnapshotFormat.lines(snap, context).joinToString("\n")
        }
        lastBody = SnapshotFormat.body(snap)
        lastUrl = snap.url
        text
    }

    /** Elements and text passages matching [query] anywhere on the page. */
    suspend fun find(query: String): String = action {
        ensurePage()
        val snap = pageSnapshot(all = true)
        val passages = (js("__locus.findText(${quote(query)}, 10)") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        SnapshotFormat.find(snap, query, passages)
    }

    private suspend fun pageSnapshot(all: Boolean): PageSnapshot {
        val obj = js("__locus.snapshot($all)") as? JsonObject
            ?: throw ToolException("The page did not return a snapshot (it may still be loading). Try browser_wait, then browser_snapshot.")
        return PageSnapshot.parse(obj)
    }

    private fun context(): BrowserContext {
        val view = ensure()
        return BrowserContext(backSteps = view.copyBackForwardList().currentIndex.coerceAtLeast(0))
    }

    /** Element facts used to decide whether a click needs confirmation. */
    suspend fun inspect(ref: String): ElementInfo? = action {
        ensurePage()
        val result = js("__locus.inspect(${quote(ref)})")
        val obj = result as? kotlinx.serialization.json.JsonObject ?: return@action null
        if (obj["error"] != null) return@action null
        ElementInfo(
            label = obj.string("label"),
            role = obj.string("role"),
            inForm = (obj["inForm"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    /**
     * Clicks an element. When the browser is on screen a real touch is sent (pages treat it as a
     * trusted event); otherwise the click is dispatched from script.
     */
    suspend fun click(ref: String): String? = action {
        ensurePage()
        val view = ensure()
        val rect = js("__locus.rect(${quote(ref)})") as? kotlinx.serialization.json.JsonObject
            ?: return@action "Could not locate $ref."
        rect.string("error")?.let { return@action it }
        rect.string("covered")?.let { return@action "$ref is covered by another element ($it). Close the overlay first, or scroll." }
        if (view.isAttachedToWindow && view.isShown && view.width > 0) {
            val density = view.resources.displayMetrics.density
            val x = (rect.string("x")?.toFloatOrNull() ?: 0f) * density
            val y = (rect.string("y")?.toFloatOrNull() ?: 0f) * density
            tap(view, x, y)
        } else {
            (js("__locus.click(${quote(ref)})") as? kotlinx.serialization.json.JsonObject)?.string("error")?.let { return@action it }
        }
        afterAction()
        null
    }

    suspend fun type(ref: String, text: String, submit: Boolean): String? = action {
        ensurePage()
        val result = js("__locus.type(${quote(ref)}, ${quote(text)}, $submit)") as? kotlinx.serialization.json.JsonObject
        result?.string("error")?.let { return@action it }
        afterAction()
        null
    }

    suspend fun select(ref: String, value: String): String? = action {
        ensurePage()
        (js("__locus.select(${quote(ref)}, ${quote(value)})") as? kotlinx.serialization.json.JsonObject)?.string("error")?.let { return@action it }
        afterAction()
        null
    }

    suspend fun pressKey(key: String) = action {
        ensurePage()
        js("__locus.key(${quote(key)})")
        afterAction()
    }

    suspend fun scroll(direction: String, ref: String?): String? = action {
        ensurePage()
        (js("__locus.scroll(${quote(direction)}, ${ref?.let(::quote) ?: "null"})") as? kotlinx.serialization.json.JsonObject)
            ?.string("error")?.let { return@action it }
        delay(400)
        null
    }

    /** The current page's HTML, for readable-text extraction. */
    suspend fun html(): String = action {
        ensurePage()
        js("document.documentElement.outerHTML").stringOrEmpty()
    }

    /** Suspends until the user taps Done on the browser screen. */
    suspend fun handOff(reason: String) {
        val done = CompletableDeferred<Unit>().also { handoff = it }
        _state.update { it.copy(handoffReason = reason) }
        try {
            done.await()
        } finally {
            handoff = null
            _state.update { it.copy(handoffReason = null) }
        }
    }

    /** Forgets cookies, storage and history of the agent's browser. */
    suspend fun clearData() = withContext(Dispatchers.Main) {
        webView?.apply {
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val profile = androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        } else {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        _state.value = BrowserState()
    }

    private suspend fun <T> action(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.Main) { block() } }

    private fun ensure(): WebView = (webView ?: create()).also(::layoutOffscreen)

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

    private fun ensurePage() {
        if (!_state.value.hasPage) throw IllegalStateException("No page is open. Use browser_open first.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val view = WebView(context)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(view, PROFILE)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Only web pages; intent://, tel:, market: and the like never leave the agent browser.
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                _state.update { it.copy(url = url, loading = true, hasPage = true) }
            }

            /** The page's renderer crashed or was killed for memory: drop this WebView, keep the app alive. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (webView === view) webView = null
                pageLoad?.complete(Unit)
                _state.value = BrowserState()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(agentJs, null)
                _state.update { it.copy(url = url, title = view.title.orEmpty(), loading = false, canGoBack = view.canGoBack()) }
                pageLoad?.complete(Unit)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _state.update { it.copy(progress = newProgress) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                _state.update { it.copy(title = title.orEmpty()) }
            }
        }
        webView = view
        return view
    }

    /** Waits until the page stops changing and has no requests in flight, up to a cap. */
    private suspend fun settle() {
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            while (true) {
                val settled = js("(window.__locus && __locus.settled()) === true")
                if ((settled as? JsonPrimitive)?.contentOrNull == "true") break
                delay(250)
            }
        }
        val view = ensure()
        _state.update { it.copy(title = view.title.orEmpty(), url = view.url.orEmpty(), canGoBack = view.canGoBack()) }
    }

    private suspend fun afterAction() {
        delay(300)
        settle()
    }

    /** Runs [expression] after making sure the agent script is present. Returns the parsed JSON result. */
    private suspend fun js(expression: String): JsonElement {
        val view = ensure()
        // A busy page, or one blocked by a JavaScript dialog, never answers: don't hang the run.
        val raw = withTimeoutOrNull(JS_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                view.evaluateJavascript("$agentJs\n;JSON.stringify((function(){ return $expression; })())") { cont.resume(it) }
            }
        } ?: throw ToolException("The page did not respond in time; it may be busy. Try browser_wait, then browser_snapshot.")
        // evaluateJavascript returns the JSON encoding of our JSON string.
        val inner = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: return JsonNull
        return runCatching { Json.parseToJsonElement(inner) }.getOrDefault(JsonPrimitive(inner))
    }

    private fun tap(view: WebView, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
    }

    private fun quote(s: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))

    private fun JsonElement.stringOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    data class ElementInfo(val label: String?, val role: String?, val inForm: Boolean)

    private companion object {
        const val PROFILE = "locus-agent"
        const val NAVIGATION_TIMEOUT_MS = 20_000L
        const val SETTLE_TIMEOUT_MS = 8_000L
        const val JS_TIMEOUT_MS = 10_000L
    }
}
