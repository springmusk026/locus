package dev.locus.feature.workspace.preview

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded images are capped at this many pixels on the long edge: sharp at 2× zoom on a phone, and within GPU texture limits. */
private const val MAX_DECODE_EDGE = 4_096

private sealed interface Decoded {
    data object Loading : Decoded
    data class Still(val bitmap: ImageBitmap, val width: Int, val height: Int) : Decoded
    data class Animated(val drawable: Drawable, val width: Int, val height: Int) : Decoded
    data object Failed : Decoded
}

/**
 * Images: fit to the screen, pinch to zoom (up to 6×) and pan, double-tap to zoom in or reset.
 * Decoded with ImageDecoder on Android 9+ (HEIC/HEIF, AVIF where the device supports it, animated
 * GIF/WebP), else BitmapFactory, downsampled to [MAX_DECODE_EDGE]. [onInfo] reports "W × H".
 */
@Composable
internal fun ImageViewer(file: File, modifier: Modifier = Modifier, onInfo: (String) -> Unit = {}) {
    val decoded by produceState<Decoded>(Decoded.Loading, file, file.lastModified()) {
        value = withContext(Dispatchers.IO) { runCatching { decode(file) }.getOrElse { Decoded.Failed } }
    }
    when (val d = decoded) {
        Decoded.Loading -> Box(modifier.fillMaxSize())
        Decoded.Failed -> EmptyState(
            "Can't show this image",
            "The format isn't supported on this phone, or the file is damaged. Try Open with.",
            modifier,
            icon = LocusIcons.File,
        )
        is Decoded.Still -> {
            LaunchedEffect(d) { onInfo("${d.width} × ${d.height}") }
            Zoomable(modifier) { Image(d.bitmap, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        is Decoded.Animated -> {
            LaunchedEffect(d) { onInfo("${d.width} × ${d.height} · animated") }
            DisposableEffect(d.drawable) {
                if (Build.VERSION.SDK_INT >= 28) (d.drawable as? AnimatedImageDrawable)?.start()
                onDispose { if (Build.VERSION.SDK_INT >= 28) (d.drawable as? AnimatedImageDrawable)?.stop() }
            }
            Zoomable(modifier) {
                AndroidView(
                    factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                    update = { it.setImageDrawable(d.drawable) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun decode(file: File): Decoded {
    if (Build.VERSION.SDK_INT >= 28) {
        var width = 0
        var height = 0
        val source = ImageDecoder.createSource(file)
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            width = info.size.width
            height = info.size.height
            decoder.setTargetSampleSize(sampleSize(width, height))
        }
        if (drawable is AnimatedImageDrawable) return Decoded.Animated(drawable, width, height)
        val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.setTargetSampleSize(sampleSize(width, height)) }
        return Decoded.Still(bitmap.asImageBitmap(), width, height)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return Decoded.Failed
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
        ?: return Decoded.Failed
    return Decoded.Still(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight)
}

/** The power-of-two sample that brings the long edge to at most [MAX_DECODE_EDGE]. */
private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (maxOf(width, height) / sample > MAX_DECODE_EDGE) sample *= 2
    return sample
}

/** Pinch-zoom and pan for a single fitted child, clamped so the content can't be dragged off screen. */
@Composable
internal fun Zoomable(modifier: Modifier = Modifier, maxZoom: Float = 6f, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = w * (s - 1f) / 2f
            val maxY = h * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { at ->
                        if (scale > 1.05f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val s = 2.5f
                            // Zoom towards the tapped point: it stays under the finger.
                            val center = Offset(w / 2f, h / 2f)
                            offset = clamp((center - at) * (s - 1f), s)
                            scale = s
                        }
                    })
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val old = scale
                        val new = (old * zoom).coerceIn(1f, maxZoom)
                        val center = Offset(w / 2f, h / 2f)
                        // Scale about the pinch centroid (the layer scales about the centre).
                        val o = (offset + center - centroid) * (new / old) + centroid - center + pan
                        scale = new
                        offset = clamp(o, new)
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) { content() }
    }
}

/** A caption row of file facts, shown under media and on the info card. */
@Composable
internal fun FactsLine(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
