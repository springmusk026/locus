package dev.locus.feature.onboarding

import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.model.SettingsDestination
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.IconSize
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.entrance

/**
 * First-run setup, also re-runnable from Settings. [onFinish] gets the id of a first chat when one
 * was started; [onExit] leaves from the first step (closing the app on a first run).
 */
@Composable
fun OnboardingRoute(
    onFinish: (chatId: String?) -> Unit,
    onExit: () -> Unit,
    /** Opens the Workspace, where Linux can be installed later; back returns to this step. */
    onOpenWorkspace: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val step = viewModel.step
    BackHandler { if (!viewModel.back()) onExit() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                LocusTopBar(
                    step.title,
                    onBack = { if (!viewModel.back()) onExit() },
                    status = { SupportingText("Step ${step.ordinal + 1} of ${Step.entries.size}", style = LocusType.mono) },
                    actions = { if (step.skippable) TextButton(onClick = viewModel::next) { Text("Skip") } },
                )
                StepProgress(step)
            }
        },
        bottomBar = { BottomAction(step, state, viewModel, onFinish) },
    ) { padding ->
        AnimatedContent(step, Modifier.padding(padding), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "step") { current ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                when (current) {
                    Step.Intro -> IntroStep()
                    Step.Privacy -> PrivacyStep(viewModel)
                    Step.Provider -> ProviderStep(state, viewModel)
                    Step.Identity -> IdentityFields(state, viewModel)
                    Step.Linux -> LinuxStep(state, viewModel, onOpenWorkspace)
                    Step.Ssh -> SshStep(state, viewModel)
                    Step.Connectors -> ConnectorsStep(state, viewModel)
                    Step.Test -> TestStep(state, viewModel)
                    Step.Done -> DoneStep(state, viewModel, onOpenWorkspace)
                }
                Box(Modifier.height(Spacing.xl))
            }
        }
    }
}

/** One thin track per step: done steps filled, the current one filling in. */
@Composable
private fun StepProgress(step: Step) {
    Meter(
        (step.ordinal + 1f) / Step.entries.size,
        Modifier.padding(horizontal = Spacing.lg),
        tone = Tone.Brand,
        contentDescription = "Setup progress",
    )
}

