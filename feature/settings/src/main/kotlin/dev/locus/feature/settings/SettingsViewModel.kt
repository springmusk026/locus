package dev.locus.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.ssh.SshService
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.ondevice.ModelState
import dev.locus.core.ondevice.OnDeviceModel
import dev.locus.core.ondevice.OnDeviceModels
import dev.locus.core.model.SshHost
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderRow(val config: ProviderConfig, val hasKey: Boolean, val isDefault: Boolean)

data class ToolRow(val name: String, val description: String, val risk: ToolRisk, val policy: ToolPolicy, val isDefault: Boolean, val defaultPolicy: ToolPolicy)

data class SettingsUiState(
    val providers: List<ProviderRow> = emptyList(),
    val tools: List<ToolRow> = emptyList(),
    val customInstructions: String = "",
    val reflectAfterTasks: Boolean = true,
    val subagentReportsContinue: Boolean = true,
    val helperProviderId: String? = null,
    val helperModel: String = "",
    val autoTitle: Boolean = true,
    val suggestFollowUps: Boolean = true,
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
) : ViewModel() {

    fun setIntegrationKey(integration: Integration, key: String?) = viewModelScope.launch { settings.setIntegrationKey(integration.secret, key) }

    fun listModels(config: ProviderConfig, key: String?, onResult: (Result<List<dev.locus.core.llm.RemoteModel>>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { settings.listModels(config, key?.takeIf { it.isNotBlank() }) })
    }

    fun downloadModel(id: String) = models.download(id)
    fun cancelModel(id: String) = models.cancel(id)
    fun deleteModel(id: String) = models.delete(id)

    val uiState: StateFlow<SettingsUiState> = combine(settings.settings, settings.providersWithKeys, models.states, settings.integrationKeys) { s, withKeys, modelStates, integrationKeys ->
        SettingsUiState(
            providers = s.providers.map { ProviderRow(it, it.id in withKeys, it.id == s.defaultProviderId) },
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
            suggestFollowUps = s.suggestFollowUps,
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
    fun setReviewerMode(mode: ReviewerMode) = viewModelScope.launch { settings.setReviewerMode(mode) }
    fun setReviewerApprovesOutward(enabled: Boolean) = viewModelScope.launch { settings.setReviewerApprovesOutward(enabled) }
    fun setSuggestFollowUps(enabled: Boolean) = viewModelScope.launch { settings.setSuggestFollowUps(enabled) }

    /** Reads the server's host key fingerprint for the user to confirm. */
    fun probeFingerprint(host: String, port: Int, onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { ssh.probeFingerprint(host, port) })
    }

    fun saveSshHost(host: SshHost, password: String?, privateKey: String?, passphrase: String?) = viewModelScope.launch {
        settings.saveSshHost(host, password, privateKey, passphrase)
    }

    fun deleteSshHost(id: String) = viewModelScope.launch { settings.deleteSshHost(id) }
}
