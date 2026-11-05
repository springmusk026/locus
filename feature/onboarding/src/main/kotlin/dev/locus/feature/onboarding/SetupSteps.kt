package dev.locus.feature.onboarding

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
import androidx.compose.ui.text.input.KeyboardType
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

// ---- Workspace & Linux ---------------------------------------------------------------------------

@Composable
internal fun ColumnScope.LinuxStep(state: OnboardingUiState, viewModel: OnboardingViewModel, onOpenWorkspace: () -> Unit = {}) {
    val setup by viewModel.linuxSetup.collectAsStateWithLifecycle()
    val linux = state.linux
    LaunchedEffect(linux) { if (linux == LinuxStatus.Ready) viewModel.refreshPackages() }
    StepHeading(
        "Workspace and Linux",
        "The agent keeps its files in a private workspace, which you can also open from your phone's Files app. " +
            "If you choose, it can also use a small Linux system (Alpine) to run code there, at /workspace. No root needed. Linux is optional.",
    )
    LocusCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(LocusIcons.Terminal, tint = if (linux == LinuxStatus.Ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                Text("Alpine Linux", style = LocusType.rowTitle)
                when (linux) {
                    LinuxStatus.NotInstalled -> Hint("Not installed. It's only downloaded if you choose to install it below.")
                    is LinuxStatus.Installing -> StatusLine(linux.step, Tone.Live)
                    LinuxStatus.Ready -> Hint("Installed, with your workspace at /workspace")
                    is LinuxStatus.Unavailable -> Text(linux.reason, style = LocusType.rowSupporting, color = Locus.colors.onCautionContainer)
                    is LinuxStatus.Failed -> Text(linux.message, style = LocusType.rowSupporting, color = Locus.colors.critical)
                }
            }
            when (linux) {
                LinuxStatus.Ready -> StatusPill("Ready", Tone.Live)
                is LinuxStatus.Installing -> InlineSpinner(color = MaterialTheme.colorScheme.primary, contentDescription = "Installing")
                is LinuxStatus.Failed -> StatusPill("Failed", Tone.Critical)
                else -> Unit
            }
        }
    }
    if (linux is LinuxStatus.Unavailable) return

    SectionLabel("Tools to add")
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
                    SupportingText(p.note, style = LocusType.rowSupporting)
                }
                when (pkg) {
                    PackageState.Installed -> StatusPill("Installed", Tone.Live)
                    PackageState.Queued -> StatusPill("Waiting", Tone.Neutral)
                    PackageState.Installing -> InlineSpinner(color = MaterialTheme.colorScheme.primary, contentDescription = "Installing")
                    is PackageState.Failed -> StatusPill("Failed", Tone.Critical)
                    PackageState.Unknown -> Unit
                }
            }
            (pkg as? PackageState.Failed)?.let { OutputBlock(it.detail, Modifier.padding(start = Spacing.sm + Sizes.touchTarget, end = Spacing.lg, bottom = Spacing.md)) }
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
                Text("Downloads third-party programs", style = LocusType.rowTitle)
                SupportingText(
                    "Installing downloads Alpine Linux (about 4 MB) and any tools you tick from alpinelinux.org. " +
                        "These programs don't come from F-Droid or this app's own release, and F-Droid hasn't checked them. " +
                        "Locus verifies the Alpine image's checksum before unpacking it.",
                    style = LocusType.rowSupporting,
                )
                // Where to do it later, as a link (Home → Files opens the same screen).
                DestinationLink(
                    destinationPath("Workspace"),
                    onNavigate = onOpenWorkspace,
                    hint = "Not now? Continue without Linux. Locus never installs it on its own; you can do it later with $LINUX_INSTALL_ACTION on the Linux card in",
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
                busy -> "Installing…"
                needsLinux && toInstall.isNotEmpty() -> "$LINUX_INSTALL_ACTION and ${toInstall.size} tool${if (toInstall.size == 1) "" else "s"}"
                needsLinux -> if (linux is LinuxStatus.Failed) "Retry download" else LINUX_INSTALL_ACTION
                toInstall.isNotEmpty() -> "Install selected (${toInstall.size})"
                else -> "All selected tools are installed"
            },
        )
    }
    when {
        setup.running -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val installing = linux as? LinuxStatus.Installing
            StatusLine(installing?.step ?: setup.phase ?: "Working…", Tone.Live)
            Meter(installing?.fraction ?: setup.progress, tone = Tone.Live, contentDescription = "Linux install progress")
            Hint("This can take a few minutes. You can keep going; it carries on in the background, with a notification.")
            if (setup.cancellable) TextButton(onClick = viewModel::cancelLinux) { Text("Cancel install") }
        }
        setup.error != null -> JobStatus(Job(error = setup.error), "")
        setup.message != null -> JobStatus(Job(result = setup.message), "")
    }
    Hint("Packages come from Alpine's servers. The agent can add more any time with apk add.")
}

