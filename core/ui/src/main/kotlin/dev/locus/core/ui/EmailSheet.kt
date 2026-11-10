package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.locus.core.agent.integrations.EmailAutoconfig
import dev.locus.core.agent.integrations.EmailCheck
import dev.locus.core.agent.integrations.EmailField
import dev.locus.core.agent.integrations.EmailForm
import dev.locus.core.agent.integrations.EmailPresets
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.MailKind
import dev.locus.core.agent.integrations.MailSecurity
import dev.locus.core.agent.integrations.ServerCheck
import dev.locus.core.agent.integrations.ServerFields
import dev.locus.core.data.integrations.EmailSetup
import dev.locus.core.data.integrations.SavedEmail
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.PasswordField
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the Email sheet can do. A blank password in a form means the saved one. */
class EmailActions(
    val load: (onResult: (SavedEmail?) -> Unit) -> Unit,
    val lookUp: (address: String, onResult: (Result<EmailAutoconfig.Found?>) -> Unit) -> Unit,
    val test: (form: EmailForm, onResult: (Result<EmailCheck>) -> Unit) -> Unit,
    val save: (form: EmailForm, onResult: (Result<Unit>) -> Unit) -> Unit,
    val remove: () -> Unit,
) {
    companion object {
        val None = EmailActions({ it(null) }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})

        /** The sheet's actions over [setup], run in [scope] (a view model's). Failures come back as results. */
        fun of(setup: EmailSetup, scope: CoroutineScope) = EmailActions(
            load = { done -> scope.launch { done(runCatching { setup.saved() }.getOrNull()) } },
            lookUp = { address, done -> scope.launch { done(runCatching { setup.lookUp(address) }) } },
            test = { form, done -> scope.launch { done(runCatching { setup.test(form) }) } },
            save = { form, done -> scope.launch { done(runCatching { setup.save(form) }) } },
            remove = { scope.launch { setup.remove() } },
        )
    }
}

/** The action running in the sheet. */
enum class EmailRun { LOAD, LOOKUP, TEST, SAVE }

/** The sheet's state, hoisted so screenshots can show any moment of it. */
@Stable
class EmailSheetState(form: EmailForm = EmailForm(), serversOpen: Boolean = false) {
    var form by mutableStateOf(form)
    var serversOpen by mutableStateOf(serversOpen)
    var running by mutableStateOf<EmailRun?>(null)
    var check by mutableStateOf<EmailCheck?>(null)
    var note by mutableStateOf<UiText?>(null)
    var error by mutableStateOf<UiText?>(null)
    /** Why the saved value couldn't be read, when it couldn't. */
    var loadProblem by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)

    /** Any edit makes an earlier test result stale. */
    fun edit(change: (EmailForm) -> EmailForm) {
        form = change(form)
        check = null
        error = null
    }
}

/**
 * Email setup, from Settings → Integrations and from onboarding: the agent's own mailbox as a form.
 * The address picks a bundled preset when it can; Server settings shows and edits everything. Test
 * connection signs in to both servers without sending anything. Look up settings is the only other
 * network call, and only on a tap (docs/design/email-setup.md).
 */
