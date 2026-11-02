package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.locus.core.designsystem.Spacing

/** What the pre-send warning can do. */
internal class SendWarningActions(
    val switchAndSend: () -> Unit = {},
    val sendAnyway: () -> Unit = {},
    val dismiss: () -> Unit = {},
)

/**
 * Asked before a message goes to a model known not to take it: switch to the suggested model and send,
 * send anyway, or go back and edit (the text returns to the composer).
 */
@Composable
internal fun SendWarningDialog(warning: SendWarning, onSwitch: () -> Unit, onSendAnyway: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(warning.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(warning.body, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Switching model starts a fresh prompt cache for this chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (warning.suggestionTitle != null) {
                TextButton(onClick = onSwitch) { Text("Switch to ${warning.suggestionTitle}") }
            } else {
                TextButton(onClick = onCancel) { Text("Back") }
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onSendAnyway) { Text("Send anyway") }
                if (warning.suggestionTitle != null) TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
