package dev.locus.core.data.repository

import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ModelRef
import dev.locus.core.model.ProviderDraft
import dev.locus.core.model.ProviderDraftApi
import dev.locus.core.model.ProviderDrafts
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider drafts in the settings (docs/design/provider-drafts.md): inert until approved, and approved only by the review. */
class ProviderDraftsTest {
    private val quiet = AppSettings(
        providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id,
        autoTitle = false, suggestFollowUps = false, reflectAfterTasks = false,
        helperProviderId = ScriptedProvider.config.id, fallbackModels = listOf(ModelRef(ScriptedProvider.config.id, "other")),
    )

    private fun draft(id: String = "draft-1", url: String = "https://evil.example/v1", headers: Map<String, String> = emptyMap()) =
        ProviderDraft(id, "Default", ProviderDraftApi.OPENAI_CHAT, url, models = listOf("gpt-x"), headers = headers, taintSources = listOf("web_read"), createdAt = 1)

    @Test
    fun `a draft changes nothing but the drafts - not the providers, the default, the helper or the backups`() = runTest {
        val g = TestGraph(backgroundScope, quiet)
        g.settingsRepository.addProviderDraft(draft())
        val s = g.settingsRepository.current()
        assertEquals(listOf(ScriptedProvider.config), s.providers)
        assertEquals(ScriptedProvider.config.id, s.defaultProviderId)
        assertEquals(ScriptedProvider.config.id, s.helperProviderId)
        assertEquals(quiet.fallbackModels, s.fallbackModels)
        assertEquals(listOf("draft-1"), s.providerDrafts.map { it.id })
        // Its host isn't one the user set up: fetch_url's local allow-list and taint's trusted hosts leave it out.
        assertFalse("evil.example" in g.tools.userHosts(s))
        // And nothing that picks a provider can pick it.
        assertEquals(ScriptedProvider.config.id, s.provider("draft-1")?.id)
    }

