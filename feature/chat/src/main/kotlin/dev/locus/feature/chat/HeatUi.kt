package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.Tone
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
    val title: String
        get() = when (warning.reason) {
            PauseReason.HOT -> if (warning.thermal >= dev.locus.core.model.ThermalLevel.CRITICAL) "The phone is very hot" else "The phone is hot"
            PauseReason.POWER_SAVE -> "Battery saver is on"
        }

    val body: String
        get() = buildString {
            append(
                when (warning.reason) {
                    PauseReason.HOT -> "A reply from the on-device model works the processor hard for a while, so the phone will get hotter and Android will slow it down. "
                    PauseReason.POWER_SAVE -> "A reply from the on-device model uses a lot of battery. "
                },
            )
            if (warning.thermal >= dev.locus.core.model.ThermalLevel.CRITICAL) {
                append("At this temperature Locus stops on-device replies early to let the phone cool; what was written is kept. ")
            }
            append(lightModeText(warning.fewerThreads))
        }
}

/** What light mode does, honestly: shorter replies always; fewer threads only on a CPU engine. */
internal fun lightModeText(fewerThreads: Boolean): String =
    "Light mode keeps replies to about $LIGHT_MAX_OUTPUT_TOKENS tokens" +
        (if (fewerThreads) " and runs the model on $LIGHT_CPU_THREADS processor cores, so it heats the phone more slowly but answers more slowly too." else ", so there is less work to do.") +
        " It turns itself off once the phone is back to normal."

/** Asked before an on-device reply while the phone is hot or in battery saver: light mode, send anyway, or not now. */
@Composable
internal fun HeatHoldSheet(hold: HeatHold, onLight: () -> Unit, onSendAnyway: () -> Unit, onNotNow: () -> Unit) {
    LocusSheet(
        title = hold.title,
        subtitle = hold.body,
        onDismiss = onNotNow,
        action = "Use light mode",
        onAction = onLight,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            TextButton(onClick = onNotNow) { Text("Not now") }
            OutlinedButton(onClick = onSendAnyway) { Text("Send anyway") }
        }
        Text(
            "Not now keeps your message in the box, to send when the phone has cooled down.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Paused: phone is hot" under the last reply while its side jobs (title, follow-ups, a lesson) wait, with Run anyway. */
@Composable
internal fun PausedChip(reason: PauseReason, onRunAnyway: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        StatusLine(reason.chip, Tone.Caution, Modifier.weight(1f))
        TextButton(onClick = onRunAnyway) { Text("Run anyway") }
    }
}
