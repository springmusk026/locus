package dev.locus.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.tools.AgentProfile
import dev.locus.core.data.repository.Library
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone

/**
 * Library → Helpers: the helper (sub-agent) types the agent can start. Built-in ones are listed for
 * reference; ones the agent drafts with agent_create wait here for review, like skills.
 */
@Composable
internal fun AgentsTab(library: Library, viewModel: LibraryViewModel, modifier: Modifier) {
    val padding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl)
    LazyColumn(modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item {
            SectionNote(stringResource(R.string.library_helpers_intro))
        }
        if (library.agents.isNotEmpty()) {
            item {
                CardGroup {
                    library.agents.forEachIndexed { i, agent ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        DefinitionRow(agent, viewModel, approvedActive = library.agents.any { !it.draft && it.name == agent.name })
                    }
                }
            }
        } else {
            item {
                SectionNote(stringResource(R.string.library_helpers_empty))
            }
        }
        item { SectionLabel(stringResource(R.string.library_helpers_built_in_label), Modifier.padding(start = 0.dp)) }
        item {
            CardGroup {
                AgentProfile.BUILT_IN.forEachIndexed { i, profile ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    val modelLabel = stringResource(if (profile.model == AgentProfile.MODEL_HELPER) R.string.library_helpers_model_helper else R.string.library_helpers_model_chat)
                    val effortLabel = profile.effort?.let { stringResource(R.string.library_helpers_effort, it.label) }
                    val stepsLabel = pluralStringResource(R.plurals.library_helpers_max_steps, profile.maxSteps, profile.maxSteps)
                    ExpandableRow(profile.label, profile.description, helperIcon(profile.type), iconDescription = stringResource(R.string.library_helpers_built_in_icon)) {
                        ToolsLine(profile.tools)
                        SupportingText(
                            listOfNotNull(modelLabel, effortLabel, stepsLabel).joinToString(" · "),
                            style = LocusType.mono,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefinitionRow(agent: AgentHome.AgentDefinition, viewModel: LibraryViewModel, approvedActive: Boolean) {
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmChanged by remember { mutableStateOf(false) }
    if (confirmChanged) {
        ConfirmApproveChanged(
            agent.name,
            stringResource(R.string.library_kind_helper),
            onDismiss = { confirmChanged = false },
            onConfirm = { confirmChanged = false; viewModel.approveAgent(agent.name) },
        )
    }
    if (confirmRemove) {
        ConfirmRemove(
            title = stringResource(R.string.library_helpers_remove_title, agent.name),
            body = stringResource(R.string.library_helpers_remove_body),
            action = stringResource(R.string.library_action_remove),
            onDismiss = { confirmRemove = false },
            onConfirm = { confirmRemove = false; viewModel.removeAgent(agent.name) },
        )
    }
    val modelChat = stringResource(R.string.library_helpers_model_chat)
    val modelHelper = stringResource(R.string.library_helpers_model_helper)
    ExpandableRow(
        agent.name,
        agent.description,
        LocusIcons.Sparkle,
        badge = when {
            agent.changedOutside -> stringResource(R.string.library_changed_outside_badge)
            agent.draft -> stringResource(R.string.library_badge_needs_review)
            else -> null
        },
        badgeTone = if (agent.changedOutside) Tone.Critical else Tone.Caution,
        startExpanded = agent.draft,
        iconDescription = stringResource(R.string.library_helpers_own_icon),
    ) {
        if (agent.changedOutside) ChangedOutsideBanner(stringResource(R.string.library_kind_helper), approvedActive)
        ToolsLine(agent.tools)
        val modelLabel = when (agent.model.lowercase()) {
            "inherit", "same" -> modelChat
            "helper" -> modelHelper
            else -> agent.model
        }
        val effortLabel = agent.effort?.let { stringResource(R.string.library_helpers_effort, it) }
        val stepsLabel = agent.maxSteps?.let { pluralStringResource(R.plurals.library_helpers_max_steps, it, it) }
        val backgroundLabel = if (agent.background) stringResource(R.string.library_helpers_background) else null
        SupportingText(
            listOfNotNull(modelLabel, effortLabel, stepsLabel, backgroundLabel).joinToString(" · "),
            style = LocusType.mono,
        )
        Markdown(
            content = agent.body,
            modifier = Modifier.fillMaxWidth().codeWell(),
        )
        // Actions wrap to a second line at a large font instead of running off the card.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (agent.draft) {
                OutlinedButton(onClick = { viewModel.rejectAgent(agent.name) }) { Text(stringResource(if (agent.changedOutside) R.string.library_helpers_discard_change else R.string.library_action_reject)) }
                Button(onClick = { if (agent.changedOutside) confirmChanged = true else viewModel.approveAgent(agent.name) }) { Text(stringResource(R.string.library_action_approve)) }
            } else {
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.library_action_remove), color = Locus.colors.critical) }
            }
        }
    }
}

/** A built-in helper's mark, by what it's for. */
internal fun helperIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    "researcher" -> LocusIcons.Search
    "coder" -> LocusIcons.Code
    "explorer" -> LocusIcons.Folder
    "ops" -> LocusIcons.Terminal
    "browser" -> LocusIcons.Globe
    else -> LocusIcons.Sparkle
}

@Composable
private fun ToolsLine(tools: List<String>) {
    SupportingText(stringResource(R.string.library_helpers_tools, tools.joinToString()))
}