    @Test
    fun `a second draft for the same address replaces the first, and at most five wait`() = runTest {
        val g = TestGraph(backgroundScope, quiet)
        g.settingsRepository.addProviderDraft(draft("a"))
        g.settingsRepository.addProviderDraft(draft("b"))
        assertEquals(listOf("b"), g.settingsRepository.current().providerDrafts.map { it.id })
        repeat(4) { g.settingsRepository.addProviderDraft(draft("d$it", url = "https://h$it.example/v1")) }
        assertEquals(ProviderDrafts.MAX_PENDING, g.settingsRepository.current().providerDrafts.size)
        assertFailsWith<IllegalStateException> { g.settingsRepository.addProviderDraft(draft("x", url = "https://x.example/v1")) }
        // Through the tool, the same limit is a refusal the agent can read.
        val tool = g.tools.registry[ProviderDrafts.TOOL]!!
        val refused = assertFailsWith<dev.locus.core.agent.ToolException> {
            tool.execute(buildJsonObject { put("name", "X"); put("api_type", "openai_chat"); put("base_url", "https://x.example/v1") })
        }
        assertTrue("wait for review" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `approval seals the provider and its address exactly as one the user added, and takes the draft away`() = runTest {
        val g = TestGraph(backgroundScope, quiet)
        g.settingsRepository.addProviderDraft(draft(url = "https://api.groq.com/openai/v1", headers = mapOf("X-Title" to "Locus")))
        val approved = ProviderDrafts.approved(
            g.settingsRepository.current().providerDrafts.single(), "groq-1", "Groq", ProviderDraftApi.OPENAI_CHAT,
            "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", requiresKey = true, headerNames = listOf("X-Title"),
        )
        assertTrue(g.settingsRepository.approveProviderDraft("draft-1", approved, " gsk_typed_by_the_user ", mapOf("X-Title" to "Locus")))
        val s = g.settingsRepository.current()
        assertTrue(s.providerDrafts.isEmpty())
        val p = s.providers.single { it.id == "groq-1" }
        assertFalse(p.addressUnconfirmed)
        assertEquals("https://api.groq.com/openai/v1", p.baseUrl)
        assertEquals(listOf("X-Title"), p.network.headerNames)
        // Not made the default: the user already has one, as when they add a provider themselves.
        assertEquals(ScriptedProvider.config.id, s.defaultProviderId)
        assertEquals("gsk_typed_by_the_user", g.secrets.get(SecretStore.providerKey("groq-1")))
        assertEquals("Locus", g.secrets.get(SecretStore.providerHeader("groq-1", "X-Title")))
        // Exactly what the provider editor saves for the same provider typed in by the user: same settings, same
        // endpoint-seal payload (the app's settings store seals every write), same vault entries.
        val byHand = TestGraph(backgroundScope, quiet)
        byHand.settingsRepository.saveProvider(approved, "gsk_typed_by_the_user", dev.locus.core.data.network.NetworkSecretEdits(mapOf("X-Title" to "Locus")))
        val manual = byHand.settingsRepository.current()
        assertEquals(manual.providers, s.providers)
        assertEquals(manual.defaultProviderId, s.defaultProviderId)
        assertTrue(dev.locus.core.model.EndpointSeal.payload(manual).contentEquals(dev.locus.core.model.EndpointSeal.payload(s)))

        // A draft that's gone (discarded, or dropped by the seal) can't be approved.
        assertFalse(g.settingsRepository.approveProviderDraft("draft-1", approved.copy(id = "again"), "k"))
        assertNull(g.settingsRepository.current().providers.firstOrNull { it.id == "again" })
    }

    @Test
    fun `discard removes the draft and nothing else`() = runTest {
        val g = TestGraph(backgroundScope, quiet)
        g.settingsRepository.addProviderDraft(draft())
        g.settingsRepository.discardProviderDraft("draft-1")
        assertEquals(quiet.providers, g.settingsRepository.current().providers)
        assertTrue(g.settingsRepository.current().providerDrafts.isEmpty())
    }

    @Test
    fun `the only tool that touches drafts is provider_draft, and it can't approve one`() = runTest {
        val g = TestGraph(backgroundScope, quiet)
        val names = g.tools.registry.all.map { it.spec.name }
        assertEquals(listOf(ProviderDrafts.TOOL), names.filter { "provider" in it })
        val schema = g.tools.registry[ProviderDrafts.TOOL]!!.spec.inputSchema.toString()
        listOf("approve", "default", "activate", "key").forEach { assertFalse("\"$it\"" in schema, it) }
    }

    @Test
    fun `a chat drafts a provider through find_tools and call_tool, and no request ever goes to it`() = runTest {
        val g = TestGraph(backgroundScope, quiet.copy(toolPolicies = mapOf(ProviderDrafts.TOOL to ToolPolicy.ALLOW)))
        val call = buildJsonObject {
            put("name", ProviderDrafts.TOOL)
            putJsonObject("arguments") { put("name", "Groq"); put("api_type", "openai_chat"); put("base_url", "https://api.groq.com/openai/v1") }
        }
        g.script(
            ScriptedProvider.toolCall("k1", dev.locus.core.model.DiscoveryTools.CALL_TOOL, call.toString()),
            ScriptedProvider.text("Drafted. Review it in Settings → Models."),
            ScriptedProvider.text("Hello again."),
        )
        val chat = g.conversations.create(quiet.defaultProviderId).id
        g.runner.send(chat, "Add Groq as a provider: https://api.groq.com/openai/v1")
        g.conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT } >= 2 }
        val d = g.settingsRepository.current().providerDrafts.single()
        assertEquals("api.groq.com", d.host)
        assertEquals(chat, d.conversationId)
        assertTrue(d.userNamedHost)
        assertFalse(d.tainted)

        // Another turn in the same chat, and the helper's model list: still only the real provider is asked.
        g.runner.send(chat, "hi")
        g.conversations.observeMessages(chat).first { m -> m.count { it.role == Role.ASSISTANT } >= 3 }
        assertTrue(g.requests.isNotEmpty())
        assertTrue(g.requests.none { it.url.host == "api.groq.com" }, g.requests.map { it.url.host }.toString())
        assertEquals(listOf(ScriptedProvider.config), g.settingsRepository.current().providers)
        assertEquals(ScriptedProvider.config.id, g.settingsRepository.current().defaultProviderId)
    }
}
