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
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.SshHost
import dev.locus.core.model.ToolPolicy

/**
 * Settings: a hub of grouped rows, each opening a focused sub-screen. Sub-navigation lives here
 * (not in the app's back stack) so system back returns to the hub before leaving Settings.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenConnectors: () -> Unit,
    onOpenAgentSetup: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenConnectors = onOpenConnectors,
        onOpenAgentSetup = onOpenAgentSetup,
        onRunSetup = onRunSetup,
        onSaveProvider = viewModel::saveProvider,
        onDeleteProvider = viewModel::deleteProvider,
        onSetDefault = viewModel::setDefault,
        onSetPolicy = viewModel::setPolicy,
        onSaveInstructions = viewModel::saveInstructions,
        onSetReflect = { viewModel.setReflectAfterTasks(it) },
        onSetHelperReports = { viewModel.setSubagentReportsContinue(it) },
        onSetHelper = viewModel::setHelperModel,
        onSetAutoTitle = { viewModel.setAutoTitle(it) },
        onSetModelCatalog = { viewModel.setModelCatalog(it) },
        onSetSuggest = { viewModel.setSuggestFollowUps(it) },
        onSetReviewerMode = { viewModel.setReviewerMode(it) },
        onSetReviewerOutward = { viewModel.setReviewerApprovesOutward(it) },
        onProbe = viewModel::probeFingerprint,
        onSaveSsh = viewModel::saveSshHost,
        onDeleteSsh = viewModel::deleteSshHost,
        onSetIntegrationKey = viewModel::setIntegrationKey,
        onListModels = viewModel::listModels,
        chatGpt = ChatGptActions(viewModel::signInChatGpt, viewModel::signOutChatGpt, { viewModel.discardChatGpt(it) }),
        onModelAction = { id, action ->
            when (action) {
                "download" -> viewModel.downloadModel(id)
                "cancel" -> viewModel.cancelModel(id)
                else -> viewModel.deleteModel(id)
            }
        },
    )
}

/** The pages inside Settings. */
internal enum class SettingsPage { Hub, Models, Extras, Agent, Permissions, Integrations, Ssh, About }

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenConnectors: () -> Unit,
    onSaveProvider: (ProviderConfig, String?) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSetDefault: (String) -> Unit,
    onSetPolicy: (ToolRow, ToolPolicy) -> Unit,
    onSaveInstructions: (String) -> Unit,
    onSetReflect: (Boolean) -> Unit = {},
    onSetHelperReports: (Boolean) -> Unit = {},
    onSetHelper: (String?, String) -> Unit = { _, _ -> },
    onSetAutoTitle: (Boolean) -> Unit = {},
    onSetModelCatalog: (Boolean) -> Unit = {},
    onSetSuggest: (Boolean) -> Unit = {},
    onSetReviewerMode: (ReviewerMode) -> Unit = {},
    onSetReviewerOutward: (Boolean) -> Unit = {},
    onProbe: (String, Int, (Result<String>) -> Unit) -> Unit,
    onSaveSsh: (SshHost, String?, String?, String?) -> Unit,
    onDeleteSsh: (String) -> Unit,
    onModelAction: (String, String) -> Unit,
    onSetIntegrationKey: (Integration, String?) -> Unit,
    onListModels: (ProviderConfig, String?, (Result<List<dev.locus.core.llm.RemoteModel>>) -> Unit) -> Unit = { _, _, _ -> },
    onOpenAgentSetup: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    chatGpt: ChatGptActions = ChatGptActions.None,
) {
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.Hub.name) }
    val page = SettingsPage.valueOf(pageName)
    val toHub = { pageName = SettingsPage.Hub.name }
    BackHandler(enabled = page != SettingsPage.Hub, onBack = toHub)

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
                state, onBack = toHub,
                onSaveProvider = onSaveProvider, onDeleteProvider = onDeleteProvider, onSetDefault = onSetDefault,
                onModelAction = onModelAction, onListModels = onListModels, chatGpt = chatGpt,
                onSetModelCatalog = onSetModelCatalog,
            )
            SettingsPage.Extras -> ExtrasSettings(
                state, onBack = toHub,
                onSetHelper = onSetHelper, onSetAutoTitle = onSetAutoTitle, onSetSuggest = onSetSuggest, onSetReflect = onSetReflect, onSetHelperReports = onSetHelperReports,
                onListModels = onListModels,
            )
            SettingsPage.Agent -> AgentSettings(state, onBack = toHub, onSaveInstructions = onSaveInstructions, onOpenAgentSetup = onOpenAgentSetup)
            SettingsPage.Permissions -> PermissionsSettings(
                state, onBack = toHub, onSetPolicy = onSetPolicy,
                onSetReviewerMode = onSetReviewerMode, onSetReviewerOutward = onSetReviewerOutward,
            )
            SettingsPage.Integrations -> IntegrationsSettings(state, onBack = toHub, onSetIntegrationKey = onSetIntegrationKey, onOpenConnectors = onOpenConnectors)
            SettingsPage.Ssh -> SshSettings(state, onBack = toHub, onProbe = onProbe, onSaveSsh = onSaveSsh, onDeleteSsh = onDeleteSsh)
            SettingsPage.About -> AboutSettings(onBack = toHub, onRunSetup = onRunSetup)
        }
    }
}