@Composable
private fun BottomAction(step: Step, state: OnboardingUiState, viewModel: OnboardingViewModel, onFinish: (String?) -> Unit) {
    val (label, enabled) = when (step) {
        Step.Intro -> "Get started" to true
        Step.Privacy -> "Continue" to viewModel.consent
        Step.Provider -> (if (state.providers.isEmpty()) "Set up a model to continue" else "Continue") to state.providers.isNotEmpty()
        Step.Identity -> (if (viewModel.identitySave.running) "Saving…" else "Save and continue") to (viewModel.personaLoaded && !viewModel.identitySave.running)
        Step.Done -> (if (state.providers.isEmpty()) "Finish" else "Start chatting") to !viewModel.finishing
        Step.Linux -> (if (state.linux == dev.locus.core.linux.LinuxStatus.Ready) "Continue" else "Continue without Linux") to true
        else -> "Continue" to true
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding().padding(Spacing.lg)) {
        Button(
            onClick = {
                when (step) {
                    Step.Privacy -> { viewModel.applyProfile(); viewModel.next() }
                    Step.Identity -> viewModel.saveIdentity { viewModel.next() }
                    Step.Done -> viewModel.finish(startChat = true, onDone = onFinish)
                    else -> viewModel.next()
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(Sizes.touchTarget),
        ) { Text(label) }
    }
}

// ---- Steps ---------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.IntroStep() {
    StepHeading("Your agent, on your phone", "Locus is an AI agent that works for you: it reads, writes, browses and runs code to get things done.", Modifier.entrance(0))
    CardGroup(Modifier.entrance(1)) {
        Fact(LocusIcons.Phone, "Runs here", "The agent, its tools, your files and its memory all live on this phone.")
        GroupDivider(Insets.iconRow)
        Fact(LocusIcons.Key, "Your keys", "Bring a key from the AI provider you choose, or run a model on the phone itself. No Locus account.")
        GroupDivider(Insets.iconRow)
        Fact(LocusIcons.Shield, "You decide", "Every action goes through your approval rules, and everything it does is logged.")
    }
    SupportingText(
        "Setup takes a few minutes. Everything after the model step is optional and can be changed later in Settings.",
        Modifier.entrance(2),
        style = LocusType.rowSupporting,
    )
}

@Composable
private fun ColumnScope.PrivacyStep(viewModel: OnboardingViewModel) {
    StepHeading("What leaves your phone", "Locus has no servers. Here is exactly where your data goes.")
    CardGroup {
        Fact(LocusIcons.Send, "Straight to your provider", "Your messages and the files the agent reads go directly to the AI provider you pick, under that provider's terms. With the on-device model, nothing leaves at all.")
        GroupDivider(Insets.iconRow)
        Fact(LocusIcons.Key, "Keys stay encrypted", "API keys and passwords are kept in an encrypted vault on this phone, never shown to the model.")
        GroupDivider(Insets.iconRow)
        Fact(LocusIcons.Globe, "Only where you point it", "Websites, services and machines are contacted only when you, or the agent with your approval, use them.")
    }
    // The one request Locus would make on its own, so it's asked here, off by default (docs/design/updates.md).
    if (viewModel.offerUpdateCheck) {
        val updateCheck by viewModel.updateCheck.collectAsStateWithLifecycle()
        CardGroup {
            SwitchRow(
                title = "Check GitHub for new versions?",
                subtitle = "About once a day on Wi-Fi, one request to GitHub with nothing about you. You decide whether to install; this bypasses app-store checks. Change it in Settings → About & privacy.",
                checked = updateCheck,
                onCheckedChange = viewModel::setUpdateCheck,
                icon = LocusIcons.Regenerate,
            )
        }
    }
    SectionLabel("Approvals")
    CardGroup {
        Fact(LocusIcons.Check, "Allow", "Runs without asking.")
        GroupDivider(Insets.iconRow)
        Fact(LocusIcons.Shield, "Ask", "Waits for you to approve each time, in the app or from a notification.")
        GroupDivider(Insets.iconRow)
        Fact(LocusIcons.Close, "Deny", "Never runs.")
    }
    SectionLabel("Starting point")
    CardGroup {
        PermissionProfile.entries.forEachIndexed { i, p ->
            // The text starts after the radio button's touch target.
            if (i > 0) GroupDivider(Spacing.sm + Sizes.touchTarget + Spacing.xs)
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.pickProfile(p) }.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = (viewModel.profile ?: PermissionProfile.Balanced) == p, onClick = { viewModel.pickProfile(p) })
                Column(Modifier.weight(1f).padding(start = Spacing.xs, end = Spacing.sm)) {
                    Text(p.title, style = LocusType.rowTitle)
                    SupportingText(p.summary, style = LocusType.rowSupporting)
                }
            }
        }
    }
    SupportingText("You can change any single tool later in Settings → Permissions.")
    LocusCard(outline = if (viewModel.consent) MaterialTheme.colorScheme.primary.copy(alpha = Emphasis.toneOutline) else null, onClick = { viewModel.agree(!viewModel.consent) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = viewModel.consent, onCheckedChange = viewModel::agree)
            Text(
                "I understand that what I ask Locus to work on is sent to the AI provider I choose, and that the agent acts within the approvals above.",
                Modifier.padding(start = Spacing.xs),
                style = LocusType.rowSupporting,
            )
        }
    }
}

