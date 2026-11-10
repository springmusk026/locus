package dev.locus.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.tools.ImageFiles
import dev.locus.core.data.images.AndroidImagePreparer
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * view_image's decoder on Android's real Bitmap code (native graphics). It lives in core/data, which has
 * no Robolectric; this module's tests already run it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AndroidImagePreparerTest {
    private val preparer = AndroidImagePreparer()

    private fun encoded(w: Int, h: Int, format: Bitmap.CompressFormat, draw: (Bitmap) -> Unit = { it.eraseColor(Color.rgb(40, 90, 120)) }): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also(draw)
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it) }.toByteArray()
    }

    @Test
    fun `a large screenshot is scaled to the long edge and stays PNG`() {
        val image = preparer.prepare(encoded(3136, 1960, Bitmap.CompressFormat.PNG), "image/png")
        assertEquals("image/png", image.mimeType)
        assertEquals(1568, image.width)
        assertEquals(980, image.height)
        assertEquals(3136 to 1960, image.sourceWidth to image.sourceHeight)
        val back = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
        assertEquals(1568, back.width)
    }

    @Test
    fun `a small image keeps its size`() {
        val image = preparer.prepare(encoded(400, 300, Bitmap.CompressFormat.JPEG), "image/jpeg")
        assertEquals(400 to 300, image.width to image.height)
        assertEquals(image.width to image.height, image.sourceWidth to image.sourceHeight)
    }

    @Test
    fun `a noisy picture whose PNG is over a megabyte becomes JPEG under the cap`() {
        val random = java.util.Random(7)
        val bytes = encoded(1500, 1500, Bitmap.CompressFormat.PNG) { b ->
            val pixels = IntArray(b.width * b.height) { random.nextInt() or 0xFF000000.toInt() }
            b.setPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        }
        val image = preparer.prepare(bytes, "image/png")
        assertEquals("image/jpeg", image.mimeType)
        assertTrue(image.bytes.size <= ImageFiles.MAX_BYTES)
        assertTrue(image.bytes[0] == 0xFF.toByte() && image.bytes[1] == 0xD8.toByte())
    }

    @Test
    fun `bytes that don't decode are refused with a reason`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
        assertFailsWith<ToolException> { preparer.prepare(bytes, "image/png") }
    }
}
