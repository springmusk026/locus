package dev.locus.feature.settings

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.automation.AutomationContract
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One app the user answered for: [allowed] always (true) or never (false). */
data class AutomationApp(val packageName: String, val label: String, val allowed: Boolean)

data class AutomationUiState(val enabled: Boolean = false, val apps: List<AutomationApp> = emptyList(), val permission: String = AutomationContract.PERMISSION)

/** Settings → Automation: whether other apps may start Locus tasks, and the apps the user answered for (docs/design/android-bridge.md). */
@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    val state: StateFlow<AutomationUiState> = settings.settings.map { s ->
        AutomationUiState(
            enabled = s.automationEnabled,
            apps = s.automationCallers.map { (pkg, allowed) -> AutomationApp(pkg, labelOf(pkg), allowed) }.sortedBy { it.label.lowercase() },
            permission = AutomationContract.permission(context.packageName),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationUiState())

    private fun labelOf(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.setAutomationEnabled(on) }

    /** Forgets the answer: the app is asked about again next time. */
    fun forget(pkg: String) = viewModelScope.launch { settings.setAutomationCaller(pkg, null) }
}

@Composable
internal fun AutomationRoute(onBack: () -> Unit, viewModel: AutomationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AutomationSettings(state, onBack, viewModel::setEnabled, viewModel::forget)
}

@Composable
internal fun AutomationSettings(state: AutomationUiState, onBack: () -> Unit, onSetEnabled: (Boolean) -> Unit, onForget: (String) -> Unit) {
    SettingsPageScaffold("Automation", onBack) {
        item {
            CardGroup {
                SwitchRow(
                    "Let other apps start tasks",
                    "Tasker, Automate and similar apps can ask Locus to start a chat, run a workflow or run a scheduled task now.",
                    LocusIcons.Play, state.enabled,
                ) { onSetEnabled(it) }
            }
        }
        item {
            SectionNote(
                "Each request is shown to you first unless Android names the app and you chose Always allow, or granted it " +
                    "“start Locus tasks” in its App info → Permissions. What an app sends is treated as untrusted: anything that would " +
                    "send or change something outside the phone asks you, as in any chat. Apps use the intents in docs/design/android-bridge.md; " +
                    "the permission is ${state.permission}.",
                NotePadding,
            )
        }
        if (state.apps.isNotEmpty()) {
            item { SectionLabel("Apps you answered for", SectionPadding) }
            item {
                CardGroup {
                    state.apps.forEachIndexed { i, app ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        ListRow(
                            title = app.label,
                            subtitle = (if (app.allowed) "Always allowed" else "Never allowed") + " · " + app.packageName,
                            leading = { IconTile(if (app.allowed) LocusIcons.Check else LocusIcons.Close) },
                            trailing = { TextButton(onClick = { onForget(app.packageName) }) { Text("Forget", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        )
                    }
                }
            }
        }
    }
}
