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
import dev.locus.core.data.agent.HelperModel
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.LibraryRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.ssh.SshService
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModels
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderPreset
import dev.locus.core.model.ProviderPresets
import dev.locus.core.model.Role
import dev.locus.core.model.SshHost
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceEngines
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ondevice.OnDeviceModels
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
) {
    val onDevice get() = preset.format == ApiFormat.ON_DEVICE
    val canSave get() = model.isNotBlank() && (onDevice || baseUrl.isNotBlank()) && (!preset.requiresKey || key.isNotBlank())
    val canListModels get() = !onDevice && baseUrl.isNotBlank() && (!preset.requiresKey || key.isNotBlank())

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

data class SshDraft(
    val alias: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val usePassword: Boolean = false,
    val secret: String = "",
    val fingerprint: String? = null,
)

/** Linux packages offered on the workspace step (Alpine v3.24 package names, checked on pkgs.alpinelinux.org). */
enum class LinuxPackage(val label: String, val note: String, val apk: List<String>) {
    Python("Python", "python3 and pip", listOf("python3", "py3-pip")),
    Git("Git", "version control", listOf("git")),
    Node("Node.js", "node and npm", listOf("nodejs", "npm")),
    Curl("curl", "HTTP from the shell", listOf("curl")),
}

/** How one offered package stands: unknown, already there, waiting, being installed, or failed. */
sealed interface PackageState {
    data object Unknown : PackageState
    data object Installed : PackageState
    data object Queued : PackageState
    data object Installing : PackageState
    data class Failed(val detail: String) : PackageState
}

/** The workspace step's install run: what's happening now, each package's state, and the outcome. */
data class LinuxSetup(
    val running: Boolean = false,
    val phase: String? = null,
    val packages: Map<LinuxPackage, PackageState> = emptyMap(),
    val message: String? = null,
    val error: String? = null,
)

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
    private val ssh: SshService,
    private val library: LibraryRepository,
    private val conversations: ConversationRepository,
    private val home: AgentHome,
    private val http: HttpClient,
    private val helper: HelperModel,
) : ViewModel() {

    val uiState: StateFlow<OnboardingUiState> = combine(
        settings.settings,
        models.states,
        linuxEnv.status,
        settings.integrationKeys,
    ) { s, modelStates, linux, keys ->
        OnboardingUiState(
            providers = s.providers,
            defaultProviderId = s.defaultProviderId,
            onDeviceModels = models.catalog.map { it to (modelStates[it.id] ?: ModelState.NotDownloaded) },
            linux = linux,
            sshHosts = s.sshHosts,
            integrations = Integrations.all.map { it to (it.secret in keys) },
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

    fun pickPreset(preset: ProviderPreset?) {
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

    fun downloadModel(id: String) = models.download(id)
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

    /** The packages ticked to install. */
    var packages by mutableStateOf(setOf(LinuxPackage.Python, LinuxPackage.Git))
        private set

    private val _linuxSetup = MutableStateFlow(LinuxSetup())
    /** Install progress: the current phase and each package's state. */
    val linuxSetup: StateFlow<LinuxSetup> = _linuxSetup.asStateFlow()

    fun togglePackage(p: LinuxPackage) {
        if (_linuxSetup.value.packages[p] == PackageState.Installed) return
        packages = if (p in packages) packages - p else packages + p
    }

    /** Checks which offered packages are already installed (`apk info -e`), when Linux is ready. */
    fun refreshPackages() {
        if (_linuxSetup.value.running || linuxEnv.status.value != LinuxStatus.Ready) return
        viewModelScope.launch {
            val installed = runCatching { installedPackages() }.getOrNull() ?: return@launch
            if (_linuxSetup.value.running) return@launch
            _linuxSetup.update { s -> s.copy(packages = LinuxPackage.entries.associateWith { p -> if (p in installed) PackageState.Installed else s.packages[p] ?: PackageState.Unknown }) }
            packages = packages - installed
        }
    }

    /**
     * Installs Alpine if needed, then each ticked package: apk update once, then apk add per
     * package, so every package gets its own progress and result.
     */
    fun setUpLinux(withPackages: Boolean = true) {
        if (_linuxSetup.value.running) return
        val chosen = if (withPackages) LinuxPackage.entries.filter { it in packages } else emptyList()
        _linuxSetup.update { s -> s.copy(running = true, phase = null, error = null, message = null, packages = s.packages + chosen.associateWith { PackageState.Queued }) }
        viewModelScope.launch {
            try {
                if (linuxEnv.status.value != LinuxStatus.Ready) {
                    phase("Installing Alpine Linux…")
                    linuxEnv.ensureInstalled()
                }
                if (chosen.isEmpty()) {
                    _linuxSetup.update { it.copy(message = "Linux is ready.") }
                    refreshAfter()
                    return@launch
                }
                phase("Checking what's already installed…")
                val already = installedPackages()
                setPackages(chosen.filter { it in already }.associateWith { PackageState.Installed })
                val todo = chosen.filterNot { it in already }
                if (todo.isNotEmpty()) {
                    phase("Updating the package list (apk update)…")
                    val update = linuxEnv.run("apk update", timeoutMs = 5 * 60_000L, maxChars = 4_000)
                    if (update.exitCode != 0) {
                        val why = if (update.timedOut) "apk update timed out." else "Couldn't reach Alpine's package servers."
                        setPackages(todo.associateWith { PackageState.Failed(why) })
                        _linuxSetup.update { it.copy(error = "$why Check the internet connection and try again.\n${tail(update.output)}".trim()) }
                        return@launch
                    }
                    todo.forEachIndexed { i, p ->
                        phase("Installing ${p.label} (${i + 1} of ${todo.size})…")
                        setPackages(mapOf(p to PackageState.Installing))
                        val r = runCatching { linuxEnv.run("apk add --no-progress ${p.apk.joinToString(" ")}", timeoutMs = 10 * 60_000L, maxChars = 6_000) }
                        val state = r.fold(
                            onSuccess = { res ->
                                when {
                                    res.exitCode == 0 -> PackageState.Installed
                                    res.timedOut -> PackageState.Failed("Timed out. The agent can finish this later with apk add.\n${tail(res.output)}".trim())
                                    else -> PackageState.Failed("apk exited with ${res.exitCode}\n${tail(res.output)}".trim())
                                }
                            },
                            onFailure = { e -> if (e is CancellationException) throw e else PackageState.Failed(e.message ?: e.toString()) },
                        )
                        setPackages(mapOf(p to state))
                    }
                }
                val failed = chosen.count { _linuxSetup.value.packages[it] is PackageState.Failed }
                packages = packages.filter { _linuxSetup.value.packages[it] != PackageState.Installed }.toSet()
                _linuxSetup.update {
                    if (failed == 0) it.copy(message = "Installed ${chosen.joinToString { p -> p.label }}.")
                    else it.copy(error = "$failed of ${chosen.size} couldn't be installed. See below; you can retry.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _linuxSetup.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _linuxSetup.update { s ->
                    s.copy(
                        running = false,
                        phase = null,
                        packages = s.packages.mapValues { (_, v) -> if (v == PackageState.Queued || v == PackageState.Installing) PackageState.Unknown else v },
                    )
                }
            }
        }
    }

    private fun phase(text: String) = _linuxSetup.update { it.copy(phase = text) }

    private fun setPackages(states: Map<LinuxPackage, PackageState>) = _linuxSetup.update { it.copy(packages = it.packages + states) }

    private fun refreshAfter() = viewModelScope.launch {
        val installed = runCatching { installedPackages() }.getOrNull() ?: return@launch
        setPackages(installed.associateWith { PackageState.Installed })
        packages = packages - installed
    }

    /** The offered packages whose apk names are all installed. */
    private suspend fun installedPackages(): Set<LinuxPackage> {
        val names = LinuxPackage.entries.flatMap { it.apk }.distinct()
        val r = linuxEnv.run(names.joinToString("; ") { "apk info -e $it >/dev/null 2>&1 && echo ok:$it" } + "; true", timeoutMs = 60_000)
        val ok = r.output.lines().map { it.trim() }.filter { it.startsWith("ok:") }.map { it.removePrefix("ok:") }.toSet()
        return LinuxPackage.entries.filter { p -> p.apk.all { it in ok } }.toSet()
    }

    private fun tail(output: String) = output.trim().lines().takeLast(6).joinToString("\n")

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
            auth = if (d.usePassword) "password" else "key",
            fingerprint = fp,
        )
        runJob(sshSave, { sshSave = it }) {
            settings.saveSshHost(host, password = d.secret.takeIf { d.usePassword }, privateKey = d.secret.takeIf { !d.usePassword }, passphrase = null)
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
                    ChatModels.create(http, provider.copy(maxOutputTokens = 1_000, effort = "low"), settings.apiKey(provider.id))
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