@Composable
fun EmailSheet(
    integration: Integration,
    hasKey: Boolean,
    actions: EmailActions,
    onDismiss: () -> Unit,
    state: EmailSheetState = remember { EmailSheetState() },
) {
    val uriHandler = LocalUriHandler.current
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (hasKey && !state.loaded) {
            state.running = EmailRun.LOAD
            actions.load { saved ->
                state.loaded = true
                state.running = null
                saved?.form?.let { if (state.form == EmailForm()) state.form = it }
                state.loadProblem = saved?.problem
            }
        }
    }

    val form = state.form
    val hasSavedPassword = hasKey && state.loadProblem == null
    val problems = form.problems()
    val preset = form.preset
    val domain = EmailPresets.domainOf(form.address)
    val unsupported = EmailPresets.unsupported(form.address)
    val busy = state.running != null
    val ready = !busy && unsupported == null && form.isComplete(hasSavedPassword)
    fun failed(e: Throwable) {
        state.error = e.message?.let { UiText.Plain(it) } ?: UiText.Res(DesignR.string.common_error_generic)
        state.running = null
    }

    LocusSheet(
        title = integration.displayName(LocalResources.current),
        onDismiss = onDismiss,
        action = stringResource(if (state.running == EmailRun.SAVE) DesignR.string.common_saving else DesignR.string.common_save),
        actionEnabled = ready,
        onAction = {
            state.running = EmailRun.SAVE
            state.error = null
            actions.save(form) { r -> r.onSuccess { state.running = null; onDismiss() }.onFailure(::failed) }
        },
    ) {
        SupportingText(stringResource(R.string.ui_email_intro))
        state.loadProblem?.let {
            Banner(stringResource(R.string.ui_email_load_problem, it), Tone.Caution)
        }

        OutlinedTextField(
            form.address, { v -> state.edit { it.withAddress(v.trim()) } },
            label = { Text(stringResource(R.string.ui_email_address)) },
            placeholder = { Text(stringResource(R.string.ui_email_address_example)) },
            singleLine = true,
            isError = EmailField.ADDRESS in problems,
            supportingText = (problems[EmailField.ADDRESS] ?: addressNote(form, domain))?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        unsupported?.let { Banner(it, Tone.Caution) }

        PasswordField(
            form.password, { v -> state.edit { it.copy(password = v) } },
            label = preset?.fieldLabel?.let { LocalResources.current.emailPresetText(it) } ?: stringResource(R.string.ui_email_app_password),
            supportingText = when {
                hasSavedPassword && form.password.isEmpty() -> stringResource(R.string.ui_email_password_keep)
                else -> preset?.passwordHint?.let { LocalResources.current.emailPresetText(it) } ?: stringResource(R.string.ui_email_password_hint)
            },
        )
        if (preset != null) {
            preset.note?.let { SupportingText(LocalResources.current.emailPresetText(it)) }
            TextButton(onClick = { uriHandler.openUri(preset.passwordUrl) }) { Text(LocalResources.current.emailPresetText(preset.passwordLabel)) }
        }

        OutlinedTextField(
            form.name, { v -> state.edit { it.copy(name = v.replace("\n", " ")) } },
            label = { Text(stringResource(R.string.ui_email_display_name)) },
            placeholder = { Text(stringResource(R.string.ui_email_display_name_example)) },
            singleLine = true,
            isError = EmailField.NAME in problems,
            supportingText = { Text(problems[EmailField.NAME] ?: stringResource(R.string.ui_email_display_name_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        ServerSettings(state, problems, domain, actions)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = ready,
                onClick = {
                    state.running = EmailRun.TEST
                    state.error = null
                    state.check = null
                    actions.test(form) { r -> r.onSuccess { state.running = null; state.check = it }.onFailure(::failed) }
                },
            ) { BusyLabel(stringResource(R.string.ui_email_test), state.running == EmailRun.TEST) }
            Spacer(Modifier.weight(1f))
            if (hasKey) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text(stringResource(DesignR.string.common_remove), color = Locus.colors.critical) }
        }
        if (state.running == EmailRun.TEST || state.check != null) TestResults(form, state.check)
        state.error?.let { Text(it.asString(), style = LocusType.supporting, color = Locus.colors.critical) }
        SupportingText(stringResource(R.string.ui_email_stored_note))
    }

    if (confirmRemove) {
        ConfirmSheet(
            title = stringResource(R.string.ui_email_remove_title),
            message = stringResource(R.string.ui_email_remove_message),
            confirmLabel = stringResource(DesignR.string.common_remove),
            tone = Tone.Critical,
            onConfirm = { confirmRemove = false; actions.remove(); onDismiss() },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** The line under the address: which settings the form is using. */
@Composable
private fun addressNote(form: EmailForm, domain: String?): String? {
    val preset = form.preset
    return when {
        domain == null -> null
        EmailPresets.unsupported(form.address) != null -> null
        preset != null && form.usesPreset -> stringResource(R.string.ui_email_note_preset, preset.name)
        preset != null -> stringResource(R.string.ui_email_note_own, preset.name)
        else -> stringResource(R.string.ui_email_note_unknown, domain)
    }
}

@Composable
private fun ServerSettings(state: EmailSheetState, problems: Map<EmailField, String>, domain: String?, actions: EmailActions) {
    val form = state.form
    val serverProblem = problems.keys.any { it != EmailField.ADDRESS && it != EmailField.NAME && it != EmailField.PASSWORD }
    // Open by itself when there's nothing to summarise yet, or something in it needs fixing.
    val forced = serverProblem || (domain != null && form.preset == null && form.smtp.host.isBlank())
    val open = state.serversOpen || forced
    SectionLabel(
        stringResource(R.string.ui_email_servers),
        action = if (forced) null else stringResource(if (open) R.string.ui_email_servers_hide else DesignR.string.common_edit),
        onAction = { state.serversOpen = !state.serversOpen },
    )
    if (!open) {
        SupportingText(serverSummary(form))
        return
    }
    if (domain != null && form.preset == null) {
        OutlinedButton(
            enabled = state.running == null,
            onClick = {
                state.running = EmailRun.LOOKUP
                state.error = null
                state.note = null
                actions.lookUp(form.address) { r ->
                    state.running = null
                    r.onSuccess { found ->
                        if (found == null) {
                            state.note = UiText.Res(R.string.ui_email_lookup_none, domain)
                        } else {
                            state.edit { it.withServers(found.imap, found.smtp, found.username) }
                            state.note = UiText.Res(R.string.ui_email_lookup_filled)
                        }
                    }.onFailure { state.error = it.message?.let { m -> UiText.Plain(m) } ?: UiText.Res(R.string.ui_email_lookup_failed) }
                }
            },
        ) { BusyLabel(stringResource(R.string.ui_email_lookup), state.running == EmailRun.LOOKUP) }
        SupportingText(state.note?.asString() ?: stringResource(R.string.ui_email_lookup_hint, domain))
    }
    ServerEditor(MailKind.IMAP, form.imap, problems, state)
    ServerEditor(MailKind.SMTP, form.smtp, problems, state)
    OutlinedTextField(
        form.username, { v -> state.edit { it.withUsername(v.trim()) } },
        label = { Text(stringResource(R.string.ui_email_username)) },
        singleLine = true,
        isError = EmailField.USERNAME in problems,
        supportingText = { Text(problems[EmailField.USERNAME] ?: stringResource(R.string.ui_email_username_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun securityName(security: MailSecurity) = stringResource(
    when (security) {
        MailSecurity.SSL -> R.string.ui_email_security_ssl
        MailSecurity.STARTTLS -> R.string.ui_email_security_starttls
        MailSecurity.NONE -> DesignR.string.common_none
    },
)

/** "IMAP imap.gmail.com, port 993, SSL/TLS" and the same for SMTP, or what's missing. */
@Composable
internal fun serverSummary(form: EmailForm): String = if (form.imap.host.isBlank() && form.smtp.host.isBlank()) {
    stringResource(R.string.ui_email_summary_empty)
} else listOf(MailKind.IMAP, MailKind.SMTP).map { kind ->
    val s = form.server(kind)
    val imap = kind == MailKind.IMAP
    when {
        s.host.isBlank() && imap -> stringResource(R.string.ui_email_summary_no_imap)
        s.host.isBlank() -> stringResource(R.string.ui_email_summary_outgoing_unset)
        else -> stringResource(
            if (imap) R.string.ui_email_summary_incoming else R.string.ui_email_summary_outgoing,
            kind.label,
            s.host.trim(),
            s.port.ifBlank { "?" },
            securityName(s.security),
        )
    }
}.joinToString("\n")

@Composable
private fun ServerEditor(kind: MailKind, fields: ServerFields, problems: Map<EmailField, String>, state: EmailSheetState) {
    val imap = kind == MailKind.IMAP
    val hostField = if (imap) EmailField.IMAP_HOST else EmailField.SMTP_HOST
    val portField = if (imap) EmailField.IMAP_PORT else EmailField.SMTP_PORT
    val securityField = if (imap) EmailField.IMAP_SECURITY else EmailField.SMTP_SECURITY
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(if (imap) R.string.ui_email_incoming else R.string.ui_email_outgoing), style = LocusType.rowTitle)
        val host = @Composable { modifier: Modifier ->
            OutlinedTextField(
                fields.host, { v -> state.edit { it.withHost(kind, v.trim()) } },
                label = { Text(stringResource(R.string.ui_email_server)) },
                placeholder = { Text(stringResource(R.string.ui_email_server_example, kind.protocol)) },
                singleLine = true,
                isError = hostField in problems,
                supportingText = (problems[hostField] ?: if (imap && fields.host.isBlank()) stringResource(R.string.ui_email_server_empty_hint) else null)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = modifier,
            )
        }
        val port = @Composable { modifier: Modifier ->
            OutlinedTextField(
                fields.port, { v -> state.edit { it.withPort(kind, v) } },
                label = { Text(stringResource(R.string.ui_email_port)) },
                singleLine = true,
                isError = portField in problems,
                supportingText = problems[portField]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = modifier,
            )
        }
        // Side by side while there's room; at a large font the port gets its own line.
        if (LocalDensity.current.fontScale >= LARGE_FONT) {
            host(Modifier.fillMaxWidth())
            port(Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                host(Modifier.weight(HOST_SHARE))
                port(Modifier.weight(1f - HOST_SHARE))
            }
        }
        val choices = buildList {
            add(MailSecurity.SSL)
            add(MailSecurity.STARTTLS)
            // Plaintext is only for a server on this phone (a local bridge), as the tools enforce.
            if (fields.security == MailSecurity.NONE || fields.host.trim().let { it == "localhost" || it.startsWith("127.") || it == "::1" }) add(MailSecurity.NONE)
        }
        Segmented(
            options = choices.map { securityName(it) },
            selected = choices.indexOf(fields.security).coerceAtLeast(0),
            onSelect = { i -> state.edit { it.withSecurity(kind, choices[i]) } },
            modifier = Modifier.fillMaxWidth(),
        )
        problems[securityField]?.let { Text(it, style = LocusType.supporting, color = Locus.colors.critical) }
    }
}

@Composable
private fun TestResults(form: EmailForm, check: EmailCheck?) {
    CardGroup {
        var first = true
        for (kind in MailKind.entries) {
            val server = form.server(kind)
            if (kind == MailKind.IMAP && server.host.isBlank()) continue
            if (!first) GroupDivider(inset = Insets.iconRow)
            first = false
            val result = check?.let { if (kind == MailKind.IMAP) it.imap else it.smtp }
            val title = stringResource(if (kind == MailKind.IMAP) R.string.ui_email_reading else R.string.ui_email_sending)
            val pill = @Composable {
                when (result) {
                    null -> InlineSpinner(size = IconSize.small, contentDescription = stringResource(R.string.ui_email_testing))
                    ServerCheck.Passed -> StatusPill(stringResource(R.string.ui_email_works), Tone.Live)
                    is ServerCheck.Failed -> StatusPill(stringResource(R.string.ui_email_failed), Tone.Critical)
                }
            }
            // At a large font the result goes under the text, so the message keeps the row's width.
            val large = LocalDensity.current.fontScale >= LARGE_FONT
            ListRow(
                title = title,
                subtitle = when (result) {
                    null -> stringResource(R.string.ui_email_signing_in, server.host.trim())
                    ServerCheck.Passed -> stringResource(R.string.ui_email_signed_in, server.host.trim())
                    is ServerCheck.Failed -> result.messageText(LocalResources.current)
                },
                subtitleMaxLines = Int.MAX_VALUE,
                titleMaxLines = 2,
                meta = if (large) ({ Box(Modifier.padding(top = Spacing.xs)) { pill() } }) else null,
                leading = {
                    when (result) {
                        null -> IconTile(LocusIcons.Server)
                        ServerCheck.Passed -> IconTile(LocusIcons.Check, tint = Tone.Live.color())
                        is ServerCheck.Failed -> IconTile(LocusIcons.Error, tint = Tone.Critical.color())
                    }
                },
                trailing = if (large) null else ({ pill() }),
            )
        }
    }
}

@Composable
private fun BusyLabel(text: String, busy: Boolean) {
    if (busy) {
        InlineSpinner(size = IconSize.small)
        Spacer(Modifier.size(Spacing.sm))
    }
    Text(text)
}

/** From this font scale the port moves under the host. */
private const val LARGE_FONT = 1.5f

/** The host's share of the host-and-port row. */
private const val HOST_SHARE = 0.68f
