package dev.locus.feature.chat

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone

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
    // Same three ways out as before: switch and send (when there's a model to suggest), send anyway, or
    // go back. With no suggestion, Send anyway is the confirm button and Back the other.
    val suggestion = warning.suggestionTitle
    ConfirmSheet(
        title = warning.title,
        message = warning.body,
        confirmLabel = if (suggestion != null) "Switch to $suggestion" else "Send anyway",
        onConfirm = if (suggestion != null) onSwitch else onSendAnyway,
        onDismiss = onCancel,
        dismissLabel = if (suggestion != null) "Cancel" else "Back",
        tone = Tone.Caution,
    ) {
        SupportingText("Switching model starts a fresh prompt cache for this chat.")
        if (suggestion != null) TextButton(onClick = onSendAnyway) { Text("Send anyway") }
    }
}
