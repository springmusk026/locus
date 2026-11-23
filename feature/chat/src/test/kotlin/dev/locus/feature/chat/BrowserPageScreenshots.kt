package dev.locus.feature.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.browser.PageRecord
import dev.locus.core.browser.PickedElement
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.feature.chat.Fx.msg
import org.junit.Test
import java.io.File

/**
 * The browser in the chat (docs/design/browser.md, "Element picker" and "The page as it was"): a message sent
 * with a picked element, the element waiting in the composer, a browser step's "See the page", and the sheet
 * in each of its states.
 */
class BrowserPageScreenshots : ScreenshotTest() {

    @Composable
    private fun Chat(state: ChatUiState) = CompositionLocalProvider(LocalBrowserPages provides { _, _ -> PageView.Failed }) {
        ChatScreen(
            state = state,
            onBack = {},
            onOpenBrowser = {},
            onSend = {},
            onStop = {},
            onAnswer = { _, _ -> },
            onDismissMessages = {},
            onResume = {},
        )
    }

    private val reserve = PickedElement(
        ref = "e57", role = "button", name = "Reserve", tag = "button", text = "Reserve now",
        url = "https://www.booking.com/hotel/pt/casa-do-alfama.html", title = "Casa do Alfama",
    )

    private val snapshot = """
        Page: Casa do Alfama, Lisbon – Updated 2026 Prices
        URL: https://www.booking.com/hotel/pt/casa-do-alfama.html
        Scroll: 20% of page (showing elements near the screen; mode "full" lists all)

        # Casa do Alfama
        [e12] link "Photos (48)"
        [e31] combobox "Rooms" selected=1
        [e44] textbox "Check-in" value="2026-12-04"
        [e57] button "Reserve"
        [e60] link "See availability"
    """.trimIndent()

    private fun transcript() = transcriptItems(
        listOf(
            msg("u1", Role.USER, Part.Text("Find a room at Casa do Alfama on booking.com for 4 December")),
            msg("a1", Role.ASSISTANT, Fx.call("c1", "browser_open", "url" to "https://www.booking.com/hotel/pt/casa-do-alfama.html")),
            msg("t1", Role.TOOL, Fx.result("c1", "browser_open", "<untrusted_web_content>\n$snapshot\n</untrusted_web_content>")),
            msg("a2", Role.ASSISTANT, Part.Text("The room is free on 4 December for €148 a night."), usage = TokenUsage(9_800, 160)),
            msg("u2", Role.USER, Part.Text("Book this one"), Part.Text(ModeNote.result(reserve.forModel()))),
        ),
    )

    @Test
    fun sentWithPick() = snap("browser-chat-picked") { Chat(Fx.state(transcript(), title = "Lisbon room")) }

    @Test
    fun composerWithPick() = snap("browser-chat-composer-pick") {
        Chat(Fx.state(transcript(), title = "Lisbon room").copy(pendingPick = reserve))
    }

    @Test
    fun keptWithPicture() = snap("browser-page-kept") {
        PageAtStepSheet(load = { PageView.Failed }, onDismiss = {}, initial = PageView.Kept(record(picture = true), picture))
    }

    @Test
    fun keptWithoutPicture() = snap("browser-page-text-only") {
        PageAtStepSheet(load = { PageView.Failed }, onDismiss = {}, initial = PageView.Kept(record(picture = false), null))
    }

    @Test
    fun notKept() = snap("browser-page-not-kept") {
        PageAtStepSheet(
            load = { PageView.Failed },
            onDismiss = {},
            initial = PageView.NotKept("Casa do Alfama, Lisbon – Updated 2026 Prices", "https://www.booking.com/hotel/pt/casa-do-alfama.html"),
        )
    }

    @Test
    fun failed() = snap("browser-page-failed") { PageAtStepSheet(load = { PageView.Failed }, onDismiss = {}, initial = PageView.Failed) }

    @Test
    fun loading() = snap("browser-page-loading") { PageAtStepSheet(load = { kotlinx.coroutines.awaitCancellation() }, onDismiss = {}) }

    private fun record(picture: Boolean) = PageRecord(
        url = "https://www.booking.com/hotel/pt/casa-do-alfama.html",
        title = "Casa do Alfama, Lisbon – Updated 2026 Prices",
        at = System.currentTimeMillis() - 7 * 60_000L,
        text = snapshot,
        picture = picture,
        pictureWidth = 720,
        pictureHeight = 1400,
    )

    private val picture: File by lazy {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "browser-page-shots").apply { mkdirs() }
        File(dir, "page.jpg").also { file ->
            if (!file.exists()) {
                val bitmap = Bitmap.createBitmap(720, 1400, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(0xFFF4F4F2.toInt())
                canvas.drawRect(0f, 0f, 720f, 140f, Paint().apply { color = 0xFF1E3A5F.toInt() })
                val block = Paint().apply { color = 0xFFD9DDE0.toInt() }
                for (i in 0 until 5) canvas.drawRect(58f, 220f + i * 170f, 662f, 330f + i * 170f, block)
                canvas.drawRoundRect(58f, 1190f, 662f, 1290f, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E6FD9.toInt() })
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
        }
    }
}
