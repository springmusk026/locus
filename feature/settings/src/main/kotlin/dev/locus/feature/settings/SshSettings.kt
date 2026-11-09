package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CriticalOutlinedButton
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.model.SettingsDestination
import androidx.compose.foundation.layout.padding
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.model.SshHost
import dev.locus.core.ui.SshAuthFields
import dev.locus.core.ui.sshEditorSave
import dev.locus.core.ui.sshHostSubtitle
import dev.locus.core.ui.sshNeedsSecret
import dev.locus.core.ui.sshSecretReady
import java.util.UUID
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.RowChevron

/** Machines Locus can reach over SSH, each pinned to its host key. */
@Composable
internal fun SshSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onProbe: (String, Int, (Result<String>) -> Unit) -> Unit,
    onSaveSsh: (SshHost, String?, String?, String?) -> Unit,
    onDeleteSsh: (String) -> Unit,
) {
    var editingSsh by remember { mutableStateOf<SshHost?>(null) }

    SettingsPageScaffold(stringResource(R.string.settings_ssh_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_ssh_section), SectionPadding) }
        item {
            CardGroup {
                state.sshHosts.forEachIndexed { i, host ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = host.alias,
                        subtitle = sshHostSubtitle(host),
                        onClick = { editingSsh = host },
                        leading = { IconTile(LocusIcons.Server) },
                        trailing = { RowChevron() },
                    )
                }
                if (state.sshHosts.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
                AddRow(stringResource(R.string.settings_ssh_add)) { editingSsh = SshHost(UUID.randomUUID().toString(), "", "", 22, "") }
            }
        }
        item { SectionNote(stringResource(R.string.settings_ssh_note), NotePadding) }
        // The link's text lines up with the note above (its button has an xs inset of its own).
        item { SettingsLink(SettingsDestination.Permissions, Modifier.padding(start = Spacing.md)) }
    }

    editingSsh?.let { host ->
        SshHostEditor(
            initial = host,
            isNew = state.sshHosts.none { it.id == host.id },
            onProbe = onProbe,
            onSave = { h, pw, key, pass -> onSaveSsh(h, pw, key, pass); editingSsh = null },
            onDelete = { onDeleteSsh(host.id); editingSsh = null },
            onDismiss = { editingSsh = null },
        )
    }
}

@Composable
private fun SshHostEditor(
    initial: SshHost,
    isNew: Boolean,
    onProbe: (String, Int, (Result<String>) -> Unit) -> Unit,
    onSave: (SshHost, String?, String?, String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var alias by rememberSaveable { mutableStateOf(initial.alias) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var user by rememberSaveable { mutableStateOf(initial.user) }
    var method by rememberSaveable { mutableStateOf(initial.authMethod) }
    // One field per method, so a password typed before switching to a key doesn't carry over.
    var privateKey by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var fingerprint by rememberSaveable { mutableStateOf(initial.fingerprint) }
    var checking by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val hostChanged = host != initial.host || port != initial.port.toString()
    val localNetwork = rememberLocalNetworkGate()

    LocusSheet(
        title = if (isNew) stringResource(R.string.settings_ssh_editor_title) else initial.alias,
        subtitle = stringResource(R.string.settings_ssh_editor_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_ssh_trust_and_save),
        actionEnabled = alias.isNotBlank() && host.isNotBlank() && user.isNotBlank() && fingerprint.isNotBlank() &&
            sshSecretReady(method, initial.authMethod, isNew, privateKey, password),
        onAction = {
            val saved = initial.copy(alias = alias.trim(), host = host, port = port.toIntOrNull() ?: 22, user = user, auth = method.id, fingerprint = fingerprint)
            val save = sshEditorSave(saved, privateKey, password, passphrase)
            localNetwork.run(host) { onSave(save.host, save.password, save.privateKey, save.passphrase) }
        },
    ) {
        OutlinedTextField(alias, { alias = it }, label = { Text(stringResource(R.string.settings_ssh_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(host, { host = it.trim(); fingerprint = "" }, label = { Text(stringResource(R.string.settings_ssh_host)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5); fingerprint = "" }, label = { Text(stringResource(R.string.settings_ssh_port)) }, singleLine = true, modifier = Modifier.weight(0.4f))
        }
        LocalNetworkNote(host, localNetwork)
        OutlinedTextField(user, { user = it.trim() }, label = { Text(stringResource(R.string.settings_ssh_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SshAuthFields(
            method, { method = it },
            privateKey, { privateKey = it },
            password, { password = it },
            passphrase, { passphrase = it },
            needsSecret = sshNeedsSecret(method, initial.authMethod, isNew),
        )
        if (fingerprint.isNotBlank() && !hostChanged || fingerprint.isNotBlank() && isNew) {
            CardGroup { KeyValueRow(stringResource(R.string.settings_ssh_host_key), fingerprint, mono = true) }
            SupportingText(stringResource(R.string.settings_ssh_host_key_hint))
        } else {
            TextButton(enabled = host.isNotBlank() && !checking, onClick = {
                // The probe is the first connection to the machine, so ask before it.
                localNetwork.run(host) {
                    checking = true
                    probeError = null
                    onProbe(host, port.toIntOrNull() ?: 22) { result ->
                        checking = false
                        result.onSuccess { fingerprint = it }.onFailure { probeError = it.message }
                    }
                }
            }) { Text(stringResource(if (checking) R.string.settings_ssh_checking else R.string.settings_ssh_check)) }
            probeError?.let { Text(it, color = Locus.colors.critical, style = LocusType.supporting) }
        }
        if (!isNew) CriticalOutlinedButton(stringResource(R.string.settings_ssh_remove), onClick = onDelete)
    }
}
