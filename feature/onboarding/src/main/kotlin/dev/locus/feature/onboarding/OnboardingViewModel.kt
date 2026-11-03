package dev.locus.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.home.AgentHome.PersonaFile
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.EmailConfig
import dev.locus.core.agent.integrations.Ntfy
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.auth.ChatGptSignIn
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.ssh.SshService
import dev.locus.core.data.linux.LinuxPackage
import dev.locus.core.data.linux.LinuxSetup
import dev.locus.core.data.linux.LinuxSetupState
import dev.locus.core.data.linux.PackageState
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderPreset
import dev.locus.core.model.ProviderPresets
import dev.locus.core.model.Role
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import dev.locus.core.data.integrations.TelegramSetup
import dev.locus.core.ui.TelegramActions
import dev.locus.core.ui.integrationRows
import dev.locus.core.ui.sshEditorSave
import dev.locus.core.ui.sshSecretReady
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceEngines
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ondevice.OnDeviceModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject

enum class Step(val title: String, val skippable: Boolean) {
    Intro("Welcome", false),
    Privacy("Privacy", false),
    Provider("Model", true),
    Identity("Your agent", true),
    Linux("Workspace", true),
    Ssh("Machines", true),
    Connectors("Connectors", true),
    Test("Check", true),
    Done("Ready", false),
}

/** What the app already has, read from the repositories. */
data class OnboardingUiState(
    val providers: List<ProviderConfig> = emptyList(),
    val defaultProviderId: String? = null,
    val onDeviceModels: List<Pair<OnDeviceModel, ModelState>> = emptyList(),
    val linux: LinuxStatus = LinuxStatus.NotInstalled,
    val sshHosts: List<SshHost> = emptyList(),
    val integrations: List<Pair<Integration, Boolean>> = emptyList(),
) {
    val defaultProvider: ProviderConfig? get() = providers.firstOrNull { it.id == defaultProviderId } ?: providers.firstOrNull()
}

/** A one-off action's progress: running, a result to show, or an error. */
data class Job(val running: Boolean = false, val result: String? = null, val error: String? = null) {
    companion object {
        val Running = Job(running = true)
    }
}

/** The provider being set up on the model step. */
data class ProviderDraft(
    val preset: ProviderPreset,
    val id: String = UUID.randomUUID().toString(),
    val baseUrl: String = preset.baseUrl,
    val key: String = "",
    val model: String = preset.model,
    val contextWindow: Int? = null,
    /** A ChatGPT draft has finished "Continue with ChatGPT". */
    val signedIn: Boolean = false,
) {
    val onDevice get() = preset.format == ApiFormat.ON_DEVICE
    val signsIn get() = preset.format == ApiFormat.CHATGPT
    private val hasCredential get() = if (signsIn) signedIn else !preset.requiresKey || key.isNotBlank()
    val canSave get() = model.isNotBlank() && (onDevice || baseUrl.isNotBlank()) && hasCredential
    val canListModels get() = !onDevice && baseUrl.isNotBlank() && hasCredential

    fun config() = ProviderConfig(
        id = id,
        name = if (onDevice) "On this phone" else preset.name,
        format = preset.format,
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        requiresKey = preset.requiresKey,
        contextWindow = contextWindow,
    )
}

/** The machine being added on the SSH step. The key and the password are kept apart, as in Settings. */
data class SshDraft(
    val alias: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val method: SshAuth = SshAuth.KEY,
    val privateKey: String = "",
    val password: String = "",
    val passphrase: String = "",
    val fingerprint: String? = null,
) {
    /** A new machine: needs a key or password unless it logs in without authentication. */
    val canSave get() = user.isNotBlank() && fingerprint != null && sshSecretReady(method, method, isNew = true, privateKey, password)
}

