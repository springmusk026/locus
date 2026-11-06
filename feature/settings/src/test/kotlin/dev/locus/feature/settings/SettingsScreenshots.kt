package dev.locus.feature.settings

import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.data.backup.BackupEstimate
import dev.locus.core.data.diagnostics.DiagnosticEntry
import dev.locus.core.data.diagnostics.DiagnosticKind
import dev.locus.core.data.diagnostics.SelfTestFix
import dev.locus.core.data.diagnostics.SelfTestLevel
import dev.locus.core.data.diagnostics.SelfTestResult
import dev.locus.core.data.diagnostics.StorageArea
import dev.locus.core.data.diagnostics.StorageItem
import dev.locus.core.data.diagnostics.StorageReport
import dev.locus.core.data.mcp.McpFirstStatus
import dev.locus.core.data.network.SavedNetworkSecrets
import dev.locus.core.data.updates.Release
import dev.locus.core.data.updates.ReleaseAsset
import dev.locus.core.data.updates.UpdateAvailability
import dev.locus.core.data.updates.UpdateInfo
import dev.locus.core.data.updates.UpdateProblem
import dev.locus.core.data.updates.UpdateState
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.DeviceState
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.ModelRef
import dev.locus.core.model.NetworkDefaults
import dev.locus.core.model.NetworkKind
import dev.locus.core.model.PowerSettings
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ProviderNetwork
import dev.locus.core.model.ProxyConfig
import dev.locus.core.model.ProxyMode
import dev.locus.core.model.ProxyType
import dev.locus.core.model.RememberedApproval
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.SettingsDestination
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.ThermalLevel
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.TrustedCertificate
import dev.locus.core.model.VoiceSettings
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.PrivateComposable
import dev.locus.core.screenshot.OnScreen
import dev.locus.core.screenshot.UtcRule
import dev.locus.core.testing.MainDispatcherRule
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** Every Settings page, sheet and state (docs/design/design-system.md, "Review gate"). */
class SettingsScreenshots : ScreenshotTest() {

    @get:Rule
    val main = MainDispatcherRule()

    /** Sheets that show a date or clock time take a fixed epoch, read in UTC. */
    @get:Rule
    val utc = UtcRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val graph by lazy { TestGraph(scope) }

    @After
    fun tearDown() = scope.cancel()

    /**
     * The real clock, for what the pages show relative to now ("3 hours ago", "28 days left"): counted
     * back from it, the words stay the same on every run. What they show as a date uses [FIXED] instead.
     */
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val day = 24 * hour

    // ---- Fixtures

