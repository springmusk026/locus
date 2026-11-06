package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText

/**
 * "Save this login for github.com?", after the user signed in themselves in Locus's browser
 * (docs/design/browser.md, "Saved logins"). The password isn't shown or editable here: it's the one
 * they just typed, held in memory until they answer. They can fix the username before saving.
 * Shown by the full-screen browser and by the chat whose browser it was.
 *
 * Read top to bottom before acting: the question, what saving does, the username that will be saved,
 * where the password goes, and only then the answers. The answers are stacked full width, as the
 * other three-way sheets are (the send warning): Save, then Not now, then Never for this site.
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
    ) {
        OutlinedTextField(
            user, { user = it },
            label = { Text("Username or email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        SupportingText("The password you just typed is kept on this phone, encrypted. Edit or delete it in Settings → Saved logins.")
        Column(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Button(onClick = { onSave(user) }, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
                Text(if (isUpdate) "Update" else "Save", style = LocusType.label, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
                Text("Not now", style = LocusType.label, textAlign = TextAlign.Center)
            }
            TextButton(onClick = onNever, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)) {
                Text("Never for this site", style = LocusType.label, textAlign = TextAlign.Center)
            }
        }
    }
}
