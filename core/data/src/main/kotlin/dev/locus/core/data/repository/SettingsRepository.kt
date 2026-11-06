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
import dev.locus.core.model.RememberedApproval
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

    /** Provider IDs that have backup API keys saved (names only, never the keys). */
    val providersWithBackupKeys: Flow<Set<String>> = kotlinx.coroutines.flow.flow {
        secrets.names.collect { names ->
            emit(names.filter { it.startsWith(SecretStore.PROVIDER_BACKUP_KEYS_PREFIX) }.map { it.removePrefix(SecretStore.PROVIDER_BACKUP_KEYS_PREFIX) }.toSet())
        }
    }

    /** Which extra-header values and proxy credentials are in the vault (names only). */
    val networkSecrets: Flow<dev.locus.core.data.network.SavedNetworkSecrets> =
        kotlinx.coroutines.flow.flow { secrets.names.collect { emit(dev.locus.core.data.network.SavedNetworkSecrets.from(it)) } }

    suspend fun current(): AppSettings = store.data.first()

    /**
     * [network] carries the vault side of the provider's network settings: new header values and the
     * proxy's credentials. Headers no longer listed, and credentials for a proxy that no longer takes
     * them, are removed from the vault. [backupKeys] is what the user typed in the backup-keys field
     * (keys separated by commas or new lines): null leaves the saved ones, empty removes them.
     */
    suspend fun saveProvider(provider: ProviderConfig, apiKey: String?, network: NetworkSecretEdits = NetworkSecretEdits(), backupKeys: String? = null) {
        val previous = store.data.first().providers.firstOrNull { it.id == provider.id }
        // Saved from the editor, where the user sees the address: that confirms it.
        val cleaned = provider.copy(network = NetworkSecretEdits.sanitize(provider.network), addressUnconfirmed = false)
        store.updateData { s ->
            val providers = if (s.providers.any { it.id == provider.id }) {
                s.providers.map { if (it.id == provider.id) cleaned else it }
            } else {
                s.providers + cleaned
            }
            // Saved from the editor, which shows the effort: whatever it holds now is the user's (EffortDefaults).
            dev.locus.core.model.EffortDefaults.current(s.copy(providers = providers, defaultProviderId = s.defaultProviderId ?: provider.id))
        }
        // Null leaves an existing key untouched; an empty string removes it.
        if (apiKey != null) secrets.put(SecretStore.providerKey(provider.id), apiKey.trim())
        if (backupKeys != null) {
            secrets.put(SecretStore.providerBackupKeys(provider.id), dev.locus.core.llm.parseApiKeys(backupKeys).joinToString("\n"))
        }
        network.apply(secrets, provider.id, previous?.network, cleaned.network)
    }

    suspend fun deleteProvider(id: String) {
        store.updateData { s ->
            val remaining = s.providers.filterNot { it.id == id }
            s.copy(
                providers = remaining,
                defaultProviderId = s.defaultProviderId.takeUnless { it == id } ?: remaining.firstOrNull()?.id,
                fallbackModels = s.fallbackModels.filterNot { it.providerId == id },
            )
        }
        secrets.put(SecretStore.providerKey(id), null)
        secrets.put(SecretStore.providerBackupKeys(id), null)
        NetworkSecretEdits.forget(secrets, id)
        chatGpt.forget(id)
    }

    suspend fun setDefaultProvider(id: String) = store.updateData { it.copy(defaultProviderId = id) }

    suspend fun setToolPolicy(toolName: String, policy: ToolPolicy?) = store.updateData {
        it.copy(toolPolicies = if (policy == null) it.toolPolicies - toolName else it.toolPolicies + (toolName to policy))
    }

    /** Replaces every per-tool override at once, e.g. when the user picks a permission profile. */
    suspend fun setToolPolicies(overrides: Map<String, ToolPolicy>) = store.updateData { it.copy(toolPolicies = overrides) }

    /**
     * A project's permissions, sealed with the global ones (see [dev.locus.core.model.PolicySeal]);
     * null or an empty policy removes them.
     */
    suspend fun setProjectPolicy(projectId: String, policy: dev.locus.core.model.ProjectPolicy?) = store.updateData {
        it.copy(projectPolicies = if (policy == null || policy.isEmpty) it.projectPolicies - projectId else it.projectPolicies + (projectId to policy))
    }

    /**
     * Where a project's commands run: one of the user's SSH machines, or null for Linux on the phone
     * (docs/design/remote-execution.md). Sealed with the permissions.
     */
    suspend fun setProjectRemote(projectId: String, remote: dev.locus.core.model.ProjectRemote?) = store.updateData {
        it.copy(projectRemotes = if (remote == null) it.projectRemotes - projectId else it.projectRemotes + (projectId to remote))
    }

    /** Lets other apps ask Locus to start tasks (docs/design/android-bridge.md). */
    suspend fun setAutomationEnabled(enabled: Boolean) = store.updateData { it.copy(automationEnabled = enabled) }

    /** Remembers the user's answer for app [packageName]: true always allow, false never, null ask each time. */
    suspend fun setAutomationCaller(packageName: String, allowed: Boolean?) = store.updateData {
        it.copy(automationCallers = if (allowed == null) it.automationCallers - packageName else it.automationCallers + (packageName to allowed))
    }

    /** Changes the Telegram remote channel's settings (docs/design/telegram-remote.md); sealed with the permissions. */
    suspend fun updateTelegramRemote(transform: (dev.locus.core.model.TelegramRemoteSettings) -> dev.locus.core.model.TelegramRemoteSettings) =
        store.updateData { it.copy(telegramRemote = transform(it.telegramRemote)) }

    /**
     * Keeps [rule] (docs/design/remembered-approvals.md), replacing one with the same tool, scope, chat
     * and project. Expired rules are dropped on the way, and only the newest [RememberedApproval.MAX_RULES] kept.
     */
    suspend fun addRememberedApproval(rule: RememberedApproval, now: Long = System.currentTimeMillis()) = store.updateData { s ->
        val kept = s.rememberedApprovals.filterNot { r ->
            r.expired(now) || (r.tool == rule.tool && r.kind == rule.kind && r.value == rule.value && r.projectId == rule.projectId && r.conversationId == rule.conversationId)
        }
        s.copy(rememberedApprovals = (kept + rule).sortedBy { it.createdAt }.takeLast(RememberedApproval.MAX_RULES))
    }

    /** Revokes one remembered approval (Settings → Permissions → Remembered, or the chat's controls). */
    suspend fun revokeRememberedApproval(id: String) = store.updateData { s ->
        s.copy(rememberedApprovals = s.rememberedApprovals.filterNot { it.id == id })
    }

    /** Drops the rules that went with a deleted chat, or (with [projectId]) a deleted project. */
    suspend fun forgetRememberedApprovals(conversationId: String? = null, projectId: String? = null) = store.updateData { s ->
        s.copy(
            rememberedApprovals = s.rememberedApprovals.filterNot { r ->
                (conversationId != null && r.conversationId == conversationId) || (projectId != null && r.projectId == projectId)
            },
        )
    }

    suspend fun setOnboardingDone(done: Boolean) = store.updateData { it.copy(onboardingDone = done) }

    /** Quick start: these skipped steps come back on Home as setup cards (docs/design/onboarding-quick-start.md). */
    suspend fun deferSetup(items: Set<dev.locus.core.model.SetupItem>) = store.updateData { it.copy(setupLater = it.setupLater + items) }

    /** A deferred step that was set up or dismissed: its Home card goes for good. */
    suspend fun clearSetup(items: Set<dev.locus.core.model.SetupItem>) = store.updateData {
        if (it.setupLater.none { item -> item in items }) it else it.copy(setupLater = it.setupLater - items)
    }

    suspend fun setCustomInstructions(text: String) = store.updateData { it.copy(customInstructions = text) }

    suspend fun setReflectAfterTasks(enabled: Boolean) = store.updateData { it.copy(reflectAfterTasks = enabled) }

    suspend fun setHelperModel(providerId: String?, model: String) = store.updateData { it.copy(helperProviderId = providerId, helperModel = model.trim()) }

    suspend fun setReviewerMode(mode: dev.locus.core.model.ReviewerMode) = store.updateData { it.copy(reviewerMode = mode) }

    suspend fun setReviewerApprovesOutward(enabled: Boolean) = store.updateData { it.copy(reviewerApprovesOutward = enabled) }

    suspend fun setAutoTitle(enabled: Boolean) = store.updateData { it.copy(autoTitle = enabled) }

    suspend fun setSuggestFollowUps(enabled: Boolean) = store.updateData { it.copy(suggestFollowUps = enabled) }

    /** Whether a background helper's late report continues the chat (see [dev.locus.core.model.AppSettings.subagentReportsContinue]). */
    suspend fun setSubagentReportsContinue(enabled: Boolean) = store.updateData { it.copy(subagentReportsContinue = enabled) }

    /** How long one helper run may take, in minutes (see [dev.locus.core.model.AppSettings.helperTimeoutMinutes]). */
    suspend fun setHelperTimeoutMinutes(minutes: Int) =
        store.updateData { it.copy(helperTimeoutMinutes = dev.locus.core.model.HelperTimeLimit.normalized(minutes)) }

    /** Stars or unstars a model in the model sheet. */
    suspend fun toggleFavoriteModel(ref: ModelRef) = store.updateData { it.copy(favoriteModels = ModelRefs.toggle(it.favoriteModels, ref.normalized())) }

    /** Remembers a model picked in the model sheet as recently used. */
    suspend fun recordRecentModel(ref: ModelRef) = store.updateData { it.copy(recentModels = ModelRefs.pushRecent(it.recentModels, ref.normalized())) }

    /** The default task budget (see [AppSettings.taskBudget]). */
    suspend fun setTaskBudget(budget: dev.locus.core.model.TaskBudget) = store.updateData { it.copy(taskBudget = budget) }

    /** Whether the models.dev catalogue fills in model details (see [AppSettings.modelCatalog]). */
    /** The default fallback chain: models tried in order when a chat's own model can't answer. */
    suspend fun setFallbackModels(models: List<ModelRef>) = store.updateData { it.copy(fallbackModels = models.map { m -> m.normalized() }.distinct()) }

    suspend fun setModelCatalog(enabled: Boolean) = store.updateData { it.copy(modelCatalog = enabled) }

    /** The daily GitHub update check ([AppSettings.updateCheck]); scheduling follows in [dev.locus.core.data.updates.Updates]. */
    suspend fun setUpdateCheck(enabled: Boolean) = store.updateData { it.copy(updateCheck = enabled) }

    /** Settings → Battery & heat. */
    suspend fun setPower(transform: (dev.locus.core.model.PowerSettings) -> dev.locus.core.model.PowerSettings) =
        store.updateData { it.copy(power = transform(it.power)) }

    /** Settings → Voice (docs/design/voice-mode.md). */
    suspend fun setVoice(transform: (dev.locus.core.model.VoiceSettings) -> dev.locus.core.model.VoiceSettings) =
        store.updateData { it.copy(voice = transform(it.voice)) }

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
