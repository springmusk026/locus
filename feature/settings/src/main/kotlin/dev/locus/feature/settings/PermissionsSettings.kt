package dev.locus.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.Accent
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.accentFor
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.ToolPolicy
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Insets

/** Per-tool permissions, grouped by kind of tool. */
@Composable
internal fun PermissionsSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetPolicy: (ToolRow, ToolPolicy) -> Unit,
    onSetReviewerMode: (ReviewerMode) -> Unit = {},
    onSetReviewerOutward: (Boolean) -> Unit = {},
) {
    SettingsPageScaffold("Permissions", onBack) {
        item { SectionLabel("Safety reviewer", SectionPadding) }
        item { ReviewerCard(state, onSetReviewerMode, onSetReviewerOutward) }
        item {
            SectionNote(
                "A second AI model checks actions against what you asked before they run. It sees your recent messages and the action, " +
                    "never web pages, emails or files the agent read. Anything it isn't sure about comes to you, and Deny below always wins.",
                NotePadding,
            )
        }
        item { SectionLabel("Tools", SectionPadding) }
        item { SectionNote("Allow runs without asking. Ask shows you each action first. Deny blocks the tool.", Modifier.padding(top = Spacing.md, start = 0.dp)) }
        item {
            val groups = state.tools.groupBy { accentFor(it.name).first }.toSortedMap(compareBy { it.ordinal })
            CardGroup {
                groups.entries.forEachIndexed { i, (accent, tools) ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    PermissionGroup(accent, tools, onSetPolicy)
                }
            }
        }
        item { SectionNote("Some actions, like sensitive clicks in the browser, always ask regardless of these settings.", NotePadding) }
    }
}

private val REVIEWER_LABELS = listOf("Off", "Ask actions", "All changes")

/** The reviewer's mode, the model it runs on, and whether it may approve outward-facing actions. */
@Composable
private fun ReviewerCard(state: SettingsUiState, onSetMode: (ReviewerMode) -> Unit, onSetOutward: (Boolean) -> Unit) {
    CardGroup {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Segmented(
                REVIEWER_LABELS,
                selected = state.reviewerMode.ordinal,
                onSelect = { onSetMode(ReviewerMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                reviewerModeHint(state.reviewerMode, reviewerModelLabel(state)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.reviewerMode != ReviewerMode.OFF) {
            GroupDivider(inset = Spacing.lg)
            SwitchRow(
                "Let it approve outward-facing actions",
                "Sending email, deploying, paying and similar. Off: these always come to you.",
                LocusIcons.Globe,
                checked = state.reviewerApprovesOutward,
                onChange = onSetOutward,
            )
        }
    }
}

private fun reviewerModeHint(mode: ReviewerMode, model: String) = when (mode) {
    ReviewerMode.OFF -> "Actions set to Ask always ask you."
    ReviewerMode.REVIEW_ASKS -> "Checks actions set to Ask: runs the clearly fine ones, blocks the clearly wrong ones, asks you about the rest. Uses $model."
    ReviewerMode.REVIEW_ALL_BUT_READS -> "Also checks allowed actions that change something; any it doesn't clear come to you. Uses $model."
}

/** The model that does the reviewing: the helper model, or each chat's own. */
internal fun reviewerModelLabel(state: SettingsUiState): String {
    val helper = state.providers.firstOrNull { it.config.id == state.helperProviderId }?.config
        ?: return "each chat's own model (set a helper model in Settings → Helper model & extras for a faster one)"
    return "${helper.name} · ${state.helperModel.ifBlank { helper.model }}"
}

private fun groupTitle(accent: Accent) = when (accent) {
    Accent.Files -> "Files"
    Accent.Web -> "Web"
    Accent.Linux -> "Linux & machines"
    Accent.Memory -> "Memory & skills"
    Accent.Device -> "Phone apps"
    Accent.Connector -> "Integrations & connectors"
    Accent.Agent -> "Other"
}

private val POLICY_LABELS = listOf("Allow", "Ask", "Deny")

@Composable
private fun policyTint(index: Int) = when (ToolPolicy.entries[index]) {
    ToolPolicy.ALLOW -> MaterialTheme.colorScheme.onSurface
    ToolPolicy.ASK -> Locus.colors.caution
    ToolPolicy.DENY -> Locus.colors.critical
}

/** One kind of tool, collapsed to a summary; open it to set each tool or the whole group at once. */
@Composable
private fun PermissionGroup(accent: Accent, tools: List<ToolRow>, onSetPolicy: (ToolRow, ToolPolicy) -> Unit) {
    var expanded by rememberSaveable(accent.name) { mutableStateOf(false) }
    val counts = tools.groupingBy { it.policy }.eachCount()
    Column {
        ListRow(
            title = groupTitle(accent),
            subtitle = policySummary(tools.size, counts),
            onClick = { expanded = !expanded },
            leading = { AccentTile(accent, accentFor(tools.first().name).second, size = 36.dp) },
            trailing = {
                Icon(
                    LocusIcons.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            },
        )
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val shared = tools.map { it.policy }.distinct().singleOrNull()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All ${tools.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Segmented(
                        POLICY_LABELS,
                        selected = shared?.ordinal ?: -1,
                        onSelect = { i -> tools.forEach { onSetPolicy(it, ToolPolicy.entries[i]) } },
                        modifier = Modifier.width(SEGMENT_WIDTH),
                        compact = true,
                        selectedTint = { policyTint(it) },
                    )
                }
                GroupDivider(inset = 0.dp)
                tools.forEach { tool -> ToolPolicyRow(tool, onSetPolicy) }
            }
        }
    }
}

private val SEGMENT_WIDTH = 156.dp

private fun policyWord(p: ToolPolicy) = when (p) {
    ToolPolicy.ALLOW -> "allowed"
    ToolPolicy.ASK -> "ask first"
    ToolPolicy.DENY -> "blocked"
}

/** "All 8 allowed", or "6 allowed · 2 ask first". */
internal fun policySummary(total: Int, counts: Map<ToolPolicy, Int>): String =
    counts.keys.singleOrNull()?.let { "All $total ${policyWord(it)}" }
        ?: ToolPolicy.entries.mapNotNull { p -> counts[p]?.let { "$it ${policyWord(p)}" } }.joinToString(" · ")

@Composable
private fun ToolPolicyRow(tool: ToolRow, onSetPolicy: (ToolRow, ToolPolicy) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tool.name, style = MonoCaption, modifier = Modifier.weight(1f).padding(end = Spacing.sm), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Segmented(
            POLICY_LABELS,
            selected = tool.policy.ordinal,
            onSelect = { onSetPolicy(tool, ToolPolicy.entries[it]) },
            modifier = Modifier.width(SEGMENT_WIDTH),
            compact = true,
            selectedTint = { policyTint(it) },
        )
    }
}
