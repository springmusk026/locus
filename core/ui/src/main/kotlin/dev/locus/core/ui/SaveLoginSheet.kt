package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText

/**
 * "Save this login for github.com?", after the user signed in themselves in Locus's browser
 * (docs/design/browser.md, "Saved logins"). The password isn't shown or editable here: it's the one
 * they just typed, held in memory until they answer. They can fix the username before saving.
 * Shown by the full-screen browser and by the chat whose browser it was.
 */
@Composable
fun SaveLoginSheet(
    host: String,
    username: String,
    /** A login for this site is already saved: saving replaces it. */
    isUpdate: Boolean,
    onSave: (username: String) -> Unit,
    onNever: () -> Unit,
    onDismiss: () -> Unit,
) {
    var user by rememberSaveable(host) { mutableStateOf(username) }
    LocusSheet(
        title = if (isUpdate) "Update the saved login for $host?" else "Save this login for $host?",
        subtitle = "Locus can then sign in to $host for you, asking you first. The AI never sees the password.",
        onDismiss = onDismiss,
        action = if (isUpdate) "Update" else "Save",
        onAction = { onSave(user) },
    ) {
        OutlinedTextField(
            user, { user = it },
            label = { Text("Username or email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        SupportingText("The password you just typed is kept on this phone, encrypted. Edit or delete it in Settings → Saved logins.")
        // Wraps onto two lines at a large font instead of squeezing a button's label into a column.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, androidx.compose.ui.Alignment.End)) {
            TextButton(onClick = onNever) { Text("Never for this site") }
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}
