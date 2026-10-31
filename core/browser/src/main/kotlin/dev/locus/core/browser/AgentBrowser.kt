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
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.SendFile.Companion.readableSize
import dev.locus.core.agent.tools.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

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
 * All WebView calls happen on the main thread; [lock] serializes agent actions so two tool calls
 * never interleave on the same page.
 */
@Singleton
class AgentBrowser @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val workspace: Workspace,
) {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    /**
     * False while a read-only (background) run's tool call is running: a download writes into the
     * workspace, so it is refused then. Set around each browser tool call.
     */
    @Volatile
    var allowDownloads: Boolean = true

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Main thread only.
    private var downloadsInFlight = 0
    private var downloadsStarted = 0

    /** Workspace files for the next file chooser the page opens (browser_upload through a button). */
    private var pendingChooser: List<File>? = null
    private var chooserAnswered: CompletableDeferred<Unit>? = null

    private val lock = Mutex()
    private val context = MutableContextWrapper(appContext)
    private var handoff: CompletableDeferred<Unit>? = null
    private val agentJs: String by lazy { appContext.assets.open("locus-agent.js").bufferedReader().use { it.readText() } }

    private class Tab(val opener: Tab?) {
        lateinit var view: WebView
        var hasPage = false
        var pageLoad: CompletableDeferred<Unit>? = null

        /** Why the last main-frame load failed, set by the WebViewClient during a navigation. */
        var loadError: String? = null

        /** The last snapshot's element lines and URL, for describing what an action changed. */
        var lastBody: List<String>? = null
        var lastUrl: String? = null

        val logs = PageLogs()
    }

    private val tabs = mutableListOf<Tab>()
    private var active: Tab? = null

    private class Dialog(val tab: Tab, val info: PendingDialog, val result: JsResult)
    private var dialog: Dialog? = null

    /** Shows a WebView in the screen that currently displays the browser (see [attach]). */
    private var display: ((WebView) -> Unit)? = null

    private val events = mutableListOf<String>()
    private var lastAgentGesture = 0L

    /**
     * The active tab's WebView for display in [activity]. [show] is called with the new view when the
     * active tab changes while this display is the latest to attach.
     */
    fun attach(activity: Activity, show: (WebView) -> Unit): WebView {
        context.baseContext = activity
        display = show
        val view = (active ?: newTab(null).also(::activate)).view
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun detach(show: (WebView) -> Unit) {
        if (display === show) display = null
    }

    /** User finished the handoff (or dismissed it); the waiting tool call continues. */
    fun completeHandoff() {
        handoff?.complete(Unit)
    }

    /** Loads [url], in a new tab when [newTab]; throws with an explanation when it can't be loaded. */
    suspend fun open(url: String, newTab: Boolean = false) = action {
        if (newTab && active?.hasPage == true) {
            if (tabs.size >= MAX_TABS) throw ToolException("$MAX_TABS tabs are open. Close one with browser_tabs first.")
            activate(newTab(null))
        }
        navigate { it.loadUrl(url) }
    }

    suspend fun back(): Boolean = action {
        val view = ensure()
        if (!view.canGoBack()) return@action false
        navigate { it.goBack() }
        true
    }

    private suspend fun navigate(start: (WebView) -> Unit) {
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
     * snapshot is described by its changes only (see [SnapshotFormat.afterAction]).
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

    private suspend fun pageSnapshot(all: Boolean): PageSnapshot {
        val obj = js("__locus.snapshot($all)") as? JsonObject
            ?: throw ToolException("The page did not return a snapshot (it may still be loading). Try browser_wait, then browser_snapshot.")
        return PageSnapshot.parse(obj)
    }

    private fun context(): BrowserContext {
        val view = ensure()
        return BrowserContext(
            backSteps = view.copyBackForwardList().currentIndex.coerceAtLeast(0),
            tabCount = tabs.size,
            activeTab = tabs.indexOf(active).coerceAtLeast(0),
            dialog = dialog?.info?.let { "${it.type} \"${SnapshotFormat.clip(it.message, 120)}\"" },
        )
    }

    /** Facts about [ref] for deciding whether acting on it must be confirmed; null when it's gone. */
    suspend fun inspect(ref: String): ElementFacts? = action {
        ensurePage()
        if (dialog != null) return@action null
        val obj = js("__locus.inspect(${quote(ref)})") as? JsonObject ?: return@action null
        if (obj["error"] != null) null else ElementFacts.parse(obj)
    }

    /** Facts about the focused element, for key presses. */
    suspend fun inspectFocused(): ElementFacts? = action {
        ensurePage()
        if (dialog != null) return@action null
        (js("__locus.inspectFocused()") as? JsonObject)?.let(ElementFacts::parse)
    }

    /**
     * Clicks an element. It is scrolled into view and must hold still, be enabled and not be covered.
     * When the browser is on screen a real touch is sent (pages treat it as a trusted event);
     * otherwise the click is dispatched from script.
     */
    suspend fun click(ref: String): String? = action {
        ensurePage()
        clickNow(ref)
    }

    private suspend fun clickNow(ref: String): String? {
        val view = ensure()
        val rect = stableRect(ref) ?: return "Could not locate $ref. Take a new snapshot."
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
                (js("__locus.click(${quote(ref)})") as? JsonObject)?.string("error")?.let { return@orDialog it }
            }
            afterAction()
            null
        }
    }

    /** The element's position once it stops moving (animations, lazy layout), up to a few tries. */
    private suspend fun stableRect(ref: String): JsonObject? {
        var last: JsonObject? = null
        repeat(5) {
            val now = js("__locus.rect(${quote(ref)})") as? JsonObject ?: return last
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

    suspend fun type(ref: String, text: String, submit: Boolean): String? = action {
        ensurePage()
        if (submit) markAgentGesture()
        orDialog {
            val result = js("__locus.type(${quote(ref)}, ${quote(text)}, $submit)") as? JsonObject
            result?.string("error")?.let { return@orDialog it }
            afterAction()
            null
        }
    }

    suspend fun select(ref: String, value: String): String? = action {
        ensurePage()
        orDialog {
            (js("__locus.select(${quote(ref)}, ${quote(value)})") as? JsonObject)?.string("error")?.let { return@orDialog it }
            afterAction()
            null
        }
    }

    /** Fills several fields; returns one line per field that failed (empty when all worked). */
    suspend fun fill(fields: List<Pair<String, String>>): List<String> = action {
        ensurePage()
        val failed = mutableListOf<String>()
        orDialog {
            fields.forEach { (ref, value) -> (js("__locus.fill(${quote(ref)}, ${quote(value)})") as? JsonObject)?.string("error")?.let(failed::add) }
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
        val result = js("__locus.scroll(${quote(direction)}, ${ref?.let(::quote) ?: "null"})") as? JsonObject
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

    /** The active tab's console messages and failed requests since its page loaded. */
    fun logs(kind: String, errorsOnly: Boolean): String {
        val tab = active ?: throw ToolException("No page is open. Use browser_open first.")
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

    /** Saves what the browser shows (the visible part of the page) as a PNG at [file]. */
    suspend fun screenshot(file: File) = action {
        ensurePage()
        val view = ensure()
        if (view.width <= 0 || view.height <= 0) throw ToolException("The browser has no size yet; open a page first.")
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(android.graphics.Canvas(bitmap))
            withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        } finally {
            bitmap.recycle()
        }
    }

    // ---- Files ------------------------------------------------------------------------------------

    /**
     * Gives workspace [files] to the page. A file input (the element, one inside or labelling it, or
     * the form's) gets them directly, which needs no file picker; otherwise [ref] is clicked and the
     * file picker it opens is answered with them. Returns what happened.
     */
    suspend fun upload(ref: String, files: List<File>): String = action {
        ensurePage()
        val target = js("__locus.uploadTarget(${quote(ref)})") as? JsonObject ?: throw ToolException("Could not reach $ref. Take a new snapshot.")
        target.string("error")?.let { throw ToolException(it) }
        if (target.string("none") == "true") return@action uploadThroughChooser(ref, files)
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
        val result = js("__locus.fileSet(${quote(input)}, [${specs.joinToString(",")}])") as? JsonObject
        result?.string("error")?.let { throw ToolException(it) }
        orDialog { afterAction() }
        "Attached ${files.joinToString { it.name }} to the file input [$input]."
    }

    private suspend fun uploadThroughChooser(ref: String, files: List<File>): String {
        val answered = CompletableDeferred<Unit>()
        pendingChooser = files
        chooserAnswered = answered
        try {
            clickNow(ref)?.let { throw ToolException(it) }
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

    /** A page started a download (a link, a button, or browser_open of a file): save it into the workspace. */
    private fun download(tab: Tab, url: String, userAgent: String?, disposition: String?, mime: String?, length: Long) {
        // Counted even when refused: the navigation that led here stops waiting for a page either way.
        downloadsStarted++
        if (!allowDownloads) {
            event("Didn't download ${SnapshotFormat.clip(url, 120)}: this run is read-only.")
            return
        }
        if (length > Transfers.MAX_DOWNLOAD_BYTES) {
            event("Didn't download ${SnapshotFormat.clip(url, 120)}: it is ${readableSize(length)}, over the ${readableSize(Transfers.MAX_DOWNLOAD_BYTES)} limit.")
            return
        }
        var name = Transfers.sanitize(android.webkit.URLUtil.guessFileName(url, disposition, mime))
        if ('.' !in name) name += Transfers.extensionFor(mime.orEmpty())
        val referer = tab.view.url
        val cookies = cookies().getCookie(url)
        downloadsInFlight++
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
                downloadsInFlight--
                result
                    .onSuccess { event("Downloaded ${workspace.relative(it)} (${readableSize(it.length())}) into the workspace. send_file gives it to the user.") }
                    .onFailure { event("The download of $name failed: ${it.message}") }
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
        val id = "dl${SystemClock.uptimeMillis()}"
        withContext(Dispatchers.Main) { js("__locus.readBlob(${quote(url)}, ${quote(id)})", tab.view) }
        var length = -1
        withTimeoutOrNull(BLOB_WAIT_MS) {
            while (length < 0) {
                val job = withContext(Dispatchers.Main) { js("__locus.job(${quote(id)})", tab.view) } as? JsonObject
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
                val chunk = withContext(Dispatchers.Main) { js("__locus.readChunk(${quote(id)}, $start, $BLOB_CHUNK_CHARS)", tab.view) }
                val text = (chunk as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (text.isEmpty()) throw IOException("the page stopped handing it over")
                out.write(android.util.Base64.decode(text, android.util.Base64.DEFAULT))
                start += text.length
            }
        }
        withContext(Dispatchers.Main) { runCatching { js("__locus.endJob(${quote(id)})", tab.view) } }
    }

    /** Lets an action's downloads finish, so its result can report them. */
    private suspend fun awaitDownloads() {
        withTimeoutOrNull(DOWNLOAD_WAIT_MS) { while (downloadsInFlight > 0) delay(200) }
    }

    private fun cookies(): CookieManager =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager
        } else {
            CookieManager.getInstance()
        }

    // ---- Tabs -------------------------------------------------------------------------------------

    /** Makes tab [index] (0-based) the active one. */
    suspend fun selectTab(index: Int) = action {
        val tab = tabs.getOrNull(index) ?: throw ToolException("There is no tab ${index + 1}; ${tabs.size} open.")
        activate(tab)
    }

    /** Closes tab [index] (0-based); the opener or the last tab becomes active. */
    suspend fun closeTab(index: Int) = action {
        val tab = tabs.getOrNull(index) ?: throw ToolException("There is no tab ${index + 1}; ${tabs.size} open.")
        close(tab)
    }

    private fun activate(tab: Tab) {
        active = tab
        val show = display
        if (show != null) {
            (tab.view.parent as? ViewGroup)?.removeView(tab.view)
            show(tab.view)
        }
        refreshState()
    }

    private fun close(tab: Tab) {
        if (dialog?.tab === tab) answer(false, null)
        tabs.remove(tab)
        tab.pageLoad?.complete(Unit)
        (tab.view.parent as? ViewGroup)?.removeView(tab.view)
        tab.view.destroy()
        if (active === tab) {
            val next = tab.opener?.takeIf { it in tabs } ?: tabs.lastOrNull()
            if (next != null) activate(next) else { active = null; refreshState() }
        } else {
            refreshState()
        }
    }

    private fun refreshState() {
        val tab = active
        val view = tab?.view
        _state.update {
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
     * Answers the waiting confirm() or prompt(); [text] is the prompt's answer (its default when null).
     * Called by the agent's browser_dialog and by the user from the browser screen.
     */
    fun answerDialog(accept: Boolean, text: String?): Boolean {
        if (dialog == null) return false
        answer(accept, text)
        return true
    }

    private fun answer(accept: Boolean, text: String?) {
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
        // One at a time; a page that stacks dialogs gets "Cancel" for the extra ones.
        if (dialog != null) {
            result.cancel()
            return true
        }
        val info = PendingDialog(type, message, default)
        dialog = Dialog(tab, info, result)
        event("The page opened a $type dialog: “${SnapshotFormat.clip(message, 200)}”. The page waits until it is answered with browser_dialog.")
        refreshState()
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

    /** Forgets cookies, storage, history and tabs of the agent's browser. */
    suspend fun clearData() = withContext(Dispatchers.Main) {
        tabs.toList().forEach { it.view.clearCache(true); close(it) }
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

    /** What happened outside the agent's view since it last asked: load errors, dialogs, downloads, new tabs. */
    fun takeEvents(): List<String> = synchronized(events) { events.toList().also { events.clear() } }

    private fun event(text: String) = synchronized(events) {
        events += text
        if (events.size > MAX_EVENTS) events.removeAt(0)
    }

    /** Page script actions carry no user gesture; pop-ups opened right after one are let through. */
    private fun markAgentGesture() {
        lastAgentGesture = SystemClock.uptimeMillis()
    }

    // ---- Plumbing ---------------------------------------------------------------------------------

    private suspend fun <T> action(block: suspend () -> T): T = lock.withLock {
        withContext(Dispatchers.Main) {
            try {
                block()
            } catch (_: DialogOpen) {
                throw ToolException(dialog?.let { dialogNote(it.info) } ?: "The page is paused by a dialog. Use browser_dialog.")
            }
        }
    }

    private fun activeTab(): Tab = active ?: newTab(null).also(::activate)

    private fun ensure(): WebView = activeTab().view.also(::layoutOffscreen)

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
        if (active?.hasPage != true) throw ToolException("No page is open. Use browser_open first.")
    }

    private fun newTab(opener: Tab?): Tab {
        val tab = Tab(opener)
        tab.view = create(tab)
        tabs += tab
        layoutOffscreen(tab.view)
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(tab: Tab): WebView {
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
                if (request.isForMainFrame) event(LoadErrors.httpStatus(request.url.toString(), response.statusCode, response.reasonPhrase.orEmpty()))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.logs.clear()
                tab.hasPage = true
                if (tab === active) _state.update { it.copy(loading = true) }
                refreshState()
            }

            /** The page's renderer crashed or was killed for memory: drop this tab, keep the app alive. */
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                event("The page in tab ${tabs.indexOf(tab) + 1} crashed and its tab was closed. Open the page again.")
                close(tab)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(agentJs, null)
                if (tab === active) _state.update { it.copy(loading = false) }
                refreshState()
                tab.pageLoad?.complete(Unit)
            }
        }
        view.setDownloadListener { url, userAgent, disposition, mime, length -> download(tab, url, userAgent, disposition, mime, length) }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab === active) _state.update { it.copy(progress = newProgress) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) = refreshState()

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
                val agentAsked = SystemClock.uptimeMillis() - lastAgentGesture < POPUP_GRACE_MS
                if (!isUserGesture && !agentAsked) {
                    event("Blocked a pop-up window the page tried to open on its own.")
                    return false
                }
                if (tabs.size >= MAX_TABS) {
                    event("Blocked a new tab: $MAX_TABS tabs are open. Close one with browser_tabs.")
                    return false
                }
                val child = newTab(tab)
                (resultMsg.obj as WebView.WebViewTransport).webView = child.view
                resultMsg.sendToTarget()
                activate(child)
                event("The page opened a new tab (tab ${tabs.size} of ${tabs.size}); it is now the active tab. browser_tabs lists and switches tabs.")
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.view === window }?.let {
                    event("The page closed its tab (tab ${tabs.indexOf(it) + 1}).")
                    close(it)
                }
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                result.confirm()
                event("The page showed an alert: “${SnapshotFormat.clip(message.orEmpty(), 200)}” (dismissed).")
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

            // Only files browser_upload armed (workspace files the user approved); anything else is cancelled.
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                val files = pendingChooser
                if (files == null) {
                    event("The page asked for a file. browser_upload can give it files from the workspace.")
                    return false
                }
                val chosen = if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) files else files.take(1)
                callback.onReceiveValue(chosen.map { android.net.Uri.fromFile(it) }.toTypedArray())
                pendingChooser = null
                chooserAnswered?.complete(Unit)
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
    private suspend fun settle() {
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            while (true) {
                val settled = js("(window.__locus && __locus.settled()) === true")
                if ((settled as? JsonPrimitive)?.contentOrNull == "true") break
                delay(250)
            }
        }
        refreshState()
    }

    private suspend fun afterAction() {
        delay(300)
        settle()
        awaitDownloads()
    }

    /**
     * Runs [expression] after making sure the agent script is present. Returns the parsed JSON result.
     * A busy page, or one blocked by a JavaScript dialog, never answers, so this never waits forever.
     */
    private suspend fun js(expression: String, target: WebView? = null): JsonElement {
        if (dialog != null) throw DialogOpen()
        val view = target ?: ensure()
        val result = CompletableDeferred<String?>()
        view.evaluateJavascript("$agentJs\n;JSON.stringify((function(){ return $expression; })())") { result.complete(it) }
        val raw = withTimeoutOrNull(JS_TIMEOUT_MS) {
            while (!result.isCompleted) {
                if (dialog != null) throw DialogOpen()
                delay(50)
            }
            result.await()
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

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val PROFILE = "locus-agent"
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

        // Base64 text per script call; multiples of 4 so each chunk decodes on its own.
        const val BLOB_CHUNK_CHARS = 1_048_576
        const val UPLOAD_CHUNK_CHARS = 524_288
    }
}
