package dev.locus.feature.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.agent.tools.ViewImage
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.ViewImages
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.feature.chat.Fx.msg
import java.io.File
import org.junit.Test

/** view_image in the transcript: the step row with the images the model looked at (docs/design/view-image.md). */
class ToolImageScreenshots : ScreenshotTest() {

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
    private fun viewed(paths: List<String>, result: Part.ToolResult) = Chat(
        Fx.state(
            transcriptItems(
                listOf(
                    msg("u1", Role.USER, Part.Text("Render the checkout page and check the layout on a phone")),
                    msg("a1", Role.ASSISTANT, Fx.call("c1", ViewImages.TOOL, *(if (paths.size == 1) arrayOf("path" to paths[0]) else emptyArray()))
                        .let { if (paths.size == 1) it else it.copy(argumentsJson = """{"paths":[${paths.joinToString(",") { "\"$it\"" }}]}""") }),
                    msg("t1", Role.TOOL, result),
                    msg("a2", Role.ASSISTANT, Part.Text(REPLY), usage = TokenUsage(9_800, 160)),
                ),
            ),
            title = "Checkout layout",
        ),
    )

    @Test
    fun oneImage() = snap("tool-images-1") {
        val shot = screenshots.take(1)
        viewed(shot.map { it.workspacePath }, result("Showing ${shot[0].workspacePath} (720×1568, scaled down from 1080×2352).", shot))
    }

    @Test
    fun twoImages() = snap("tool-images-2") {
        val shots = screenshots.take(2)
        viewed(shots.map { it.workspacePath }, result(shots.joinToString("\n") { "Showing ${it.workspacePath} (${it.width}×${it.height})." }, shots))
    }

    @Test
    fun copyGone() = snap("tool-images-missing") {
        val gone = screenshots[0].copy(path = "/nonexistent/tool-images/gone.png")
        viewed(listOf(gone.workspacePath), result("Showing ${gone.workspacePath} (720×1568).", listOf(gone)))
    }

    @Test
    fun noVision() = snap("tool-images-no-vision") {
        viewed(listOf("shots/checkout.png"), Fx.result("c1", ViewImages.TOOL, ViewImage.NO_VISION, error = true))
    }

    private fun result(text: String, images: List<Part.Attachment>) = Fx.result("c1", ViewImages.TOOL, text).copy(images = images)

    private val dir: File by lazy {
        File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "tool-image-shots").apply { mkdirs() }
    }

    /** Two "screenshots" of a phone page: a header, blocks of content and a button. */
    private val screenshots: List<Part.Attachment> by lazy {
        listOf("checkout" to 0xFF2E5A7A.toInt(), "checkout-dark" to 0xFF1F2A33.toInt()).map { (name, colour) ->
            val file = File(dir, "$name.png")
            if (!file.exists()) file.outputStream().use { page(720, 1568, colour).compress(Bitmap.CompressFormat.PNG, 100, it) }
            Part.Attachment("$name.png", "image/png", file.path, "shots/$name.png", file.length(), 720, 1568)
        }
    }

    private fun page(w: Int, h: Int, header: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF4F4F2.toInt())
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.1f, Paint().apply { color = header })
        val block = Paint().apply { color = 0xFFD9DDE0.toInt() }
        for (i in 0 until 5) canvas.drawRect(w * 0.08f, h * (0.16f + i * 0.12f), w * 0.92f, h * (0.24f + i * 0.12f), block)
        canvas.drawRoundRect(w * 0.08f, h * 0.86f, w * 0.92f, h * 0.93f, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = header })
        return bitmap
    }

    private companion object {
        const val REPLY = "The page fits the screen. The pay button sits under the fold on the first screenshot, so I moved it up into the summary card."
    }
}
