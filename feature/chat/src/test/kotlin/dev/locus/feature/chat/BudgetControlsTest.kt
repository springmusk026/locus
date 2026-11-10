package dev.locus.feature.chat

import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.TaskBudget
import dev.locus.core.designsystem.UiText
import dev.locus.core.model.SettingsDestination
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BudgetControlsTest {
    @Test
    fun `a chat's own budget sits beside its tool switches without counting as one`() {
        val tools = setOf("group:web")
        val controls = ChatControls(disabledTools = TaskBudget.withOverride(tools, TaskBudget(tokens = 200_000)), defaultBudget = TaskBudget(minutes = 30))
        assertEquals(1, controls.disabledCount)
        assertEquals(TaskBudget(tokens = 200_000), controls.budget)
        assertEquals("200k tokens for this chat", budgetSubtitle(testResources, controls))
        assertEquals("Default: 30 min", budgetSubtitle(testResources, controls.copy(disabledTools = tools)))
        assertEquals("Off", budgetSubtitle(testResources, ChatControls()))
    }

    @Test
    fun `the cost limit says why it can't apply`() {
        val provider = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5")
        val catalog = ModelCatalogIndex.parseModelsDev("""{"openai":{"models":{"gpt-5":{"cost":{"input":1.25,"output":10}},"free-ish":{}}}}""")
        val off = costNote(AppSettings(), provider, "gpt-5", catalog)
        assertEquals(UiText.Res(R.string.chat_budget_cost_off), off)
        assertTrue("Settings → Models" in off?.asString(testResources).orEmpty())
        // Only this note sends the user to a Settings page; the others are about waiting or about the model.
        assertEquals(SettingsDestination.Models, costNoteDestination(off))
        val on = AppSettings(modelCatalog = true)
        val notLoaded = costNote(on, provider, "gpt-5", null)
        assertEquals(UiText.Res(R.string.chat_budget_cost_not_loaded), notLoaded)
        assertTrue("haven't loaded" in notLoaded?.asString(testResources).orEmpty())
        val noPrice = costNote(on, provider, "free-ish", catalog)
        assertEquals(UiText.Res(R.string.chat_budget_cost_no_price, "free-ish"), noPrice)
        assertTrue("no price for free-ish" in noPrice?.asString(testResources).orEmpty())
        assertNull(costNoteDestination(notLoaded))
        assertNull(costNoteDestination(noPrice))
        assertNull(costNoteDestination(null))
        assertNull(costNote(on, provider, "gpt-5", catalog))
    }
}
