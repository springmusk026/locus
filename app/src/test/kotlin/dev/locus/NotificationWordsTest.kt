package dev.locus

import android.app.Application
import android.content.res.Resources
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetReached
import dev.locus.core.model.toolActivity
import dev.locus.core.model.toolLabel
import dev.locus.core.ondevice.gguf.GgufDownloadState
import dev.locus.core.ondevice.gguf.GgufDownloadText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.locus.core.data.R as DataR
import dev.locus.core.ondevice.R as OnDeviceR

/**
 * The notifications' words in the app's language (docs/design/localization.md, "Notifications"): in English the
 * phrases the app gives core/data say exactly what the core said before, and in Spanish they follow the language.
 * core/data's NotificationEnglishTest checks its own strings word for word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationWordsTest {
    private val res: Resources get() = RuntimeEnvironment.getApplication().resources

    private val tools = listOf(
        "run_command" to """{"command":"ls"}""",
        "browser_click" to """{"ref":"e1"}""",
        "web_search" to """{"query":"heat pumps"}""",
        "write_file" to """{"path":"notes.md","content":"x"}""",
        "email_send" to """{"to":"a@example.org"}""",
    )
    private val budgets = listOf(
        BudgetReached(BudgetLimit.TOKENS, paused = true, tokens = 200_000, costUsd = 0.0, minutes = 0),
        BudgetReached(BudgetLimit.TIME, paused = true, tokens = 0, costUsd = 0.0, minutes = 30),
        BudgetReached(BudgetLimit.COST, paused = true, tokens = 0, costUsd = 2.0, minutes = 0),
    )

    @Test
    fun `in English the phrases are the core's, word for word`() {
        tools.forEach { (tool, args) ->
            assertEquals(toolActivity(tool), AppNotificationPhrases.toolActivity(res, tool))
            assertEquals(toolLabel(tool, args, done = false), AppNotificationPhrases.toolLabel(res, tool, args))
        }
        budgets.forEach { assertEquals(it.english, AppNotificationPhrases.budgetReached(res, it)) }
    }

    @Test
    fun `a model download's progress reads as before`() {
        val running = GgufDownloadState.Running(800_000_000, 1_915_305_312, 4_200_000, 190)
        assertEquals(GgufDownloadText.progress(running), GgufDownloadText.progress(res, running))
        val hours = GgufDownloadState.Running(4_000_000, 10_000_000, 2_000_000, 3_725)
        assertEquals(GgufDownloadText.progress(hours), GgufDownloadText.progress(res, hours))
        assertEquals("Downloading Gemma 4", res.getString(OnDeviceR.string.ondevice_download_title, "Gemma 4"))
        assertEquals("Starting…", res.getString(OnDeviceR.string.ondevice_download_starting))
        assertEquals("120 of 1900 MB", res.getString(OnDeviceR.string.ondevice_download_megabytes, 120L, 1_900L))
        assertEquals("Model downloads", res.getString(OnDeviceR.string.ondevice_download_channel))
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish`() {
        assertEquals("Ejecutando un comando", AppNotificationPhrases.toolActivity(res, "run_command"))
        assertTrue(AppNotificationPhrases.toolLabel(res, "run_command", """{"command":"ls"}""") != toolLabel("run_command", """{"command":"ls"}""", done = false))
        assertTrue(AppNotificationPhrases.budgetReached(res, budgets.first()).startsWith("En pausa"))
        assertEquals("Locus te necesita", res.getString(DataR.string.data_notif_title_needs_you))
        assertEquals("Trabajando · 2 ayudantes", res.getQuantityString(DataR.plurals.data_notif_status_helpers, 2, res.getString(DataR.string.data_notif_status_working), 2))
        assertEquals("Permitir", res.getString(DataR.string.data_notif_action_allow))
        assertEquals("Actividad del agente", res.getString(DataR.string.data_notif_channel_status))
        val running = GgufDownloadState.Running(800_000_000, 1_915_305_312, 4_200_000, 190)
        val nb = GgufDownloadText.NBSP
        assertEquals("0,8$nb/${nb}1,9${nb}GB · 4,2${nb}MB/s · quedan 4${nb}min", GgufDownloadText.progress(res, running))
    }

    @Test
    fun `the Live Update chips fit in every language`() {
        listOf("en", "es", "pt", "zh-rCN").forEach { language ->
            RuntimeEnvironment.setQualifiers(language)
            val chips = listOf(DataR.string.data_notif_chip_asks, DataR.string.data_notif_chip_paused, DataR.string.data_notif_chip_working)
            // Past seven characters the chip is cut off; "Step N" falls back to the Working chip when it would be.
            chips.map { res.getString(it) }.forEach { assertTrue(it.length <= 7, "$language: $it") }
            assertTrue(res.getString(DataR.string.data_notif_chip_step, 9).length <= 7, language)
            // Each language's own words, not English fallen back to.
            assertEquals(language == "en", res.getString(DataR.string.data_notif_chip_working) == "Working", language)
        }
    }
}
