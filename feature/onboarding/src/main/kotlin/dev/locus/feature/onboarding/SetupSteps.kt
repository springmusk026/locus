package dev.locus.feature.onboarding

import androidx.compose.ui.platform.LocalResources
import dev.locus.core.designsystem.asString
import dev.locus.core.designsystem.UiText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.integrations.Integration
import dev.locus.core.agent.integrations.Integrations
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.destinationPath
import dev.locus.core.designsystem.DestinationLink
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.ui.SshAuthFields
import dev.locus.core.ui.TelegramSheet
import dev.locus.core.ui.asString
import dev.locus.core.ui.descriptionText
import dev.locus.core.ui.displayName
import dev.locus.core.ui.keyHelpText
import dev.locus.core.ui.keyLabelText
import dev.locus.core.ui.linkLabelText
import dev.locus.core.ui.sshHostSubtitle
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.IntegrationIcon
import dev.locus.core.designsystem.LocalNetworkNote
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.StatusPill
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.rememberLocalNetworkGate
import dev.locus.core.data.linux.LinuxPackage
import dev.locus.core.data.linux.PackageState
import dev.locus.core.designsystem.Meter
import dev.locus.core.linux.LinuxStatus
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.LINUX_INSTALL_ACTION
import dev.locus.core.ui.summaryText
import dev.locus.core.ui.toUiText

