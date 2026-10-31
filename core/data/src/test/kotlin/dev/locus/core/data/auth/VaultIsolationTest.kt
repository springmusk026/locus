package dev.locus.core.data.auth

import dev.locus.core.data.di.DataModule
import dev.locus.core.datastore.SecretStore
import dev.locus.core.llm.ChatRequest
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.SshHost
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vault's namespaces and the one place requests get credentials. A connector the agent drafted
 * names its secret, so that name must never reach provider keys, SSH credentials or OAuth sessions;
 * and one provider's key must never be sent to another provider's server.
 */
class VaultIsolationTest {
    private val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.example", "claude")
    private val openAi = ProviderConfig("b", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.example/v1", "gpt")

    private suspend fun TestGraph.seedEverySecretKind() {
        secrets.put(SecretStore.providerKey("a"), "KEY-A")
        secrets.put(SecretStore.sshPassword("h"), "SSH-PW")
        secrets.put(SecretStore.mcpOAuthSession("srv"), "MCP-SESSION")
        secrets.put(SecretStore.mcpOAuthClient("https://issuer.example"), "MCP-CLIENT")
        secrets.put(SecretStore.chatGptSession("c"), "CHATGPT-SESSION")
        secrets.put(SecretStore.chatGptAccount("c"), "CHATGPT-ACCOUNT")
        secrets.put(SecretStore.CHATGPT_HOST_ID, "HOST")
        secrets.put(SecretStore.connectorSecret("github-token"), "GH")
    }

    @Test
    fun `connectors resolve only connector secrets, whatever name they give`() = runTest {
        val graph = TestGraph(backgroundScope)
        graph.seedEverySecretKind()
        val resolver = DataModule.secretResolver(graph.secrets)

        assertEquals("GH", resolver.resolve("github-token"))
        val attempts = listOf(
            "provider:a", "../provider:a", "ssh:h:password", "mcp-oauth:srv", ":mcp-oauth:srv", "mcp-oauth-client:https://issuer.example",
            "chatgpt-oauth:c", "chatgpt-oauth-account:c", SecretStore.CHATGPT_HOST_ID, "connector:github-token", "", " github-token",
        )
        attempts.forEach { name -> assertNull(resolver.resolve(name), "connector name '$name' reached a secret") }
    }

    @Test
    fun `every non-connector secret lives outside the connector namespace`() {
        val names = listOf(
            SecretStore.providerKey("connector:x"), SecretStore.sshPassword("connector:x"), SecretStore.sshPrivateKey("x"),
            SecretStore.sshPassphrase("x"), SecretStore.mcpOAuthSession("connector:x"), SecretStore.mcpOAuthClient("x"),
            SecretStore.chatGptSession("connector:x"), SecretStore.chatGptAccount("x"), SecretStore.CHATGPT_HOST_ID,
        )
        names.forEach { assertFalse(it.startsWith("connector:"), "$it is reachable by connectors") }
        assertTrue(SecretStore.chatGptSession("p").startsWith(SecretStore.CHATGPT_SESSION_PREFIX))
    }

    @Test
    fun `integration keys list connector names only, never values or other secrets`() = runTest {
        val graph = TestGraph(backgroundScope)
        graph.seedEverySecretKind()
        graph.settingsRepository.setIntegrationKey("netlify", "  NETLIFY  ")

        assertEquals(setOf("github-token", "netlify"), graph.settingsRepository.integrationKeys.first())
        // Saved trimmed, under the name connectors use.
        assertEquals("NETLIFY", DataModule.secretResolver(graph.secrets).resolve("netlify"))
        graph.settingsRepository.setIntegrationKey("netlify", null)
        assertEquals(setOf("github-token"), graph.settingsRepository.integrationKeys.first())
    }

    @Test
    fun `provider keys are saved per provider, kept on null, removed on empty and on delete`() = runTest {
        val graph = TestGraph(backgroundScope)
        val repo = graph.settingsRepository
        repo.saveProvider(anthropic, " KEY-A ")
        repo.saveProvider(openAi, "KEY-B")
        assertEquals("KEY-A", graph.access.apiKey("a"))
        assertEquals("KEY-B", graph.access.apiKey("b"))
        assertEquals("a", repo.current().defaultProviderId)

        repo.saveProvider(anthropic.copy(model = "claude-2"), null)
        assertEquals("KEY-A", graph.access.apiKey("a"))
        assertEquals("claude-2", repo.current().provider("a")?.model)

        repo.saveProvider(openAi, "")
        assertNull(graph.access.apiKey("b"))

        graph.secrets.put(SecretStore.chatGptAccount("a"), "{}")
        repo.deleteProvider("a")
        assertNull(graph.access.apiKey("a"))
        assertNull(graph.secrets.get(SecretStore.chatGptAccount("a")))
        // The default moves to a provider that still exists.
        assertEquals("b", repo.current().defaultProviderId)
    }

    @Test
    fun `a provider counts as having a credential by its own key or its own sign-in`() = runTest {
        val graph = TestGraph(backgroundScope)
        graph.secrets.put(SecretStore.providerKey("a"), "KEY-A")
        graph.secrets.put(SecretStore.chatGptSession("chat"), "{}")
        graph.secrets.put(SecretStore.connectorSecret("provider:b"), "not a provider key")
        assertEquals(setOf("a", "chat"), graph.settingsRepository.providersWithKeys.first())
    }

    @Test
    fun `missing names the credential a provider lacks`() = runTest {
        val graph = TestGraph(backgroundScope)
        val chatGpt = ProviderConfig("chat", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.example", "gpt", requiresKey = false)
        val local = ProviderConfig("local", "Ollama", ApiFormat.OPENAI_CHAT, "http://10.0.0.2:11434/v1", "llama", requiresKey = false)

        assertTrue(graph.access.missing(anthropic)!!.contains("no API key"))
        assertTrue(graph.access.missing(chatGpt)!!.contains("isn't signed in"))
        assertNull(graph.access.missing(local))

        graph.secrets.put(SecretStore.providerKey("b"), "KEY-B")
        graph.secrets.put(SecretStore.chatGptSession("chat-2"), "{}")
        // Another provider's key or sign-in doesn't count.
        assertNotNull(graph.access.missing(anthropic))
        assertNotNull(graph.access.missing(chatGpt))

        graph.secrets.put(SecretStore.providerKey("a"), "KEY-A")
        graph.secrets.put(SecretStore.chatGptSession("chat"), "{}")
        assertNull(graph.access.missing(anthropic))
        assertNull(graph.access.missing(chatGpt))
    }

    @Test
    fun `each provider's requests carry only its own key, to its own host`() = runTest {
        val graph = TestGraph(backgroundScope)
        graph.secrets.put(SecretStore.providerKey("a"), "KEY-A")
        graph.secrets.put(SecretStore.providerKey("b"), "KEY-B")
        graph.http = { respond("""{"error":{"message":"nope"}}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json")) }
        val request = ChatRequest("system", listOf(Message("u", "c", Role.USER, listOf(Part.Text("hi")), 0)), emptyList())

        runCatching { graph.access.chatModel(anthropic).stream(request).toList() }
        runCatching { graph.access.chatModel(openAi).stream(request).toList() }
        runCatching { graph.access.listModels(openAi, typedKey = null) }
        runCatching { graph.access.listModels(anthropic, typedKey = "TYPED") }

        assertEquals(4, graph.requests.size)
        graph.requests.forEach { r ->
            val headers = r.headers.entries().flatMap { (_, values) -> values }.joinToString("\n")
            when (r.url.host) {
                "api.anthropic.example" -> assertFalse("KEY-B" in headers, "OpenAI's key went to Anthropic")
                "api.openai.example" -> assertFalse("KEY-A" in headers || "TYPED" in headers, "another key went to OpenAI")
                else -> error("request to an unexpected host ${r.url}")
            }
        }
        val (anthropicChat, openAiChat, openAiList, anthropicList) = graph.requests
        assertEquals("KEY-A", anthropicChat.headers["x-api-key"])
        assertEquals("Bearer KEY-B", openAiChat.headers[HttpHeaders.Authorization])
        assertEquals("Bearer KEY-B", openAiList.headers[HttpHeaders.Authorization])
        // A key typed in the editor (not saved yet) is the one used to list models.
        assertEquals("TYPED", anthropicList.headers["x-api-key"])
    }

    @Test
    fun `removing an SSH host removes all its credentials`() = runTest {
        val graph = TestGraph(backgroundScope)
        val host = SshHost("h", "box", "10.0.0.5", 22, "me", fingerprint = "SHA256:abc")
        graph.settingsRepository.saveSshHost(host, "pw", "key", "phrase")
        assertEquals(Triple("pw", "key", "phrase"), graph.settingsRepository.sshCredentials("h"))
        // Null keeps what's stored.
        graph.settingsRepository.saveSshHost(host, null, null, null)
        assertEquals(Triple("pw", "key", "phrase"), graph.settingsRepository.sshCredentials("h"))

        graph.settingsRepository.deleteSshHost("h")
        assertEquals(Triple(null, null, null), graph.settingsRepository.sshCredentials("h"))
        assertTrue(graph.settingsRepository.current().sshHosts.isEmpty())
    }
}
