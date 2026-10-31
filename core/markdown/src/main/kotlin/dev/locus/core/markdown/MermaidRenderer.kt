package dev.locus.core.markdown

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** A drawn diagram and its natural size in dp (Mermaid's CSS pixels). */
class MermaidImage(val bitmap: ImageBitmap, val widthDp: Int, val heightDp: Int)

/**
 * Draws Mermaid diagrams to bitmaps in one shared, offscreen, locked-down WebView.
 *
 * - **One at a time.** Requests queue on a mutex, so there is never more than one WebView or one
 *   render in flight. A request whose card leaves the screen is cancelled while it waits.
 * - **Cached.** Results are kept by [diagramCacheKey] (an LRU by bitmap bytes), and failures are
 *   remembered too, so a diagram is drawn or tried once per process.
 * - **Sandboxed.** JavaScript runs only the bundled page (`assets/mermaid`), served through
 *   [WebViewAssetLoader]; every other request gets an empty 403, navigation is refused, file and
 *   content access are off, and there is no JavaScript interface. The app talks to the page only by
 *   evaluating `locusRender` and polling `locusTake`.
 * - **Short-lived.** The WebView is destroyed after [IDLE_MS] without work, when memory runs low, and
 *   if its renderer dies or a render hangs.
 */
@SuppressLint("StaticFieldLeak")
object MermaidRenderer {
    private val cache = object : LruCache<String, MermaidImage>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: MermaidImage) = value.bitmap.width * value.bitmap.height * 4
    }
    private val failures = LruCache<String, String>(256)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Built on the application context and destroyed when idle, so it holds no Activity.
    @SuppressLint("StaticFieldLeak")
    private var web: WebView? = null
    private var pageReady: CompletableDeferred<Boolean>? = null
    private var idleJob: Job? = null
    private var callbacksRegistered = false
    private var nextId = 0

    fun cached(key: String): MermaidImage? = cache.get(key)

    fun failure(key: String): String? = failures.get(key)

    /** Draws [source] (cache first) and returns the image, or null if it couldn't be drawn. */
    suspend fun render(context: Context, source: String, dark: Boolean): MermaidImage? {
        val key = diagramCacheKey(source, dark)
        cache.get(key)?.let { return it }
        if (failures.get(key) != null) return null
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            withContext(Dispatchers.Main.immediate) {
                idleJob?.cancel()
                val outcome = try {
                    drawOnce(context.applicationContext, source, dark)
                } finally {
                    scheduleIdleTeardown()
                }
                when (outcome) {
                    is MermaidOutcome.Drawn -> decode(outcome)?.also { cache.put(key, it) }
                        ?: null.also { failures.put(key, "The picture couldn't be decoded") }
                    is MermaidOutcome.Failed -> null.also { failures.put(key, outcome.message) }
                    null -> null // Timed out or the WebView is unavailable: not remembered, a later visit retries.
                }
            }
        }
    }

    private suspend fun drawOnce(context: Context, source: String, dark: Boolean): MermaidOutcome? {
        val view = runCatching { ensureWebView(context) }.getOrNull() ?: return null
        val ready = withTimeoutOrNull(LOAD_TIMEOUT_MS) { pageReady?.await() } == true &&
            evaluate(view, "window.locusReady === true") == "true"
        if (!ready) {
            teardown()
            return null
        }
        val id = (++nextId).toString()
        val density = context.resources.displayMetrics.density.coerceIn(1f, 3f)
        evaluate(view, "locusRender(${jsStringLiteral(id)}, ${jsStringLiteral(source)}, $dark, $density, $MAX_SIDE_PX, $MAX_AREA_PX)")
        val outcome = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            var result: MermaidOutcome? = null
            while (result == null) {
                delay(POLL_MS)
                if (web !== view) return@withTimeoutOrNull MermaidOutcome.Failed("The renderer stopped")
                result = parseMermaidTake(evaluate(view, "locusTake(${jsStringLiteral(id)})"))
            }
            result
        }
        if (outcome == null) {
            // A diagram that never finishes may have wedged the page; start fresh next time.
            teardown()
            return MermaidOutcome.Failed("Drawing took too long")
        }
        return outcome
    }

    private suspend fun decode(drawn: MermaidOutcome.Drawn): MermaidImage? = withContext(Dispatchers.Default) {
        runCatching {
            val bytes = Base64.decode(drawn.pngBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            MermaidImage(bitmap.asImageBitmap(), drawn.width, drawn.height)
        }.getOrNull()
    }

    private suspend fun evaluate(view: WebView, script: String): String? = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { if (cont.isActive) cont.resume(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context): WebView {
        web?.let { return it }
        registerTrimCallbacks(context)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val ready = CompletableDeferred<Boolean>()
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                val allowed = url.scheme == "https" && url.host == ASSET_HOST && url.path in PAGE_FILES
                return (if (allowed) loader.shouldInterceptRequest(url) else null) ?: blocked()
            }

            override fun onPageFinished(view: WebView, url: String) {
                ready.complete(url == PAGE_URL)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === web) teardown() else view.destroy()
                return true
            }
        }
        // Lay it out at screen size without attaching it to a window, as the agent browser does, so
        // the page has a viewport and Mermaid can measure text.
        val metrics = context.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        web = view
        pageReady = ready
        view.loadUrl(PAGE_URL)
        return view
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun scheduleIdleTeardown() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            if (!mutex.isLocked) teardown()
        }
    }

    private fun teardown() {
        val view = web ?: return
        web = null
        pageReady = null
        view.stopLoading()
        view.destroy()
    }

    private fun registerTrimCallbacks(context: Context) {
        if (callbacksRegistered) return
        callbacksRegistered = true
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // UI hidden or memory getting tight: the WebView and the pictures are cheap to redo.
                @Suppress("DEPRECATION")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    scope.launch { if (!mutex.isLocked) teardown() }
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.evictAll()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                cache.evictAll()
            }
        })
    }

    private const val ASSET_HOST = "appassets.androidplatform.net"
    private const val PAGE_URL = "https://$ASSET_HOST/assets/mermaid/render.html"
    private val PAGE_FILES = setOf("/assets/mermaid/render.html", "/assets/mermaid/render.js", "/assets/mermaid/mermaid.tiny.js")
    private const val CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_SIDE_PX = 4096
    private const val MAX_AREA_PX = 6_000_000
    private const val POLL_MS = 40L
    private const val LOAD_TIMEOUT_MS = 10_000L
    private const val RENDER_TIMEOUT_MS = 12_000L
    private const val IDLE_MS = 45_000L
}
