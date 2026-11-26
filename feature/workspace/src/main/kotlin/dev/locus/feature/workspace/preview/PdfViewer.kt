package dev.locus.feature.workspace.preview

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import dev.locus.core.designsystem.Sizes
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.PasswordField
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.feature.workspace.R
import androidx.core.graphics.createBitmap
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 4f

/** A rendered page, with the page's shape (width / height) as the PDF gives it. */
private class RenderedPage(val bitmap: Bitmap, val aspect: Float)

/**
 * One open PDF. [PdfRenderer] allows only one open page at a time and is not thread-safe, so every
 * call goes through [lock] on a background dispatcher. Page bitmaps are capped ([PdfMemory.pageSize]),
 * and rendered pages are kept in a byte-budgeted LRU cache ([PdfMemory.CACHE_BYTES]); evicted bitmaps
 * are simply dropped (not `recycle()`d) because one may still be on screen, and since API 26 bitmap
 * pixels are native memory freed once nothing references the bitmap.
 */
private class PdfDocument(file: File, password: String?, private val io: CoroutineDispatcher) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int

    /**
     * Page shapes (width / height), each read when its page first renders, so opening a long PDF
     * doesn't open every page first. NaN until then; written under [lock], read anywhere (a stale
     * read only means the first page's shape for a moment).
     */
    private val aspects: FloatArray

    /** The first page's shape: what a page not rendered yet is laid out with. */
    val firstAspect: Float

    init {
        try {
            renderer = if (password != null && Build.VERSION.SDK_INT >= 35) {
                PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
            } else {
                PdfRenderer(pfd)
            }
        } catch (e: Throwable) {
            pfd.close()
            throw e
        }
        try {
            pageCount = renderer.pageCount
            aspects = FloatArray(pageCount) { Float.NaN }
            firstAspect = if (pageCount == 0) 1f else renderer.openPage(0).use { shapeOf(it) }.also { aspects[0] = it }
        } catch (e: Throwable) {
            runCatching { renderer.close() }
            pfd.close()
            throw e
        }
    }

    private fun shapeOf(page: PdfRenderer.Page) = page.width.toFloat() / page.height.coerceAtLeast(1)

    /** Page [index]'s shape when known, else the first page's. */
    fun aspect(index: Int): Float = aspects[index].takeUnless { it.isNaN() } ?: firstAspect

    private val cache = PageCache<Long, RenderedPage>(PdfMemory.CACHE_BYTES) { it.bitmap.allocationByteCount.toLong() }

    fun cached(index: Int, widthPx: Int): RenderedPage? = cache[key(index, widthPx)]

    suspend fun render(index: Int, widthPx: Int): RenderedPage? = lock.withLock {
        cache[key(index, widthPx)]?.let { return@withLock it }
        if (closed) return@withLock null
        withContext(io) {
            renderer.openPage(index).use { page ->
                val aspect = shapeOf(page).also { aspects[index] = it }
                val (w, h) = PdfMemory.pageSize(aspect, widthPx)
                val bitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
                // Pages render with a transparent background; paper is white.
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                // This page at another size isn't kept: the screen shows this one now.
                cache.removeIf { it ushr 32 == index.toLong() }
                RenderedPage(bitmap, aspect).also { cache.put(key(index, widthPx), it) }
            }
        }
    }

    private fun key(index: Int, widthPx: Int) = (index.toLong() shl 32) or widthPx.toLong()

    /** Closes off the main thread, after any in-flight render, rather than closing the renderer under it. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun closeLater() {
        kotlinx.coroutines.GlobalScope.launch(io) {
            lock.withLock {
                closed = true
                cache.clear()
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

private sealed interface PdfState {
    data object Loading : PdfState
    data class Ready(val doc: PdfDocument) : PdfState
    data class NeedsPassword(val wrong: Boolean) : PdfState
    data class Failed(val message: UiText) : PdfState
}

/**
 * The PDF viewer: pages top to bottom at screen width, rendered as they scroll into view, with page
 * numbers. Pinch to zoom (up to 4×), drag sideways to pan when zoomed, double-tap to zoom in/out.
 * Pages re-render sharper once a zoom settles. [onPageCount] reports the count for the top bar.
 */