// ---- Workspace & Linux ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.LinuxStep(state: OnboardingUiState, viewModel: OnboardingViewModel, onOpenWorkspace: () -> Unit = {}) {
    val setup by viewModel.linuxSetup.collectAsStateWithLifecycle()
    val linux = state.linux
    LaunchedEffect(linux) { if (linux == LinuxStatus.Ready) viewModel.refreshPackages() }
    StepHeading(stringResource(R.string.onboarding_linux_title), stringResource(R.string.onboarding_linux_body))
    LocusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(LocusIcons.Terminal, tint = if (linux == LinuxStatus.Ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text(stringResource(R.string.onboarding_linux_alpine), style = LocusType.rowTitle)
                when (linux) {
                    LinuxStatus.NotInstalled -> Hint(stringResource(R.string.onboarding_linux_not_installed))
                    is LinuxStatus.Installing -> StatusLine(linux.stepText.asString(), Tone.Live)
                    LinuxStatus.Ready -> Hint(stringResource(R.string.onboarding_linux_installed_note))
                    is LinuxStatus.Unavailable -> Text(linux.reasonText.asString(), style = LocusType.rowSupporting, color = Locus.colors.onCautionContainer)
                    is LinuxStatus.Failed -> Text(linux.message, style = LocusType.rowSupporting, color = Locus.colors.critical)
                }
            }
            when (linux) {
                LinuxStatus.Ready -> StatusPill(stringResource(R.string.onboarding_linux_ready), Tone.Live)
                is LinuxStatus.Installing -> InlineSpinner(color = MaterialTheme.colorScheme.primary, contentDescription = stringResource(R.string.onboarding_linux_installing))
                is LinuxStatus.Failed -> StatusPill(stringResource(R.string.onboarding_linux_failed), Tone.Critical)
                else -> Unit
            }
        }
    }
    if (linux is LinuxStatus.Unavailable) return

    SectionLabel(stringResource(R.string.onboarding_linux_tools_label))
    CardGroup {
        LinuxPackage.entries.forEachIndexed { i, p ->
            // The text starts after the checkbox's touch target.
            if (i > 0) GroupDivider(Spacing.sm + Sizes.touchTarget)
            val pkg = setup.packages[p] ?: PackageState.Unknown
            val installed = pkg == PackageState.Installed
            val selectable = !installed && !setup.running
            Row(
                Modifier.fillMaxWidth().clickable(enabled = selectable) { viewModel.togglePackage(p) }.padding(start = Spacing.sm, end = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = installed || p in viewModel.packages, onCheckedChange = { viewModel.togglePackage(p) }, enabled = selectable)
                Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                    Text(p.label, style = LocusType.rowTitle)
                    SupportingText(stringResource(p.noteRes), style = LocusType.rowSupporting)
                }
                when (pkg) {
                    PackageState.Installed -> StatusPill(stringResource(R.string.onboarding_linux_package_installed), Tone.Live)
                    PackageState.Queued -> StatusPill(stringResource(R.string.onboarding_linux_package_waiting), Tone.Neutral)
                    PackageState.Installing -> InlineSpinner(color = MaterialTheme.colorScheme.primary, contentDescription = stringResource(R.string.onboarding_linux_installing))
                    is PackageState.Failed -> StatusPill(stringResource(R.string.onboarding_linux_failed), Tone.Critical)
                    PackageState.Unknown -> Unit
                }
            }
            (pkg as? PackageState.Failed)?.let { OutputBlock(it.detail.asString(), Modifier.padding(start = Spacing.sm + Sizes.touchTarget, end = Spacing.lg, bottom = Spacing.md)) }
        }
    }

    val toInstall = LinuxPackage.entries.filter { it in viewModel.packages && setup.packages[it] != PackageState.Installed }
    val needsLinux = linux != LinuxStatus.Ready
    val busy = setup.running || linux is LinuxStatus.Installing
    // F-Droid's inclusion policy: downloading programs must be an explicit opt-in that says it
    // bypasses F-Droid's checks, and declining must be as easy as accepting ("Continue without Linux").
    if (needsLinux) {
        LocusCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.onboarding_linux_downloads_title), style = LocusType.rowTitle)
                SupportingText(stringResource(R.string.onboarding_linux_downloads_body), style = LocusType.rowSupporting)
                // Where to do it later, as a link (Home → Files opens the same screen).
                DestinationLink(
                    destinationPath(stringResource(R.string.onboarding_linux_workspace_link)),
                    onNavigate = onOpenWorkspace,
                    hint = stringResource(R.string.onboarding_linux_later_hint, LINUX_INSTALL_ACTION),
                )
            }
        }
    }
    Button(
        onClick = { viewModel.setUpLinux() },
        enabled = !busy && (needsLinux || toInstall.isNotEmpty()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                busy -> stringResource(R.string.onboarding_linux_install_busy)
                needsLinux && toInstall.isNotEmpty() -> pluralStringResource(R.plurals.onboarding_linux_install_with_tools, toInstall.size, LINUX_INSTALL_ACTION, toInstall.size)
                needsLinux -> if (linux is LinuxStatus.Failed) stringResource(R.string.onboarding_linux_retry_download) else LINUX_INSTALL_ACTION
                toInstall.isNotEmpty() -> stringResource(R.string.onboarding_linux_install_selected, toInstall.size)
                else -> stringResource(R.string.onboarding_linux_all_installed)
            },
        )
    }
    when {
        setup.running -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val installing = linux as? LinuxStatus.Installing
            StatusLine(installing?.stepText?.asString() ?: setup.phase?.asString() ?: stringResource(R.string.onboarding_linux_working), Tone.Live)
            Meter(installing?.fraction ?: setup.progress, tone = Tone.Live, contentDescription = stringResource(R.string.onboarding_linux_progress))
            Hint(stringResource(R.string.onboarding_linux_background_note))
            if (setup.cancellable) TextButton(onClick = viewModel::cancelLinux) { Text(stringResource(R.string.onboarding_linux_cancel_install)) }
        }
        setup.error != null -> JobStatus(Job(error = setup.error?.toUiText()), "")
        setup.message != null -> JobStatus(Job(result = setup.message?.toUiText()), "")
    }
    Hint(stringResource(R.string.onboarding_linux_packages_note))
}

@Composable
private fun Hint(text: String) {
    SupportingText(text)
}

