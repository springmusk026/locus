package dev.locus.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.tools.AgentProfile
import dev.locus.core.data.repository.Library
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ExpandableRow

/**
 * Library → Helpers: the helper (sub-agent) types the agent can start. Built-in ones are listed for
 * reference; ones the agent drafts with agent_create wait here for review, like skills.
 */
@Composable
internal fun AgentsTab(library: Library, viewModel: LibraryViewModel, modifier: Modifier) {
    val padding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xxl)
    LazyColumn(modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        item {
            SectionNote(
                "Helpers are agents Locus can hand a focused job to, each with its own fresh context and only the tools its role needs. " +
                    "They ask you before anything that needs approval, and their work shows as nested steps in the chat.",
                Modifier.padding(start = Spacing.xs),
            )
        }
        if (library.agents.isNotEmpty()) {
            item {
                CardGroup {
                    library.agents.forEachIndexed { i, agent ->
                        if (i > 0) GroupDivider(inset = Insets.iconRow)
                        DefinitionRow(agent, viewModel)
                    }
                }
            }
        } else {
            item {
                SectionNote(
                    "You have no helpers of your own yet. Ask Locus to “make a helper that…” and it drafts one for you to review here.",
                    Modifier.padding(start = Spacing.xs),
                )
            }
        }
        item { SectionLabel("Built in", Modifier.padding(start = 0.dp)) }
        item {
            CardGroup {
                AgentProfile.BUILT_IN.forEachIndexed { i, profile ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    ExpandableRow(profile.label, profile.description, LocusIcons.Sparkle) {
                        ToolsLine(profile.tools)
                        Text(
                            buildString {
                                append(if (profile.model == AgentProfile.MODEL_HELPER) "Helper model" else "Chat's model")
                                profile.effort?.let { append(" · ${it.label} effort") }
                                append(" · up to ${profile.maxSteps} steps")
                            },
                            style = MonoCaption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefinitionRow(agent: AgentHome.AgentDefinition, viewModel: LibraryViewModel) {
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        ConfirmRemove(
            title = "Remove “${agent.name}”?",
            body = "Locus can't start this helper any more. Its definition is deleted from this phone.",
            action = "Remove",
            onDismiss = { confirmRemove = false },
            onConfirm = { confirmRemove = false; viewModel.removeAgent(agent.name) },
        )
    }
    ExpandableRow(agent.name, agent.description, LocusIcons.Sparkle, badge = if (agent.draft) "Needs review" else null, startExpanded = agent.draft) {
        ToolsLine(agent.tools)
        Text(
            buildString {
                append(
                    when (agent.model.lowercase()) {
                        "inherit", "same" -> "Chat's model"
                        "helper" -> "Helper model"
                        else -> agent.model
                    },
                )
                agent.effort?.let { append(" · $it effort") }
                agent.maxSteps?.let { append(" · up to $it steps") }
                if (agent.background) append(" · runs in the background")
            },
            style = MonoCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Markdown(
            content = agent.body,
            modifier = Modifier.fillMaxWidth().codeWell(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            if (agent.draft) {
                OutlinedButton(onClick = { viewModel.rejectAgent(agent.name) }) { Text("Reject") }
                Button(onClick = { viewModel.approveAgent(agent.name) }) { Text("Approve") }
            } else {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove", color = Locus.colors.critical) }
            }
        }
    }
}

@Composable
private fun ToolsLine(tools: List<String>) {
    Text(
        "Tools: ${tools.joinToString()}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

