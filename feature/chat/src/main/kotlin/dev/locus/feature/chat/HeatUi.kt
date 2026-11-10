package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.chipRes
import dev.locus.core.model.LocalWarning
import dev.locus.core.model.PauseReason
import dev.locus.core.ondevice.LIGHT_CPU_THREADS
import dev.locus.core.ondevice.LIGHT_MAX_OUTPUT_TOKENS

/** What the heat sheet and the paused chip can do. */
internal class HeatActions(
    val light: () -> Unit = {},
    val sendAnyway: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val runDeferred: () -> Unit = {},
    val stopServices: () -> Unit = {},
    val dismissServices: () -> Unit = {},
)

/** A message held before an on-device reply because the phone is hot or in battery saver; [text] goes back on Not now. */
internal data class HeatHold(val warning: LocalWarning, val text: String) {
    val title: UiText
        get() = when (warning.reason) {
            PauseReason.HOT ->
                if (warning.thermal >= dev.locus.core.model.ThermalLevel.CRITICAL) UiText.Res(R.string.chat_heat_title_very_hot)
                else UiText.Res(R.string.chat_heat_title_hot)
            PauseReason.POWER_SAVE -> UiText.Res(R.string.chat_heat_title_battery_saver)
        }

    /** The lead sentence for the reason, the extra sentence at critical heat, then [lightModeText], one paragraph. */
    val body: List<UiText>
        get() = listOfNotNull(
            when (warning.reason) {
                PauseReason.HOT -> UiText.Res(R.string.chat_heat_body_hot)
                PauseReason.POWER_SAVE -> UiText.Res(R.string.chat_heat_body_battery_saver)
            },
            UiText.Res(R.string.chat_heat_body_critical).takeIf { warning.thermal >= dev.locus.core.model.ThermalLevel.CRITICAL },
            lightModeText(warning.fewerThreads),
        )
}

/** What light mode does, honestly: shorter replies always; fewer threads only on a CPU engine. */
internal fun lightModeText(fewerThreads: Boolean): UiText =
    if (fewerThreads) UiText.Res(R.string.chat_heat_light_cpu, LIGHT_MAX_OUTPUT_TOKENS, LIGHT_CPU_THREADS)
    else UiText.Res(R.string.chat_heat_light, LIGHT_MAX_OUTPUT_TOKENS)

/** Asked before an on-device reply while the phone is hot or in battery saver: light mode, send anyway, or not now. */
@Composable
internal fun HeatHoldSheet(hold: HeatHold, onLight: () -> Unit, onSendAnyway: () -> Unit, onNotNow: () -> Unit) {
    LocusSheet(
        title = hold.title.asString(),
        subtitle = hold.body.map { it.asString() }.joinToString(" "),
        onDismiss = onNotNow,
        action = stringResource(R.string.chat_heat_use_light),
        onAction = onLight,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            TextButton(onClick = onNotNow) { Text(stringResource(R.string.chat_heat_not_now)) }
            OutlinedButton(onClick = onSendAnyway) { Text(stringResource(R.string.chat_heat_send_anyway)) }
        }
        SupportingText(stringResource(R.string.chat_heat_not_now_note))
    }
}

/** "Paused: phone is hot" under the last reply while its side jobs (title, follow-ups, a lesson) wait, with Run anyway. */
@Composable
internal fun PausedChip(reason: PauseReason, onRunAnyway: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        StatusLine(stringResource(reason.chipRes), Tone.Caution, Modifier.weight(1f))
        TextButton(onClick = onRunAnyway) { Text(stringResource(R.string.chat_heat_run_anyway)) }
    }
}
