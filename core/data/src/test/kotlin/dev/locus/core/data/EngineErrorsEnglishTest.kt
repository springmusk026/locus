package dev.locus.core.data

import dev.locus.core.data.auth.VaultTexts
import dev.locus.core.data.connectors.errorText
import dev.locus.core.data.connectors.warningText
import dev.locus.core.data.mcp.McpTemplates
import dev.locus.core.datastore.SecretStore
import dev.locus.core.datastore.VaultState
import dev.locus.core.datastore.userMessage
import dev.locus.core.linux.ChecksumException
import dev.locus.core.linux.EngineText
import dev.locus.core.linux.EngineTextError
import dev.locus.core.linux.LinuxNotInstalledException
import dev.locus.core.linux.R as LinuxR
import dev.locus.core.linux.engineText
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.LINUX_INSTALL_PATH
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ServiceAddress
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Errors core/data and core/linux throw for the user carry their words for the screen ([EngineTextError]),
 * and in English those words are exactly the message the exception keeps for the model and the logs
 * (docs/design/localization.md, "Text from the engine"). Rendered from the English strings with [EnglishResources].
 */
class EngineErrorsEnglishTest {
    private fun EngineText.en() = EnglishResources.render(this)

    /** [e]'s screen text is a resource (not its English passed through) and reads as its message. */
    private fun assertWorded(e: Throwable) {
        val text = assertIs<EngineTextError>(e, "${e::class.simpleName}: ${e.message}").text
        assertTrue(text !is EngineText.Plain, "not in resources: ${e.message}")
        assertEquals(e.message, text.en())
    }

    @Test
    fun `why a provider can't answer reads as the English the run keeps`() = runTest {
        val graph = TestGraph(backgroundScope)
        val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.example", "claude")
        val chatGpt = ProviderConfig("chat", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.example", "gpt", requiresKey = false)
        val tampered = anthropic.copy(baseUrl = "https://relay.example", addressUnconfirmed = true)
        listOf(anthropic, chatGpt, tampered).forEach { p ->
            val problem = assertNotNull(graph.access.problem(p))
            assertIs<EngineText.Res>(problem.text)
            assertEquals(problem.english, problem.text.en())
            assertEquals(graph.access.missing(p), problem.english)
        }
        graph.secrets.put(SecretStore.providerKey("a"), "KEY-A")
        assertNull(graph.access.problem(anthropic))
    }

    @Test
    fun `the saved-keys messages read as their English`() {
        listOf(VaultState.Locked, VaultState.NeedsReset("gone", permanent = true), VaultState.NotOpened, VaultState.Unavailable("x")).forEach { state ->
            val english = assertNotNull(state.userMessage())
            val text = VaultTexts.text(english)
            assertIs<EngineText.Res>(text, english)
            assertEquals(english, text.en())
        }
        assertEquals(EngineText.Plain("something else"), VaultTexts.text("something else"))
    }

    @Test
    fun `every refused server address reads as the English the agent reads`() {
        val inputs = listOf(
            "", "rss example.com", "mailto:me@example.com", "ftp://rss.example.com", "https:///path", "https://me:pw@rss.example.com",
            "https://rss.example.com/?a=1", "https://rss.example.com:99999", "http://rss.example.com", "https://[::1",
        )
        val seen = mutableSetOf<ServiceAddress.Problem>()
        inputs.forEach { input ->
            val checked = ServiceAddress.check(input)
            val problem = assertNotNull(checked.problem, input)
            seen += problem
            val text = assertNotNull(checked.errorText, input)
            assertIs<EngineText.Res>(text, input)
            assertEquals(checked.error, text.en(), input)
        }
        assertEquals(ServiceAddress.Problem.entries.toSet() - seen, emptySet(), "inputs cover every problem")
        val lan = ServiceAddress.check("http://192.168.1.20:8123")
        assertNull(lan.errorText)
        assertEquals(ServiceAddress.LAN_HTTP_WARNING, assertIs<EngineText.Res>(lan.warningText).en())
        assertNull(ServiceAddress.check("https://rss.example.com").warningText)
    }

    @Test
    fun `Linux's errors read as the English the tools and logs keep`() {
        assertWorded(LinuxNotInstalledException())
        assertTrue(LINUX_INSTALL_PATH in LinuxNotInstalledException().message.orEmpty())
        assertWorded(LinuxNotInstalledException("Linux is still being installed.", EngineText.Res(LinuxR.string.linux_error_still_installing)))
        assertWorded(LinuxNotInstalledException("Linux isn't installed.", EngineText.Res(LinuxR.string.linux_error_not_installed_short)))
        assertWorded(ChecksumException("ab12"))
        assertEquals(LINUX_INSTALL_PATH, EnglishResources.render(EngineText.Res(LinuxR.string.linux_install_path)))
        // An error without words of its own shows its message as it is.
        assertEquals(EngineText.Plain("disk full"), java.io.IOException("disk full").engineText())
    }

    @Test
    fun `Library's setup errors read as their English`() = runTest {
        val graph = TestGraph(backgroundScope)
        val github = assertNotNull(McpTemplates.byId("github"))
        assertWorded(runCatching { graph.library.addMcpTemplate(github, url = "http://api.githubcopilot.com/mcp/") }.exceptionOrNull()!!)
        assertWorded(runCatching { graph.library.addMcpTemplate(github, url = "https://elsewhere.example/mcp/", useSavedKey = true) }.exceptionOrNull()!!)
        val miniflux = assertNotNull(dev.locus.core.data.connectors.ConnectorTemplates.byId("miniflux"))
        assertWorded(runCatching { graph.library.addService(miniflux, "ftp://rss.example.com", "", "key") }.exceptionOrNull()!!)
        assertWorded(runCatching { graph.library.addHomeAssistant("https://ha.example.com", full = false, token = " ", rules = dev.locus.core.agent.home.Connector.HomeAssistantRules()) }.exceptionOrNull()!!)
        val failed = assertIs<dev.locus.core.data.connectors.ConnectionCheck.Failed>(graph.library.testService(miniflux, "", "", "key"))
        assertEquals("Enter your server's address.", failed.message.en())
        assertWorded(runCatching { graph.library.moveMemoryToGeneral(java.util.UUID.randomUUID().toString(), "topic") }.exceptionOrNull()!!)
    }
}
