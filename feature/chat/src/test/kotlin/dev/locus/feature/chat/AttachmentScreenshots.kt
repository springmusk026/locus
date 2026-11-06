package dev.locus.feature.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.feature.chat.Fx.msg
import java.io.File
import org.junit.Test

/** Photos and files in a sent message, and waiting in the composer, from fake pictures written for the test. */
class AttachmentScreenshots : ScreenshotTest() {

    @Composable
    private fun Chat(state: ChatUiState) = ChatScreen(
        state = state,
        onBack = {},
        onOpenBrowser = {},
        onSend = {},
        onStop = {},
        onAnswer = { _, _ -> },
        onDismissMessages = {},
        onResume = {},
    )

    @Composable
    private fun sent(text: String, attachments: List<Part.Attachment>) = Chat(
        Fx.state(
            transcriptItems(
                listOf(
                    msg("u1", Role.USER, Part.Text(text), *attachments.toTypedArray()),
                    msg("a1", Role.ASSISTANT, Part.Text(REPLY), usage = TokenUsage(6_200, 140)),
                ),
            ),
            title = "Plant photos",
        ),
    )

    @Test
    fun oneImage() = snap("attachments-1-image") { sent("What's wrong with this leaf?", photos.take(1)) }

    @Test
    fun twoImages() = snap("attachments-2-images") { sent("Which of these two is healthier?", photos.take(2)) }

    @Test
    fun threeImages() = snap("attachments-3-images") { sent("Name these plants", photos.take(3)) }

    @Test
    fun fiveImages() = snap("attachments-5-images") { sent("Make a care plan for all of these", photos.take(5)) }

    @Test
    fun imagesAndPdf() = snap("attachments-images-pdf") {
        sent("Does the invoice match what I bought?", photos.take(2) + listOf(pdf, code))
    }

    @Test
    fun composer() = snap("attachments-composer") {
        Chat(
            Fx.state(Fx.conversation, title = "Plant photos")
                .copy(pendingAttachments = photos.take(2) + pdf),
        )
    }

    private val dir: File by lazy {
        File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "attachment-shots").apply { mkdirs() }
    }

    /** Five "photos" in different shapes and colours: landscape, portrait and square. */
    private val photos: List<Part.Attachment> by lazy {
        listOf(
            Triple(1200, 900, intArrayOf(0xFF4C7A3A.toInt(), 0xFFB9D58C.toInt())),
            Triple(900, 1200, intArrayOf(0xFF7A4C2E.toInt(), 0xFFE7C38F.toInt())),
            Triple(1000, 1000, intArrayOf(0xFF2E5A7A.toInt(), 0xFF9CC9E7.toInt())),
            Triple(1200, 800, intArrayOf(0xFF6A3A7A.toInt(), 0xFFD9B3E8.toInt())),
            Triple(900, 900, intArrayOf(0xFF7A6A2E.toInt(), 0xFFEDE29A.toInt())),
        ).mapIndexed { i, (w, h, colours) ->
            val file = File(dir, "IMG_20261106_${1400 + i}.jpg")
            if (!file.exists()) file.outputStream().use { picture(w, h, colours).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            Part.Attachment(file.name, "image/jpeg", file.path, "inbox/${file.name}", file.length())
        }
    }

    private val pdf = Part.Attachment("invoice-garden-centre-march.pdf", "application/pdf", "/nonexistent/invoice.pdf", "inbox/invoice.pdf", 182_000)
    private val code = Part.Attachment("watering.py", "text/x-python", "/nonexistent/watering.py", "inbox/watering.py", 2_400)

    private fun picture(w: Int, h: Int, colours: IntArray): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), colours[0], colours[1], Shader.TileMode.CLAMP) })
        val leaf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
        canvas.drawOval(w * 0.25f, h * 0.3f, w * 0.75f, h * 0.7f, leaf)
        return bitmap
    }

    private companion object {
        const val REPLY = "The lower leaves are yellowing from the edges in, which usually means overwatering rather than too little light."
    }
}
