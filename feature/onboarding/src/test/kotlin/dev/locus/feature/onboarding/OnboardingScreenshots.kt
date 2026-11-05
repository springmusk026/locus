package dev.locus.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.lifecycle.SavedStateHandle
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.AppSettings
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderPresets
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** First-run setup, every step and its sheets, over the real view model (docs/design/design-system.md, "Review gate"). */
class OnboardingScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private val anthropic = ProviderConfig("anthropic", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5", contextWindow = 200_000)
    private val openRouter = ProviderConfig("openrouter", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3.1")

    private val homelab = SshHost("h1", "homelab", "192.168.1.40", 22, "maya", SshAuth.KEY.id, "SHA256:Xw3n0l8Pq2yH7v6tF1mJk9cR4sUe5dZaB0gL2oNiT8E")
    private val vps = SshHost("h2", "vps-fra", "fra1.okafor.dev", 2222, "deploy", SshAuth.PASSWORD.id, "SHA256:aK8d2Lq0Rm3Zp7Xv1Nc5Bt9Wy4Hs6Ju0Ef2Gi8Ko3Ql")

    private fun graph(settings: AppSettings = AppSettings()) = TestGraph(scope, settings)

    /** A phone a few steps in: a model with its key, two machines, and three integrations. */
    private fun setUp(): TestGraph {
        val g = graph(AppSettings(sshHosts = listOf(homelab, vps)))
        runBlocking {
            g.settingsRepository.saveProvider(anthropic, "sk-ant-api03-Xq7m2Lr9vT4pN8sK1dF6hJ3cB0wZ5yE2uA7gR4tY9iO1pS8")
            g.settingsRepository.saveProvider(openRouter, "sk-or-v1-4b9e2a7c1d8f3e6b0a5c9d2e7f1b4a8c3d6e9f2a5b8c1d4e7f0a3b6c9d2e5f8")
            g.settingsRepository.setIntegrationKey(Integrations.github.secret, "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ")
            g.settingsRepository.setIntegrationKey(Integrations.brave.secret, "BSAx9f2Kq7Lm3Np8Rt4Vw6Yz1Ab5Cd0Ef")
        }
        return g
    }

    private fun vm(g: TestGraph, step: Step? = null): OnboardingViewModel = OnboardingViewModel(
        SavedStateHandle(), g.settingsRepository, g.tools, g.onDeviceModels, g.onDevice, g.linux, g.linuxSetup, g.ssh,
        g.library, g.conversations, g.home, g.access, g.chatGpt, g.helper, g.telegram, g.updates(),
    ).also { vm ->
        step?.let(vm::go)
        // The persona loads from disk on IO; every step after it reads the answers.
        settle { vm.personaLoaded }
    }

    /** The repositories' state as the screen will collect it (uiState only updates while collected). */
    private fun OnboardingViewModel.ready(until: (OnboardingUiState) -> Boolean) = runBlocking { withTimeout(10_000) { uiState.first(until) } }

    private fun settle(done: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!done()) delay(10) } }

    private fun screen(vm: OnboardingViewModel): @Composable () -> Unit = { OnboardingRoute(onFinish = {}, onExit = {}, viewModel = vm) }

    // ---- Steps

    @Test
    fun intro() = snap("onboarding-intro", content = screen(vm(graph())))

    @Test
    fun privacy() = snap("onboarding-privacy", heightDp = 1700, content = screen(vm(graph(), Step.Privacy)))

    @Test
    fun privacyAgreed() {
        val vm = vm(graph(), Step.Privacy)
        vm.pickProfile(PermissionProfile.entries.first())
        vm.agree(true)
        snap("onboarding-privacy-agreed", heightDp = 1700, content = screen(vm))
    }

    @Test
    fun providerChoose() = snap("onboarding-provider", heightDp = 1300, content = screen(vm(graph(), Step.Provider)))

    @Test
    fun providerAnthropic() {
        val vm = vm(graph(), Step.Provider)
        vm.pickPreset(ProviderPresets.all.first { it.format == ApiFormat.ANTHROPIC_MESSAGES })
        vm.editProvider { it.copy(key = "sk-ant-api03-Xq7m2Lr9vT4pN8sK1dF6hJ3cB0wZ5yE2uA7gR4tY9iO1pS8") }
        snap("onboarding-provider-anthropic", heightDp = 1000, content = screen(vm))
    }

    @Test
    fun providerOllama() {
        val vm = vm(graph(), Step.Provider)
        vm.pickPreset(ProviderPresets.all.first { it.name.startsWith("Ollama") })
        vm.editProvider { it.copy(model = "qwen3:14b") }
        snap("onboarding-provider-ollama", heightDp = 1000, content = screen(vm))
    }

    @Test
    fun providerChatGpt() {
        val vm = vm(graph(), Step.Provider)
        vm.pickPreset(ProviderPresets.all.first { it.format == ApiFormat.CHATGPT })
        snap("onboarding-provider-chatgpt", heightDp = 900, content = screen(vm))
    }

    @Test
    fun providerSaved() {
        val g = setUp()
        val vm = vm(g, Step.Provider)
        vm.ready { it.providers.size == 2 }
        snap("onboarding-provider-saved", heightDp = 1500, content = screen(vm))
    }

    @Test
    fun identity() {
        val vm = vm(graph(), Step.Identity)
        vm.editAnswers {
            it.copy(
                agentName = "Juniper",
                style = TalkStyle.Concise,
                areas = setOf(HelpArea.Coding, HelpArea.Servers, HelpArea.Planning),
                userName = "Maya Okafor",
                callMe = "Maya",
                timeZone = "Africa/Lagos",
                location = "Lagos, Nigeria",
                language = "English",
                always = "Use metric units and 24-hour times.",
                never = "Never push to main or delete files without asking.",
            )
        }
        snap("onboarding-identity", heightDp = 2400, content = screen(vm))
    }

    @Test
    fun linux() = snap("onboarding-linux", heightDp = 1300, content = screen(vm(graph(), Step.Linux)))

    @Test
    fun sshEmpty() = snap("onboarding-ssh", heightDp = 1200, content = screen(vm(graph(), Step.Ssh)))

    @Test
    fun sshHosts() {
        val g = setUp()
        val vm = vm(g, Step.Ssh)
        vm.ready { it.sshHosts.size == 2 }
        vm.editSsh { it.copy(alias = "pi-garage", host = "192.168.1.52", user = "pi", method = SshAuth.PASSWORD, password = "correct-horse-battery") }
        snap("onboarding-ssh-hosts", heightDp = 1500, content = screen(vm))
    }

    @Test
    fun connectors() {
        val g = setUp()
        val vm = vm(g, Step.Connectors)
        vm.ready { it.integrations.count { it.second } == 2 }
        snap("onboarding-connectors", heightDp = 2000, content = screen(vm))
    }

    @Test
    fun check() {
        val g = setUp()
        // The key is refused, as a revoked one would be: the check shows the provider's answer.
        g.http = {
            respond(
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val vm = vm(g, Step.Test)
        vm.ready { it.providers.size == 2 }
        vm.testModel()
        settle { !vm.modelTest.running && vm.modelTest != Job() }
        snap("onboarding-check", heightDp = 900, content = screen(vm))
    }

    @Test
    fun checkNothingSetUp() {
        val vm = vm(graph(), Step.Test)
        vm.testModel()
        settle { !vm.modelTest.running && vm.modelTest != Job() }
        snap("onboarding-check-nothing", heightDp = 900, content = screen(vm))
    }

    @Test
    fun done() {
        val g = setUp()
        val vm = vm(g, Step.Done)
        vm.pickProfile(PermissionProfile.entries.first())
        vm.editAnswers { it.copy(agentName = "Juniper", style = TalkStyle.Concise) }
        vm.ready { it.providers.size == 2 && it.integrations.count { it.second } == 2 }
        snap("onboarding-done", heightDp = 1000, content = screen(vm))
    }

    @Test
    fun doneNothingSetUp() = snap("onboarding-done-nothing", heightDp = 1000, content = screen(vm(graph(), Step.Done)))

    @Test
    fun personaRoute() {
        val vm = vm(graph())
        vm.editAnswers { it.copy(agentName = "Juniper", userName = "Maya Okafor", callMe = "Maya", timeZone = "Africa/Lagos", location = "Lagos, Nigeria") }
        snap("persona-route", heightDp = 2400) { PersonaRoute(onBack = {}, viewModel = vm) }
    }

    // ---- Sheets

    @Test
    fun personaEditor() {
        val files = PersonaTemplates.generate(
            PersonaAnswers(agentName = "Juniper", style = TalkStyle.Concise, areas = setOf(HelpArea.Coding, HelpArea.Planning), userName = "Maya Okafor", callMe = "Maya", timeZone = "Africa/Lagos", location = "Lagos, Nigeria"),
        )
        snap("onboarding-sheet-persona-editor", heightDp = 1000) {
            CallPrivate(IDENTITY, "PersonaEditorSheet", files, dev.locus.core.agent.home.AgentHome.PersonaFile.IDENTITY, { _: PersonaFiles -> }, {})
        }
    }

    @Test
    fun integrationKey() = snap("onboarding-sheet-integration-key", heightDp = 800) {
        CallPrivate(SETUP, "IntegrationKeySheet", Integrations.firecrawl, false, { _: String? -> }, {})
    }

    @Test
    fun integrationKeySaved() = snap("onboarding-sheet-integration-key-saved", heightDp = 800) {
        CallPrivate(SETUP, "IntegrationKeySheet", Integrations.ntfy, true, { _: String? -> }, {})
    }

    /** Calls a private composable of [fileClass] with [args]; Compose adds a composer and masks. */
    @Composable
    private fun CallPrivate(fileClass: String, name: String, vararg args: Any?) {
        val method = Class.forName(fileClass).declaredMethods.single { it.name == name }
        method.isAccessible = true
        val masks = List(method.parameterCount - args.size - 1) { 0 }
        method.invoke(null, *(args.toList() + currentComposer + masks).toTypedArray())
    }

    private companion object {
        const val IDENTITY = "dev.locus.feature.onboarding.IdentityStepKt"
        const val SETUP = "dev.locus.feature.onboarding.SetupStepsKt"
    }
}