@Composable
internal fun PdfViewer(file: File, modifier: Modifier = Modifier, onPageCount: (Int) -> Unit = {}) {
    // Kept across a rotation, like every secret in a form (docs/design/design-system.md, "Secrets"): otherwise
    // turning the phone re-opens the file and asks for the password again.
    var password by rememberSaveable(file.path) { mutableStateOf<String?>(null) }
    val work = LocalPreviewDispatchers.current
    val state by produceState<PdfState>(PdfState.Loading, file, password) {
        value = PdfState.Loading
        value = withContext(work.io) {
            try {
                PdfState.Ready(PdfDocument(file, password, work.io))
            } catch (_: SecurityException) {
                // PdfRenderer throws SecurityException for password-protected or unsupported-security files.
                PdfState.NeedsPassword(wrong = password != null)
            } catch (e: Exception) {
                PdfState.Failed(UiText.Plain(e.message ?: e.javaClass.simpleName))
            } catch (_: OutOfMemoryError) {
                PdfState.Failed(UiText.Res(R.string.workspace_preview_pdf_no_memory))
            }
        }
    }
    val ready = state as? PdfState.Ready
    DisposableEffect(ready) { onDispose { ready?.doc?.closeLater() } }
    LaunchedEffect(ready) { ready?.let { onPageCount(it.doc.pageCount) } }

    when (val s = state) {
        // Opening reads only the first page, but a big file can still take a moment.
        PdfState.Loading -> LoadingState(modifier)
        is PdfState.Failed -> EmptyState(
            stringResource(R.string.workspace_preview_pdf_failed_title),
            stringResource(R.string.workspace_preview_pdf_failed_body, s.message.asString()),
            modifier,
            icon = LocusIcons.File,
        )
        is PdfState.NeedsPassword -> PasswordPrompt(s.wrong, modifier) { password = it }
        is PdfState.Ready -> if (s.doc.pageCount == 0) {
            EmptyState(stringResource(R.string.workspace_preview_pdf_empty_title), stringResource(R.string.workspace_preview_pdf_empty_body), modifier, icon = LocusIcons.File)
        } else {
            PdfPages(s.doc, modifier)
        }
    }
}

@Composable
private fun PasswordPrompt(wrong: Boolean, modifier: Modifier, onSubmit: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < 35) {
        EmptyState(
            stringResource(R.string.workspace_preview_pdf_protected_title),
            stringResource(R.string.workspace_preview_pdf_protected_unsupported),
            modifier,
            icon = LocusIcons.Key,
        )
        return
    }
    var text by rememberSaveable { mutableStateOf("") }
    EmptyState(
        stringResource(R.string.workspace_preview_pdf_protected_title),
        stringResource(if (wrong) R.string.workspace_preview_pdf_password_wrong else R.string.workspace_preview_pdf_password_prompt),
        modifier,
        icon = LocusIcons.Key,
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PasswordField(
                    text,
                    { text = it },
                    label = stringResource(R.string.workspace_preview_pdf_password),
                    modifier = Modifier.widthIn(max = Sizes.readable),
                    imeAction = ImeAction.Go,
                    keyboardActions = KeyboardActions(onGo = { if (text.isNotEmpty()) onSubmit(text) }),
                )
                Button(onClick = { onSubmit(text) }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.workspace_preview_pdf_open)) }
            }
        },
    )
}

