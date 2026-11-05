package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText

/** What the pre-send warning can do. */
internal class SendWarningActions(
    val switchAndSend: () -> Unit = {},
    val sendAnyway: () -> Unit = {},
    val dismiss: () -> Unit = {},
)

/**
 * Asked before a message goes to a model known not to take it. The ways out, most useful first, each a
 * full-width button so no label wraps inside its pill at any font: switch to the suggested model and
 * send (when there is one), choose another model in the model sheet, or send anyway. Closing the sheet
 * goes back to edit; the text returns to the composer.
 */
@Composable
internal fun SendWarningDialog(
    warning: SendWarning,
    onSwitch: () -> Unit,
    onSendAnyway: () -> Unit,
    onCancel: () -> Unit,
    onChooseModel: () -> Unit = onCancel,
) {
    val suggestion = warning.suggestionTitle
    LocusSheet(title = warning.title, onDismiss = onCancel) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(warning.body, style = LocusType.rowSupporting)
            if (suggestion != null) SupportingText("Switching model starts a fresh prompt cache for this chat.")
            Column(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (suggestion != null) {
                    Button(onClick = onSwitch, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
                        Text("Switch to $suggestion and send", style = LocusType.label, textAlign = TextAlign.Center)
                    }
                    OutlinedButton(onClick = onChooseModel, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
                        Text("Choose another model", style = LocusType.label, textAlign = TextAlign.Center)
                    }
                } else {
                    Button(onClick = onChooseModel, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
                        Text("Choose model", style = LocusType.label, textAlign = TextAlign.Center)
                    }
                }
                TextButton(onClick = onSendAnyway, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
                    Text("Send anyway", style = LocusType.label, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
