package dev.locus.core.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.locus.core.agent.integrations.Gotify
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Webhook
import dev.locus.core.data.connectors.ServiceSetupException
import dev.locus.core.data.connectors.errorText
import dev.locus.core.data.device.PhoneIntents
import dev.locus.core.data.integrations.ReachMeChannel
import dev.locus.core.data.integrations.ReachMeSetup
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
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
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.rememberCopyFeedback
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.model.ServiceAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the Gotify and Webhook sheets can do. A blank secret means the one already saved (same address only); null, no secret. */
class ReachMeActions(
    /** The saved address and whether a secret is saved with it; null when nothing is. */
    val saved: (channel: ReachMeChannel, onResult: (Saved?) -> Unit) -> Unit,
    val test: (channel: ReachMeChannel, url: String, secret: String?, onResult: (Result<Unit>) -> Unit) -> Unit,
    val save: (channel: ReachMeChannel, url: String, secret: String?, onResult: (Result<Unit>) -> Unit) -> Unit,
    val remove: (channel: ReachMeChannel) -> Unit,
    /** A new random signing secret for a webhook. */
    val newSecret: () -> String = { ReachMeSetup.newSecret() },
    /** Matrix: checks the homeserver, token and room, then posts a test; the result is the bot's user ID. */
    val matrixTest: (url: String, token: String, room: String, onResult: (Result<String>) -> Unit) -> Unit = { _, _, _, _ -> },
    /** Matrix: checks the homeserver, token and room, then saves them (an alias as its room ID). */
    val matrixSave: (url: String, token: String, room: String, onResult: (Result<Unit>) -> Unit) -> Unit = { _, _, _, _ -> },
) {
    /** [room] is the saved Matrix room's ID. */
    data class Saved(val url: String, val hasSecret: Boolean, val room: String? = null)

    companion object {
        val None = ReachMeActions({ _, done -> done(null) }, { _, _, _, _ -> }, { _, _, _, _ -> }, {})

        /** The sheet's actions over [setup], run in [scope] (a view model's). Failures come back as results. */
        fun of(setup: ReachMeSetup, scope: CoroutineScope) = ReachMeActions(
            saved = { channel, done ->
                scope.launch {
                    done(
                        runCatching {
                            setup.savedUrl(channel)?.let { Saved(it, setup.hasSecret(channel), if (channel == ReachMeChannel.MATRIX) setup.savedRoom() else null) }
                        }.getOrNull(),
                    )
                }
            },
            test = { channel, url, secret, done -> scope.launch { done(runCatching { setup.sendTest(channel, url, secret); Unit }) } },
            save = { channel, url, secret, done -> scope.launch { done(runCatching { setup.save(channel, url, secret) }) } },
            remove = { channel -> scope.launch { setup.remove(channel) } },
            matrixTest = { url, token, room, done ->
                scope.launch { done(runCatching { checkNotNull(setup.sendTest(ReachMeChannel.MATRIX, url, token, room)) }) }
            },
            matrixSave = { url, token, room, done -> scope.launch { done(runCatching { setup.save(ReachMeChannel.MATRIX, url, token, room) }) } },
        )
    }
}

/** A failure in the user's language when core gave one, else its own words, else a generic line. */
internal fun Throwable.uiText(): UiText =
    (this as? ServiceSetupException)?.text?.toUiText() ?: message?.let(UiText::Plain) ?: UiText.Res(DesignR.string.common_error_generic)

