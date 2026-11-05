package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.llm.DeviceConditions
import dev.locus.core.model.DeviceState
import dev.locus.core.model.NetworkKind
import dev.locus.core.model.PowerSettings
import dev.locus.core.model.TaskConditions
import dev.locus.core.model.ThermalLevel
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

/** Heat, battery saver and low memory, each as a chip in its tone; empty when none applies. */
internal fun conditionAlerts(now: DeviceState): List<Pair<String, Tone>> = listOfNotNull(
    now.thermal.label.takeIf { now.hot }?.let { it to if (now.critical) Tone.Critical else Tone.Caution },
    ("Battery saver" to Tone.Caution).takeIf { now.powerSave },
    ("Memory low" to Tone.Caution).takeIf { now.lowMemory },
)

/** What's fine or merely a fact: the temperature when it isn't hot, the battery, the network. */
internal fun calmConditions(now: DeviceState): String = listOfNotNull(
    now.thermal.label.takeIf { !now.hot },
    now.batteryPercent?.let { "Battery $it%" + if (now.charging) ", charging" else "" },
    when (now.network) {
        NetworkKind.NONE -> "Offline"
        NetworkKind.METERED -> "Metered network"
        NetworkKind.UNMETERED -> "Wi-Fi"
    },
).joinToString(" · ")

@Composable
internal fun BatterySettings(state: BatteryUiState, onBack: () -> Unit, onChange: (transform: (PowerSettings) -> PowerSettings) -> Unit) {
    val power = state.power
    val tasks = power.taskDefaults
    fun setTasks(c: TaskConditions) = onChange { it.copy(taskDefaults = c) }

    SettingsPageScaffold("Battery & heat", onBack) {
        item {
            CardGroup {
                val alerts = conditionAlerts(state.now)
                ListRow(
                    title = "Right now",
                    subtitle = calmConditions(state.now),
                    subtitleMaxLines = Int.MAX_VALUE,
                    leading = { IconTile(LocusIcons.Gauge) },
                    // What's holding work back gets a toned chip, so heat and low memory aren't one more word in a list.
                    meta = if (alerts.isEmpty()) null else ({
                        FlowRow(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            alerts.forEach { (text, tone) -> LocusChip(text, tone = tone) }
                        }
                    }),
                )
            }
        }
        item { SectionLabel("When the phone is hot or in battery saver", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = "Pause extras",
                    checked = power.deferWhenHot,
                    onCheckedChange = { on -> onChange { it.copy(deferWhenHot = on) } },
                    subtitle = "Titles, suggestions, lessons and the model catalogue wait; scheduled tasks wait while it's hot. A chat shows Run anyway",
                    icon = LocusIcons.Clock,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "Ask before on-device replies",
                    checked = power.warnOnDevice,
                    onCheckedChange = { on -> onChange { it.copy(warnOnDevice = on) } },
                    subtitle = "Offers light mode (shorter replies, fewer processor cores) or waiting until the phone cools",
                    icon = LocusIcons.Phone,
                )
            }
        }
        item {
            SectionNote(
                "“Hot” means Android is already slowing the phone down. Hotter still, Locus ends an on-device reply early and keeps what it wrote. " +
                    "An unused on-device model is unloaded sooner when it's hot or memory is short. A task you are watching is never paused.",
                NotePadding,
            )
        }
        item { SectionLabel("New scheduled tasks run only", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = "When the battery isn't low",
                    checked = tasks.batteryNotLow,
                    onCheckedChange = { setTasks(tasks.copy(batteryNotLow = it)) },
                    subtitle = "Skips the time Android shows its low-battery warning.",
                    icon = LocusIcons.Gauge,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "When charging",
                    checked = tasks.requiresCharging,
                    onCheckedChange = { setTasks(tasks.copy(requiresCharging = it)) },
                    subtitle = "Waits until the phone is plugged in.",
                    icon = LocusIcons.Plug,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "On Wi-Fi",
                    checked = tasks.unmeteredOnly,
                    onCheckedChange = { setTasks(tasks.copy(unmeteredOnly = it)) },
                    subtitle = "Not on mobile data or another metered network.",
                    icon = LocusIcons.Globe,
                )
            }
        }
        item { SectionNote("Each task can change these under Scheduled tasks → Conditions. Run now ignores them.", NotePadding) }
        item { SectionLabel("Downloads", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = "Download models on Wi-Fi only",
                    checked = power.downloadsUnmeteredOnly,
                    onCheckedChange = { on -> onChange { it.copy(downloadsUnmeteredOnly = on) } },
                    subtitle = "On-device models are 2.6–3.7 GB. A download on mobile data waits, with Use mobile data to go ahead.",
                    icon = LocusIcons.Import,
                )
            }
        }
    }
}