@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalCoroutinesApi::class)
@Composable
private fun PdfPages(doc: PdfDocument, modifier: Modifier) {
    val listState = rememberLazyListState()
    var scale by remember(doc) { mutableFloatStateOf(1f) }
    var panX by remember(doc) { mutableFloatStateOf(0f) }
    // Render resolution follows zoom in whole steps once the pinch settles, so pages sharpen without re-rendering every frame.
    var quality by remember(doc) { mutableIntStateOf(1) }
    LaunchedEffect(doc) {
        snapshotFlow { scale }.debounce(250).distinctUntilChanged().collect { quality = ceil(it.coerceAtMost(3f)).toInt().coerceAtLeast(1) }
    }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val pagePadPx = with(density) { Spacing.sm.toPx() }
        val baseWidth = (widthPx - pagePadPx * 2).toInt().coerceAtLeast(1)
        val sharpWidth = (baseWidth * quality).coerceAtMost(PdfMemory.MAX_WIDTH)
        // Zoomed in, the list is laid out unzoomed and only its top 1/scale shows: only pages in that part
        // render at the sharper size; the others stay at screen width (a bitmap each, not a zoomed one).
        val sharp by remember(listState, heightPx) {
            derivedStateOf {
                val shown = heightPx / scale
                listState.layoutInfo.visibleItemsInfo.filter { it.offset < shown && it.offset + it.size > 0 }.mapTo(HashSet()) { it.index }
            }
        }

        fun zoomBy(factor: Float, centroid: Offset, pan: Offset) {
            val old = scale
            val new = (old * factor).coerceIn(1f, MAX_ZOOM)
            // Keep the content under the fingers in place: x by panning, y by scrolling the list.
            panX = ((panX + centroid.x) * new / old - centroid.x - pan.x).coerceIn(0f, widthPx * (new - 1f))
            val dy = centroid.y / old - centroid.y / new - pan.y / new
            scale = new
            listState.dispatchRawDelta(dy)
        }

        val hScroll = rememberScrollableState { delta ->
            val old = panX
            panX = (panX - delta).coerceIn(0f, widthPx * (scale - 1f))
            old - panX
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(doc) {
                    // Two-finger pinch is handled before the list sees it (Initial pass), so the list only gets one-finger scrolls.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                zoomBy(event.calculateZoom(), event.calculateCentroid(), event.calculatePan())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(doc) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (scale > 1.05f) zoomBy(1f / scale, at, Offset.Zero) else zoomBy(2.5f, at, Offset.Zero)
                    })
                }
                .scrollable(hScroll, Orientation.Horizontal, enabled = scale > 1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = -panX
                },
            // When zoomed only the top 1/scale of the list is visible, so extra bottom space lets the last page reach it.
            contentPadding = PaddingValues(
                start = Spacing.sm,
                end = Spacing.sm,
                top = Spacing.sm,
                bottom = Spacing.xxl + with(density) { (heightPx * (1f - 1f / scale)).toDp() },
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(doc.pageCount, key = { it }) { index ->
                PdfPage(doc, index, if (index in sharp) sharpWidth else baseWidth)
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, renderWidth: Int) {
    // Keep showing the last bitmap while a sharper one renders.
    var shown by remember(doc, index) { mutableStateOf(doc.cached(index, renderWidth)) }
    var failed by remember(doc, index) { mutableStateOf(false) }
    LaunchedEffect(doc, index, renderWidth) {
        val page = runCatching { doc.render(index, renderWidth) }.getOrElse { e ->
            if (e is OutOfMemoryError) System.gc()
            null
        }
        if (page != null) shown = page else if (shown == null) failed = true
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // A page not rendered yet takes the first page's shape until its own is read.
        val aspect = shown?.aspect ?: doc.aspect(index)
        // design-exception: a PDF page is paper, white in both themes, and its fallback text is ink on that paper
        Box(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.1f, 10f)).background(Color.White)) {
            val bitmap = shown?.bitmap
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = stringResource(R.string.workspace_preview_pdf_page, index + 1), modifier = Modifier.fillMaxSize())
            } else if (failed) {
                Text(stringResource(R.string.workspace_preview_pdf_render_failed), style = LocusType.mono, color = Color.DarkGray, modifier = Modifier.align(Alignment.Center)) // design-exception: ink on the white page above
            }
        }
        SupportingText(stringResource(R.string.workspace_preview_progress, index + 1, doc.pageCount), style = LocusType.mono, modifier = Modifier.padding(top = Spacing.xs))
    }
}
