package dev.locus.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost

/** The login methods an SSH machine can use, in the order the editors show them. */
val SSH_AUTH_OPTIONS: List<Pair<SshAuth, Int>> = listOf(
    SshAuth.KEY to R.string.ui_ssh_auth_key,
    SshAuth.PASSWORD to R.string.ui_ssh_auth_password,
    SshAuth.NONE to DesignR.string.common_none,
)

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
        SSH_AUTH_OPTIONS.map { stringResource(it.second) },
        selected = SSH_AUTH_OPTIONS.indexOfFirst { it.first == method },
        onSelect = { onMethod(SSH_AUTH_OPTIONS[it].first) },
        modifier = Modifier.fillMaxWidth(),
    )
    val note: (@Composable () -> Unit)? = secretNote?.let { { Text(it) } }
    when (method) {
        SshAuth.NONE -> {
            Text(stringResource(R.string.ui_ssh_auth_none_title), style = LocusType.rowSupporting)
            SupportingText(stringResource(R.string.ui_ssh_auth_none_warning))
        }
        SshAuth.KEY -> {
            OutlinedTextField(
                privateKey, onPrivateKey,
                label = { Text(stringResource(if (needsSecret) R.string.ui_ssh_key_paste else R.string.ui_ssh_key_saved)) },
                supportingText = note,
                minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                passphrase, onPassphrase, label = { Text(stringResource(R.string.ui_ssh_key_passphrase)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
        }
        SshAuth.PASSWORD -> OutlinedTextField(
            password, onPassword,
            label = { Text(stringResource(if (needsSecret) R.string.ui_ssh_auth_password else R.string.ui_ssh_password_saved)) },
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
