package dev.locus.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.designsystem.Segmented
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost

/** The login methods an SSH machine can use, in the order the editors show them. */
val SSH_AUTH_OPTIONS: List<Pair<SshAuth, String>> = listOf(SshAuth.KEY to "Private key", SshAuth.PASSWORD to "Password", SshAuth.NONE to "None")

/**
 * How an SSH machine logs in: private key, password or none. The key and the password are separate
 * fields, so text typed for one never shows up in (or gets saved as) the other.
 *
 * [needsSecret] is [sshNeedsSecret] for this editor: when false, a key or password is already saved and
 * a blank field keeps it. [secretNote] is shown under the key and password fields.
 */
@Composable
fun SshAuthFields(
    method: SshAuth,
    onMethod: (SshAuth) -> Unit,
    privateKey: String,
    onPrivateKey: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    passphrase: String,
    onPassphrase: (String) -> Unit,
    needsSecret: Boolean,
    secretNote: String? = null,
) {
    Segmented(
        SSH_AUTH_OPTIONS.map { it.second },
        selected = SSH_AUTH_OPTIONS.indexOfFirst { it.first == method },
        onSelect = { onMethod(SSH_AUTH_OPTIONS[it].first) },
        modifier = Modifier.fillMaxWidth(),
    )
    val note: (@Composable () -> Unit)? = secretNote?.let { { Text(it) } }
    when (method) {
        SshAuth.NONE -> {
            Text("No authentication", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Locus sends no key or password, and answers any login prompt with an empty reply. Only for throwaway or " +
                    "sandbox machines: anyone who can reach this machine can log in the same way. The host key is still checked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SshAuth.KEY -> {
            OutlinedTextField(
                privateKey, onPrivateKey,
                label = { Text(if (needsSecret) "Paste private key (OpenSSH format)" else "Private key (saved — paste to replace)") },
                supportingText = note,
                minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                passphrase, onPassphrase, label = { Text("Key passphrase (if any)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
        }
        SshAuth.PASSWORD -> OutlinedTextField(
            password, onPassword,
            label = { Text(if (needsSecret) "Password" else "Password (saved — type to replace)") },
            supportingText = note,
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "user@host:port", plus a warning when the machine logs in without authentication. */
fun sshHostSubtitle(host: SshHost): String =
    "${host.user}@${host.host}:${host.port}" + if (host.authMethod == SshAuth.NONE) " · no authentication" else ""

/**
 * Whether the editor must ask for a new key or password before saving: always for a new machine, and when
 * switching method, since the vault may hold nothing for the new one. "No authentication" never needs one.
 */
fun sshNeedsSecret(method: SshAuth, saved: SshAuth, isNew: Boolean): Boolean =
    method != SshAuth.NONE && (isNew || method != saved)

/** The text typed for [method]'s own field: the key for KEY, the password for PASSWORD, nothing for NONE. */
fun sshSecretFor(method: SshAuth, privateKey: String, password: String): String = when (method) {
    SshAuth.KEY -> privateKey
    SshAuth.PASSWORD -> password
    SshAuth.NONE -> ""
}

/** Whether the login part of the form is complete: a needed key or password was typed. */
fun sshSecretReady(method: SshAuth, saved: SshAuth, isNew: Boolean, privateKey: String, password: String): Boolean =
    !sshNeedsSecret(method, saved, isNew) || sshSecretFor(method, privateKey, password).isNotBlank()

/** What an SSH editor hands to the repository. Vault arguments follow its rule: null keeps, empty removes. */
data class SshEditorSave(val host: SshHost, val password: String?, val privateKey: String?, val passphrase: String?)

/**
 * The save for [host] (already carrying its chosen method) from the editor's fields. Only the field of the
 * chosen method counts: a password typed before switching to a key is never saved as the key, or the other way.
 */
fun sshEditorSave(host: SshHost, privateKey: String, password: String, passphrase: String): SshEditorSave = when (host.authMethod) {
    // Nothing is sent to a no-auth machine, so nothing for it stays in the vault.
    SshAuth.NONE -> SshEditorSave(host, password = "", privateKey = "", passphrase = "")
    SshAuth.PASSWORD -> SshEditorSave(host, password = password.ifBlank { null }, privateKey = null, passphrase = null)
    // The passphrase belongs to the key: replace it only along with a newly pasted key.
    SshAuth.KEY -> if (privateKey.isBlank()) SshEditorSave(host, null, null, null) else SshEditorSave(host, null, privateKey, passphrase)
}
