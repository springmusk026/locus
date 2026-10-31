package dev.locus.feature.settings

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings over the real repositories: providers and their keys, tool permissions, the reviewer, SSH. */
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val host = ViewModelHost()

    /** runTest that clears the view models at the end, before Main is reset. */
    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val anthropic = ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.test", "claude")
    private val openAi = ProviderConfig("b", "OpenAI", ApiFormat.OPENAI_CHAT, "https://api.openai.test/v1", "gpt")

    private class Fixture(val graph: TestGraph, val vm: SettingsViewModel)

    private fun TestScope.fixture(settings: AppSettings = AppSettings()): Fixture {
        val g = TestGraph(backgroundScope, settings)
        val vm = host.add(SettingsViewModel(g.settingsRepository, g.tools, g.ssh, g.onDeviceModels, g.chatGpt, g.telegram))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return Fixture(g, vm)
    }

    private suspend fun SettingsViewModel.state(until: (SettingsUiState) -> Boolean) = uiState.first { !it.loading && until(it) }

    @Test
    fun `providers show whether their own key is saved and which is the default`() = runTest {
        val f = fixture()
        f.vm.saveProvider(anthropic, "KEY-A")
        f.vm.saveProvider(openAi, null)
        val s = f.vm.state { it.providers.size == 2 }
        assertEquals(listOf(true, false), s.providers.map { it.hasKey })
        assertEquals(listOf(true, false), s.providers.map { it.isDefault })

        f.vm.setDefault("b")
        f.vm.deleteProvider("a")
        val after = f.vm.state { it.providers.size == 1 }
        assertTrue(after.providers.single().isDefault)
        assertNull(f.graph.secrets.get(SecretStore.providerKey("a")))
    }

    @Test
    fun `listing models with a blank key uses the saved one, a typed key wins`() = runTest {
        val f = fixture()
        f.vm.saveProvider(openAi, "SAVED")
        f.graph.http = { respond("""{"data":[{"id":"gpt-x"},{"id":"text-embedding-3"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }

        var models: Result<List<dev.locus.core.llm.RemoteModel>>? = null
        f.vm.listModels(openAi, "   ") { models = it }.join()
        assertEquals(listOf("gpt-x"), models!!.getOrThrow().map { it.id })
        assertEquals("Bearer SAVED", f.graph.requests.last().headers[HttpHeaders.Authorization])

        f.vm.listModels(openAi, "TYPED") { models = it }.join()
        assertEquals("Bearer TYPED", f.graph.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a listing failure comes back as a result, not a crash`() = runTest {
        val f = fixture()
        var models: Result<List<dev.locus.core.llm.RemoteModel>>? = null
        f.vm.listModels(openAi, "k") { models = it }.join()
        assertTrue(models!!.isFailure)
        assertTrue("404" in models!!.exceptionOrNull()!!.message.orEmpty())
    }

    @Test
    fun `tool permissions show the default, and choosing the default clears the override`() = runTest {
        val f = fixture()
        val s = f.vm.state { it.tools.isNotEmpty() }
        val write = s.tools.first { it.name == "write_file" }
        assertEquals(ToolRisk.WRITE, write.risk)
        assertEquals(ToolPolicy.ASK, write.policy)
        assertTrue(write.isDefault)
        assertTrue(s.tools.first { it.name == "read_file" }.let { it.policy == ToolPolicy.ALLOW && it.isDefault })
        assertEquals(s.tools.map { it.name }.sorted(), s.tools.map { it.name })

        f.vm.setPolicy(write, ToolPolicy.ALLOW)
        val allowed = f.vm.state { st -> st.tools.first { it.name == "write_file" }.policy == ToolPolicy.ALLOW }.tools.first { it.name == "write_file" }
        assertFalse(allowed.isDefault)
        assertEquals(ToolPolicy.ALLOW, f.graph.settingsRepository.current().toolPolicies["write_file"])

        f.vm.setPolicy(allowed, ToolPolicy.ASK)
        f.vm.state { st -> st.tools.first { it.name == "write_file" }.isDefault }
        assertFalse("write_file" in f.graph.settingsRepository.current().toolPolicies)
    }

    @Test
    fun `reviewer and assistant options are saved`() = runTest {
        val f = fixture()
        f.vm.setReviewerMode(ReviewerMode.REVIEW_ALL_BUT_READS)
        f.vm.setReviewerApprovesOutward(true)
        f.vm.setAutoTitle(false)
        f.vm.setSuggestFollowUps(false)
        f.vm.setReflectAfterTasks(false)
        f.vm.setSubagentReportsContinue(false)
        f.vm.setModelCatalog(true)
        f.vm.setHelperModel("a", " small-model ")
        f.vm.saveInstructions("Be brief.")
        val s = f.vm.state { it.customInstructions == "Be brief." }
        assertEquals(ReviewerMode.REVIEW_ALL_BUT_READS, s.reviewerMode)
        assertTrue(s.reviewerApprovesOutward && s.modelCatalog)
        assertFalse(s.autoTitle || s.suggestFollowUps || s.reflectAfterTasks || s.subagentReportsContinue)
        assertEquals("a", s.helperProviderId)
        assertEquals("small-model", s.helperModel)
    }

    @Test
    fun `integration keys show as set without exposing them`() = runTest {
        val f = fixture()
        val tavily = Integrations.all.first { it.id == "tavily" }
        f.vm.setIntegrationKey(tavily, " tvly-1 ")
        val s = f.vm.state { st -> st.integrations.any { it.second } }
        assertEquals(listOf("tavily"), s.integrations.filter { it.second }.map { it.first.id })
        assertEquals("tvly-1", f.graph.secrets.get(SecretStore.connectorSecret(tavily.secret)))
    }

    @Test
    fun `a ChatGPT sign-in that was never saved is forgotten, a saved provider's is kept`() = runTest {
        val chatGpt = ProviderConfig("c", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.test", "", requiresKey = false)
        val f = fixture(AppSettings(providers = listOf(chatGpt)))
        f.graph.secrets.put(SecretStore.chatGptAccount("c"), "{}")
        f.graph.secrets.put(SecretStore.chatGptAccount("unsaved"), "{}")
        f.graph.secrets.put(SecretStore.chatGptSession("c"), "{}")

        assertEquals(setOf("c"), f.vm.state { it.chatGptAccounts.isNotEmpty() }.chatGptAccounts.keys)
        f.vm.discardChatGpt("unsaved").join()
        f.vm.discardChatGpt("c").join()
        assertNull(f.graph.secrets.get(SecretStore.chatGptAccount("unsaved")))
        assertNotNull(f.graph.secrets.get(SecretStore.chatGptAccount("c")))
    }

    @Test
    fun `SSH hosts are saved with their credentials and removed with them`() = runTest {
        val f = fixture()
        val host = dev.locus.core.model.SshHost("h", "box", "10.0.0.5", user = "me", fingerprint = "SHA256:x")
        f.vm.saveSshHost(host, "pw", null, null)
        assertEquals(listOf(host), f.vm.state { it.sshHosts.isNotEmpty() }.sshHosts)
        assertEquals("pw", f.graph.secrets.get(SecretStore.sshPassword("h")))
        f.vm.deleteSshHost("h")
        f.vm.state { it.sshHosts.isEmpty() }
        assertNull(f.graph.secrets.get(SecretStore.sshPassword("h")))
    }

    @Test
    fun `on-device models are listed with their state`() = runTest {
        val s = fixture().vm.state { it.onDeviceModels.isNotEmpty() }
        assertEquals(listOf("gemma-4-e2b", "gemma-4-e4b"), s.onDeviceModels.map { it.first.id })
    }
}