/**
 * Gotify or a webhook as a reach-me channel, from Settings → Integrations and onboarding: the server's
 * address or the webhook's URL, the application token or an optional signing secret, Send a test, Save
 * and Remove. See docs/design/reach-me-channels.md.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReachMeSheet(
    integration: Integration,
    hasKey: Boolean,
    actions: ReachMeActions,
    onDismiss: () -> Unit,
    localNetwork: LocalNetworkGate = rememberLocalNetworkGate(),
    /** What the fields start with (renders); null loads what's saved. */
    initial: ReachMeForm? = null,
) {
    val channel = ReachMeChannel.of(integration.id) ?: return
    if (channel == ReachMeChannel.MATRIX) {
        MatrixSheet(integration, hasKey, actions, onDismiss, localNetwork, initial)
        return
    }
    val gotify = channel == ReachMeChannel.GOTIFY
    val res = LocalResources.current
    val uriHandler = LocalUriHandler.current
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf(initial?.secret.orEmpty()) }
    // A webhook signs by default; a saved one without a secret starts unsigned.
    var sign by rememberSaveable { mutableStateOf(initial?.sign ?: true) }
    var savedSecret by remember { mutableStateOf(initial?.savedSecret ?: false) }
    /** The action running now ("test" or "save"), or null. */
    var running by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<UiText?>(initial?.note) }
    var error by remember { mutableStateOf<UiText?>(initial?.error) }
    var confirmRemove by remember { mutableStateOf(false) }
    val busy = running != null

    LaunchedEffect(Unit) {
        if (hasKey && initial == null) actions.saved(channel) { s ->
            if (s != null && url.isEmpty()) {
                url = s.url
                savedSecret = s.hasSecret
                sign = s.hasSecret
            }
        }
    }

    val checked = if (gotify) ServiceAddress.check(url) else Webhook.check(url)
    val secretOk = when {
        gotify -> if (secret.isBlank()) hasKey else Gotify.isToken(secret.trim())
        !sign -> true
        secret.isBlank() -> savedSecret
        else -> Webhook.isSecret(secret.trim())
    }
    val ready = checked.ok && secretOk && !busy
    /** What goes to core: blank keeps the saved secret; null is "don't sign". */
    val sent: String? = if (!gotify && !sign) null else secret.trim()
    fun edited() { error = null; note = null }
    fun failed(e: Throwable) { error = e.uiText(); note = null; running = null }

    LocusSheet(
        title = integration.displayName(res),
        onDismiss = onDismiss,
        action = stringResource(DesignR.string.common_save),
        actionEnabled = ready,
        onAction = {
            val target = checked.url ?: return@LocusSheet
            localNetwork.run(target) {
                running = "save"; edited()
                actions.save(channel, url, sent) { r -> r.onSuccess { running = null; onDismiss() }.onFailure(::failed) }
            }
        },
    ) {
        SupportingText(integration.descriptionText(res))
        val showError = url.isNotBlank() && checked.error != null
        OutlinedTextField(
            url, { url = it.trim(); edited() },
            label = { Text(stringResource(if (gotify) R.string.ui_reachme_server_address else R.string.ui_reachme_webhook_url)) },
            placeholder = { Text(stringResource(if (gotify) R.string.ui_reachme_server_placeholder else R.string.ui_reachme_url_placeholder)) },
            singleLine = true,
            isError = showError,
            supportingText = { Text(if (showError) checked.errorText?.asString().orEmpty() else stringResource(if (gotify) R.string.ui_reachme_server_hint else R.string.ui_reachme_url_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (checked.warning != null) Banner(stringResource(R.string.ui_reachme_http_warning), Tone.Caution, title = stringResource(R.string.ui_reachme_not_encrypted))
        checked.url?.let { LocalNetworkNote(it, localNetwork) }

        if (gotify) {
            PasswordField(
                secret, { secret = it; edited() },
                label = integration.keyLabelText(res),
                supportingText = stringResource(if (hasKey) R.string.ui_reachme_token_keep else R.string.ui_reachme_token_where),
                isError = secret.isNotBlank() && !Gotify.isToken(secret.trim()),
            )
        } else {
            CardGroup {
                SwitchRow(
                    stringResource(R.string.ui_reachme_sign_title),
                    checked = sign,
                    onCheckedChange = { sign = it; edited() },
                    subtitle = stringResource(R.string.ui_reachme_sign_subtitle),
                )
            }
            if (sign) {
                PasswordField(
                    secret, { secret = it; edited() },
                    label = stringResource(R.string.ui_reachme_secret_label),
                    supportingText = stringResource(if (savedSecret) R.string.ui_reachme_secret_keep else R.string.ui_reachme_secret_hint),
                    isError = secret.isNotBlank() && !Webhook.isSecret(secret.trim()),
                )
                val context = LocalContext.current
                val copied = rememberCopyFeedback()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { secret = actions.newSecret(); edited() }, enabled = !busy) { Text(stringResource(R.string.ui_reachme_generate)) }
                    if (secret.isNotBlank()) {
                        TextButton(onClick = {
                            runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(PhoneIntents.sensitiveClip("Locus", secret.trim())) }
                            copied.copied()
                        }) { Text(stringResource(if (copied.shown) R.string.ui_reachme_copied else R.string.ui_reachme_copy)) }
                    }
                }
            }
        }

        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            OutlinedButton(
                enabled = ready,
                onClick = {
                    val target = checked.url ?: return@OutlinedButton
                    localNetwork.run(target) {
                        running = "test"; edited()
                        actions.test(channel, url, sent) { r -> r.onSuccess { running = null; note = UiText.Res(R.string.ui_reachme_test_sent) }.onFailure(::failed) }
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
        note?.let { SupportingText(it.asString()) }
        error?.let { Text(it.asString(), style = LocusType.supporting, color = Locus.colors.critical) }
        SupportingText(stringResource(R.string.ui_reachme_stored))
        TextButton(onClick = { uriHandler.openUri(integration.signupUrl) }) { Text(integration.linkLabelText(res)) }
    }

    if (confirmRemove) {
        val name = integration.displayName(res)
        ConfirmSheet(
            title = stringResource(R.string.ui_reachme_remove_title, name),
            message = stringResource(R.string.ui_reachme_remove_message, name),
            confirmLabel = stringResource(DesignR.string.common_remove),
            tone = Tone.Critical,
            onConfirm = { confirmRemove = false; actions.remove(channel); onDismiss() },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** A starting state for [ReachMeSheet], for renders. */
data class ReachMeForm(
    val url: String = "",
    val secret: String = "",
    val sign: Boolean = true,
    val savedSecret: Boolean = false,
    val note: UiText? = null,
    val error: UiText? = null,
    /** Matrix: the room's address or ID. */
    val room: String = "",
    /** Matrix: the room turned out to be end-to-end encrypted. */
    val encrypted: Boolean = false,
    /** The action shown as running ("test" or "save"), for renders of the checking state. */
    val running: String? = null,
)
