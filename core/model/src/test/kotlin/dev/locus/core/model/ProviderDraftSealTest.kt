package dev.locus.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider drafts (docs/design/provider-drafts.md): sealed with the endpoints, inert, and approved into an ordinary provider. */
class ProviderDraftSealTest {
    private val key = TestKey()
    private val anthropic = ProviderConfig("p1", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5")
    private val evil = ProviderDraft(
        id = "draft-1", name = "Default", api = ProviderDraftApi.OPENAI_CHAT, baseUrl = "https://evil.example/v1",
        models = listOf("gpt-x"), conversationId = "c1", chatTitle = "Research", taintSources = listOf("web_read"), createdAt = 5,
    )
    private val settings = AppSettings(providers = listOf(anthropic), defaultProviderId = "p1")

    @Test
    fun `settings without drafts seal exactly as before drafts existed`() {
        // The payload gains a key only when a draft exists, so every seal made by an older Locus still matches.
        assertFalse("providerDrafts" in EndpointSeal.payload(settings).decodeToString())
        assertTrue("providerDrafts" in EndpointSeal.payload(settings.copy(providerDrafts = listOf(evil))).decodeToString())
    }

    @Test
    fun `a draft is sealed with the endpoints, so one planted or edited outside Locus breaks the seal`() {
        val sealed = EndpointSeal.sealed(settings.copy(providerDrafts = listOf(evil)), key)
        assertEquals(SealCheck.VALID, EndpointSeal.check(sealed, key))
        // Clearing the taint flag, so the review wouldn't warn.
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(providerDrafts = listOf(evil.copy(taintSources = emptyList()))), key))
        // Claiming the user named the host.
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(providerDrafts = listOf(evil.copy(userNamedHost = true))), key))
        // Moving the address, or planting a second draft.
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(providerDrafts = listOf(evil.copy(baseUrl = "https://other.example/v1"))), key))
        assertEquals(SealCheck.TAMPERED, EndpointSeal.check(sealed.copy(providerDrafts = listOf(evil, evil.copy(id = "draft-2"))), key))
        // Drafts alone are something to protect: not "defaults".
        assertFalse(EndpointSeal.isDefault(AppSettings(providerDrafts = listOf(evil))))
    }

    @Test
    fun `restricted settings drop every draft`() {
        val restricted = EndpointSeal.restricted(settings.copy(providerDrafts = listOf(evil)))
        assertTrue(restricted.providerDrafts.isEmpty())
        assertEquals(listOf("p1"), restricted.providers.map { it.id })
    }

    @Test
    fun `a draft never receives traffic - nothing that picks a provider can pick it`() {
        val s = settings.copy(providerDrafts = listOf(evil))
        // Asking for the draft by id falls back to the default provider, never to the draft.
        assertEquals("p1", s.provider("draft-1")?.id)
        assertEquals("p1", s.provider(null)?.id)
        // With no providers at all there is nothing to route to, draft or not.
        assertNull(AppSettings(providerDrafts = listOf(evil)).provider("draft-1"))
        assertNull(AppSettings(providerDrafts = listOf(evil)).provider(null))
        // A draft alone still sends the user through onboarding: it isn't a provider.
        assertTrue(AppSettings(providerDrafts = listOf(evil)).needsOnboarding)
    }

    @Test
    fun `approval builds an ordinary provider under a new id with its address confirmed`() {
        val p = ProviderDrafts.approved(evil, "new-id", " My gateway ", ProviderDraftApi.OPENROUTER, "https://openrouter.ai/api/v1/", "x/y", requiresKey = true, headerNames = listOf("X-Title"))
        assertEquals("new-id", p.id)
        assertNotEquals(evil.id, p.id)
        assertEquals("My gateway", p.name)
        assertEquals(ApiFormat.OPENAI_CHAT, p.format)
        assertEquals("https://openrouter.ai/api/v1", p.baseUrl)
        assertFalse(p.addressUnconfirmed)
        assertEquals(listOf("X-Title"), p.network.headerNames)
        assertFalse(p.network.allowAuthOverride)
    }

    @Test
    fun `known hosts, local servers and plain http`() {
        assertTrue(ProviderDrafts.knownHost("api.anthropic.com"))
        assertTrue(ProviderDrafts.knownHost("openrouter.ai"))
        assertTrue(ProviderDrafts.knownHost("api.groq.com"))
        assertFalse(ProviderDrafts.knownHost("evil.example"))
        assertFalse(ProviderDrafts.knownHost("api.anthropic.com.evil.example"))
        assertTrue(ProviderDrafts.knownHost("api.together.xyz", catalogHosts = setOf("api.together.xyz")))
        val ollama = evil.copy(baseUrl = "http://192.168.1.10:11434/v1")
        assertTrue(ollama.local && ollama.cleartext)
        assertFalse(evil.local || evil.cleartext)
        assertEquals("evil.example", evil.host)
    }

    @Test
    fun `the review names what the run read`() {
        assertEquals("a web page", ProviderDrafts.describeSources(listOf("web_read", "browser_open")))
        assertEquals("a web page and an email", ProviderDrafts.describeSources(listOf("fetch_url", "email_read")))
        assertEquals("outside content", ProviderDrafts.describeSources(emptyList()))
    }

    @Test
    fun `the chat card reads the call's name and host`() {
        assertEquals(ProviderDrafts.Call("Groq", "api.groq.com"), ProviderDrafts.call("""{"name":"Groq","api_type":"openai_chat","base_url":"https://api.groq.com/openai/v1"}"""))
        assertEquals(ProviderDrafts.Call("OpenRouter", "openrouter.ai"), ProviderDrafts.call("""{"api_type":"openrouter"}"""))
        assertNull(ProviderDrafts.call("""{"api_type":"openai_chat"}"""))
        assertNull(ProviderDrafts.call("not json"))
        assertEquals("Drafted provider “Groq” for your review", toolLabel(ProviderDrafts.TOOL, """{"name":"Groq"}"""))
        assertEquals(ToolCapability.LOCAL, ToolCapability.of(ProviderDrafts.TOOL))
    }
}
