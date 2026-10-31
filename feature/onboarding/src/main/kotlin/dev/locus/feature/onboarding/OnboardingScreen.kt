package dev.locus.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusLine
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.entrance

/**
 * First-run setup, also re-runnable from Settings. [onFinish] gets the id of a first chat when one
 * was started; [onExit] leaves from the first step (closing the app on a first run).
 */
@Composable
fun OnboardingRoute(onFinish: (chatId: String?) -> Unit, onExit: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
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
                    status = { Text("Step ${step.ordinal + 1} of ${Step.entries.size}", style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                    Step.Linux -> LinuxStep(state, viewModel)
                    Step.Ssh -> SshStep(state, viewModel)
                    Step.Connectors -> ConnectorsStep(state, viewModel)
                    Step.Test -> TestStep(state, viewModel)
                    Step.Done -> DoneStep(state, viewModel)
                }
                Box(Modifier.height(Spacing.xl))
            }
        }
    }
}

/** One thin track per step: done steps filled, the current one filling in. */
@Composable
private fun StepProgress(step: Step) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Step.entries.forEach { s ->
            val fill by animateFloatAsState(if (s.ordinal <= step.ordinal) 1f else 0f, label = "progress")
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(Locus.colors.track)) {
                Box(Modifier.fillMaxWidth(fill).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun BottomAction(step: Step, state: OnboardingUiState, viewModel: OnboardingViewModel, onFinish: (String?) -> Unit) {
    val (label, enabled) = when (step) {
        Step.Intro -> "Get started" to true
        Step.Privacy -> "Continue" to viewModel.consent
        Step.Provider -> (if (state.providers.isEmpty()) "Set up a model to continue" else "Continue") to state.providers.isNotEmpty()
        Step.Identity -> (if (viewModel.identitySave.running) "Saving…" else "Save and continue") to (viewModel.personaLoaded && !viewModel.identitySave.running)
        Step.Done -> (if (state.providers.isEmpty()) "Finish" else "Start chatting") to !viewModel.finishing
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
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(label) }
    }
}

// ---- Steps ---------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.IntroStep() {
    StepHeading("Your agent, on your phone", "Locus is an AI agent that works for you: it reads, writes, browses and runs code to get things done.", Modifier.entrance(0))
    CardGroup(Modifier.entrance(1)) {
        Fact(LocusIcons.Phone, "Runs here", "The agent, its tools, your files and its memory all live on this phone.")
        GroupDivider(ROW_INSET)
        Fact(LocusIcons.Key, "Your keys", "Bring a key from the AI provider you choose, or run a model on the phone itself. No Locus account.")
        GroupDivider(ROW_INSET)
        Fact(LocusIcons.Shield, "You decide", "Every action goes through your approval rules, and everything it does is logged.")
    }
    Text(
        "Setup takes a few minutes. Everything after the model step is optional and can be changed later in Settings.",
        Modifier.entrance(2),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.PrivacyStep(viewModel: OnboardingViewModel) {
    StepHeading("What leaves your phone", "Locus has no servers. Here is exactly where your data goes.")
    CardGroup {
        Fact(LocusIcons.Send, "Straight to your provider", "Your messages and the files the agent reads go directly to the AI provider you pick, under that provider's terms. With the on-device model, nothing leaves at all.")
        GroupDivider(ROW_INSET)
        Fact(LocusIcons.Key, "Keys stay encrypted", "API keys and passwords are kept in an encrypted vault on this phone, never shown to the model.")
        GroupDivider(ROW_INSET)
        Fact(LocusIcons.Globe, "Only where you point it", "Websites, services and machines are contacted only when you, or the agent with your approval, use them.")
    }
    SectionLabel("Approvals")
    CardGroup {
        Fact(LocusIcons.Check, "Allow", "Runs without asking.")
        GroupDivider(ROW_INSET)
        Fact(LocusIcons.Shield, "Ask", "Waits for you to approve each time, in the app or from a notification.")
        GroupDivider(ROW_INSET)
        Fact(LocusIcons.Close, "Deny", "Never runs.")
    }
    SectionLabel("Starting point")
    CardGroup {
        PermissionProfile.entries.forEachIndexed { i, p ->
            if (i > 0) GroupDivider(Spacing.lg + 48.dp)
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.pickProfile(p) }.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = (viewModel.profile ?: PermissionProfile.Balanced) == p, onClick = { viewModel.pickProfile(p) })
                Column(Modifier.weight(1f).padding(start = Spacing.xs, end = Spacing.sm)) {
                    Text(p.title, style = MaterialTheme.typography.bodyLarge)
                    Text(p.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text(
        "You can change any single tool later in Settings → Permissions.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LocusCard(outline = if (viewModel.consent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else null, onClick = { viewModel.agree(!viewModel.consent) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = viewModel.consent, onCheckedChange = viewModel::agree)
            Text(
                "I understand that what I ask Locus to work on is sent to the AI provider I choose, and that the agent acts within the approvals above.",
                Modifier.padding(start = Spacing.xs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ColumnScope.DoneStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeading("${viewModel.answers.agentName.trim().ifBlank { "Locus" }} is ready", "Here is what you set up. All of it can be changed in Settings.")
    CardGroup {
        Summary(LocusIcons.Sparkle, "Model", state.defaultProvider?.let { "${it.name} · ${it.model}" }, "Not set up. Add one in Settings → Models.")
        GroupDivider(ROW_INSET)
        Summary(LocusIcons.Note, "Agent", "${viewModel.answers.agentName.trim().ifBlank { "Locus" }} · ${viewModel.answers.style.label.lowercase()}", null)
        GroupDivider(ROW_INSET)
        Summary(LocusIcons.Shield, "Approvals", viewModel.profile?.title ?: "Unchanged", null)
        GroupDivider(ROW_INSET)
        Summary(LocusIcons.Terminal, "Linux", if (state.linux == dev.locus.core.linux.LinuxStatus.Ready) "Installed" else null, "Not installed. The agent installs it the first time it needs a shell.")
        GroupDivider(ROW_INSET)
        Summary(LocusIcons.Server, "Machines", state.sshHosts.takeIf { it.isNotEmpty() }?.joinToString { it.alias }, "None")
        GroupDivider(ROW_INSET)
        Summary(LocusIcons.Plug, "Integrations", state.integrations.filter { it.second }.takeIf { it.isNotEmpty() }?.joinToString { it.first.name }, "None")
    }
}

// ---- Shared pieces -------------------------------------------------------------------------------

internal val ROW_INSET = Spacing.lg + 36.dp + Spacing.md

@Composable
internal fun StepHeading(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = Spacing.sm, bottom = Spacing.xs, start = Spacing.xs, end = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Fact(icon: ImageVector, title: String, body: String) {
    ListRow(title, subtitle = body, subtitleMaxLines = 4, leading = { IconTile(icon) })
}

@Composable
private fun Summary(icon: ImageVector, title: String, value: String?, fallback: String?) {
    ListRow(
        title,
        subtitle = value ?: fallback,
        subtitleMaxLines = 2,
        leading = { IconTile(icon, tint = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = if (value != null) { { Icon(LocusIcons.Check, null, Modifier.size(18.dp), tint = Locus.colors.live) } } else null,
    )
}

/** The state of a one-off action, in one line (or a short block for longer results). */
@Composable
internal fun JobStatus(job: Job, running: String, modifier: Modifier = Modifier) {
    when {
        job.running -> StatusLine(running, Tone.Live, modifier)
        job.error != null -> Text(job.error, modifier, style = MaterialTheme.typography.bodySmall, color = Locus.colors.critical)
        job.result != null -> Row(modifier, verticalAlignment = Alignment.Top) {
            Icon(LocusIcons.Check, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Locus.colors.live)
            Text(
                job.result,
                Modifier.padding(start = Spacing.sm),
                style = if ('\n' in job.result) MonoCaption else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A quiet panel for command output and fingerprints. */
@Composable
internal fun OutputBlock(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), RoundedCornerShape(Radius.control)).padding(Spacing.md),
        style = MonoCaption,
    )
}