// ---- SSH -----------------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.SshStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(stringResource(R.string.onboarding_ssh_title), stringResource(R.string.onboarding_ssh_body))
    if (state.sshHosts.isNotEmpty()) {
        CardGroup {
            state.sshHosts.forEachIndexed { i, h ->
                if (i > 0) GroupDivider(Insets.iconRow)
                ListRow(h.alias, subtitle = sshHostSubtitle(h), leading = { IconTile(LocusIcons.Server, tint = MaterialTheme.colorScheme.primary) })
            }
        }
    }
    SectionLabel(stringResource(R.string.onboarding_ssh_add_label))
    val d = viewModel.sshDraft
    val localNetwork = rememberLocalNetworkGate()
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(d.alias, { v -> viewModel.editSsh { it.copy(alias = v) } }, label = { Text(stringResource(R.string.onboarding_ssh_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val host = @Composable { modifier: Modifier ->
                OutlinedTextField(
                    d.host, { v -> viewModel.editSsh { it.copy(host = v.trim()) } },
                    label = { Text(stringResource(R.string.onboarding_ssh_host)) }, singleLine = true, modifier = modifier,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            val port = @Composable { modifier: Modifier ->
                OutlinedTextField(
                    d.port, { v -> viewModel.editSsh { it.copy(port = v.filter(Char::isDigit).take(5)) } },
                    label = { Text(stringResource(R.string.onboarding_ssh_port)) }, singleLine = true, modifier = modifier,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            // Side by side, unless a large font would squeeze Port's label into "Por / t": then stacked.
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
                host(Modifier.fillMaxWidth())
                port(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    host(Modifier.weight(3f))
                    port(Modifier.weight(1f))
                }
            }
            LocalNetworkNote(d.host, localNetwork)
            OutlinedTextField(d.user, { v -> viewModel.editSsh { it.copy(user = v.trim()) } }, label = { Text(stringResource(R.string.onboarding_ssh_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SshAuthFields(
                d.method, { v -> viewModel.editSsh { it.copy(method = v) } },
                d.privateKey, { v -> viewModel.editSsh { it.copy(privateKey = v) } },
                d.password, { v -> viewModel.editSsh { it.copy(password = v) } },
                d.passphrase, { v -> viewModel.editSsh { it.copy(passphrase = v) } },
                needsSecret = true,
                secretNote = stringResource(R.string.onboarding_ssh_secret_note),
            )
            if (d.fingerprint == null) {
                OutlinedButton(
                    onClick = { localNetwork.run(d.host, viewModel::probeSsh) },
                    enabled = d.host.isNotBlank() && !viewModel.sshProbe.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_ssh_check_host_key)) }
                JobStatus(viewModel.sshProbe.copy(result = null), stringResource(R.string.onboarding_ssh_connecting))
            } else {
                Text(stringResource(R.string.onboarding_ssh_fingerprint_label), style = LocusType.label)
                OutputBlock(d.fingerprint)
                SupportingText(stringResource(R.string.onboarding_ssh_fingerprint_note))
                Button(
                    onClick = viewModel::saveSsh,
                    enabled = d.canSave && !viewModel.sshSave.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onboarding_ssh_trust_add)) }
            }
            JobStatus(viewModel.sshSave, stringResource(R.string.onboarding_shared_saving))
        }
    }
}

// ---- Connectors ----------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.ConnectorsStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(stringResource(R.string.onboarding_connectors_title), stringResource(R.string.onboarding_connectors_body))
    var keyFor by rememberSaveable { mutableStateOf<String?>(null) }
    val localNetwork = rememberLocalNetworkGate()
    SectionLabel(stringResource(R.string.onboarding_connectors_integrations_label))
    CardGroup {
        state.integrations.forEachIndexed { i, (integration, hasKey) ->
            if (i > 0) GroupDivider(Insets.iconRow)
            ListRow(
                integration.displayName(LocalResources.current),
                subtitle = integration.summaryText(LocalResources.current),
                onClick = { keyFor = integration.id },
                leading = { IntegrationIcon(integration.id) },
                subtitleMaxLines = 2,
                // Connected is a state; without a key the row is an action, so it reads as one.
                trailing = { if (hasKey) StatusPill(stringResource(R.string.onboarding_connectors_connected), Tone.Live, dot = true) else Text(stringResource(R.string.onboarding_connectors_add_key), style = LocusType.label, color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
    state.integrations.firstOrNull { it.first.id == keyFor }?.let { (integration, hasKey) ->
        // Telegram needs its chat found after you message the bot, so it gets the same sheet as in Settings.
        if (integration.id == Integrations.telegram.id) {
            TelegramSheet(integration, hasKey, viewModel.telegram, onDismiss = { keyFor = null })
        } else if (integration.id == Integrations.email.id) {
            // The mailbox is a form with a connection test: the same sheet as in Settings.
            dev.locus.core.ui.EmailSheet(integration, hasKey, viewModel.email, onDismiss = { keyFor = null })
        } else {
            IntegrationKeySheet(integration, hasKey, onSave = { viewModel.setIntegrationKey(integration, it); keyFor = null }, onDismiss = { keyFor = null })
        }
    }

    SectionLabel(stringResource(R.string.onboarding_connectors_mcp_label))
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.onboarding_connectors_mcp_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it.trim() }, label = { Text(stringResource(R.string.onboarding_connectors_mcp_url)) }, placeholder = { Text(stringResource(R.string.onboarding_connectors_mcp_url_placeholder)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            LocalNetworkNote(url, localNetwork)
            OutlinedTextField(
                token, { token = it }, label = { Text(stringResource(R.string.onboarding_connectors_mcp_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text(stringResource(R.string.onboarding_connectors_mcp_token_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
            val adding = viewModel.mcpAdd.running
            Button(
                onClick = { localNetwork.run(url) { viewModel.addMcp(name, url, token) } },
                enabled = name.isNotBlank() && url.startsWith("http") && !adding,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (adding) stringResource(R.string.onboarding_connectors_mcp_connecting) else stringResource(R.string.onboarding_connectors_mcp_add)) }
            if (adding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLine(stringResource(R.string.onboarding_connectors_mcp_sign_in), Tone.Live, Modifier.weight(1f))
                    TextButton(onClick = viewModel::cancelMcp) { Text(stringResource(R.string.onboarding_shared_cancel)) }
                }
            } else {
                JobStatus(viewModel.mcpAdd, "")
            }
            // Clear the form once the server is added, so it's ready for another.
            LaunchedEffect(viewModel.mcpAdd.result) {
                if (viewModel.mcpAdd.result != null) { name = ""; url = ""; token = "" }
            }
        }
    }
}

@Composable
private fun IntegrationKeySheet(integration: Integration, hasKey: Boolean, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    val problem = keyProblem(integration, key)
    val uri = LocalUriHandler.current
    val res = LocalResources.current
    LocusSheet(
        title = integration.displayName(res),
        subtitle = integration.descriptionText(res),
        onDismiss = onDismiss,
        action = stringResource(R.string.onboarding_connectors_key_save),
        actionEnabled = key.isNotBlank() && problem == null,
        onAction = { onSave(key) },
    ) {
        OutlinedTextField(
            key,
            { key = it },
            label = { Text(integration.keyLabelText(res).let { if (hasKey) stringResource(R.string.onboarding_connectors_key_label_saved, it) else it }) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = problem != null,
            supportingText = (problem?.asString() ?: integration.keyHelpText(res))?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { uri.openUri(integration.signupUrl) }) { Text(integration.linkLabelText(res)) }
            if (hasKey) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.onboarding_connectors_key_remove), color = Locus.colors.critical) }
            }
        }
    }
}

// ---- Check ---------------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.TestStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(stringResource(R.string.onboarding_test_title), stringResource(R.string.onboarding_test_body))
    val provider = state.defaultProvider
    LaunchedEffect(provider?.id) {
        if (provider != null && viewModel.modelTest == Job()) viewModel.testModel()
    }
    LaunchedEffect(state.linux) {
        if (state.linux == LinuxStatus.Ready && viewModel.linuxTest == Job()) viewModel.testLinux()
    }

    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (provider != null) ProviderBadge(provider.name, provider.baseUrl, provider.format == ApiFormat.ON_DEVICE, size = IconSize.tile, model = provider.model)
                else IconTile(LocusIcons.Sparkle)
                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                    Text(stringResource(R.string.onboarding_test_model), style = LocusType.rowTitle)
                    SupportingText(provider?.let { stringResource(R.string.onboarding_test_model_value, it.name, dev.locus.core.model.ModelNames.short(it.model)) } ?: stringResource(R.string.onboarding_test_not_set_up), style = LocusType.rowSupporting)
                }
                if (provider != null) TextButton(onClick = viewModel::testModel, enabled = !viewModel.modelTest.running) { Text(stringResource(R.string.onboarding_test_again)) }
            }
            JobStatus(viewModel.modelTest, stringResource(R.string.onboarding_test_model_running))
        }
    }

    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(LocusIcons.Terminal)
                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                    Text(stringResource(R.string.onboarding_test_linux), style = LocusType.rowTitle)
                    SupportingText(if (state.linux == LinuxStatus.Ready) stringResource(R.string.onboarding_test_linux_command) else stringResource(R.string.onboarding_test_linux_skipped), style = LocusType.rowSupporting)
                }
                if (state.linux == LinuxStatus.Ready) TextButton(onClick = viewModel::testLinux, enabled = !viewModel.linuxTest.running) { Text(stringResource(R.string.onboarding_test_run_again)) }
            }
            val job = viewModel.linuxTest
            if (job.result != null) OutputBlock(job.result.asString()) else JobStatus(job, stringResource(R.string.onboarding_test_linux_running))
        }
    }
}
