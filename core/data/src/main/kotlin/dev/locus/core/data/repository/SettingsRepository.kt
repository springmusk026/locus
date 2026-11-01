package dev.locus.core.data.repository

import androidx.datastore.core.DataStore
import dev.locus.core.data.auth.ChatGptSignIn
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.network.NetworkSecretEdits
import dev.locus.core.datastore.SecretStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.HelperPrompts
import dev.locus.core.model.ModelRef
import dev.locus.core.model.ModelRefs
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.SshHost
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<AppSettings>,
    private val secrets: SecretStore,
    private val access: ProviderAccess,
    private val chatGpt: ChatGptSignIn,
) {
    val settings: Flow<AppSettings> = store.data

    /** Provider IDs that have their credential saved: an API key, or a ChatGPT sign-in. */
    val providersWithKeys: Flow<Set<String>> = kotlinx.coroutines.flow.flow {
        secrets.names.collect { names ->
            emit(
                names.filter { it.startsWith("provider:") }.map { it.removePrefix("provider:") }.toSet() +
                    names.filter { it.startsWith(SecretStore.CHATGPT_SESSION_PREFIX) }.map { it.removePrefix(SecretStore.CHATGPT_SESSION_PREFIX) },
            )
        }
    }

    /** Which extra-header values and proxy credentials are in the vault (names only). */
    val networkSecrets: Flow<dev.locus.core.data.network.SavedNetworkSecrets> =
        kotlinx.coroutines.flow.flow { secrets.names.collect { emit(dev.locus.core.data.network.SavedNetworkSecrets.from(it)) } }

    suspend fun current(): AppSettings = store.data.first()

    /**
     * [network] carries the vault side of the provider's network settings: new header values and the
     * proxy's credentials. Headers no longer listed, and credentials for a proxy that no longer takes
     * them, are removed from the vault.
     */
    suspend fun saveProvider(provider: ProviderConfig, apiKey: String?, network: NetworkSecretEdits = NetworkSecretEdits()) {
        val previous = store.data.first().providers.firstOrNull { it.id == provider.id }
        val cleaned = provider.copy(network = NetworkSecretEdits.sanitize(provider.network))
        store.updateData { s ->
            val providers = if (s.providers.any { it.id == provider.id }) {
                s.providers.map { if (it.id == provider.id) cleaned else it }
            } else {
                s.providers + cleaned
            }
            s.copy(providers = providers, defaultProviderId = s.defaultProviderId ?: provider.id)
        }
        // Null leaves an existing key untouched; an empty string removes it.
        if (apiKey != null) secrets.put(SecretStore.providerKey(provider.id), apiKey.trim())
        network.apply(secrets, provider.id, previous?.network, cleaned.network)
    }

    suspend fun deleteProvider(id: String) {
        store.updateData { s ->
            val remaining = s.providers.filterNot { it.id == id }
            s.copy(providers = remaining, defaultProviderId = s.defaultProviderId.takeUnless { it == id } ?: remaining.firstOrNull()?.id)
        }
        secrets.put(SecretStore.providerKey(id), null)
        NetworkSecretEdits.forget(secrets, id)
        chatGpt.forget(id)
    }

    suspend fun setDefaultProvider(id: String) = store.updateData { it.copy(defaultProviderId = id) }

    suspend fun setToolPolicy(toolName: String, policy: ToolPolicy?) = store.updateData {
        it.copy(toolPolicies = if (policy == null) it.toolPolicies - toolName else it.toolPolicies + (toolName to policy))
    }

    /** Replaces every per-tool override at once, e.g. when the user picks a permission profile. */
    suspend fun setToolPolicies(overrides: Map<String, ToolPolicy>) = store.updateData { it.copy(toolPolicies = overrides) }

    suspend fun setOnboardingDone(done: Boolean) = store.updateData { it.copy(onboardingDone = done) }

    suspend fun setCustomInstructions(text: String) = store.updateData { it.copy(customInstructions = text) }

    suspend fun setReflectAfterTasks(enabled: Boolean) = store.updateData { it.copy(reflectAfterTasks = enabled) }

    suspend fun setHelperModel(providerId: String?, model: String) = store.updateData { it.copy(helperProviderId = providerId, helperModel = model.trim()) }

    suspend fun setReviewerMode(mode: dev.locus.core.model.ReviewerMode) = store.updateData { it.copy(reviewerMode = mode) }

    suspend fun setReviewerApprovesOutward(enabled: Boolean) = store.updateData { it.copy(reviewerApprovesOutward = enabled) }

    suspend fun setAutoTitle(enabled: Boolean) = store.updateData { it.copy(autoTitle = enabled) }

    suspend fun setSuggestFollowUps(enabled: Boolean) = store.updateData { it.copy(suggestFollowUps = enabled) }

    /** Whether a background helper's late report continues the chat (see [dev.locus.core.model.AppSettings.subagentReportsContinue]). */
    suspend fun setSubagentReportsContinue(enabled: Boolean) = store.updateData { it.copy(subagentReportsContinue = enabled) }

    /** Stars or unstars a model in the model sheet. */
    suspend fun toggleFavoriteModel(ref: ModelRef) = store.updateData { it.copy(favoriteModels = ModelRefs.toggle(it.favoriteModels, ref.normalized())) }

    /** Remembers a model picked in the model sheet as recently used. */
    suspend fun recordRecentModel(ref: ModelRef) = store.updateData { it.copy(recentModels = ModelRefs.pushRecent(it.recentModels, ref.normalized())) }

    /** The default task budget (see [AppSettings.taskBudget]). */
    suspend fun setTaskBudget(budget: dev.locus.core.model.TaskBudget) = store.updateData { it.copy(taskBudget = budget) }

    /** Whether the models.dev catalogue fills in model details (see [AppSettings.modelCatalog]). */
    suspend fun setModelCatalog(enabled: Boolean) = store.updateData { it.copy(modelCatalog = enabled) }

    /**
     * Saves the user's text for a helper prompt; null, blank or the default's text stores null (the
     * default). Text that fails [HelperPrompts.check] is refused and nothing changes.
     */
    suspend fun setHelperPrompt(prompt: HelperPrompt, text: String?): Boolean {
        val stored = HelperPrompts.normalize(prompt, text)
        if (stored != null && !HelperPrompts.check(prompt, stored).ok) return false
        store.updateData { HelperPrompts.withOverride(it, prompt, stored) }
        return true
    }

    private fun ModelRef.normalized() = copy(model = model.trim())

    /** Models the provider offers; [apiKey] null uses the saved key (or the ChatGPT sign-in). */
    suspend fun listModels(config: ProviderConfig, apiKey: String?): List<dev.locus.core.llm.RemoteModel> = access.listModels(config, apiKey)

    /**
     * Whether new chats reach integration [id] through its MCP server when one works ([useMcp]), or keep
     * its built-in tools. Chats that already exist keep the tools they started with.
     */
    suspend fun setUseMcpFor(id: String, useMcp: Boolean) = store.updateData {
        it.copy(builtInOverMcp = if (useMcp) it.builtInOverMcp - id else it.builtInOverMcp + id)
    }

    /** Built-in integrations store their keys under the same names connectors use. */
    suspend fun setIntegrationKey(secret: String, value: String?) = secrets.put(SecretStore.connectorSecret(secret), value?.trim())

    val integrationKeys: Flow<Set<String>> = kotlinx.coroutines.flow.flow {
        secrets.names.collect { names -> emit(names.filter { it.startsWith("connector:") }.map { it.removePrefix("connector:") }.toSet()) }
    }

    /** Null credential arguments keep what is stored; empty strings remove it. */
    suspend fun saveSshHost(host: SshHost, password: String?, privateKey: String?, passphrase: String?) {
        store.updateData { s -> s.copy(sshHosts = s.sshHosts.filterNot { it.id == host.id } + host) }
        password?.let { secrets.put(SecretStore.sshPassword(host.id), it) }
        privateKey?.let { secrets.put(SecretStore.sshPrivateKey(host.id), it) }
        passphrase?.let { secrets.put(SecretStore.sshPassphrase(host.id), it) }
    }

    suspend fun deleteSshHost(id: String) {
        store.updateData { s -> s.copy(sshHosts = s.sshHosts.filterNot { it.id == id }) }
        listOf(SecretStore.sshPassword(id), SecretStore.sshPrivateKey(id), SecretStore.sshPassphrase(id)).forEach { secrets.put(it, null) }
    }

    suspend fun sshCredentials(id: String) = Triple(
        secrets.get(SecretStore.sshPassword(id)),
        secrets.get(SecretStore.sshPrivateKey(id)),
        secrets.get(SecretStore.sshPassphrase(id)),
    )
}
