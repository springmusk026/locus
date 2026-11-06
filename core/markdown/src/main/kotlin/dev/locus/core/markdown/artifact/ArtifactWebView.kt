package dev.locus.core.markdown.artifact

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.UUID

/** What a live preview reports back to its card. Called on the main thread. */
interface ArtifactEvents {
    /** The host page finished loading. */
    fun loaded() {}

    /** The artifact asked for something outside the sandbox (a CDN script, a fetch…), which was refused. */
    fun blocked() {}

    /** The user tapped a link; the card asks before opening it in the browser. */
    fun link(url: String) {}

    /** The renderer crashed or was killed; the WebView is gone. */
    fun crashed() {}
}

/**
 * Builds the WebView for one live preview. Everything that keeps the artifact in its box is here:
 *
 * - **Own profile.** On WebViews with multi-profile support (androidx.webkit `MULTI_PROFILE`) it runs
 *   in the `locus-artifact` profile, apart from the agent browser's cookies and storage; cookies are
 *   refused there anyway.
 * - **No storage, files or bridges.** DOM storage and databases off, no file or content access, no
 *   JavaScript interface, no WebChromeClient (so no permission prompts, pop-ups or file pickers).
 * - **No network.** Every request goes through [ArtifactSandbox.decide]: this preview's two documents
 *   come from memory through [WebViewAssetLoader]; data: and blob: stay local; the rest gets a 403.
 *   WebSockets, which shouldInterceptRequest doesn't see, are covered by the CSP's connect-src.
 * - **No navigation.** shouldOverrideUrlLoading refuses everything; tapped http(s) links go to the
 *   card to offer "Open in browser?".
 * - **Safe Browsing** on where it can be set.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createArtifactWebView(context: Context, artifact: Artifact, background: Int, backgroundCss: String, events: ArtifactEvents): WebView {
    registerTrimCallbacks(context.applicationContext)
    val id = UUID.randomUUID().toString().replace("-", "")
    val host = ArtifactSandbox.hostDocument(artifact.title ?: "${artifact.kind.label} artifact", backgroundCss, ArtifactSandbox.frameScheme(artifact)).toByteArray()
    val content = ArtifactSandbox.contentDocument(artifact).toByteArray()
    val loader = WebViewAssetLoader.Builder()
        .setDomain(ArtifactSandbox.HOST)
        .addPathHandler(ArtifactSandbox.basePath(id)) { path ->
            when (path) {
                ArtifactSandbox.HOST_FILE -> document(host, ArtifactSandbox.HOST_CSP)
                ArtifactSandbox.CONTENT_FILE -> document(content, ArtifactSandbox.CONTENT_HEADER_CSP)
                else -> null
            }
        }
        .build()
    val main = Handler(Looper.getMainLooper())
    val view = WebView(context)
    // Fill the space Compose gives it. AndroidView's default WRAP_CONTENT puts WebView in its
    // content-height mode, where the host page's `height:100%` resolves to 0: the frame was 0px tall
    // and the card showed only the background, black in dark mode.
    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
        // Must come before the WebView loads anything.
        runCatching {
            WebViewCompat.setProfile(view, PROFILE)
            ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager.setAcceptCookie(false)
        }
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
    view.setBackgroundColor(background)
    // Off screen (a dialog over it, the app in the background) the renderer may be killed first.
    view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
    view.settings.apply {
        javaScriptEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        @Suppress("DEPRECATION")
        databaseEnabled = false
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        saveFormData = false
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(view.settings, true)
    }
    // The page decides its own dark styling (its color-scheme, see ArtifactSandbox.hostDocument); no automatic darkening.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
    }
    view.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            ArtifactSandbox.linkToOffer(request.url.toString(), request.hasGesture())?.let { url -> main.post { events.link(url) } }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            when (ArtifactSandbox.decide(request.url.toString(), id)) {
                ArtifactSandbox.Decision.Host, ArtifactSandbox.Decision.Content -> loader.shouldInterceptRequest(request.url) ?: blocked()
                ArtifactSandbox.Decision.Local -> null
                ArtifactSandbox.Decision.Block -> blocked().also { main.post { events.blocked() } }
            }

        override fun onPageFinished(view: WebView, url: String) {
            if (url == ArtifactSandbox.hostUrl(id)) events.loaded()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // A crashed or killed renderer must not take the app down. The card stops using this view
            // and removes it, which destroys it (AndroidView's onRelease).
            events.crashed()
            return true
        }
    }
    view.loadUrl(ArtifactSandbox.hostUrl(id))
    return view
}

private fun document(bytes: ByteArray, csp: String) =
    WebResourceResponse("text/html", "utf-8", 200, "OK", ArtifactSandbox.headers(csp), ByteArrayInputStream(bytes))

private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/**
 * What the WebView shows now, as a bitmap at most [maxWidth] pixels wide (the snapshot and Share as
 * image). Null if it hasn't been laid out or can't draw.
 */
fun captureArtifact(view: WebView, maxWidth: Int = SNAPSHOT_MAX_WIDTH): Bitmap? = runCatching {
    val w = view.width
    val h = view.height
    if (w <= 0 || h <= 0) return@runCatching null
    val scale = minOf(1f, maxWidth.toFloat() / w)
    val bitmap = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(scale, scale)
    canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
    view.draw(canvas)
    bitmap
}.getOrNull()

/** The process-wide live slot and snapshots for artifact previews. */
object ArtifactPreviews {
    val slot = ArtifactLiveSlot()
    val snapshots = SnapshotCache<ImageBitmap>(SNAPSHOT_BYTES) { it.width.toLong() * it.height * 4 }

    fun remember(key: String, bitmap: Bitmap) = snapshots.put(key, bitmap.asImageBitmap())
}

private const val PROFILE = "locus-artifact"
private const val SNAPSHOT_MAX_WIDTH = 720
private const val SNAPSHOT_BYTES = 16L * 1024 * 1024

private var trimRegistered = false

private fun registerTrimCallbacks(context: Context) {
    if (trimRegistered) return
    trimRegistered = true
    context.registerComponentCallbacks(object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // Snapshots are cheap to redo: drop them once the app is in the background.
            @Suppress("DEPRECATION")
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) ArtifactPreviews.snapshots.clear()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = ArtifactPreviews.snapshots.clear()
    })
}