@Composable
private fun Hint(text: String) {
    SupportingText(text)
}

// ---- SSH -----------------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.SshStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading(
        "Your machines",
        "Optionally let the agent run commands and move files on a computer or server you own, over SSH. Commands ask first by default.",
    )
    if (state.sshHosts.isNotEmpty()) {
        CardGroup {
            state.sshHosts.forEachIndexed { i, h ->
                if (i > 0) GroupDivider(Insets.iconRow)
                ListRow(h.alias, subtitle = sshHostSubtitle(h), leading = { IconTile(LocusIcons.Server, tint = MaterialTheme.colorScheme.primary) })
            }
        }
    }
    SectionLabel("Add a machine")
    val d = viewModel.sshDraft
    val localNetwork = rememberLocalNetworkGate()
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(d.alias, { v -> viewModel.editSsh { it.copy(alias = v) } }, label = { Text("Name (e.g. home-server)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val host = @Composable { modifier: Modifier ->
                OutlinedTextField(
                    d.host, { v -> viewModel.editSsh { it.copy(host = v.trim()) } },
                    label = { Text("Host") }, singleLine = true, modifier = modifier,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            val port = @Composable { modifier: Modifier ->
                OutlinedTextField(
                    d.port, { v -> viewModel.editSsh { it.copy(port = v.filter(Char::isDigit).take(5)) } },
                    label = { Text("Port") }, singleLine = true, modifier = modifier,
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
            OutlinedTextField(d.user, { v -> viewModel.editSsh { it.copy(user = v.trim()) } }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SshAuthFields(
                d.method, { v -> viewModel.editSsh { it.copy(method = v) } },
                d.privateKey, { v -> viewModel.editSsh { it.copy(privateKey = v) } },
                d.password, { v -> viewModel.editSsh { it.copy(password = v) } },
                d.passphrase, { v -> viewModel.editSsh { it.copy(passphrase = v) } },
                needsSecret = true,
                secretNote = "Kept encrypted on this phone.",
            )
            if (d.fingerprint == null) {
                OutlinedButton(
                    onClick = { localNetwork.run(d.host, viewModel::probeSsh) },
                    enabled = d.host.isNotBlank() && !viewModel.sshProbe.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check host key") }
                JobStatus(viewModel.sshProbe.copy(result = null), "Connecting…")
            } else {
                Text("Host key fingerprint", style = LocusType.label)
                OutputBlock(d.fingerprint)
                SupportingText(
                    "Check it matches the server, e.g. by running ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub there. Locus will refuse any other key.",
                )
                Button(
                    onClick = viewModel::saveSsh,
                    enabled = d.canSave && !viewModel.sshSave.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Trust and add") }
            }
            JobStatus(viewModel.sshSave, "Saving…")
        }
    }
}

// ---- Connectors ----------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.ConnectorsStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading("Connectors", "Give the agent web search and other services with your own keys, or connect an MCP server. All optional.")
    var keyFor by rememberSaveable { mutableStateOf<String?>(null) }
    val localNetwork = rememberLocalNetworkGate()
    SectionLabel("Integrations")
    CardGroup {
        state.integrations.forEachIndexed { i, (integration, hasKey) ->
            if (i > 0) GroupDivider(Insets.iconRow)
            ListRow(
                integration.name,
                subtitle = integration.summary,
                onClick = { keyFor = integration.id },
                leading = { IntegrationIcon(integration.id) },
                subtitleMaxLines = 2,
                // Connected is a state; without a key the row is an action, so it reads as one.
                trailing = { if (hasKey) StatusPill("Connected", Tone.Live, dot = true) else Text("Add key", style = LocusType.label, color = MaterialTheme.colorScheme.primary) },
            )
        }
    }
    state.integrations.firstOrNull { it.first.id == keyFor }?.let { (integration, hasKey) ->
        // Telegram needs its chat found after you message the bot, so it gets the same sheet as in Settings.
        if (integration.id == Integrations.telegram.id) {
            TelegramSheet(integration, hasKey, viewModel.telegram, onDismiss = { keyFor = null })
        } else {
            IntegrationKeySheet(integration, hasKey, onSave = { viewModel.setIntegrationKey(integration, it); keyFor = null }, onDismiss = { keyFor = null })
        }
    }

    SectionLabel("MCP server")
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it.trim() }, label = { Text("Server URL") }, placeholder = { Text("https://example.com/mcp") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            LocalNetworkNote(url, localNetwork)
            OutlinedTextField(
                token, { token = it }, label = { Text("Token (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Leave empty for servers you sign in to; Locus opens the sign-in page if needed.") },
                modifier = Modifier.fillMaxWidth(),
            )
            val adding = viewModel.mcpAdd.running
            Button(
                onClick = { localNetwork.run(url) { viewModel.addMcp(name, url, token) } },
                enabled = name.isNotBlank() && url.startsWith("http") && !adding,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (adding) "Connecting…" else "Add server") }
            if (adding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLine("Connecting. If a sign-in page opens, finish there.", Tone.Live, Modifier.weight(1f))
                    TextButton(onClick = viewModel::cancelMcp) { Text("Cancel") }
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
    LocusSheet(
        title = integration.name,
        subtitle = integration.description,
        onDismiss = onDismiss,
        action = "Save",
        actionEnabled = key.isNotBlank() && problem == null,
        onAction = { onSave(key) },
    ) {
        OutlinedTextField(
            key,
            { key = it },
            label = { Text(if (hasKey) "${integration.keyLabel} (saved — type to replace)" else integration.keyLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = problem != null,
            supportingText = (problem ?: integration.keyHelp)?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { uri.openUri(integration.signupUrl) }) { Text(integration.linkLabel) }
            if (hasKey) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { onSave(null) }) { Text("Remove key", color = Locus.colors.critical) }
            }
        }
    }
}

// ---- Check ---------------------------------------------------------------------------------------

@Composable
internal fun ColumnScope.TestStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading("Quick check", "A tiny test message to your model, and a command in Linux if it's installed.")
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
                    Text("Model", style = LocusType.rowTitle)
                    SupportingText(provider?.let { "${it.name} · ${dev.locus.core.model.ModelNames.short(it.model)}" } ?: "Not set up", style = LocusType.rowSupporting)
                }
                if (provider != null) TextButton(onClick = viewModel::testModel, enabled = !viewModel.modelTest.running) { Text("Test again") }
            }
            JobStatus(viewModel.modelTest, "Asking the model…")
        }
    }

    LocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(LocusIcons.Terminal)
                Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                    Text("Linux", style = LocusType.rowTitle)
                    SupportingText(if (state.linux == LinuxStatus.Ready) "uname -a" else "Not installed, skipped", style = LocusType.rowSupporting)
                }
                if (state.linux == LinuxStatus.Ready) TextButton(onClick = viewModel::testLinux, enabled = !viewModel.linuxTest.running) { Text("Run again") }
            }
            val job = viewModel.linuxTest
            if (job.result != null) OutputBlock(job.result) else JobStatus(job, "Running…")
        }
    }
}
