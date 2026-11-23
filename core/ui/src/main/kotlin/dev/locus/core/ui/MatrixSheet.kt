package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Matrix
import dev.locus.core.data.connectors.ServiceSetupException
import dev.locus.core.data.integrations.ReachMeChannel
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.LocalNetworkGate
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.PasswordField
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.linux.EngineText
import dev.locus.core.model.ServiceAddress
import dev.locus.core.data.R as DataR

/** Whether [this] is the "room is end-to-end encrypted" refusal, which gets its own banner. */
internal fun Throwable.isEncryptedRoom(): Boolean =
    ((this as? ServiceSetupException)?.text as? EngineText.Res)?.id == DataR.string.data_reachme_matrix_encrypted

/**
 * A Matrix bot as a reach-me channel (from [ReachMeSheet]): the homeserver's address, the bot account's
 * access token and the room. Send a test and Save both check the homeserver first: the token, the room
 * (an alias is resolved), that the bot has joined, and that the room isn't end-to-end encrypted, which
 * Locus refuses. See docs/design/reach-me-channels.md#matrix.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatrixSheet(
    integration: Integration,
    hasKey: Boolean,
    actions: ReachMeActions,
    onDismiss: () -> Unit,
    localNetwork: LocalNetworkGate,
    initial: ReachMeForm?,
) {
    val res = LocalResources.current
    val uriHandler = LocalUriHandler.current
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var token by rememberSaveable { mutableStateOf(initial?.secret.orEmpty()) }
    var room by rememberSaveable { mutableStateOf(initial?.room.orEmpty()) }
    /** The action running now ("test" or "save"), or null. */
    var running by remember { mutableStateOf(initial?.running) }
    var note by remember { mutableStateOf(initial?.note) }
    var error by remember { mutableStateOf(initial?.error) }
    var encrypted by remember { mutableStateOf(initial?.encrypted ?: false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val busy = running != null

    LaunchedEffect(Unit) {
        if (hasKey && initial == null) actions.saved(ReachMeChannel.MATRIX) { s ->
            if (s != null && url.isEmpty()) {
                url = s.url
                if (room.isEmpty()) room = s.room.orEmpty()
            }
        }
    }

    val checked = ServiceAddress.check(url)
    val tokenOk = if (token.isBlank()) hasKey else Matrix.isToken(token.trim())
    val roomOk = Matrix.isRoomId(room.trim()) || Matrix.isAlias(room.trim())
    val ready = checked.ok && tokenOk && roomOk && !busy
    fun edited() { error = null; note = null; encrypted = false }
    fun failed(e: Throwable) {
        encrypted = e.isEncryptedRoom()
        error = if (encrypted) null else e.uiText()
        note = null
        running = null
    }

    LocusSheet(
        title = integration.displayName(res),
        onDismiss = onDismiss,
        action = stringResource(DesignR.string.common_save),
        actionEnabled = ready,
        onAction = {
            val target = checked.url ?: return@LocusSheet
            localNetwork.run(target) {
                running = "save"; edited()
                actions.matrixSave(url, token.trim(), room.trim()) { r -> r.onSuccess { running = null; onDismiss() }.onFailure(::failed) }
            }
        },
    ) {
        SupportingText(integration.descriptionText(res))
        val showUrlError = url.isNotBlank() && checked.error != null
        OutlinedTextField(
            url, { url = it.trim(); edited() },
            label = { Text(stringResource(R.string.ui_reachme_matrix_server)) },
            placeholder = { Text(stringResource(R.string.ui_reachme_matrix_server_placeholder)) },
            singleLine = true,
            isError = showUrlError,
            supportingText = { Text(if (showUrlError) checked.error.orEmpty() else stringResource(R.string.ui_reachme_matrix_server_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (checked.warning != null) Banner(stringResource(R.string.ui_reachme_http_warning), Tone.Caution, title = stringResource(R.string.ui_reachme_not_encrypted))
        checked.url?.let { LocalNetworkNote(it, localNetwork) }

        PasswordField(
            token, { token = it; edited() },
            label = integration.keyLabelText(res),
            supportingText = stringResource(if (hasKey) R.string.ui_reachme_token_keep else R.string.ui_reachme_matrix_token_where),
            isError = token.isNotBlank() && !Matrix.isToken(token.trim()),
        )

        val showRoomError = room.isNotBlank() && !roomOk
        OutlinedTextField(
            room, { room = it.trim(); edited() },
            label = { Text(stringResource(R.string.ui_reachme_matrix_room)) },
            placeholder = { Text(stringResource(R.string.ui_reachme_matrix_room_placeholder)) },
            singleLine = true,
            isError = showRoomError,
            supportingText = { Text(stringResource(if (showRoomError) R.string.ui_reachme_matrix_room_error else R.string.ui_reachme_matrix_room_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        if (encrypted) {
            Banner(stringResource(DataR.string.data_reachme_matrix_encrypted), Tone.Critical, title = stringResource(R.string.ui_reachme_matrix_encrypted_title))
        } else {
            Banner(stringResource(R.string.ui_reachme_matrix_e2ee_text), Tone.Info, title = stringResource(R.string.ui_reachme_matrix_e2ee_title))
        }

        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(
                enabled = ready,
                onClick = {
                    val target = checked.url ?: return@OutlinedButton
                    localNetwork.run(target) {
                        running = "test"; edited()
                        actions.matrixTest(url, token.trim(), room.trim()) { r ->
                            r.onSuccess { user -> running = null; note = UiText.Res(R.string.ui_reachme_matrix_test_sent, user) }.onFailure(::failed)
                        }
                    }
                },
            ) {
                if (running == "test") {
                    InlineSpinner(size = IconSize.small)
                    Spacer(Modifier.size(Spacing.sm))
                }
                Text(stringResource(R.string.ui_reachme_send_test))
            }
            if (hasKey) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text(stringResource(DesignR.string.common_remove), color = Locus.colors.critical) }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running == "save") {
                    InlineSpinner(size = IconSize.small)
                    Spacer(Modifier.size(Spacing.sm))
                }
                SupportingText(stringResource(R.string.ui_reachme_matrix_checking))
            }
        }
        note?.let { SupportingText(it.asString()) }
        error?.let { Text(it.asString(), style = LocusType.supporting, color = Locus.colors.critical) }
        SupportingText(stringResource(R.string.ui_reachme_matrix_stored))
        TextButton(onClick = { uriHandler.openUri(integration.signupUrl) }) { Text(integration.linkLabelText(res)) }
    }

    if (confirmRemove) {
        val name = integration.displayName(res)
        ConfirmSheet(
            title = stringResource(R.string.ui_reachme_remove_title, name),
            message = stringResource(R.string.ui_reachme_remove_message, name),
            confirmLabel = stringResource(DesignR.string.common_remove),
            tone = Tone.Critical,
            onConfirm = { confirmRemove = false; actions.remove(ReachMeChannel.MATRIX); onDismiss() },
            onDismiss = { confirmRemove = false },
        )
    }
}
