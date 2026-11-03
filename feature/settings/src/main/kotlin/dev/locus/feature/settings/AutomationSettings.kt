package dev.locus.feature.settings

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import dev.locus.core.designsystem.SwitchRow
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
    val context = LocalContext.current
    // Re-read on resume: the user picks the assistant in Android's settings and comes back.
    var assistant by remember { mutableStateOf(DefaultAssistant.isLocus(context)) }
    LifecycleResumeEffect(Unit) {
        assistant = DefaultAssistant.isLocus(context)
        onPauseOrDispose { }
    }
    AutomationSettings(state, onBack, viewModel::setEnabled, viewModel::forget, assistant) { DefaultAssistant.openSettings(context) }
}

/**
 * Locus as Android's default digital assistant (docs/design/android-bridge.md). The role isn't
 * requestable by apps (`requestable="false"` in AOSP's roles.xml), so Locus can only open the settings
 * page where the user picks it; an exported ACTION_ASSIST activity (the app's AssistLocus alias) is what
 * makes Locus eligible.
 */
internal object DefaultAssistant {
    /** True when Locus holds the assistant role; null where Android can't say (below Android 10). */
    fun isLocus(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        return runCatching { roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrNull()
    }

    /** Android's Default apps page, else Assist & voice input, else Settings. */
    fun openSettings(context: Context) {
        val tries = listOf(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_SETTINGS)
        for (action in tries) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
    }
}

@Composable
internal fun AutomationSettings(
    state: AutomationUiState,
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onForget: (String) -> Unit,
    assistant: Boolean? = null,
    onOpenAssistant: () -> Unit = {},
) {
    SettingsPageScaffold("Automation", onBack) {
        item {
            CardGroup {
                SwitchRow(
                    title = "Let other apps start tasks",
                    checked = state.enabled,
                    onCheckedChange = { onSetEnabled(it) },
                    subtitle = "Tasker, Automate and similar apps can ask Locus to start a chat, run a workflow or run a scheduled task now.",
                    icon = LocusIcons.Play,
                )
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
        item { SectionLabel("Assistant", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Default digital assistant",
                    subtitle = when (assistant) {
                        true -> "Locus. The assist gesture opens the ask bar."
                        false -> "Pick Locus in Android's Default apps to open the ask bar with the assist gesture."
                        null -> "Pick Locus in Android's settings to open the ask bar with the assist gesture."
                    },
                    subtitleMaxLines = 3,
                    onClick = onOpenAssistant,
                    leading = { IconTile(LocusIcons.Mic) },
                    trailing = { TextButton(onClick = onOpenAssistant) { Text("Change", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                )
            }
        }
        item {
            SectionNote(
                "Android's assist gesture (a long press of home, a swipe from a bottom corner, or holding the power button where " +
                    "that is set to the assistant) then opens Locus's ask bar. Locus doesn't see the screen you were on.",
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
