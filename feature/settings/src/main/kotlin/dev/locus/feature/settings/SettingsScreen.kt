package dev.locus.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.data.network.NetworkSecretEdits
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.SettingsDestination
import dev.locus.core.model.SshHost
import dev.locus.core.model.ToolPolicy
import dev.locus.core.ui.TelegramActions

/**
 * Settings: a hub of grouped rows, each opening a focused sub-screen. Sub-navigation lives here
 * (not in the app's back stack) so system back returns to the hub before leaving Settings, unless a
 * "Settings → X" link opened the page directly (`initialPage`): then back returns to the link's screen.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    /** The page to open on, from a "Settings → X" link; null opens the hub. Back from it calls [onBack]. */
    initialPage: SettingsDestination? = null,
    onOpenConnectors: () -> Unit,
    /** Opens Library → Connectors on an MCP template (by id), for "Use the official MCP server". */
    onUseMcpTemplate: (String) -> Unit = {},
    onOpenAgentSetup: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    /** Opens the Linux health screen (Self-test and Storage link to it). */
    onOpenLinux: () -> Unit = {},
    /** Opens the On-device models screen (its own route). */
    onOpenOnDeviceModels: () -> Unit = {},
    /** Opens Activity, the log of every action (About links to it). */
    onOpenActivity: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        initialPage = initialPage,
        onBack = onBack,
        onOpenConnectors = onOpenConnectors,
        onUseMcpTemplate = onUseMcpTemplate,
        onSetUseMcp = viewModel::setUseMcp,
        onCheckMcp = { viewModel.checkMcpServers() },
        onOpenAgentSetup = onOpenAgentSetup,
        onRunSetup = onRunSetup,
        onOpenLinux = onOpenLinux,
        onOpenActivity = onOpenActivity,
        onSaveProvider = viewModel::saveProvider,
        onSetFallbacks = { viewModel.setFallbackModels(it) },
        onDeleteProvider = viewModel::deleteProvider,
        onSetDefault = viewModel::setDefault,
        onSetPolicy = viewModel::setPolicy,
        onSaveInstructions = viewModel::saveInstructions,
        onSetTaskBudget = { viewModel.setTaskBudget(it) },
        onSetReflect = { viewModel.setReflectAfterTasks(it) },
        onSetHelperReports = { viewModel.setSubagentReportsContinue(it) },
        onSetHelperTimeout = { viewModel.setHelperTimeoutMinutes(it) },
        onSetHelper = viewModel::setHelperModel,
        onSetAutoTitle = { viewModel.setAutoTitle(it) },
        onSetModelCatalog = { viewModel.setModelCatalog(it) },
        onSetSuggest = { viewModel.setSuggestFollowUps(it) },
        onSetPrompt = { prompt, text -> viewModel.setHelperPrompt(prompt, text) },
        onSetReviewerMode = { viewModel.setReviewerMode(it) },
        onSetReviewerOutward = { viewModel.setReviewerApprovesOutward(it) },
        onRevokeRemembered = { viewModel.revokeRemembered(it) },
        onProbe = viewModel::probeFingerprint,
        onSaveSsh = viewModel::saveSshHost,
        onDeleteSsh = viewModel::deleteSshHost,
        onSetIntegrationKey = viewModel::setIntegrationKey,
        onListModels = viewModel::listModels,
        chatGpt = ChatGptActions(viewModel::signInChatGpt, viewModel::signOutChatGpt, { viewModel.discardChatGpt(it) }),
        telegram = viewModel.telegramActions,
        email = viewModel.emailActions,
        onOpenOnDeviceModels = onOpenOnDeviceModels,
        onModelAction = { id, action ->
            when (action) {
                "download" -> viewModel.downloadModel(id)
                "cancel" -> viewModel.cancelModel(id)
                "anynetwork" -> viewModel.downloadModelOverAnyNetwork(id)
                "unload" -> viewModel.unloadModel()
                "benchmark" -> viewModel.benchmarkModel(id)
                "delete" -> viewModel.deleteModel(id)
            }
        },
    )
}

