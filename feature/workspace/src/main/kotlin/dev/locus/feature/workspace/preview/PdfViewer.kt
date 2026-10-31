package dev.locus.feature.workspace.preview

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

private const val MAX_ZOOM = 4f

/**
 * One open PDF. [PdfRenderer] allows only one open page at a time and is not thread-safe, so every
 * call goes through [lock] on a background dispatcher. Rendered pages live in a byte-budgeted LRU
 * cache; evicted bitmaps are simply dropped (not `recycle()`d) because one may still be on screen,
 * and since API 26 bitmap pixels are native memory freed once nothing references the bitmap.
 */
private class PdfDocument(file: File, password: String?) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int

    /** Page shapes (width / height), read once up front so the list can lay out before anything renders. */
    val aspect: FloatArray

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
            aspect = FloatArray(pageCount) { i -> renderer.openPage(i).use { it.width.toFloat() / it.height.coerceAtLeast(1) } }
        } catch (e: Throwable) {
            runCatching { renderer.close() }
            pfd.close()
            throw e
        }
    }

    private val cache = object : LruCache<Long, Bitmap>(
        // An eighth of the heap, at least ~24 MB: a few pages at screen width, more at lower zoom.
        (Runtime.getRuntime().maxMemory() / 8).coerceAtLeast(24L shl 20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(key(index, widthPx))

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        cache.get(key(index, widthPx))?.let { return@withLock it }
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            val height = (widthPx / aspect[index]).toInt().coerceIn(1, 16_384)
            val bitmap = createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            // Pages render with a transparent background; paper is white.
            bitmap.eraseColor(AndroidColor.WHITE)
            renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            cache.put(key(index, widthPx), bitmap)
            bitmap
        }
    }

    private fun key(index: Int, widthPx: Int) = (index.toLong() shl 32) or widthPx.toLong()

    /** Closes off the main thread, after any in-flight render, rather than closing the renderer under it. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun closeLater() {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            lock.withLock {
                closed = true
                cache.evictAll()
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
    data class Failed(val message: String) : PdfState
}

/**
 * The PDF viewer: pages top to bottom at screen width, rendered as they scroll into view, with page
 * numbers. Pinch to zoom (up to 4×), drag sideways to pan when zoomed, double-tap to zoom in/out.
 * Pages re-render sharper once a zoom settles. [onPageCount] reports the count for the top bar.
 */
@Composable
internal fun PdfViewer(file: File, modifier: Modifier = Modifier, onPageCount: (Int) -> Unit = {}) {
    var password by remember(file) { mutableStateOf<String?>(null) }
    val state by produceState<PdfState>(PdfState.Loading, file, password) {
        value = PdfState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                PdfState.Ready(PdfDocument(file, password))
            } catch (_: SecurityException) {
                // PdfRenderer throws SecurityException for password-protected or unsupported-security files.
                PdfState.NeedsPassword(wrong = password != null)
            } catch (e: Exception) {
                PdfState.Failed(e.message ?: e.javaClass.simpleName)
            } catch (_: OutOfMemoryError) {
                PdfState.Failed("Not enough memory to open this PDF.")
            }
        }
    }
    val ready = state as? PdfState.Ready
    DisposableEffect(ready) { onDispose { ready?.doc?.closeLater() } }
    LaunchedEffect(ready) { ready?.let { onPageCount(it.doc.pageCount) } }

    when (val s = state) {
        PdfState.Loading -> Box(modifier.fillMaxSize())
        is PdfState.Failed -> EmptyState(
            "Can't open this PDF",
            "It may be damaged or not a PDF. Try Open with to use another app.\n(${s.message})",
            modifier,
            icon = LocusIcons.File,
        )
        is PdfState.NeedsPassword -> PasswordPrompt(s.wrong, modifier) { password = it }
        is PdfState.Ready -> if (s.doc.pageCount == 0) {
            EmptyState("Empty PDF", "This PDF has no pages.", modifier, icon = LocusIcons.File)
        } else {
            PdfPages(s.doc, modifier)
        }
    }
}

@Composable
private fun PasswordPrompt(wrong: Boolean, modifier: Modifier, onSubmit: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < 35) {
        EmptyState(
            "Password-protected PDF",
            "This Android version can't open protected PDFs here. Use Open with to view it in another app.",
            modifier,
            icon = LocusIcons.Key,
        )
        return
    }
    var text by remember { mutableStateOf("") }
    EmptyState(
        "Password-protected PDF",
        if (wrong) "That password didn't work. Try again." else "Enter the password to view this PDF.",
        modifier,
        icon = LocusIcons.Key,
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    text,
                    { text = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(onClick = { onSubmit(text) }, enabled = text.isNotEmpty()) { Text("Open") }
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
        val renderWidth = ((widthPx - pagePadPx * 2) * quality).toInt().coerceIn(1, 4_096)

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
                PdfPage(doc, index, renderWidth)
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
        val bitmap = runCatching { doc.render(index, renderWidth) }.getOrElse { e ->
            if (e is OutOfMemoryError) System.gc()
            null
        }
        if (bitmap != null) shown = bitmap else if (shown == null) failed = true
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(doc.aspect[index].coerceIn(0.1f, 10f)).background(Color.White)) {
            val bitmap = shown
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize())
            } else if (failed) {
                Text("Couldn't render this page", style = MonoCaption, color = Color.DarkGray, modifier = Modifier.align(Alignment.Center))
            }
        }
        Text(
            "${index + 1} / ${doc.pageCount}",
            style = MonoCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
