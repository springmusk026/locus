package dev.locus.core.data.repository

import dev.locus.core.database.ConversationEntity
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.Effort
import dev.locus.core.model.ProviderConfig
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one-tap move from the old "high" to the model's own effort (docs/design/cost.md#effort): existing chats
 * keep the level they've been sending, so their cached prefix holds; new chats get the model's default.
 */
class EffortOfferTest {
    private val opus = ProviderConfig("opus", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.test", "claude-opus-5-5", effort = "high")
    private val other = ProviderConfig("gpt", "OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.test/v1", "gpt-6", effort = "high")

    private fun TestScope.graph(settings: AppSettings) = TestGraph(backgroundScope, settings)

    private fun entity(id: String, providerId: String?, effort: String? = null, parent: String? = null) =
        ConversationEntity(id, id, providerId, "system", 1L, 1L, effort = effort, parentConversationId = parent)

    @Test
    fun `existing chats on the provider keep High, new and empty chats and other providers don't change`() = runTest {
        val g = graph(AppSettings(providers = listOf(opus, other), defaultProviderId = "opus"))
        val repo = g.conversations
        val used = repo.create("opus").also { repo.appendUserMessage(it.id, "Summarise the report") }
        val empty = repo.create("opus")
        val chosen = repo.create("opus").also { repo.appendUserMessage(it.id, "Quick one"); repo.setEffort(it.id, Effort.LOW) }
        val elsewhere = repo.create("gpt").also { repo.appendUserMessage(it.id, "Hello") }
        // Chats that fall back to the default provider: none saved, or one since removed.
        g.database.conversationDao.upsert(entity("unassigned", null))
        g.database.conversationDao.upsert(entity("removed", "deleted-provider"))
        repo.appendUserMessage("unassigned", "One")
        repo.appendUserMessage("removed", "Two")
        // A helper's chat follows its own profile, not the provider's setting.
        g.database.conversationDao.upsert(entity("helper", "opus", parent = used.id))
        repo.appendUserMessage("helper", "Look this up")

        assertEquals(3, repo.moveToModelDefaultEffort("opus"))

        assertEquals(Effort.HIGH, repo.get(used.id)?.effort)
        assertEquals(Effort.HIGH, repo.get("unassigned")?.effort)
        assertEquals(Effort.HIGH, repo.get("removed")?.effort)
        assertNull(repo.get(empty.id)?.effort)
        assertEquals(Effort.LOW, repo.get(chosen.id)?.effort)
        assertNull(repo.get(elsewhere.id)?.effort)
        assertNull(repo.get("helper")?.effort)

        val s = g.settingsRepository.current()
        assertEquals("", s.providers.first { it.id == "opus" }.effort)
        assertEquals("high", s.providers.first { it.id == "gpt" }.effort)
        assertTrue("opus" in s.effortChosen)
        assertFalse(dev.locus.core.model.EffortDefaults.offersModelDefault(s, s.providers.first { it.id == "opus" }))
    }

    @Test
    fun `the request an existing chat sends is unchanged by the move, a new chat sends no effort`() = runTest {
        val g = graph(AppSettings(providers = listOf(opus), defaultProviderId = "opus"))
        val repo = g.conversations
        val chat = repo.create("opus").also { repo.appendUserMessage(it.id, "Plan the week") }
        val before = Effort.ofProvider(opus.effort, opus.format, opus.baseUrl, opus.model).wireValue(opus.format)

        repo.moveToModelDefaultEffort("opus")

        val provider = g.settingsRepository.current().providers.single()
        // As AgentRunner's chatProvider resolves it: the chat's own level wins over the provider's.
        assertEquals(before, repo.get(chat.id)?.effort?.wireValue(provider.format) ?: provider.effort)
        val fresh = repo.create("opus")
        assertEquals("", repo.get(fresh.id)?.effort?.wireValue(provider.format) ?: provider.effort)
    }

    @Test
    fun `nothing moves for a provider that isn't offered the move`() = runTest {
        val sonnet = opus.copy(id = "sonnet", model = "claude-sonnet-4-5") // its own default is high already
        val g = graph(AppSettings(providers = listOf(sonnet, opus), defaultProviderId = "sonnet", effortChosen = setOf("opus")))
        val repo = g.conversations
        val chat = repo.create("sonnet").also { repo.appendUserMessage(it.id, "Hi") }
        assertEquals(0, repo.moveToModelDefaultEffort("sonnet"))
        assertEquals(0, repo.moveToModelDefaultEffort("opus"))
        assertEquals(0, repo.moveToModelDefaultEffort("missing"))
        assertNull(repo.get(chat.id)?.effort)
        assertEquals(listOf("high", "high"), g.settingsRepository.current().providers.map { it.effort })
    }

    @Test
    fun `saving a provider keeps the offer unless its effort changed, and removing it forgets the answer`() = runTest {
        val g = graph(AppSettings(providers = listOf(opus), defaultProviderId = "opus"))
        val settings = g.settingsRepository
        // A new key, the level untouched: still the old default, still offered.
        settings.saveProvider(opus.copy(name = "Claude"), "KEY")
        assertTrue(dev.locus.core.model.EffortDefaults.offersModelDefault(settings.current(), settings.current().providers.single()))
        // Picked in the editor: the user's own choice from now on, even when it's High again.
        settings.saveProvider(opus.copy(effort = "medium"), null)
        settings.saveProvider(opus.copy(effort = "high"), null)
        assertFalse(dev.locus.core.model.EffortDefaults.offersModelDefault(settings.current(), settings.current().providers.single()))
        settings.deleteProvider("opus")
        assertFalse("opus" in settings.current().effortChosen)
    }

    @Test
    fun `keep High stores the answer and changes nothing else`() = runTest {
        val g = graph(AppSettings(providers = listOf(opus), defaultProviderId = "opus"))
        val chat = g.conversations.create("opus").also { g.conversations.appendUserMessage(it.id, "Hi") }
        g.settingsRepository.keepProviderEffort("opus")
        val s = g.settingsRepository.current()
        assertEquals("high", s.providers.single().effort)
        assertFalse(dev.locus.core.model.EffortDefaults.offersModelDefault(s, s.providers.single()))
        assertNull(g.conversations.get(chat.id)?.effort)
    }
}