/** The pages inside Settings. Every [SettingsDestination] is one of them, by the same name. */
internal enum class SettingsPage { Hub, Models, Extras, Agent, Permissions, Integrations, Ssh, Logins, Network, Battery, Voice, Automation, Phone, Backup, About, Diagnostics, SelfTest, Storage }

/**
 * The page a link to [destination] opens; the hub for none. On-device models is a screen of its own
 * (the app routes links to it there), so a Settings route that names it opens Models, its parent.
 */
internal fun settingsPageFor(destination: SettingsDestination?): SettingsPage = when (destination) {
    null -> SettingsPage.Hub
    SettingsDestination.OnDeviceModels -> SettingsPage.Models
    else -> SettingsPage.entries.firstOrNull { it.name == destination.name } ?: SettingsPage.Hub
}

/**
 * Where back goes from [page] when Settings opened on [start]: null leaves Settings. A page a link
 * opened goes back to the link's screen, not to a hub the user never saw; Diagnostics goes back to
 * About, and Self-test and Storage to Diagnostics, where they're opened from.
 */
internal fun settingsBack(page: SettingsPage, start: SettingsPage): SettingsPage? = when {
    page == SettingsPage.Hub || page == start -> null
    page == SettingsPage.Diagnostics -> SettingsPage.About
    page == SettingsPage.SelfTest || page == SettingsPage.Storage -> SettingsPage.Diagnostics
    else -> SettingsPage.Hub
}

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    initialPage: SettingsDestination? = null,
    onOpenConnectors: () -> Unit,
    onUseMcpTemplate: (String) -> Unit = {},
    onSetUseMcp: (String, Boolean) -> Unit = { _, _ -> },
    onCheckMcp: () -> Unit = {},
    onSaveProvider: (ProviderConfig, String?, NetworkSecretEdits, String?) -> Unit,
    onSetFallbacks: (List<dev.locus.core.model.ModelRef>) -> Unit = {},
    onDeleteProvider: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onSetPolicy: (ToolRow, ToolPolicy) -> Unit,
    onSaveInstructions: (String) -> Unit,
    onSetTaskBudget: (dev.locus.core.model.TaskBudget) -> Unit = {},
    onSetReflect: (Boolean) -> Unit = {},
    onSetHelperReports: (Boolean) -> Unit = {},
    onSetHelperTimeout: (Int) -> Unit = {},
    onSetHelper: (String?, String, Boolean) -> Unit = { _, _, _ -> },
    onSetAutoTitle: (Boolean) -> Unit = {},
    onSetModelCatalog: (Boolean) -> Unit = {},
    onSetSuggest: (Boolean) -> Unit = {},
    onSetPrompt: (HelperPrompt, String?) -> Unit = { _, _ -> },
    onSetReviewerMode: (ReviewerMode) -> Unit = {},
    onSetReviewerOutward: (Boolean) -> Unit = {},
    onRevokeRemembered: (String) -> Unit = {},
    onProbe: (String, Int, (Result<String>) -> Unit) -> Unit,
    onSaveSsh: (SshHost, String?, String?, String?) -> Unit,
    onDeleteSsh: (String) -> Unit,
    onModelAction: (String, String) -> Unit,
    onSetIntegrationKey: (Integration, String?) -> Unit,
    onListModels: (ProviderConfig, String?, (Result<List<dev.locus.core.llm.RemoteModel>>) -> Unit) -> Unit = { _, _, _ -> },
    onOpenAgentSetup: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    onOpenLinux: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
    chatGpt: ChatGptActions = ChatGptActions.None,
    telegram: TelegramActions = TelegramActions.None,
    email: dev.locus.core.ui.EmailActions = dev.locus.core.ui.EmailActions.None,
    onOpenOnDeviceModels: () -> Unit = {},
) {
    val start = settingsPageFor(initialPage)
    var pageName by rememberSaveable { mutableStateOf(start.name) }
    val page = SettingsPage.valueOf(pageName)
    val backFrom = { from: SettingsPage -> settingsBack(from, start)?.let { pageName = it.name } ?: onBack() }
    val back = { backFrom(page) } // a page's own back arrow
    BackHandler(enabled = page != SettingsPage.Hub) { backFrom(page) }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != SettingsPage.Hub
            (slideInHorizontally { w -> if (forward) w / 5 else -w / 5 } + fadeIn()) togetherWith
                (slideOutHorizontally { w -> if (forward) -w / 5 else w / 5 } + fadeOut())
        },
        label = "settings-page",
    ) { current ->
        when (current) {
            SettingsPage.Hub -> SettingsHub(state, onBack = onBack, onOpen = { pageName = it.name })
            SettingsPage.Models -> ModelsSettings(
                state, onBack = back,
                onSaveProvider = onSaveProvider, onDeleteProvider = onDeleteProvider, onSetDefault = onSetDefault,
                onModelAction = onModelAction, onListModels = onListModels, chatGpt = chatGpt,
                onSetModelCatalog = onSetModelCatalog,
                onSetFallbacks = onSetFallbacks,
                onOpenOnDeviceModels = onOpenOnDeviceModels,
            )
            SettingsPage.Extras -> ExtrasSettings(
                state, onBack = back,
                onSetHelper = onSetHelper, onSetAutoTitle = onSetAutoTitle, onSetSuggest = onSetSuggest, onSetReflect = onSetReflect,
                onSetPrompt = onSetPrompt, onListModels = onListModels,
            )
            SettingsPage.Agent -> AgentSettings(
                state, onBack = back, onSaveInstructions = onSaveInstructions, onOpenAgentSetup = onOpenAgentSetup, onSetTaskBudget = onSetTaskBudget,
                onSetHelperReports = onSetHelperReports, onSetHelperTimeout = onSetHelperTimeout,
            )
            SettingsPage.Permissions -> PermissionsSettings(
                state, onBack = back, onSetPolicy = onSetPolicy,
                onSetReviewerMode = onSetReviewerMode, onSetReviewerOutward = onSetReviewerOutward,
                onRevoke = onRevokeRemembered,
            )
            SettingsPage.Integrations -> IntegrationsSettings(state, onBack = back, onSetIntegrationKey = onSetIntegrationKey, onOpenConnectors = onOpenConnectors, onUseMcpTemplate = onUseMcpTemplate, onSetUseMcp = onSetUseMcp, onCheckMcp = onCheckMcp, telegram = telegram, email = email)
            SettingsPage.Ssh -> SshSettings(state, onBack = back, onProbe = onProbe, onSaveSsh = onSaveSsh, onDeleteSsh = onDeleteSsh)
            SettingsPage.Logins -> SavedLoginsRoute(onBack = back)
            SettingsPage.Network -> NetworkRoute(onBack = back)
            SettingsPage.Battery -> BatteryRoute(onBack = back)
            SettingsPage.Voice -> VoiceRoute(onBack = back)
            SettingsPage.Automation -> AutomationRoute(onBack = back)
            SettingsPage.Phone -> PhoneRoute(onBack = back)
            SettingsPage.Backup -> BackupRoute(onBack = back)
            SettingsPage.About -> AboutSettings(onBack = back, onRunSetup = onRunSetup, onOpenDiagnostics = { pageName = SettingsPage.Diagnostics.name }, onOpenActivity = onOpenActivity)
            SettingsPage.Diagnostics -> DiagnosticsRoute(
                onBack = back,
                onOpenSelfTest = { pageName = SettingsPage.SelfTest.name },
                onOpenStorage = { pageName = SettingsPage.Storage.name },
            )
            SettingsPage.SelfTest -> SelfTestRoute(
                onBack = back,
                onOpenStorage = { pageName = SettingsPage.Storage.name },
                onOpenModels = { pageName = SettingsPage.Models.name },
                onOpenLinux = onOpenLinux,
            )
            SettingsPage.Storage -> StorageRoute(
                onBack = back,
                // The on-device models screen itself, where they're deleted, not the providers page.
                onOpenModels = onOpenOnDeviceModels,
                onOpenLinux = onOpenLinux,
            )
        }
    }
}
