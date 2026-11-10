package dev.locus.core.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.ImageFiles
import dev.locus.core.agent.tools.ImagePreparer
import dev.locus.core.agent.tools.PreparedImage
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * view_image's images on the phone (docs/design/view-image.md, "Resizing"): decoded with a power-of-two
 * `inSampleSize` so a large file never sits in memory at full size, turned upright by its EXIF
 * orientation, scaled to at most [ImageFiles.MAX_EDGE] px on the long edge, and encoded again, which
 * drops EXIF and every other bit of metadata. PNG while it stays under [ImageFiles.PNG_LIMIT]
 * (screenshots and graphics stay sharp), else JPEG at [ImageFiles.JPEG_QUALITY] on white (no alpha). A
 * GIF gives its first frame. Never more than [ImageFiles.MAX_BYTES]: a result still over it is scaled down again.
 */
@Singleton
class AndroidImagePreparer @Inject constructor() : ImagePreparer {
    override fun prepare(bytes: ByteArray, mimeType: String): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw undecodable(mimeType)
        val orientation = if (mimeType == "image/jpeg" || mimeType == "image/heif" || mimeType == "image/webp") orientationOf(bytes) else ExifInterface.ORIENTATION_NORMAL
        val turned = orientation in TURNED
        val sourceWidth = if (turned) bounds.outHeight else bounds.outWidth
        val sourceHeight = if (turned) bounds.outWidth else bounds.outHeight
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ImageFiles.MAX_EDGE) sample *= 2
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: OutOfMemoryError) {
            null
        } ?: throw undecodable(mimeType)
        var image = scaled(upright(decoded, orientation), ImageFiles.MAX_EDGE)
        var encoded = encode(image)
        // Rare (a noisy photo at full size): smaller until it fits.
        while (encoded.first.size > ImageFiles.MAX_BYTES && max(image.width, image.height) > MIN_EDGE) {
            image = scaled(image, (max(image.width, image.height) * 0.75f).roundToInt())
            encoded = encode(image, jpegOnly = true)
        }
        val result = PreparedImage(encoded.first, encoded.second, image.width, image.height, sourceWidth, sourceHeight)
        image.recycle()
        return result
    }

    /** PNG when it's small enough, else JPEG; the bytes and their type. */
    private fun encode(bitmap: Bitmap, jpegOnly: Boolean = false): Pair<ByteArray, String> {
        if (!jpegOnly) {
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (png.size <= ImageFiles.PNG_LIMIT) return png to "image/png"
        }
        // JPEG has no alpha: transparent parts go on white, as most viewers show them.
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
            }
        } else {
            bitmap
        }
        val jpeg = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, ImageFiles.JPEG_QUALITY, it) }.toByteArray()
        if (opaque !== bitmap) opaque.recycle()
        return jpeg to "image/jpeg"
    }

    /** [bitmap] with its long edge at most [edge] px; itself when it already is (the old one is recycled otherwise). */
    private fun scaled(bitmap: Bitmap, edge: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= edge) return bitmap
        val factor = edge.toFloat() / long
        val out = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1), (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /** [bitmap] turned the way its EXIF [orientation] says a viewer shows it. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bitmap
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun undecodable(mimeType: String) =
        ToolException("Locus couldn't decode this ${mimeType.removePrefix("image/").uppercase()} image; it may be damaged, or in a variant this phone can't read.")

    private companion object {
        /** Scaling down to fit [ImageFiles.MAX_BYTES] stops here. */
        const val MIN_EDGE = 256

        val TURNED = setOf(
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
        )
    }
}