/**
 * First-run setup. Form drafts live here as Compose state (not in a flow) so text fields update
 * synchronously, and survive rotation with the ViewModel; the current step also survives process
 * death through [SavedStateHandle]. API keys are deliberately never put in saved state.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val settings: SettingsRepository,
    private val catalog: ToolCatalog,
    private val models: OnDeviceModels,
    private val engines: OnDeviceEngines,
    private val linuxEnv: LinuxEnvironment,
    private val setup: LinuxSetup,
    private val ssh: SshService,
    private val library: LibraryRepository,
    private val conversations: ConversationRepository,
    private val home: AgentHome,
    private val access: ProviderAccess,
    private val chatGpt: ChatGptSignIn,
    private val helper: HelperModel,
    telegramSetup: TelegramSetup,
    private val updates: dev.locus.core.data.updates.Updates,
) : ViewModel() {

    /** Whether this build can check GitHub for updates, so the privacy step asks (docs/design/updates.md). */
    val offerUpdateCheck: Boolean get() = updates.availability.canSchedule

    /** The update check's setting; off until the user turns it on. */
    val updateCheck: StateFlow<Boolean> = updates.enabled.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), false)

    fun setUpdateCheck(on: Boolean) {
        viewModelScope.launch { updates.setEnabled(on) }
    }

    /** The Telegram sheet's actions, the same as in Settings → Integrations. */
    val telegram = TelegramActions.of(telegramSetup, viewModelScope)

    val uiState: StateFlow<OnboardingUiState> = combine(
        settings.settings,
        models.states,
        linuxEnv.status,
        settings.integrationKeys,
        telegramSetup.connected,
    ) { s, modelStates, linux, keys, telegramConnected ->
        OnboardingUiState(
            providers = s.providers,
            defaultProviderId = s.defaultProviderId,
            onDeviceModels = models.catalog.map { it to (modelStates[it.id] ?: ModelState.NotDownloaded) },
            linux = linux,
            sshHosts = s.sshHosts,
            // Telegram opens its own sheet (token, then the chat found after messaging the bot), and shows
            // as connected only when what's saved parses.
            integrations = integrationRows(keys, telegramConnected),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OnboardingUiState())

    // ---- Steps ----------------------------------------------------------------------------------

    var step by mutableStateOf(Step.entries.getOrElse(saved[STEP] ?: 0) { Step.Intro })
        private set

    fun go(to: Step) {
        step = to
        saved[STEP] = to.ordinal
    }

    fun next() = Step.entries.getOrNull(step.ordinal + 1)?.let(::go)

    /** False on the first step, so the caller can leave onboarding instead. */
    fun back(): Boolean = Step.entries.getOrNull(step.ordinal - 1)?.let { go(it); true } ?: false

    // ---- Privacy --------------------------------------------------------------------------------

    var consent by mutableStateOf(saved[CONSENT] ?: false)
        private set
    var profile by mutableStateOf<PermissionProfile?>(null)
        private set

    fun agree(value: Boolean) {
        consent = value
        saved[CONSENT] = value
    }

    fun pickProfile(value: PermissionProfile) {
        profile = value
    }

    /** Applies the chosen profile, if any, when the user moves on from the privacy step. */
    fun applyProfile() {
        val chosen = profile ?: return
        viewModelScope.launch { settings.setToolPolicies(chosen.overrides(catalog.registry.all)) }
    }

    // ---- Model provider -------------------------------------------------------------------------

    val presets = ProviderPresets.all

    var provider by mutableStateOf<ProviderDraft?>(null)
        private set
    var remoteModels by mutableStateOf<List<RemoteModel>>(emptyList())
        private set
    var modelList by mutableStateOf(Job())
        private set
    var providerSave by mutableStateOf(Job())
        private set

    var chatGptSignIn by mutableStateOf(Job())
        private set
    /** Shown once after a first ChatGPT sign-in: requests now use the user's plan. */
    var chatGptWelcome by mutableStateOf(false)
        private set

    fun pickPreset(preset: ProviderPreset?) {
        provider?.takeIf { it.signsIn && it.signedIn }?.let { discardChatGpt(it.id) }
        chatGptSignIn = Job()
        val onDevice = preset?.format == ApiFormat.ON_DEVICE
        provider = preset?.let { ProviderDraft(it, model = if (onDevice) models.catalog.first().id else it.model) }
        remoteModels = emptyList()
        modelList = Job()
        providerSave = Job()
    }

    fun editProvider(change: (ProviderDraft) -> ProviderDraft) {
        provider = provider?.let(change)
        providerSave = Job()
    }

    fun loadModels() {
        val draft = provider?.takeIf { it.canListModels } ?: return
        runJob(modelList, { modelList = it }) {
            val list = settings.listModels(draft.config(), draft.key.takeIf { it.isNotBlank() })
            // The draft may have changed provider while this was loading.
            if (provider?.id == draft.id) remoteModels = list
            if (list.isEmpty()) "The provider didn't list any models. Type a model ID instead." else "${list.size} models"
        }
    }

    private var chatGptSignInJob: kotlinx.coroutines.Job? = null

    /** "Continue with ChatGPT" for the draft: the browser sign-in, then its models. */
    fun signInChatGpt() {
        val draft = provider?.takeIf { it.signsIn } ?: return
        chatGptSignInJob = runJob(chatGptSignIn, { chatGptSignIn = it }) {
            val first = chatGpt.signIn(draft.id)
            if (provider?.id == draft.id) {
                provider = provider?.copy(signedIn = true)
                chatGptWelcome = first
                loadModels()
            }
            chatGpt.email(draft.id)?.let { "Signed in as $it" } ?: "Signed in"
        }
    }

    /** Stops waiting for the browser sign-in (the user changed their mind there). */
    fun cancelChatGptSignIn() {
        chatGptSignInJob?.cancel()
        chatGptSignInJob = null
    }

    fun dismissChatGptWelcome() {
        chatGptWelcome = false
    }

    /** A sign-in for a draft that was never saved shouldn't linger in the vault. */
    private fun discardChatGpt(id: String) = viewModelScope.launch {
        if (settings.current().providers.none { it.id == id }) runCatching { chatGpt.forget(id) }
    }

    fun downloadModel(id: String) = viewModelScope.launch { models.download(id, settings.current().power.downloadsUnmeteredOnly) }

    /** Goes ahead on mobile data with a download that was waiting for Wi-Fi. */
    fun downloadModelOverAnyNetwork(id: String) = models.downloadOverAnyNetwork(id)

    /** A warning when this phone has less memory than the model is meant for (or is low right now). */
    fun ramWarning(id: String): String? {
        val model = models.model(id) ?: return null
        return models.ramCheck(id)?.let { dev.locus.core.ondevice.MemoryGate.warning(model.name, it) }
    }
    fun pauseModel(id: String) = models.cancel(id)

    /** Saves the draft and makes it the default for new chats. */
    fun saveProvider() {
        val draft = provider?.takeIf { it.canSave } ?: return
        runJob(providerSave, { providerSave = it }) {
            settings.saveProvider(draft.config(), draft.key.takeIf { it.isNotBlank() })
            settings.setDefaultProvider(draft.id)
            // A new default model deserves a fresh check.
            modelTest = Job()
            "Saved as your default model"
        }
    }

    fun useExisting(id: String) = viewModelScope.launch {
        settings.setDefaultProvider(id)
        modelTest = Job()
    }

    // ---- Identity -------------------------------------------------------------------------------

    /** The questionnaire's answers. */
    var answers by mutableStateOf(PersonaAnswers())
        private set
    /** False until the existing files and answers are read, so nothing typed early is overwritten. */
    var personaLoaded by mutableStateOf(false)
        private set
    /**
     * Text edited directly, written by the model, or found on disk that the answers wouldn't
     * produce. Null means the files follow the answers.
     */
    var personaOverride by mutableStateOf<PersonaFiles?>(null)
        private set
    var identitySave by mutableStateOf(Job())
        private set
    var personaPolish by mutableStateOf(Job())
        private set

    /** What will be written on save. */
    val personaPreview: PersonaFiles get() = personaOverride ?: PersonaTemplates.generate(answers)

    private val answersFile get() = java.io.File(home.dir, ANSWERS_FILE)

    init {
        viewModelScope.launch {
            val (existing, saved) = withContext(Dispatchers.IO) {
                val files = PersonaFiles(
                    home.readPersona(PersonaFile.IDENTITY),
                    home.readPersona(PersonaFile.SOUL),
                    home.readPersona(PersonaFile.USER),
                    home.readPersona(PersonaFile.AGENTS),
                )
                files to answersFile.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }?.let(PersonaTemplates::decode)
            }
            val loaded = saved?.first ?: PersonaTemplates.fromFiles(existing, deviceDefaults())
            answers = loaded
            val hasFiles = PersonaFile.entries.any { existing[it].isNotBlank() }
            if (hasFiles && existing.fingerprint() != PersonaTemplates.generate(loaded).fingerprint()) personaOverride = existing
            personaLoaded = true
        }
    }

    fun editAnswers(change: (PersonaAnswers) -> PersonaAnswers) {
        answers = change(answers)
        identitySave = Job()
    }

    /** Sets the text from the advanced editor; null goes back to generating it from the answers. */
    fun setPersonaText(files: PersonaFiles?) {
        personaOverride = files?.takeIf { it.fingerprint() != PersonaTemplates.generate(answers).fingerprint() }
        identitySave = Job()
        personaPolish = Job()
    }

    /** True when there's a cloud model to polish the text with (on-device is too slow for this). */
    fun canPolish(state: OnboardingUiState) = state.defaultProvider?.let { it.format != ApiFormat.ON_DEVICE } == true

    /** Turns the answers into polished files with the user's model; the templates stay on failure. */
    fun polishPersona() {
        val a = answers
        val drafts = PersonaTemplates.generate(a)
        runJob(personaPolish, { personaPolish = it }) {
            val s = settings.current()
            val provider = s.provider(s.defaultProviderId) ?: s.providers.firstOrNull() ?: error("Set up a model first.")
            val reply = helper.complete(provider, PersonaTemplates.polishPrompt(a, drafts), maxOutputTokens = 1_500, timeoutMs = 90_000)
                ?: error("Your model didn't answer, so the template text is kept. Check the model in Settings, or just use the templates.")
            val files = PersonaTemplates.parsePolished(reply, a)
                ?: error("Your model's reply wasn't in the expected shape, so the template text is kept.")
            personaOverride = files
            "Rewritten by your model. Review it below."
        }
    }

    /** Writes IDENTITY.md, SOUL.md, USER.md and AGENTS.md, plus the answers; new chats pick them up. */
    fun saveIdentity(then: () -> Unit = {}) {
        val a = answers
        val files = personaPreview
        runJob(identitySave, { identitySave = it }, onSuccess = { then() }) {
            withContext(Dispatchers.IO) {
                PersonaFile.entries.forEach { home.writePersona(it, files[it]) }
                answersFile.writeText(PersonaTemplates.encode(a, files))
            }
            "Saved. New chats use it."
        }
    }

    private fun deviceDefaults(): PersonaAnswers {
        val locale = java.util.Locale.getDefault()
        return PersonaAnswers(
            timeZone = java.util.TimeZone.getDefault().id,
            location = locale.getDisplayCountry(java.util.Locale.ENGLISH),
            language = locale.getDisplayLanguage(java.util.Locale.ENGLISH),
        )
    }

    // ---- Linux ----------------------------------------------------------------------------------
    // Installing is LinuxSetup's (core/data), shared with the Workspace's Linux card; it runs as a
    // foreground job, so it carries on after setup closes. Only the ticked packages live here.

    /** The packages ticked to install. */
    var packages by mutableStateOf(setOf(LinuxPackage.Python, LinuxPackage.Git))
        private set

    /** Install progress: the current phase and each package's state. */
    val linuxSetup: StateFlow<LinuxSetupState> = setup.state

    fun togglePackage(p: LinuxPackage) {
        if (setup.state.value.packages[p] == PackageState.Installed) return
        packages = if (p in packages) packages - p else packages + p
    }

    /** Checks which offered packages are already installed (`apk info -e`), when Linux is ready. */
    fun refreshPackages() = setup.refreshPackages()

    /** Installs Alpine if needed, then each ticked package that isn't installed yet. */
    fun setUpLinux(withPackages: Boolean = true) {
        val installed = setup.state.value.installedPackages
        setup.install(if (withPackages) packages - installed else emptySet())
    }

    /** Cancels an install in progress; what it downloaded is deleted. */
    fun cancelLinux() = setup.cancel()

    // ---- Jobs -----------------------------------------------------------------------------------

    /**
     * Runs one action on the main thread's scope: [set] gets Running, then the result or the error.
     * Ignored while [current] is still running, so double taps don't start it twice.
     */
    private fun runJob(
        current: Job,
        set: (Job) -> Unit,
        onSuccess: () -> Unit = {},
        block: suspend () -> String?,
    ): kotlinx.coroutines.Job? {
        if (current.running) return null
        set(Job.Running)
        return viewModelScope.launch {
            try {
                set(Job(result = block()))
                onSuccess()
            } catch (e: TimeoutCancellationException) {
                set(Job(error = "Timed out. Try again, or check the connection."))
            } catch (e: CancellationException) {
                set(Job())
                throw e
            } catch (e: Throwable) {
                set(Job(error = e.message ?: e.toString()))
            }
        }
    }

    // ---- SSH ------------------------------------------------------------------------------------

    var sshDraft by mutableStateOf(SshDraft())
        private set
    var sshProbe by mutableStateOf(Job())
        private set
    var sshSave by mutableStateOf(Job())
        private set

    fun editSsh(change: (SshDraft) -> SshDraft) {
        val before = sshDraft
        sshDraft = change(before).let { d ->
            // A different address means a different host key: ask again.
            if (d.host != before.host || d.port != before.port) d.copy(fingerprint = null) else d
        }
        if (sshDraft.fingerprint == null) sshProbe = Job()
        sshSave = Job()
    }

    fun probeSsh() {
        val d = sshDraft
        val port = d.port.toIntOrNull() ?: 22
        runJob(sshProbe, { sshProbe = it }) {
            val fp = ssh.probeFingerprint(d.host.trim(), port)
            // Only if the address wasn't changed while connecting.
            if (sshDraft.host == d.host && sshDraft.port == d.port) sshDraft = sshDraft.copy(fingerprint = fp)
            fp
        }
    }

    /** Saves the machine with the fingerprint the user just confirmed. */
    fun saveSsh() {
        val d = sshDraft
        val fp = d.fingerprint ?: return
        val host = SshHost(
            id = UUID.randomUUID().toString(),
            alias = d.alias.trim().ifBlank { d.host.trim() },
            host = d.host.trim(),
            port = d.port.toIntOrNull() ?: 22,
            user = d.user.trim(),
            auth = d.method.id,
            fingerprint = fp,
        )
        if (!d.canSave) return
        val save = sshEditorSave(host, d.privateKey, d.password, d.passphrase)
        runJob(sshSave, { sshSave = it }) {
            settings.saveSshHost(save.host, save.password, save.privateKey, save.passphrase)
            sshDraft = SshDraft()
            sshProbe = Job()
            "Added ${host.alias}"
        }
    }

    // ---- Connectors -----------------------------------------------------------------------------

    var mcpAdd by mutableStateOf(Job())
        private set
    private var mcpRun: kotlinx.coroutines.Job? = null

    fun setIntegrationKey(integration: Integration, key: String?) = viewModelScope.launch { settings.setIntegrationKey(integration.secret, key) }

    /** Without a token, a server that needs sign-in opens the browser to sign in. */
    fun addMcp(name: String, url: String, token: String) {
        runJob(mcpAdd, { mcpAdd = it }) {
            library.addMcpServer(name, url, token.takeIf { it.isNotBlank() })
            "Added ${name.trim()}"
        }?.let { mcpRun = it }
    }

    /** Stops waiting, e.g. for a browser sign-in the user abandoned. */
    fun cancelMcp() {
        mcpRun?.cancel()
        mcpRun = null
    }

    // ---- Check ----------------------------------------------------------------------------------

    var modelTest by mutableStateOf(Job())
        private set
    var linuxTest by mutableStateOf(Job())
        private set

    /** One tiny request to the default model, without tools, to prove the key and model work. */
    fun testModel() {
        val started = System.currentTimeMillis()
        runJob(modelTest, { modelTest = it }) {
            run {
                val s = settings.current()
                val provider = s.provider(s.defaultProviderId) ?: error("No model is set up yet. Go back to the Model step.")
                val model = if (provider.format == ApiFormat.ON_DEVICE) {
                    check(models.isReady(provider.model)) { "The on-device model hasn't finished downloading yet." }
                    engines.chatModel(provider.model)
                } else {
                    access.missing(provider)?.let(::error)
                    access.chatModel(provider.copy(maxOutputTokens = 1_000, effort = "low"))
                }
                val message = Message(UUID.randomUUID().toString(), "onboarding", Role.USER, listOf(Part.Text(TEST_PROMPT)), started)
                // On-device models load into memory on first use, which takes a while.
                withTimeout(if (provider.format == ApiFormat.ON_DEVICE) 180_000 else 60_000) {
                    var text = ""
                    model.stream(ChatRequest(system = "", messages = listOf(message), tools = emptyList())).collect { e ->
                        if (e is ChatEvent.Completed) text = e.turn.text
                    }
                    "${provider.model}: “${text.trim().take(200).ifBlank { "(empty reply)" }}”"
                }
            } + " · ${(System.currentTimeMillis() - started) / 100 / 10.0} s"
        }
    }

    fun testLinux() {
        runJob(linuxTest, { linuxTest = it }) {
            val r = linuxEnv.run("uname -a && cat /etc/alpine-release", timeoutMs = 30_000)
            if (r.exitCode != 0) error(r.output.trim().ifBlank { if (r.timedOut) "Timed out" else "Exited with ${r.exitCode}" })
            r.output.trim()
        }
    }

    // ---- Finish ---------------------------------------------------------------------------------

    /**
     * Marks onboarding done and, when a model is set up, starts a first chat (after the persona was
     * saved, so its frozen system prompt already has the agent's name). Calls back with its id.
     */
    var finishing by mutableStateOf(false)
        private set

    fun finish(startChat: Boolean, onDone: (String?) -> Unit) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            try {
                settings.setOnboardingDone(true)
                val s = settings.current()
                val chatId = if (startChat && s.providers.isNotEmpty()) runCatching { conversations.create(s.defaultProviderId).id }.getOrNull() else null
                onDone(chatId)
            } finally {
                finishing = false
            }
        }
    }

    private companion object {
        const val STEP = "step"
        const val CONSENT = "consent"
        const val ANSWERS_FILE = "persona-answers.properties"
        const val TEST_PROMPT = "This is a connection test from the Locus app. Reply with one short friendly sentence saying you're ready."
    }
}

/**
 * Why [value] can't work as [integration]'s secret, or null when it can (or is empty, or the integration
 * takes a plain key). Saving one that doesn't parse would show Connected while nothing ever works.
 */
internal fun keyProblem(integration: Integration, value: String): String? {
    if (value.isBlank()) return null
    return when (integration.id) {
        Integrations.ntfy.id -> if (Ntfy.parse(value) == null) "That isn't a topic URL like https://ntfy.sh/your-topic (optionally followed by a tk_ token)." else null
        Integrations.email.id -> runCatching { EmailConfig.parse(value) }.exceptionOrNull()?.let { it.message ?: "That isn't a mail server setting Locus can read." }
        else -> null
    }
}