@Composable
private fun ColumnScope.DoneStep(state: OnboardingUiState, viewModel: OnboardingViewModel, onOpenWorkspace: () -> Unit = {}) {
    StepHeading("${viewModel.answers.agentName.trim().ifBlank { "Locus" }} is ready", "Here is what you set up. All of it can be changed in Settings.")
    CardGroup {
        Summary(LocusIcons.Sparkle, "Model", state.defaultProvider?.let { "${it.name} · ${dev.locus.core.model.ModelNames.short(it.model)}" }, "Not set up. Add one in Settings → Models.", link = SettingsDestination.Models)
        GroupDivider(Insets.iconRow)
        Summary(LocusIcons.Note, "Agent", "${viewModel.answers.agentName.trim().ifBlank { "Locus" }} · ${viewModel.answers.style.label.lowercase()}", null)
        GroupDivider(Insets.iconRow)
        Summary(LocusIcons.Shield, "Approvals", viewModel.profile?.title ?: "Unchanged", null)
        GroupDivider(Insets.iconRow)
        Summary(
            LocusIcons.Terminal, "Linux", if (state.linux == dev.locus.core.linux.LinuxStatus.Ready) "Installed" else null,
            "Not installed. Install it any time in Workspace.",
            onOpen = onOpenWorkspace,
        )
        GroupDivider(Insets.iconRow)
        Summary(LocusIcons.Server, "Machines", state.sshHosts.takeIf { it.isNotEmpty() }?.joinToString { it.alias }, "None")
        GroupDivider(Insets.iconRow)
        Summary(LocusIcons.Plug, "Integrations", state.integrations.filter { it.second }.takeIf { it.isNotEmpty() }?.joinToString { it.first.name }, "None")
    }
}

// ---- Shared pieces -------------------------------------------------------------------------------

@Composable
internal fun StepHeading(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = Spacing.sm, bottom = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        SupportingText(body, style = LocusType.rowTitle)
    }
}

@Composable
private fun Fact(icon: ImageVector, title: String, body: String) {
    // The facts are the step's content: never cut.
    ListRow(title, subtitle = body, titleMaxLines = 3, subtitleMaxLines = Int.MAX_VALUE, leading = { IconTile(icon) })
}

@Composable
private fun Summary(
    icon: ImageVector,
    title: String,
    value: String?,
    fallback: String?,
    link: SettingsDestination? = null,
    onOpen: (() -> Unit)? = null,
) {
    // Something not set up whose fallback names a place (a Settings page, the Workspace) opens it; back
    // returns to this step.
    val settings = Locus.settings
    val open = if (value != null) null else onOpen ?: link?.let { page -> settings?.let { { it.open(page) } } }
    ListRow(
        title,
        subtitle = value ?: fallback,
        subtitleMaxLines = 2,
        onClick = open,
        leading = { IconTile(icon, tint = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = when {
            value != null -> { { Icon(LocusIcons.Check, contentDescription = "Done", Modifier.size(IconSize.medium), tint = Locus.colors.live) } }
            open != null -> { { RowChevron() } }
            else -> null
        },
    )
}

/** The state of a one-off action, in one line (or a short block for longer results). */
@Composable
internal fun JobStatus(job: Job, running: String, modifier: Modifier = Modifier) {
    when {
        job.running -> StatusLine(running, Tone.Live, modifier)
        job.error != null -> Text(job.error, modifier, style = LocusType.supporting, color = Locus.colors.critical)
        job.result != null -> Row(modifier, verticalAlignment = Alignment.Top) {
            Icon(LocusIcons.Check, null, Modifier.padding(top = Spacing.xxs).size(IconSize.small), tint = Locus.colors.live)
            SupportingText(
                job.result,
                Modifier.padding(start = Spacing.sm),
                style = if ('\n' in job.result) LocusType.mono else LocusType.supporting,
            )
        }
    }
}

/** A quiet panel for command output and fingerprints. */
@Composable
internal fun OutputBlock(text: String, modifier: Modifier = Modifier) {
    CodeSurface(modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(Spacing.md), style = LocusType.mono)
    }
}
