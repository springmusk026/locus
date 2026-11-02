package dev.locus.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.llm.DeviceConditions
import dev.locus.core.model.DeviceState
import dev.locus.core.model.PowerPolicy
import dev.locus.core.model.PowerSettings
import dev.locus.core.model.TaskConditions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → Battery & heat: the phone's conditions now, and what Locus does about them. */
data class BatteryUiState(val now: DeviceState = DeviceState(), val power: PowerSettings = PowerSettings())

@HiltViewModel
class BatteryViewModel @Inject constructor(
    private val settings: SettingsRepository,
    conditions: DeviceConditions,
) : ViewModel() {
    val state: StateFlow<BatteryUiState> = combine(conditions.state, settings.settings) { now, s -> BatteryUiState(now, s.power) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BatteryUiState(conditions.state.value))

    fun update(transform: (PowerSettings) -> PowerSettings) = viewModelScope.launch { settings.setPower(transform) }
}

@Composable
internal fun BatteryRoute(onBack: () -> Unit, viewModel: BatteryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BatterySettings(state, onBack, viewModel::update)
}

@Composable
internal fun BatterySettings(state: BatteryUiState, onBack: () -> Unit, onChange: (transform: (PowerSettings) -> PowerSettings) -> Unit) {
    val power = state.power
    val tasks = power.taskDefaults
    fun setTasks(c: TaskConditions) = onChange { it.copy(taskDefaults = c) }

    SettingsPageScaffold("Battery & heat", onBack) {
        item {
            CardGroup {
                ListRow(title = "Right now", subtitle = PowerPolicy.describe(state.now), subtitleMaxLines = 2, leading = { IconTile(LocusIcons.Gauge) })
            }
        }
        item { SectionLabel("When the phone is hot or in battery saver", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    "Pause background extras",
                    "Chat titles, follow-up suggestions, saving lessons and the model catalogue refresh wait, and scheduled tasks wait while it's hot. A chat shows “Paused” with Run anyway.",
                    LocusIcons.Clock, power.deferWhenHot,
                ) { on -> onChange { it.copy(deferWhenHot = on) } }
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    "Ask before on-device replies",
                    "Offers light mode (shorter replies, fewer processor cores on CPU) or waiting until the phone cools.",
                    LocusIcons.Phone, power.warnOnDevice,
                ) { on -> onChange { it.copy(warnOnDevice = on) } }
            }
        }
        item {
            SectionNote(
                "“Hot” is Android's severe thermal status, when it is already slowing the phone down. At critical, Locus stops an on-device reply " +
                    "early and keeps what it wrote, and the Linux workspace suggests stopping running services; nothing is stopped without telling you. " +
                    "An unused on-device model is unloaded after 10 minutes, after 2 in battery saver or while hot, and within a minute when memory is short. " +
                    "A task you are watching is never paused.",
                NotePadding,
            )
        }
        item { SectionLabel("New scheduled tasks run only", SectionPadding) }
        item {
            CardGroup {
                SwitchRow("When the battery isn't low", "Skips the time Android shows its low-battery warning.", LocusIcons.Gauge, tasks.batteryNotLow) {
                    setTasks(tasks.copy(batteryNotLow = it))
                }
                GroupDivider(inset = Insets.iconRow)
                SwitchRow("When charging", "Waits until the phone is plugged in.", LocusIcons.Plug, tasks.requiresCharging) {
                    setTasks(tasks.copy(requiresCharging = it))
                }
                GroupDivider(inset = Insets.iconRow)
                SwitchRow("On Wi-Fi", "Not on mobile data or another metered network.", LocusIcons.Globe, tasks.unmeteredOnly) {
                    setTasks(tasks.copy(unmeteredOnly = it))
                }
            }
        }
        item { SectionNote("Each task can change these under Scheduled tasks → Conditions. Run now ignores them.", NotePadding) }
        item { SectionLabel("Downloads", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    "Download models on Wi-Fi only",
                    "On-device models are 2.6–3.7 GB. A download on mobile data waits, with Use mobile data to go ahead.",
                    LocusIcons.Import, power.downloadsUnmeteredOnly,
                ) { on -> onChange { it.copy(downloadsUnmeteredOnly = on) } }
            }
        }
    }
}
