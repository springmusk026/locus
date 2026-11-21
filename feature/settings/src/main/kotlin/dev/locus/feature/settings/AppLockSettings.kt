package dev.locus.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.lock.AppLock
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.LocusType
import dev.locus.core.model.AppLockStatus
import dev.locus.core.model.DeviceAuthOutcome
import dev.locus.core.model.LockAfter
import dev.locus.core.ui.lockMessage
import dev.locus.core.ui.rememberUnlock
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → About & privacy → Lock (docs/design/app-lock.md). */
@HiltViewModel
class AppLockSettingsViewModel @Inject constructor(val appLock: AppLock) : ViewModel() {
    val status = appLock.status

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { appLock.setEnabled(enabled) }
    }

    fun setLockAfter(minutes: Int) {
        viewModelScope.launch { appLock.setLockAfter(minutes) }
    }

    /** Back from Android's security settings, a screen lock may have been set or removed. */
    fun refresh() = appLock.refreshDeviceSecure()
}

/** What the Lock section shows. */
internal data class AppLockSectionState(
    /** False until the settings are read: the switch waits rather than showing "off" by mistake. */
    val loaded: Boolean = true,
    /** The phone has a PIN, pattern or password. */
    val available: Boolean = true,
    val enabled: Boolean = false,
    val lockAfterMinutes: Int = LockAfter.DEFAULT_MINUTES,
    val lockedChats: Int = 0,
    /** The prompt to turn the lock off is up. */
    val busy: Boolean = false,
    /** Why turning it off didn't work (a lockout), under the switch. */
    val outcome: DeviceAuthOutcome? = null,
) {
    companion object {
        fun of(status: AppLockStatus?, busy: Boolean = false, outcome: DeviceAuthOutcome? = null) = AppLockSectionState(
            loaded = status != null,
            available = status?.available ?: true,
            enabled = status?.enabled == true,
            lockAfterMinutes = LockAfter.normalized(status?.settings?.lockAfterMinutes ?: LockAfter.DEFAULT_MINUTES),
            lockedChats = status?.settings?.lockedChats?.size ?: 0,
            busy = busy,
            outcome = outcome,
        )
    }

    /**
     * The switch can be used: to turn the lock on the phone needs a screen lock; turning it off is
     * always possible (it asks first, or not at all when there's nothing to ask with).
     */
    val switchEnabled: Boolean get() = loaded && !busy && (available || enabled)
}

internal class AppLockSectionActions(
    val onToggle: (Boolean) -> Unit = {},
    val onLockAfter: (Int) -> Unit = {},
    val onOpenSecurity: () -> Unit = {},
)

/**
 * The section's state and actions from [viewModel]. Turning the lock off asks for authentication first,
 * so someone handed an unlocked Locus can't switch it off; with no screen lock left there's nothing to
 * ask with, and it turns off at once.
 */
@Composable
internal fun rememberAppLockSection(viewModel: AppLockSettingsViewModel): Pair<AppLockSectionState, AppLockSectionActions> {
    val status = viewModel.status.collectAsStateWithLifecycle().value
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val context = LocalContext.current
    val unlock = rememberUnlock(viewModel.appLock, stringResource(dev.locus.core.ui.R.string.ui_lock_prompt_confirm)) { viewModel.setEnabled(false) }
    val actions = remember(viewModel, unlock) {
        AppLockSectionActions(
            onToggle = { on ->
                val current = viewModel.status.value
                when {
                    on -> viewModel.setEnabled(true)
                    current != null && current.available -> unlock.launcher.launch()
                    else -> viewModel.setEnabled(false)
                }
            },
            onLockAfter = viewModel::setLockAfter,
            onOpenSecurity = { PhoneSystemPages.security(context) },
        )
    }
    return AppLockSectionState.of(status, busy = unlock.launcher.busy, outcome = unlock.outcome) to actions
}

/** The Lock section of About & privacy: the switch, Lock after, and why it can't be used. */
internal fun LazyListScope.appLockSection(state: AppLockSectionState, actions: AppLockSectionActions) {
    item { SectionLabel(stringResource(R.string.settings_lock_section), SectionPadding) }
    item {
        CardGroup {
            SwitchRow(
                title = stringResource(R.string.settings_lock_title),
                checked = state.enabled,
                onCheckedChange = actions.onToggle,
                subtitle = stringResource(if (state.available) R.string.settings_lock_subtitle else R.string.settings_lock_subtitle_no_screen_lock),
                icon = LocusIcons.Lock,
                enabled = state.switchEnabled,
                busy = state.busy,
                error = lockMessage(state.outcome)?.let { stringResource(it) },
            )
            if (state.enabled && state.available) {
                GroupDivider()
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                    Text(stringResource(R.string.settings_lock_after_title), style = LocusType.rowTitle)
                    SupportingText(stringResource(R.string.settings_lock_after_subtitle), Modifier.padding(top = Spacing.xxs, bottom = Spacing.sm), style = LocusType.rowSupporting)
                    Segmented(
                        options = LockAfter.CHOICES.map { lockAfterLabel(it) },
                        selected = LockAfter.CHOICES.indexOf(state.lockAfterMinutes).coerceAtLeast(0),
                        onSelect = { actions.onLockAfter(LockAfter.CHOICES[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (!state.available && state.loaded) {
        item {
            Banner(
                stringResource(if (state.enabled || state.lockedChats > 0) R.string.settings_lock_suspended else R.string.settings_lock_needs_screen_lock),
                Tone.Caution,
                Modifier.padding(top = Spacing.sm),
                actionsBelow = true,
            ) {
                TextButton(onClick = actions.onOpenSecurity) { Text(stringResource(R.string.settings_lock_open_security)) }
            }
        }
    }
    item { SectionNote(stringResource(R.string.settings_lock_note), NotePadding) }
    if (state.lockedChats > 0) {
        item { SectionNote(pluralStringResource(R.plurals.settings_lock_locked_chats, state.lockedChats, state.lockedChats), NotePadding) }
    }
}

@Composable
private fun lockAfterLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.settings_lock_after_now) else stringResource(R.string.settings_lock_after_minutes, minutes)
