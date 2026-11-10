package dev.locus.core.designsystem

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.model.SettingsDestination
import dev.locus.core.model.TaskBudget
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * [SettingsLinkIn], [SettingsActionFor] and [TaskBudgetEditor] link to the page they're given, whatever
 * language the text is in, and still find it in English text when they're given none
 * (docs/design/localization.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsLinksTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val opened = mutableListOf<SettingsDestination>()
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @Before
    fun registerHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
    }

    @After
    fun closeHost() {
        scenario?.close()
    }

    private fun show(content: @Composable () -> Unit) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity {
            it.setContent { LocusTheme(darkTheme = false) { ProvideSettingsLinks({ d -> opened += d }, content) } }
        }
    }

    @Test
    fun `a link in translated text opens the page it is given`() {
        show { SettingsLinkIn(SPANISH, destination = SettingsDestination.Battery) }
        compose.onNodeWithContentDescription("Settings, Battery & heat").performClick()
        assertEquals(listOf(SettingsDestination.Battery), opened)
    }

    @Test
    fun `a link in English text still finds its page`() {
        show { SettingsLinkIn(ENGLISH) }
        compose.onNodeWithContentDescription("Settings, Battery & heat").performClick()
        assertEquals(listOf(SettingsDestination.Battery), opened)
    }

    @Test
    fun `translated text with no page given shows no link`() {
        show { SettingsLinkIn(SPANISH) }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun `an action for translated text opens the page it is given`() {
        show { SettingsActionFor(SPANISH, destination = SettingsDestination.Battery) }
        compose.onNodeWithText("Open Battery & heat").performClick()
        assertEquals(listOf(SettingsDestination.Battery), opened)
    }

    @Test
    fun `an action for English text still finds its page`() {
        show { SettingsActionFor(ENGLISH) }
        compose.onNodeWithText("Open Battery & heat").performClick()
        assertEquals(listOf(SettingsDestination.Battery), opened)
    }

    @Test
    fun `a translated cost note shows whole, with a link to the page it is given`() {
        val note = "El coste necesita precios: activa los detalles de modelos (models.dev) en Ajustes → Modelos."
        show { TaskBudgetEditor(TaskBudget(), onChange = {}, costNote = note, costNoteDestination = SettingsDestination.Models) }
        compose.onNodeWithText(note).assertExists()
        compose.onNodeWithContentDescription("Settings, Models").performClick()
        assertEquals(listOf(SettingsDestination.Models), opened)
    }

    @Test
    fun `an English cost note still leads into its link`() {
        show { TaskBudgetEditor(TaskBudget(), onChange = {}, costNote = "Cost needs model prices: turn on model details (models.dev) in Settings → Models.") }
        compose.onNodeWithContentDescription("Settings, Models").performClick()
        assertEquals(listOf(SettingsDestination.Models), opened)
    }

    private companion object {
        const val SPANISH = "Locus espera mientras el teléfono está caliente; cámbialo en Ajustes → Batería y calor."
        const val ENGLISH = "Locus holds a run while the phone is hot; change it in Settings → Battery & heat."
    }
}
