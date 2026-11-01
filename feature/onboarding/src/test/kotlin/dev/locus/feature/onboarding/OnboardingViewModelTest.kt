package dev.locus.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import dev.locus.core.agent.home.AgentHome.PersonaFile
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderPresets
import dev.locus.core.model.ToolPolicy
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.ScriptedProvider
import dev.locus.core.testing.ScriptedProvider.script
import dev.locus.core.testing.TestGraph
import dev.locus.core.testing.ViewModelHost
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** First-run setup over the real repositories: steps, the model step, the check, SSH and finishing. */
class OnboardingViewModelTest {
    /**
     * Main runs on its own scheduler, apart from the test's: the view model's jobs have timeouts, and
     * the test's virtual clock jumps ahead while a real HTTP or IO call is out, which would fire them.
     */
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher(TestCoroutineScheduler()))

    private val host = ViewModelHost()

    /** runTest on its own scheduler (see [main]) that clears the view models at the end, before Main is reset. */
    private fun runTest(body: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest(StandardTestDispatcher(TestCoroutineScheduler())) {
        try {
            body()
        } finally {
            host.clear()
        }
    }

    private val anthropic = ProviderPresets.all.first { it.format == ApiFormat.ANTHROPIC_MESSAGES }

    private fun TestScope.vm(graph: TestGraph, saved: SavedStateHandle = SavedStateHandle()) = host.add(OnboardingViewModel(
        saved, graph.settingsRepository, graph.tools, graph.onDeviceModels, graph.onDevice, graph.linux, graph.ssh,
        graph.library, graph.conversations, graph.home, graph.access, graph.chatGpt, graph.helper,
    )).also { runCurrent() }

    /**
     * Waits for work the view model hands to real dispatchers (IO, the HTTP engine) to come back. The
     * test body suspends instead of blocking, so the test dispatcher keeps running what returns to Main.
     */
    private suspend fun settle(done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!done()) delay(5) }
    }

    @Test
    fun `Telegram isn't offered as a plain key, which couldn't deliver anything`() = runTest {
        val g = TestGraph(backgroundScope)
        val model = vm(g)
        val ids = model.uiState.first { it.integrations.isNotEmpty() }.integrations.map { it.first.id }
        assertFalse("telegram" in ids)
        assertTrue("tavily" in ids)
    }

    @Test
    fun `steps move forward and back and survive process death, with consent`() = runTest {
        val g = TestGraph(backgroundScope)
        val saved = SavedStateHandle()
        val first = vm(g, saved)
        assertEquals(Step.Intro, first.step)
        assertFalse(first.back())
        first.next()
        first.agree(true)
        first.next()
        assertEquals(Step.Provider, first.step)
        assertTrue(first.back())
        assertEquals(Step.Privacy, first.step)

        // A new ViewModel from the same saved state, as after the process was killed.
        val restored = vm(g, saved)
        assertEquals(Step.Privacy, restored.step)
        assertTrue(restored.consent)
        first.go(Step.Done)
        first.next()
        assertEquals(Step.Done, first.step)
    }

    @Test
    fun `an API key typed on the model step is never put in saved state`() = runTest {
        val g = TestGraph(backgroundScope)
        val saved = SavedStateHandle()
        val vm = vm(g, saved)
        vm.pickPreset(anthropic)
        vm.editProvider { it.copy(key = "sk-ant-secret", model = "claude-x") }
        vm.next()
        assertTrue(saved.keys().none { key -> saved.get<Any?>(key).toString().contains("sk-ant-secret") })
        assertEquals(setOf("step"), saved.keys())
    }

    @Test
    fun `a provider draft can be saved only with what it needs`() {
        val onDevice = ProviderDraft(ProviderPresets.all.first { it.format == ApiFormat.ON_DEVICE })
        assertTrue(onDevice.canSave)
        assertFalse(onDevice.canListModels)

        val keyed = ProviderDraft(anthropic, model = "claude")
        assertFalse(keyed.canSave)
        assertTrue(keyed.copy(key = "k").canSave)
        assertFalse(keyed.copy(key = "  ").canListModels)
        assertFalse(keyed.copy(key = "k", model = " ").canSave)

        val chatGpt = ProviderDraft(ProviderPresets.all.first { it.format == ApiFormat.CHATGPT }, model = "gpt")
        assertFalse(chatGpt.copy(key = "a key doesn't help").canSave)
        assertTrue(chatGpt.copy(signedIn = true).canSave)

        val local = ProviderDraft(ProviderPresets.all.first { it.name.startsWith("Ollama") }, model = "llama", baseUrl = " http://10.0.0.2:11434/v1/ ")
        assertTrue(local.canSave)
        assertEquals("http://10.0.0.2:11434/v1", local.config().baseUrl)
        assertEquals("On this phone", onDevice.config().name)
    }

    @Test
    fun `saving the provider stores its key under its own id and makes it the default`() = runTest {
        val g = TestGraph(backgroundScope, AppSettings(providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id))
        val vm = vm(g)
        vm.pickPreset(anthropic)
        vm.saveProvider() // no key or model yet: nothing happens
        assertFalse(vm.providerSave.running)
        vm.editProvider { it.copy(key = " sk-ant-1 ", model = "claude-x") }
        vm.saveProvider()
        settle { vm.providerSave.result != null || vm.providerSave.error != null }

        val id = vm.provider!!.id
        assertEquals("Saved as your default model", vm.providerSave.result)
        assertEquals(id, g.settingsRepository.current().defaultProviderId)
        assertEquals("sk-ant-1", g.secrets.get(SecretStore.providerKey(id)))
        assertNull(g.secrets.get(SecretStore.providerKey(ScriptedProvider.config.id)))
    }

    @Test
    fun `picking the on-device preset picks the first on-device model`() = runTest {
        val vm = vm(TestGraph(backgroundScope))
        vm.pickPreset(ProviderPresets.all.first { it.format == ApiFormat.ON_DEVICE })
        assertEquals("gemma-4-e2b", vm.provider?.model)
        vm.pickPreset(null)
        assertNull(vm.provider)
    }

    @Test
    fun `models are listed for the draft with the key typed in it`() = runTest {
        val g = TestGraph(backgroundScope)
        g.http = { respond("""{"data":[{"id":"claude-b"},{"id":"claude-a"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val vm = vm(g)
        vm.pickPreset(anthropic)
        vm.loadModels() // no key yet
        assertTrue(g.requests.isEmpty())
        vm.editProvider { it.copy(key = "typed") }
        vm.loadModels()
        settle { vm.modelList.result != null || vm.modelList.error != null }
        assertEquals(listOf("claude-a", "claude-b"), vm.remoteModels.map { it.id })
        assertEquals("typed", g.requests.single().headers["x-api-key"])
    }

    @Test
    fun `the check step sends one small request to the default model`() = runTest {
        val g = TestGraph(backgroundScope, AppSettings(providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id))
        g.script(ScriptedProvider.text("Ready when you are!"))
        val vm = vm(g)
        vm.testModel()
        settle { !vm.modelTest.running }
        assertTrue(vm.modelTest.result.orEmpty().startsWith("test-model: “Ready when you are!”"), vm.modelTest.toString())
        assertEquals(1, g.requests.size)
    }

    @Test
    fun `the check explains what is missing instead of failing silently`() = runTest {
        val vm = vm(TestGraph(backgroundScope))
        vm.testModel()
        settle { !vm.modelTest.running }
        assertTrue("No model is set up" in vm.modelTest.error.orEmpty())

        val keyed = TestGraph(backgroundScope, AppSettings(providers = listOf(dev.locus.core.model.ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://x.test", "m")), defaultProviderId = "a"))
        val noKey = vm(keyed)
        noKey.testModel()
        settle { !noKey.modelTest.running }
        assertTrue("no API key" in noKey.modelTest.error.orEmpty())
        assertTrue(keyed.requests.isEmpty())
    }

    @Test
    fun `a permission profile is applied when the privacy step is left`() = runTest {
        val g = TestGraph(backgroundScope)
        val vm = vm(g)
        vm.applyProfile() // none picked: nothing changes
        runCurrent()
        assertTrue(g.settingsRepository.current().toolPolicies.isEmpty())
        vm.pickProfile(PermissionProfile.Careful)
        vm.applyProfile()
        settle { g.settingsStore.value.toolPolicies.isNotEmpty() }
        assertEquals(ToolPolicy.ASK, g.settingsRepository.current().toolPolicies["browser_open"])
    }

    @Test
    fun `changing an SSH address forgets the confirmed fingerprint`() = runTest {
        val g = TestGraph(backgroundScope)
        val vm = vm(g)
        vm.editSsh { it.copy(host = "10.0.0.5", user = "me") }
        vm.editSsh { it.copy(fingerprint = "SHA256:abc") }
        vm.editSsh { it.copy(alias = "box") }
        assertEquals("SHA256:abc", vm.sshDraft.fingerprint)
        vm.editSsh { it.copy(port = "2222") }
        assertNull(vm.sshDraft.fingerprint)
        vm.saveSsh() // unconfirmed: not saved
        runCurrent()
        assertTrue(g.settingsRepository.current().sshHosts.isEmpty())

        vm.editSsh { it.copy(fingerprint = "SHA256:def", usePassword = true, secret = "pw") }
        vm.saveSsh()
        settle { vm.sshSave.result != null }
        val host = g.settingsRepository.current().sshHosts.single()
        assertEquals(2222, host.port)
        assertEquals("password", host.auth)
        assertEquals("SHA256:def", host.fingerprint)
        assertEquals("pw", g.secrets.get(SecretStore.sshPassword(host.id)))
        assertNull(g.secrets.get(SecretStore.sshPrivateKey(host.id)))
        assertEquals(SshDraft(), vm.sshDraft)
    }

    @Test
    fun `saving the identity writes the persona files new chats read`() = runTest {
        val g = TestGraph(backgroundScope)
        val vm = vm(g)
        settle { vm.personaLoaded }
        vm.editAnswers { it.copy(agentName = "Juniper") }
        vm.saveIdentity()
        settle { vm.identitySave.result != null || vm.identitySave.error != null }
        assertTrue("Juniper" in g.home.readPersona(PersonaFile.IDENTITY), g.home.readPersona(PersonaFile.IDENTITY))
        // Reopening onboarding reads the answers back.
        val again = vm(g)
        settle { again.personaLoaded }
        assertEquals("Juniper", again.answers.agentName)
    }

    @Test
    fun `finishing marks onboarding done and starts a first chat only with a model`() = runTest {
        val none = TestGraph(backgroundScope)
        var chat: String? = "unset"
        vm(none).finish(startChat = true) { chat = it }
        settle { chat != "unset" }
        assertNull(chat)
        assertTrue(none.settingsRepository.current().onboardingDone)

        val g = TestGraph(backgroundScope, AppSettings(providers = listOf(ScriptedProvider.config), defaultProviderId = ScriptedProvider.config.id))
        var started: String? = null
        vm(g).finish(startChat = true) { started = it }
        settle { started != null }
        assertEquals(ScriptedProvider.config.id, g.conversations.get(started!!)?.providerId)
        assertNotNull(g.settingsRepository.current().onboardingDone)
    }
}
