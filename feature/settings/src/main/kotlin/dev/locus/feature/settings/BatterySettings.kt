package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
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
import dev.locus.core.designsystem.labelRes
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
@Composable
internal fun conditionAlerts(now: DeviceState): List<Pair<String, Tone>> = listOfNotNull(
    stringResource(now.thermal.labelRes).takeIf { now.hot }?.let { it to if (now.critical) Tone.Critical else Tone.Caution },
    (stringResource(R.string.settings_battery_alert_saver) to Tone.Caution).takeIf { now.powerSave },
    (stringResource(R.string.settings_battery_alert_memory) to Tone.Caution).takeIf { now.lowMemory },
)

/** What's fine or merely a fact: the temperature when it isn't hot, the battery, the network. */
@Composable
internal fun calmConditions(now: DeviceState): String = summaryJoin(
    listOfNotNull(
        stringResource(now.thermal.labelRes).takeIf { !now.hot },
        now.batteryPercent?.let { stringResource(if (now.charging) R.string.settings_battery_level_charging else R.string.settings_battery_level, it) },
        stringResource(
            when (now.network) {
                NetworkKind.NONE -> R.string.settings_battery_network_offline
                NetworkKind.METERED -> R.string.settings_battery_network_metered
                NetworkKind.UNMETERED -> R.string.settings_battery_network_wifi
            },
        ),
    ),
)

@Composable
internal fun BatterySettings(state: BatteryUiState, onBack: () -> Unit, onChange: (transform: (PowerSettings) -> PowerSettings) -> Unit) {
    val power = state.power
    val tasks = power.taskDefaults
    fun setTasks(c: TaskConditions) = onChange { it.copy(taskDefaults = c) }

    SettingsPageScaffold(stringResource(R.string.settings_battery_title), onBack) {
        item {
            CardGroup {
                val alerts = conditionAlerts(state.now)
                ListRow(
                    title = stringResource(R.string.settings_battery_now_title),
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
        item { SectionLabel(stringResource(R.string.settings_battery_hot_section), SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_battery_pause_title),
                    checked = power.deferWhenHot,
                    onCheckedChange = { on -> onChange { it.copy(deferWhenHot = on) } },
                    subtitle = stringResource(R.string.settings_battery_pause_subtitle),
                    icon = LocusIcons.Clock,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = stringResource(R.string.settings_battery_ask_title),
                    checked = power.warnOnDevice,
                    onCheckedChange = { on -> onChange { it.copy(warnOnDevice = on) } },
                    subtitle = stringResource(R.string.settings_battery_ask_subtitle),
                    icon = LocusIcons.Phone,
                )
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_battery_hot_note),
                NotePadding,
            )
        }
        item { SectionLabel(stringResource(R.string.settings_battery_tasks_section), SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_battery_not_low_title),
                    checked = tasks.batteryNotLow,
                    onCheckedChange = { setTasks(tasks.copy(batteryNotLow = it)) },
                    subtitle = stringResource(R.string.settings_battery_not_low_subtitle),
                    icon = LocusIcons.Gauge,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = stringResource(R.string.settings_battery_charging_title),
                    checked = tasks.requiresCharging,
                    onCheckedChange = { setTasks(tasks.copy(requiresCharging = it)) },
                    subtitle = stringResource(R.string.settings_battery_charging_subtitle),
                    icon = LocusIcons.Plug,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = stringResource(R.string.settings_battery_wifi_title),
                    checked = tasks.unmeteredOnly,
                    onCheckedChange = { setTasks(tasks.copy(unmeteredOnly = it)) },
                    subtitle = stringResource(R.string.settings_battery_wifi_subtitle),
                    icon = LocusIcons.Globe,
                )
            }
        }
        item { SectionNote(stringResource(R.string.settings_battery_tasks_note), NotePadding) }
        item { SectionLabel(stringResource(R.string.settings_battery_downloads_section), SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_battery_downloads_title),
                    checked = power.downloadsUnmeteredOnly,
                    onCheckedChange = { on -> onChange { it.copy(downloadsUnmeteredOnly = on) } },
                    subtitle = stringResource(R.string.settings_battery_downloads_subtitle),
                    icon = LocusIcons.Import,
                )
            }
        }
    }
}