    private val anthropic = ProviderConfig("anthropic", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-sonnet-4-5", contextWindow = 200_000)
    private val chatGpt = ProviderConfig("chatgpt", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-5-codex", requiresKey = false)
    private val openRouter = ProviderConfig(
        "openrouter", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3.1",
        network = ProviderNetwork(headerNames = listOf("HTTP-Referer", "X-Title"), proxyMode = ProxyMode.DEFAULT),
    )
    private val ollama = ProviderConfig("ollama", "Ollama on the desktop", ApiFormat.OPENAI_CHAT, "http://192.168.1.20:11434/v1", "qwen3:14b", requiresKey = false)
    private val gemini = ProviderConfig("gemini", "Google Gemini", ApiFormat.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-pro")

    private val certificate = TrustedCertificate(
        id = "corp-root",
        pem = "-----BEGIN CERTIFICATE-----\nMIIB…\n-----END CERTIFICATE-----",
        subject = "Northwind Corp Root CA 2024",
        sha256 = "3A:9F:12:C4:7B:E0:55:81:9D:2E:6F:A3:14:B8:C9:70:5D:E2:41:0A:88:3C:F1:67:9B:24:D5:E8:0C:73:A6:1F",
        notAfter = now + 400 * day,
    )

    private val network = NetworkDefaults(
        proxy = ProxyConfig(ProxyType.HTTP, "proxy.northwind.lan", 3128, hasCredentials = true),
        certificates = listOf(certificate, certificate.copy(id = "lab", subject = "homelab.internal", allProviders = true, notAfter = now + 30 * day)),
    )

    private val tools: List<ToolRow> by lazy {
        val overrides = mapOf("run_command" to ToolPolicy.ALLOW, "send_email" to ToolPolicy.DENY)
        graph.tools.registry.all.map { tool ->
            val override = overrides[tool.spec.name]
            ToolRow(tool.spec.name, tool.spec.description, tool.risk, override ?: tool.defaultPolicy, override == null, tool.defaultPolicy)
        }
    }

    private val populated: SettingsUiState by lazy {
        val integrations = Integrations.all.map { it to (it.id in setOf("firecrawl", "github", "telegram", "brave")) }
        SettingsUiState(
            providers = listOf(
                ProviderRow(anthropic, hasKey = true, isDefault = true, hasBackupKeys = true),
                ProviderRow(chatGpt, hasKey = true, isDefault = false),
                ProviderRow(openRouter, hasKey = true, isDefault = false),
                ProviderRow(ollama, hasKey = false, isDefault = false),
                ProviderRow(gemini, hasKey = false, isDefault = false),
            ),
            chatGptAccounts = mapOf("chatgpt" to "maya.okafor@fastmail.com"),
            tools = tools,
            customInstructions = "I'm a backend developer in Lagos (WAT). Answer briefly, use metric units, and ask before deleting files or pushing to main.",
            helperProviderId = "openrouter",
            helperModel = "google/gemini-2.5-flash",
            helperPrompts = mapOf(HelperPrompt.TITLE to "Write a 3–6 word title for this chat. No quotes.\n\n{chat}"),
            reviewerMode = ReviewerMode.REVIEW_ASKS,
            reviewerApprovesOutward = false,
            sshHosts = listOf(
                SshHost("h1", "homelab", "192.168.1.40", 22, "maya", SshAuth.KEY.id, "SHA256:Xw3n0l8Pq2yH7v6tF1mJk9cR4sUe5dZaB0gL2oNiT8E"),
                SshHost("h2", "vps-fra", "fra1.okafor.dev", 2222, "deploy", SshAuth.PASSWORD.id, "SHA256:aK8d2Lq0Rm3Zp7Xv1Nc5Bt9Wy4Hs6Ju0Ef2Gi8Ko3Ql"),
            ),
            integrations = integrations,
            mcpFirst = mapOf(
                "github" to McpFirstStatus(Integrations.github, connector = null, builtInKey = true, mcpReady = false),
            ),
            network = network,
            networkSecrets = SavedNetworkSecrets(headers = mapOf("openrouter" to setOf("http-referer")), proxyScopes = setOf(SavedNetworkSecrets.DEFAULT_SCOPE)),
            taskBudget = TaskBudget(tokens = 500_000, costUsd = 2.0, minutes = 30),
            fallbackModels = listOf(ModelRef("openrouter", "deepseek/deepseek-chat-v3.1"), ModelRef("chatgpt", "gpt-5-codex")),
            remembered = listOf(
                RememberedApproval("r1", "browser_open", ApprovalScopeKind.SITE, "github.com", createdAt = now - 2 * hour, expiresAt = now + 29 * day),
                RememberedApproval("r2", "write_file", ApprovalScopeKind.FOLDER, "projects/invoice-parser", conversationId = "c1", createdAt = now - day),
                RememberedApproval("r3", "run_command", ApprovalScopeKind.COMMAND, "./gradlew test", createdAt = now - 3 * day, expiresAt = now + 27 * day),
            ),
            loading = false,
        )
    }

    private val fresh = SettingsUiState(tools = emptyList(), integrations = Integrations.all.map { it to false }, loading = false)

    // ---- The hub and the pages that read SettingsUiState

    @Test fun hub() = snap("settings-hub", heightDp = 1400) { SettingsScreenHost(populated, null) }
    @Test fun hubFresh() = snap("settings-hub-fresh", heightDp = 1400) { SettingsScreenHost(fresh, null) }
    @Test fun hubLoading() = snap("settings-hub-loading") { SettingsScreenHost(SettingsUiState(), null) }

    @Test fun models() = snap("settings-models", heightDp = 1200) { SettingsScreenHost(populated, SettingsDestination.Models) }
    @Test fun modelsEmpty() = snap("settings-models-empty") { SettingsScreenHost(fresh, SettingsDestination.Models) }

    @Test fun extras() = snap("settings-extras", heightDp = 1000) { SettingsScreenHost(populated, SettingsDestination.Extras) }
    @Test fun extrasDefaults() = snap("settings-extras-defaults", heightDp = 1000) { SettingsScreenHost(fresh, SettingsDestination.Extras) }

    @Test fun agent() = snap("settings-agent", heightDp = 1100) { SettingsScreenHost(populated, SettingsDestination.Agent) }
    @Test fun agentEmpty() = snap("settings-agent-empty", heightDp = 1100) { SettingsScreenHost(fresh, SettingsDestination.Agent) }

    @Test fun permissions() = snap("settings-permissions", heightDp = 2400) { SettingsScreenHost(populated, SettingsDestination.Permissions) }

    @Test fun integrations() = snap("settings-integrations", heightDp = 1400) { SettingsScreenHost(populated, SettingsDestination.Integrations) }
    @Test fun integrationsNone() = snap("settings-integrations-none", heightDp = 1400) { SettingsScreenHost(fresh, SettingsDestination.Integrations) }

    @Test fun ssh() = snap("settings-ssh") { SettingsScreenHost(populated, SettingsDestination.Ssh) }
    @Test fun sshEmpty() = snap("settings-ssh-empty") { SettingsScreenHost(fresh, SettingsDestination.Ssh) }

    // ---- Pages with their own view models: their stateless content

    private val logins = listOf(
        dev.locus.core.model.SiteLogin("l1", "https://github.com", "alice-dev", "x-test-only", totpSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"),
        dev.locus.core.model.SiteLogin("l2", "https://accounts.example.com", "alice@example.com", "x-test-only", label = "Example Cloud"),
        dev.locus.core.model.SiteLogin("l3", "http://localhost:3000", "admin", "x-test-only"),
    )

    @Test
    fun savedLogins() = snap("settings-saved-logins") {
        SavedLoginsSettings(SavedLoginsState(loading = false, logins = logins), onBack = {}, onSave = { _, _ -> }, onDelete = {})
    }

    @Test
    fun savedLoginsEmpty() = snap("settings-saved-logins-empty") {
        SavedLoginsSettings(SavedLoginsState(loading = false), onBack = {}, onSave = { _, _ -> }, onDelete = {})
    }

    @Test
    fun savedLoginsLocked() = snap("settings-saved-logins-locked") {
        SavedLoginsSettings(SavedLoginsState(loading = false, problem = "Saved logins can't be opened right now: Saved keys aren't open."), onBack = {}, onSave = { _, _ -> }, onDelete = {})
    }

    @Test
    fun savedLoginAdd() = snap("settings-saved-login-add", heightDp = 1000) {
        SavedLoginsSettings(
            SavedLoginsState(loading = false, logins = logins), onBack = {}, onSave = { _, _ -> }, onDelete = {},
            initialEditing = dev.locus.core.model.SiteLogin("new", "", "", ""),
        )
    }

    @Test
    fun savedLoginEdit() = snap("settings-saved-login-edit", heightDp = 1000) {
        SavedLoginsSettings(SavedLoginsState(loading = false, logins = logins), onBack = {}, onSave = { _, _ -> }, onDelete = {}, initialEditing = logins.first())
    }

    @Test
    fun network() = snap("settings-network", heightDp = 1000) {
        NetworkSettings(
            NetworkUiState(
                defaults = network,
                saved = SavedNetworkSecrets(proxyScopes = setOf(SavedNetworkSecrets.DEFAULT_SCOPE)),
                usedBy = mapOf("corp-root" to listOf("OpenRouter", "Ollama on the desktop")),
            ),
            onBack = {}, onImport = {}, onDismissMessage = {}, onSetProxy = { _, _ -> }, onSetTrustedByAll = { _, _ -> }, onRemove = {},
        )
    }

    @Test
    fun networkEmpty() = snap("settings-network-empty") {
        NetworkSettings(NetworkUiState(), onBack = {}, onImport = {}, onDismissMessage = {}, onSetProxy = { _, _ -> }, onSetTrustedByAll = { _, _ -> }, onRemove = {})
    }

    @Test
    fun networkImportFailed() = snap("settings-network-import-failed") {
        NetworkSettings(
            NetworkUiState(message = "That file isn't a PEM or DER certificate." to true),
            onBack = {}, onImport = {}, onDismissMessage = {}, onSetProxy = { _, _ -> }, onSetTrustedByAll = { _, _ -> }, onRemove = {},
        )
    }

    @Test
    fun battery() = snap("settings-battery", heightDp = 1000) {
        BatterySettings(BatteryUiState(DeviceState(batteryPercent = 64, charging = false, network = NetworkKind.UNMETERED), PowerSettings()), onBack = {}, onChange = {})
    }

    @Test
    fun batteryHot() = snap("settings-battery-hot", heightDp = 1000) {
        BatterySettings(
            BatteryUiState(
                DeviceState(thermal = ThermalLevel.SEVERE, batteryPercent = 12, powerSave = true, network = NetworkKind.METERED, lowMemory = true),
                PowerSettings(deferWhenHot = false, taskDefaults = TaskConditions(requiresCharging = true, unmeteredOnly = true), downloadsUnmeteredOnly = false),
            ),
            onBack = {}, onChange = {},
        )
    }

    @Test
    fun voice() {
        // The shadow's flag outlives a test: say which recognizer this phone has.
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        snap("settings-voice") { VoiceSettingsPage(VoiceSettings(speechRate = 1.25f), onBack = {}, onChange = {}) }
    }

    @Test
    fun automation() = snap("settings-automation") {
        AutomationSettings(
            AutomationUiState(
                enabled = true,
                apps = listOf(
                    AutomationApp("net.dinglisch.android.taskerm", "Tasker", allowed = true),
                    AutomationApp("com.arlosoft.macrodroid", "MacroDroid", allowed = true),
                    AutomationApp("com.example.unknownapp", "com.example.unknownapp", allowed = false),
                ),
                permission = "dev.locus.permission.RUN_TASK",
            ),
            onBack = {}, onSetEnabled = {}, onForget = {}, assistant = false,
        )
    }

    @Test
    fun automationOff() = snap("settings-automation-off") {
        AutomationSettings(AutomationUiState(enabled = false, permission = "dev.locus.permission.RUN_TASK"), onBack = {}, onSetEnabled = {}, onForget = {}, assistant = true)
    }

    private fun backup(state: BackupUiState) = @androidx.compose.runtime.Composable {
        BackupSettings(state, onBack = {}, onIncludeWorkspace = {}, onIncludeVault = {}, onBackUp = {}, onCancelBackup = {}, onDismissResult = {}, onRestore = {})
    }

    @Test fun backupIdle() = snap("settings-backup", content = backup(BackupUiState(estimate = BackupEstimate(baseBytes = 48_300_000, workspaceBytes = 1_240_000_000), includeWorkspace = true)))
    @Test fun backupEstimating() = snap("settings-backup-estimating", content = backup(BackupUiState()))
    @Test fun backupWithKeys() = snap("settings-backup-with-keys", content = backup(BackupUiState(estimate = BackupEstimate(48_300_000, 1_240_000_000), includeVault = true)))

    @Test
    fun backupRunning() = snap(
        "settings-backup-running",
        content = backup(BackupUiState(estimate = BackupEstimate(48_300_000, 1_240_000_000), includeWorkspace = true, backupProgress = BackupProgress("Backing up", 512_000_000, 1_288_300_000))),
    )

    @Test fun backupDone() = snap("settings-backup-done", content = backup(BackupUiState(estimate = BackupEstimate(48_300_000, 0), result = "Backup saved: 46.1 MB before compression." to false)))

    @Test
    fun backupFailed() = snap(
        "settings-backup-failed",
        content = backup(BackupUiState(estimate = BackupEstimate(48_300_000, 0), result = backupFailure(java.io.IOException("write failed: ENOSPC (No space left on device)")) to true)),
    )

    @Test fun backupRunActive() = snap("settings-backup-run-active", content = backup(BackupUiState(estimate = BackupEstimate(48_300_000, 0), runActive = true)))

    private val diagnostics = listOf(
        DiagnosticEntry("d1", DiagnosticKind.CRASH, now - 3 * hour, "Crash in ChatViewModel", "java.lang.IllegalStateException: Conversation 4f2a not found\n\tat dev.locus.feature.chat.ChatViewModel.load(ChatViewModel.kt:212)"),
        DiagnosticEntry("d2", DiagnosticKind.EXIT, now - day, "Stopped by Android: low memory", "The system killed Locus while it was in the background (importance 400)."),
        DiagnosticEntry("d3", DiagnosticKind.ERROR, now - 2 * day, "Provider error: OpenRouter 429", "Rate limited: retry after 30 s."),
        DiagnosticEntry("d4", DiagnosticKind.STORAGE, now - 5 * day, "Database grew past 500 MB", "Chats and history use 512 MB."),
    )

    @Test fun diagnosticsPopulated() = snap("settings-diagnostics", heightDp = 1000) { DiagnosticsSettings(diagnostics, onBack = {}, onShare = {}, onClear = {}) }
    @Test fun diagnosticsEmpty() = snap("settings-diagnostics-empty") { DiagnosticsSettings(emptyList(), onBack = {}, onShare = {}, onClear = {}) }

    private val checks = listOf(
        SelfTestRow("vault", "Saved keys", SelfTestResult("vault", "Saved keys", SelfTestLevel.Pass, "7 keys open with the hardware key.")),
        SelfTestRow("database", "Database", SelfTestResult("database", "Database", SelfTestLevel.Pass, "Integrity check passed (48 MB).")),
        SelfTestRow("storage", "Free space", SelfTestResult("storage", "Free space", SelfTestLevel.Warn, "1.2 GB free. Downloads and Linux packages need more.", SelfTestFix.OpenStorage)),
        SelfTestRow("notifications", "Notifications", SelfTestResult("notifications", "Notifications", SelfTestLevel.Fail, "Turned off: tasks can't tell you when they finish.", SelfTestFix.NotificationSettings)),
        SelfTestRow("linux", "Linux", SelfTestResult("linux", "Linux", SelfTestLevel.Skipped, "Not installed.")),
        SelfTestRow("workspace", "Workspace", SelfTestResult("workspace", "Workspace", SelfTestLevel.Pass, "Readable and writable.")),
    )

    @Test
    fun selfTestDone() = snap("settings-self-test", heightDp = 1100) {
        SelfTestSettings(SelfTestUiState(rows = checks, includeProviders = false, providerCount = 3, finishedAt = now - 60_000), onBack = {}, onRun = {}, onIncludeProviders = {}, onFix = { _, _ -> })
    }

    @Test
    fun selfTestRunning() = snap("settings-self-test-running", heightDp = 1100) {
        val rows = checks.mapIndexed { i, r -> if (i < 2) r else r.copy(result = null) }
        SelfTestSettings(SelfTestUiState(rows = rows, checking = "storage", running = true, providerCount = 3), onBack = {}, onRun = {}, onIncludeProviders = {}, onFix = { _, _ -> })
    }

    @Test
    fun selfTestAllPass() = snap("settings-self-test-all-pass", heightDp = 1100) {
        // Every check passing, with the words a pass actually shows.
        val passed = mapOf(
            "storage" to "18.4 GB free.",
            "notifications" to "On: tasks can tell you when they finish.",
            "linux" to "Installed and starts.",
        )
        val rows = checks.map { r -> r.copy(result = r.result!!.copy(level = SelfTestLevel.Pass, fix = null, detail = passed[r.id] ?: r.result!!.detail)) }
        SelfTestSettings(SelfTestUiState(rows = rows, includeProviders = true, providerCount = 3, finishedAt = now - 60_000), onBack = {}, onRun = {}, onIncludeProviders = {}, onFix = { _, _ -> })
    }

    @Test
    fun selfTestError() = snap("settings-self-test-error") {
        SelfTestSettings(SelfTestUiState(error = "Couldn't read the provider list: settings file is locked."), onBack = {}, onRun = {}, onIncludeProviders = {}, onFix = { _, _ -> })
    }

    private val storageReport = StorageReport(
        items = listOf(
            StorageItem(StorageArea.Database, 48_300_000),
            StorageItem(StorageArea.Workspace, 1_240_000_000),
            StorageItem(StorageArea.Trash, 86_000_000),
            StorageItem(StorageArea.Linux, 712_000_000),
            StorageItem(StorageArea.Models, 2_580_000_000),
            StorageItem(StorageArea.Browser, 64_000_000),
            StorageItem(StorageArea.Caches, 12_400_000),
            StorageItem(StorageArea.Logs, 380_000),
            StorageItem(StorageArea.AgentHome, null),
        ),
        freeBytes = 18_400_000_000,
        totalBytes = 128_000_000_000,
        takenAt = now - 30_000,
    )

    private fun storage(state: StorageUiState) = @androidx.compose.runtime.Composable {
        StorageSettings(state, onBack = {}, onRefresh = {}, onAction = {}, onOpenModels = {}, onOpenLinux = {})
    }

    @Test fun storageReport() = snap("settings-storage", heightDp = 1200, content = storage(StorageUiState(report = storageReport, measuring = false)))
    @Test fun storageMeasuring() = snap("settings-storage-measuring", content = storage(StorageUiState()))
    @Test fun storageError() = snap("settings-storage-error", content = storage(StorageUiState(measuring = false, error = "Permission denied: /data/user/0/dev.locus/files/linux")))
    @Test fun storageBusy() = snap("settings-storage-clearing", heightDp = 1200, content = storage(StorageUiState(report = storageReport, measuring = true, busy = StorageAction.ClearCaches)))

    // ---- About and its Updates section

    @Test
    fun about() {
        val vm = UpdatesViewModel(graph.updates())
        runBlocking { withTimeout(10_000) { vm.uiState.first { it.info != null } } }
        snap("settings-about", heightDp = 1200) { AboutSettings(onBack = {}, onRunSetup = {}, updatesViewModel = vm) }
    }

    private val release = Release(
        tag = "v0.2.0",
        name = "Locus 0.2.0",
        body = "## What's new\n\n- **Projects**: group chats, files and memory per project.\n- Voice mode can be interrupted by speaking.\n- Faster Linux start-up.\n\n## Fixes\n\n- Backups no longer stall on large workspaces.",
        htmlUrl = "https://github.com/locus-app/locus/releases/tag/v0.2.0",
        publishedAt = "2026-10-28T09:12:00Z",
        assets = listOf(ReleaseAsset("locus-0.2.0-arm64-v8a.apk", "https://github.com/locus-app/locus/releases/download/v0.2.0/locus-0.2.0-arm64-v8a.apk", size = 61_400_000)),
    )

    private val info = UpdateInfo(UpdateAvailability.Available, enabled = true, checkedAt = now - 2 * hour, installedVersion = "0.1.4")

    private fun updates(ui: UpdatesUiState) = @androidx.compose.runtime.Composable {
        SettingsPageScaffold("About & privacy", onBack = {}) { updatesSection(ui, UpdateActions()) }
    }

    @Test fun updatesUpToDate() = snap("settings-updates-up-to-date", heightDp = 500, content = updates(UpdatesUiState(info, UpdateState.UpToDate("0.1.4"))))
    @Test fun updatesChecking() = snap("settings-updates-checking", heightDp = 500, content = updates(UpdatesUiState(info, UpdateState.Checking)))
    @Test fun updatesAvailable() = snap("settings-updates-available", heightDp = 1000, content = updates(UpdatesUiState(info, UpdateState.Available(release, skipped = false))))
    @Test fun updatesSkipped() = snap("settings-updates-skipped", heightDp = 600, content = updates(UpdatesUiState(info, UpdateState.Available(release, skipped = true))))
    @Test fun updatesDownloading() = snap("settings-updates-downloading", heightDp = 1000, content = updates(UpdatesUiState(info, UpdateState.Downloading(release, 22_000_000, 61_400_000))))
    @Test fun updatesReady() = snap("settings-updates-ready", heightDp = 1000, content = updates(UpdatesUiState(info, UpdateState.ReadyToInstall(release))))
    @Test fun updatesFailed() = snap("settings-updates-failed", heightDp = 700, content = updates(UpdatesUiState(info, UpdateState.Failed(UpdateProblem.ChecksumMismatch, release))))
    @Test fun updatesNoNetwork() = snap("settings-updates-no-network", heightDp = 700, content = updates(UpdatesUiState(info, UpdateState.Failed(UpdateProblem.NoNetwork, null))))
    @Test fun updatesFromStore() = snap("settings-updates-from-store", heightDp = 500, content = updates(UpdatesUiState(info.copy(availability = UpdateAvailability.FromStore("F-Droid"), enabled = false))))
    @Test fun updatesOff() = snap("settings-updates-off", heightDp = 500, content = updates(UpdatesUiState(info.copy(enabled = false, checkedAt = 0), UpdateState.Idle)))

    /** Beta versions on, and a newer pre-release found: the card says Beta before anything else. */
    @Test fun updatesBetaAvailable() = snap(
        "settings-updates-beta-available", heightDp = 1100,
        content = updates(UpdatesUiState(info.copy(beta = true), UpdateState.Available(release.copy(tag = "v0.2.0-beta.1", prerelease = true), skipped = false))),
    )

    /** Beta on, nothing newer: the switch is on under the daily check. */
    @Test fun updatesBetaUpToDate() = snap("settings-updates-beta-up-to-date", heightDp = 600, content = updates(UpdatesUiState(info.copy(beta = true), UpdateState.UpToDate("0.1.4"))))

    // ---- Sheets

    @Test fun helperPromptDefault() = snap("settings-helper-prompt-sheet") { HelperPromptSheet(HelperPrompt.SUGGESTIONS, saved = null, onSave = {}, onDismiss = {}) }

    @Test
    fun helperPromptCustom() = snap("settings-helper-prompt-sheet-custom") {
        HelperPromptSheet(HelperPrompt.TITLE, saved = "Write a 3–6 word title for this chat. No quotes.\n\n{chat}", onSave = {}, onDismiss = {})
    }

    @Test
    fun helperPromptInvalid() = snap("settings-helper-prompt-sheet-invalid") {
        HelperPromptSheet(HelperPrompt.COMPACTION, saved = "Summarise the conversation in five bullet points.", onSave = {}, onDismiss = {})
    }

    @Test
    fun providerNetworkSheet() = snap("settings-provider-network-sheet", heightDp = 1200) {
        ProviderNetworkSheet(
            providerId = "openrouter",
            providerName = "OpenRouter",
            signsIn = false,
            draft = NetworkDraft(openRouter.network.copy(certificateIds = listOf("corp-root"))),
            defaults = network,
            savedSecrets = SavedNetworkSecrets(headers = mapOf("openrouter" to setOf("http-referer"))),
            onDismiss = {},
        )
    }

    @Test
    fun providerNetworkSheetCustomProxy() = snap("settings-provider-network-sheet-custom-proxy", heightDp = 1200) {
        ProviderNetworkSheet(
            providerId = "ollama",
            providerName = "Ollama on the desktop",
            signsIn = false,
            draft = NetworkDraft(ProviderNetwork(headerNames = listOf("Authorization"), proxyMode = ProxyMode.CUSTOM, proxy = ProxyConfig(ProxyType.SOCKS5, "10.8.0.1", 1080))),
            defaults = NetworkDefaults(),
            savedSecrets = SavedNetworkSecrets(),
            onDismiss = {},
        )
    }

    @Test
    fun chatGptSignedIn() = snap("settings-chatgpt-signed-in", heightDp = 300) {
        // As the provider editor lays it out: in a column, on the screen's background.
        OnScreen { ChatGptAccountSection("chatgpt", signedIn = true, email = "maya.okafor@fastmail.com", actions = ChatGptActions.None) }
    }

    @Test
    fun chatGptSignedOut() = snap("settings-chatgpt-signed-out", heightDp = 300) {
        OnScreen { ChatGptAccountSection("chatgpt", signedIn = false, email = null, actions = ChatGptActions.None) }
    }

    // ---- Every sheet and state not reached above

    /** A date that renders the same on every run (2026-10-20 12:40 UTC). */
    private val FIXED = 1_792_500_000_000L

    /** Certificates for the sheets, which show the expiry as a date: one far ahead, one past. */
    private val corpRoot = certificate.copy(notAfter = 2_028_000_000_000L)
    private val sheetNetwork = NetworkDefaults(
        proxy = network.proxy,
        certificates = listOf(
            corpRoot,
            certificate.copy(id = "lab", subject = "homelab.internal", allProviders = true, sha256 = "9C:41:0E:7A:B2:68:D3:15:F0:4A:27:E9:5B:C8:13:6D:A0:F7:32:8E:41:D9:06:BB:7C:54:E2:19:A8:3F:60:D1", notAfter = 1_760_000_000_000L),
        ),
    )

    private fun networkPage(state: NetworkUiState) = @androidx.compose.runtime.Composable {
        NetworkSettings(state, onBack = {}, onImport = {}, onDismissMessage = {}, onSetProxy = { _, _ -> }, onSetTrustedByAll = { _, _ -> }, onRemove = {})
    }

    private val sheetNetworkState = NetworkUiState(
        defaults = sheetNetwork,
        saved = SavedNetworkSecrets(proxyScopes = setOf(SavedNetworkSecrets.DEFAULT_SCOPE)),
        usedBy = mapOf("corp-root" to listOf("OpenRouter", "Ollama on the desktop")),
    )

    @Test fun networkProxySheet() = snapAfter("settings-network-proxy-sheet", listOf(Step.Tap("proxy.northwind.lan:3128")), heightDp = 1000, content = networkPage(sheetNetworkState))
    @Test fun networkProxySheetNone() = snapAfter("settings-network-proxy-sheet-none", listOf(Step.Tap("No proxy")), heightDp = 600, content = networkPage(NetworkUiState()))
    @Test fun networkCertificate() = snapAfter("settings-network-certificate-sheet", listOf(Step.Tap("Northwind Corp Root CA 2024")), heightDp = 800, content = networkPage(sheetNetworkState))
    @Test fun networkCertificateAll() = snapAfter("settings-network-certificate-sheet-all-providers", listOf(Step.Tap("homelab.internal")), heightDp = 900, content = networkPage(sheetNetworkState))

    @Test
    fun networkCertificateConfirmAll() = snapAfter(
        "settings-network-certificate-confirm-all",
        listOf(Step.Tap("Northwind Corp Root CA 2024"), Step.Tap("Trust for every provider")),
        heightDp = 900, content = networkPage(sheetNetworkState),
    )

    @Test
    fun networkCertificateRemove() = snapAfter(
        "settings-network-certificate-confirm-remove",
        listOf(Step.Tap("Northwind Corp Root CA 2024"), Step.Tap("Remove certificate")),
        heightDp = 900, content = networkPage(sheetNetworkState),
    )

    // Backup: the password sheet and each step of the restore sheet.

    private val backupFile = "dev.locus.feature.settings.BackupSettingsKt"

    @Test
    fun backupPassword() = snap("settings-backup-password-sheet", heightDp = 800) {
        PrivateComposable(backupFile, "NewPasswordSheet", false, {}, { _: String -> })
    }

    @Test
    fun backupPasswordShort() = snapAfter("settings-backup-password-sheet-short", listOf(Step.Type("basil")), heightDp = 800) {
        PrivateComposable(backupFile, "NewPasswordSheet", true, {}, { _: String -> })
    }

    @Test
    fun backupPasswordMismatch() = snapAfter(
        "settings-backup-password-sheet-mismatch",
        listOf(Step.Type("tram twenty eight lisbon"), Step.Type("tram twenty eight lisboa", index = 1)),
        heightDp = 800,
    ) {
        PrivateComposable(backupFile, "NewPasswordSheet", true, {}, { _: String -> })
    }

    private val backupUri = android.net.Uri.parse("content://com.android.providers.downloads.documents/document/msf%3A1000004521")

    private fun restore(step: RestoreStep, runActive: Boolean = false) = @androidx.compose.runtime.Composable {
        PrivateComposable(backupFile, "RestoreSheet", step, runActive, { _: String -> }, {}, {}, {})
    }

    private val manifest = dev.locus.core.data.backup.BackupManifest(
        appVersion = "0.1.4", appVersionCode = 104, schemaVersion = 17, createdAt = FIXED,
        sections = listOf(
            dev.locus.core.data.backup.BackupSection(dev.locus.core.data.backup.BackupArchive.DATABASE, 1, 48_300_000),
            dev.locus.core.data.backup.BackupSection(dev.locus.core.data.backup.BackupArchive.SETTINGS, 1, 18_400),
            dev.locus.core.data.backup.BackupSection(dev.locus.core.data.backup.BackupArchive.AGENT_HOME, 214, 3_120_000),
            dev.locus.core.data.backup.BackupSection(dev.locus.core.data.backup.BackupArchive.WORKSPACE, 1_806, 1_240_000_000),
            dev.locus.core.data.backup.BackupSection(dev.locus.core.data.backup.BackupArchive.VAULT, 7, 6_200),
        ),
        contentVersion = 2, projects = 3,
    )

    @Test fun restorePassword() = snap("settings-restore-password", heightDp = 600, content = restore(RestoreStep.Password(backupUri)))
    @Test fun restoreWrongPassword() = snap("settings-restore-wrong-password", heightDp = 600, content = restore(RestoreStep.Password(backupUri, "That password doesn't open this backup.")))
    @Test fun restoreNotABackup() = snap("settings-restore-not-a-backup", heightDp = 500, content = restore(RestoreStep.Password(backupUri, "This isn't a Locus backup file.", unreadable = true)))
    @Test fun restoreVerifying() = snap("settings-restore-verifying", heightDp = 500, content = restore(RestoreStep.Verifying(BackupProgress("Checking the backup", 402_000_000, 1_291_700_000))))
    @Test fun restorePreview() = snap("settings-restore-preview", heightDp = 1100, content = restore(RestoreStep.Preview(manifest)))
    @Test fun restorePreviewRunActive() = snap("settings-restore-preview-run-active", heightDp = 1200, content = restore(RestoreStep.Preview(manifest.copy(sections = manifest.sections.take(3))), runActive = true))

    @Test
    fun restorePreviewNoSpace() = snap(
        "settings-restore-preview-no-space", heightDp = 1200,
        content = restore(
            RestoreStep.Preview(
                manifest,
                "There isn't enough free space on this phone to restore this backup. Free some space, then try again.",
                noSpace = SpaceNeeded(neededBytes = 1_420_000_000, freeBytes = 610_000_000),
            ),
        ),
    )

    @Test fun restoring() = snap("settings-restoring", heightDp = 400, content = restore(RestoreStep.Restoring))

    // A provider's network: adding a header, with what the name check says.

    private fun addHeader(name: String, steps: List<Step>) = snapAfter(name, steps, heightDp = 600) {
        PrivateComposable("dev.locus.feature.settings.ProviderNetworkEditorKt", "AddHeaderSheet", setOf("http-referer", "x-title"), { _: String, _: String -> }, {})
    }

    @Test fun addHeaderEmpty() = addHeader("settings-add-header-sheet", emptyList())
    @Test fun addHeaderFilled() = addHeader("settings-add-header-sheet-filled", listOf(Step.Type("X-Org-Id"), Step.Type("org-northwind-prod", index = 1)))
    @Test fun addHeaderCredential() = addHeader("settings-add-header-sheet-credential", listOf(Step.Type("Authorization")))
    @Test fun addHeaderForbidden() = addHeader("settings-add-header-sheet-forbidden", listOf(Step.Type("Host")))
    @Test fun addHeaderDuplicate() = addHeader("settings-add-header-sheet-duplicate", listOf(Step.Type("X-Title")))

    // Integrations: the key sheet with and without a saved key, its MCP card, removing a key, Telegram.

    @Test fun integrationKey() = snapAfter("settings-integration-key-sheet", listOf(Step.Tap("Tavily")), heightDp = 1000) { SettingsScreenHost(populated, SettingsDestination.Integrations) }
    @Test fun integrationKeySaved() = snapAfter("settings-integration-key-sheet-saved", listOf(Step.Tap("Brave Search")), heightDp = 1000) { SettingsScreenHost(populated, SettingsDestination.Integrations) }
    @Test fun integrationKeyMcp() = snapAfter("settings-integration-key-sheet-mcp", listOf(Step.Tap("GitHub")), heightDp = 1200) { SettingsScreenHost(populated, SettingsDestination.Integrations) }

    @Test
    fun integrationKeyRemove() = snapAfter("settings-integration-key-remove", listOf(Step.Tap("Brave Search"), Step.Tap("Remove key")), heightDp = 1000) {
        SettingsScreenHost(populated, SettingsDestination.Integrations)
    }

    @Test fun telegram() = snapAfter("settings-telegram-sheet", listOf(Step.Tap("Telegram")), heightDp = 1200) { SettingsScreenHost(populated, SettingsDestination.Integrations) }

    // Helper model & extras: the helper sheet.

    @Test fun helperSheet() = snapAfter("settings-helper-sheet", listOf(Step.Tap("Helper model")), heightDp = 1200) { SettingsScreenHost(populated, SettingsDestination.Extras) }
    @Test fun helperSheetSame() = snapAfter("settings-helper-sheet-same-as-chat", listOf(Step.Tap("Helper model")), heightDp = 1000) { SettingsScreenHost(fresh.copy(providers = populated.providers), SettingsDestination.Extras) }
    @Test fun helperPromptFromPage() = snapAfter("settings-extras-prompt-sheet", listOf(Step.Tap("Chat title")), heightDp = 1800) { SettingsScreenHost(populated, SettingsDestination.Extras) }

    // Models: adding a provider, editing one (API key, ChatGPT sign-in).

    @Test fun modelsAdd() = snapAfter("settings-models-add-provider", listOf(Step.Tap("Add a model provider")), heightDp = 1200) { SettingsScreenHost(populated, SettingsDestination.Models) }
    @Test fun modelsEditAnthropic() = snapAfter("settings-models-edit-provider", listOf(Step.Tap("Anthropic")), heightDp = 1600) { SettingsScreenHost(populated, SettingsDestination.Models) }
    @Test fun modelsEditChatGpt() = snapAfter("settings-models-edit-chatgpt", listOf(Step.Tap("ChatGPT")), heightDp = 1400) { SettingsScreenHost(populated, SettingsDestination.Models) }

    // SSH machines: adding and editing one.

    @Test fun sshAdd() = snapAfter("settings-ssh-add", listOf(Step.Tap("Add a machine")), heightDp = 1100) { SettingsScreenHost(populated, SettingsDestination.Ssh) }
    @Test fun sshEdit() = snapAfter("settings-ssh-edit", listOf(Step.Tap("homelab")), heightDp = 1100) { SettingsScreenHost(populated, SettingsDestination.Ssh) }

    // ChatGPT sign-in: waiting for the browser, a failed sign-in, the first-sign-in note, signing out.

    private fun chatGpt(name: String, steps: List<Step>, signedIn: Boolean, actions: ChatGptActions, height: Int = 500) = snapAfter(name, steps, heightDp = height) {
        OnScreen { ChatGptAccountSection("chatgpt", signedIn = signedIn, email = if (signedIn) "maya.okafor@fastmail.com" else null, actions = actions) }
    }

    private val waiting = ChatGptActions({ _, _ -> kotlinx.coroutines.Job() }, { _, _ -> }, {})

    @Test fun chatGptWaiting() = chatGpt("settings-chatgpt-signing-in", listOf(Step.Tap("Continue with ChatGPT")), signedIn = false, actions = waiting)

    @Test
    fun chatGptFailed() = chatGpt(
        "settings-chatgpt-sign-in-failed", listOf(Step.Tap("Continue with ChatGPT")), signedIn = false,
        actions = ChatGptActions({ _, done -> done(Result.failure(dev.locus.core.agent.auth.ChatGptAuthException("Couldn't reach OpenAI's sign-in service. Check the connection and try again."))); null }, { _, _ -> }, {}),
    )

    @Test
    fun chatGptWelcome() = chatGpt(
        "settings-chatgpt-welcome", listOf(Step.Tap("Continue with ChatGPT")), signedIn = false, height = 700,
        actions = ChatGptActions({ _, done -> done(Result.success(true)); null }, { _, _ -> }, {}),
    )

    @Test fun chatGptSignOut() = chatGpt("settings-chatgpt-sign-out-confirm", listOf(Step.Tap("Sign out")), signedIn = true, actions = ChatGptActions.None, height = 700)

    @Test
    fun chatGptSignOutUnconfirmed() = chatGpt(
        "settings-chatgpt-sign-out-unconfirmed", listOf(Step.Tap("Sign out"), Step.Tap("Sign out", index = 1)), signedIn = true,
        actions = ChatGptActions({ _, _ -> null }, { _, done -> done(false) }, {}),
    )

    // Diagnostics: an entry opened, and Clear's confirmation.

    @Test fun diagnosticsExpanded() = snapAfter("settings-diagnostics-expanded", listOf(Step.Tap("Crash in ChatViewModel")), heightDp = 1000) { DiagnosticsSettings(diagnostics, onBack = {}, onShare = {}, onClear = {}) }
    @Test fun diagnosticsClear() = snapAfter("settings-diagnostics-clear-confirm", listOf(Step.Tap("Clear")), heightDp = 1600) { DiagnosticsSettings(diagnostics, onBack = {}, onShare = {}, onClear = {}) }

    // Voice: interrupt by speaking off, and each kind of recognizer the phone can have.

    @Test
    fun voiceOnDevice() {
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        snap("settings-voice-on-device", heightDp = 1000) { VoiceSettingsPage(VoiceSettings(), onBack = {}, onChange = {}) }
    }

    @Test
    fun voiceSystemRecognizer() {
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val service = android.content.ComponentName("com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService")
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(service, android.content.IntentFilter(android.speech.RecognitionService.SERVICE_INTERFACE))
        snap("settings-voice-system-recognizer", heightDp = 1000) {
            VoiceSettingsPage(VoiceSettings(speechRate = 0.8f, interruptByVoice = false, spokenStatus = false), onBack = {}, onChange = {})
        }
    }

    @Test
    fun voiceNoRecognizer() {
        org.robolectric.shadows.ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        snap("settings-voice-no-recognizer", heightDp = 1000) {
            VoiceSettingsPage(VoiceSettings(autoListen = false, interruptByVoice = false), onBack = {}, onChange = {})
        }
    }
}


/** SettingsScreen opened on [page] with no-op callbacks. */
@androidx.compose.runtime.Composable
private fun SettingsScreenHost(state: SettingsUiState, page: SettingsDestination?) = SettingsScreen(
    state = state,
    onBack = {},
    initialPage = page,
    onOpenConnectors = {},
    onSaveProvider = { _, _, _, _ -> },
    onDeleteProvider = {},
    onSetDefault = {},
    onSetPolicy = { _, _ -> },
    onSaveInstructions = {},
    onProbe = { _, _, _ -> },
    onSaveSsh = { _, _, _, _ -> },
    onDeleteSsh = {},
    onModelAction = { _, _ -> },
    onSetIntegrationKey = { _, _ -> },
)
