package dev.locus.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.auth.ChatGptSignIn
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.ssh.SshService
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.agent.integrations.Telegram
import dev.locus.core.data.integrations.TelegramSetup
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ondevice.OnDeviceModels
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.HelperPrompts
import dev.locus.core.model.SshHost
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderRow(val config: ProviderConfig, val hasKey: Boolean, val isDefault: Boolean)

data class ToolRow(val name: String, val description: String, val risk: ToolRisk, val policy: ToolPolicy, val isDefault: Boolean, val defaultPolicy: ToolPolicy)

data class SettingsUiState(
    val providers: List<ProviderRow> = emptyList(),
    /** Signed-in ChatGPT providers by id, with the account's email when known. */
    val chatGptAccounts: Map<String, String?> = emptyMap(),
    val tools: List<ToolRow> = emptyList(),
    val customInstructions: String = "",
    val reflectAfterTasks: Boolean = true,
    val subagentReportsContinue: Boolean = true,
    val helperProviderId: String? = null,
    val helperModel: String = "",
    val autoTitle: Boolean = true,
    /** Model details from the models.dev catalogue in the model sheet. */
    val modelCatalog: Boolean = false,
    val suggestFollowUps: Boolean = true,
    /** The user's saved helper prompts; a prompt that isn't here uses its default. */
    val helperPrompts: Map<HelperPrompt, String> = emptyMap(),
    val reviewerMode: ReviewerMode = ReviewerMode.OFF,
    val reviewerApprovesOutward: Boolean = false,
    val sshHosts: List<SshHost> = emptyList(),
    val onDeviceModels: List<Pair<OnDeviceModel, ModelState>> = emptyList(),
    val integrations: List<Pair<Integration, Boolean>> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val catalog: ToolCatalog,
    private val ssh: SshService,
    private val models: OnDeviceModels,
    private val chatGpt: ChatGptSignIn,
    private val telegram: TelegramSetup,
) : ViewModel() {

    // Telegram setup. A blank token means the one already saved.
    fun telegramChatId(onResult: (Long?) -> Unit) = viewModelScope.launch { onResult(runCatching { telegram.savedChatId() }.getOrNull()) }

    fun findTelegramChats(token: String, onResult: (Result<Telegram.Discovery>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { telegram.findChats(token) })
    }

    fun testTelegram(token: String, chatId: Long, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { telegram.sendTest(token, chatId) })
    }

    fun saveTelegram(token: String, chatId: Long, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { telegram.save(token, chatId) })
    }

    fun removeTelegram() = viewModelScope.launch { telegram.remove() }

    fun signInChatGpt(providerId: String, onResult: (Result<Boolean>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { chatGpt.signIn(providerId) })
    }

    fun signOutChatGpt(providerId: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(runCatching { chatGpt.signOut(providerId) }.getOrDefault(false))
    }

    /** For a ChatGPT provider that was signed in but never saved. */
    fun discardChatGpt(providerId: String) = viewModelScope.launch {
        if (settings.current().providers.none { it.id == providerId }) runCatching { chatGpt.forget(providerId) }
    }

    fun setIntegrationKey(integration: Integration, key: String?) = viewModelScope.launch { settings.setIntegrationKey(integration.secret, key) }

    fun listModels(config: ProviderConfig, key: String?, onResult: (Result<List<dev.locus.core.llm.RemoteModel>>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { settings.listModels(config, key?.takeIf { it.isNotBlank() }) })
    }

    fun downloadModel(id: String) = models.download(id)
    fun cancelModel(id: String) = models.cancel(id)
    fun deleteModel(id: String) = models.delete(id)

    private val chatGptAccounts = chatGpt.signedIn.map { ids -> ids.associateWith { chatGpt.email(it) } }

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.settings, settings.providersWithKeys, models.states, settings.integrationKeys, chatGptAccounts,
    ) { s, withKeys, modelStates, integrationKeys, accounts ->
        SettingsUiState(
            providers = s.providers.map { ProviderRow(it, it.id in withKeys, it.id == s.defaultProviderId) },
            chatGptAccounts = accounts,
            tools = catalog.registry.all.map { tool ->
                val override = s.toolPolicies[tool.spec.name]
                ToolRow(tool.spec.name, tool.spec.description, tool.risk, override ?: tool.defaultPolicy, override == null, tool.defaultPolicy)
            },
            customInstructions = s.customInstructions,
            reflectAfterTasks = s.reflectAfterTasks,
            subagentReportsContinue = s.subagentReportsContinue,
            helperProviderId = s.helperProviderId,
            helperModel = s.helperModel,
            autoTitle = s.autoTitle,
            modelCatalog = s.modelCatalog,
            suggestFollowUps = s.suggestFollowUps,
            // Only overrides that are in use: one that fails the checks is sent as the default, so it's shown as one.
            helperPrompts = HelperPrompt.entries.filter { HelperPrompts.isCustom(s, it) }.associateWith { HelperPrompts.template(s, it) },
            reviewerMode = s.reviewerMode,
            reviewerApprovesOutward = s.reviewerApprovesOutward,
            sshHosts = s.sshHosts,
            integrations = Integrations.all.map { it to (it.secret in integrationKeys) },
            onDeviceModels = models.catalog.map { it to (modelStates[it.id] ?: ModelState.NotDownloaded) },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** [apiKey] null keeps the stored key; empty removes it. */
    fun saveProvider(config: ProviderConfig, apiKey: String?) = viewModelScope.launch { settings.saveProvider(config, apiKey) }

    fun deleteProvider(id: String) = viewModelScope.launch { settings.deleteProvider(id) }

    fun setDefault(id: String) = viewModelScope.launch { settings.setDefaultProvider(id) }

    fun setPolicy(tool: ToolRow, policy: ToolPolicy) = viewModelScope.launch {
        settings.setToolPolicy(tool.name, policy.takeUnless { it == tool.defaultPolicy })
    }

    fun saveInstructions(text: String) = viewModelScope.launch { settings.setCustomInstructions(text) }

    fun setReflectAfterTasks(enabled: Boolean) = viewModelScope.launch { settings.setReflectAfterTasks(enabled) }
    fun setSubagentReportsContinue(enabled: Boolean) = viewModelScope.launch { settings.setSubagentReportsContinue(enabled) }
    fun setHelperModel(providerId: String?, model: String) = viewModelScope.launch { settings.setHelperModel(providerId, model) }
    fun setAutoTitle(enabled: Boolean) = viewModelScope.launch { settings.setAutoTitle(enabled) }

    fun setModelCatalog(enabled: Boolean) = viewModelScope.launch { settings.setModelCatalog(enabled) }
    fun setReviewerMode(mode: ReviewerMode) = viewModelScope.launch { settings.setReviewerMode(mode) }
    fun setReviewerApprovesOutward(enabled: Boolean) = viewModelScope.launch { settings.setReviewerApprovesOutward(enabled) }
    fun setSuggestFollowUps(enabled: Boolean) = viewModelScope.launch { settings.setSuggestFollowUps(enabled) }

    /** Saves an edited helper prompt; null (or the default's text) goes back to the default. */
    fun setHelperPrompt(prompt: HelperPrompt, text: String?) = viewModelScope.launch { settings.setHelperPrompt(prompt, text) }

    /** Reads the server's host key fingerprint for the user to confirm. */
    fun probeFingerprint(host: String, port: Int, onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { ssh.probeFingerprint(host, port) })
    }

    fun saveSshHost(host: SshHost, password: String?, privateKey: String?, passphrase: String?) = viewModelScope.launch {
        settings.saveSshHost(host, password, privateKey, passphrase)
    }

    fun deleteSshHost(id: String) = viewModelScope.launch { settings.deleteSshHost(id) }
}
