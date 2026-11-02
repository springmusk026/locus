package dev.locus.feature.chat

import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.TaskBudget
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetControlsTest {
    @Test
    fun `a chat's own budget sits beside its tool switches without counting as one`() {
        val tools = setOf("group:web")
        val controls = ChatControls(disabledTools = TaskBudget.withOverride(tools, TaskBudget(tokens = 200_000)), defaultBudget = TaskBudget(minutes = 30))
        assertEquals(1, controls.disabledCount)
        assertEquals(TaskBudget(tokens = 200_000), controls.budget)
        assertEquals("200k tokens for this chat", budgetSubtitle(controls))
        assertEquals("Default: 30 min", budgetSubtitle(controls.copy(disabledTools = tools)))
        assertEquals("Off", budgetSubtitle(ChatControls()))
    }

    @Test
    fun `the cost limit says why it can't apply`() {
        val provider = ProviderConfig("p", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1", "gpt-5")
        val catalog = ModelCatalogIndex.parseModelsDev("""{"openai":{"models":{"gpt-5":{"cost":{"input":1.25,"output":10}},"free-ish":{}}}}""")
        assertTrue("Settings → Models" in costNote(AppSettings(), provider, "gpt-5", catalog).orEmpty())
        val on = AppSettings(modelCatalog = true)
        assertTrue("haven't loaded" in costNote(on, provider, "gpt-5", null).orEmpty())
        assertTrue("no price for free-ish" in costNote(on, provider, "free-ish", catalog).orEmpty())
        assertNull(costNote(on, provider, "gpt-5", catalog))
    }
}
