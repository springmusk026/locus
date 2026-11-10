package dev.locus.feature.settings

import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SettingsLinkIn
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.Accent
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.codeSpans
import dev.locus.core.designsystem.accentFor
import androidx.compose.material3.TextButton
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.RememberedApproval
import dev.locus.core.model.toolLabel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.ToolPolicy
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.SwitchRow
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.model.SettingsDestination

/** Per-tool permissions, grouped by kind of tool. */
@Composable
internal fun PermissionsSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetPolicy: (ToolRow, ToolPolicy) -> Unit,
    onSetReviewerMode: (ReviewerMode) -> Unit = {},
    onSetReviewerOutward: (Boolean) -> Unit = {},
    onRevoke: (String) -> Unit = {},
) {
    SettingsPageScaffold(stringResource(R.string.settings_permissions_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_permissions_reviewer_section), SectionPadding) }
        item { ReviewerCard(state, onSetReviewerMode, onSetReviewerOutward) }
        item {
            SectionNote(
                stringResource(R.string.settings_permissions_reviewer_note),
                NotePadding,
            )
        }
        item { SectionLabel(stringResource(R.string.settings_permissions_tools_section), SectionPadding) }
        item { SectionNote(stringResource(R.string.settings_permissions_tools_note), Modifier.padding(top = Spacing.md, start = 0.dp)) }
        item {
            val groups = state.tools.groupBy { accentFor(it.name).first }.toSortedMap(compareBy { it.ordinal })
            CardGroup {
                groups.entries.forEachIndexed { i, (accent, tools) ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    PermissionGroup(accent, tools, onSetPolicy)
                }
            }
        }
        item { SectionNote(stringResource(R.string.settings_permissions_always_ask_note), NotePadding) }
        item { SectionLabel(stringResource(R.string.settings_permissions_remembered_section), SectionPadding) }
        item { RememberedCard(state.remembered, onRevoke) }
        item {
            SectionNote(
                stringResource(R.string.settings_permissions_remembered_note),
                NotePadding,
            )
        }
    }
}

/** The remembered approvals, each with Revoke. */
@Composable
private fun RememberedCard(rules: List<RememberedApproval>, onRevoke: (String) -> Unit) {
    CardGroup {
        if (rules.isEmpty()) {
            ListRow(title = stringResource(R.string.settings_permissions_remembered_empty_title), subtitle = stringResource(R.string.settings_permissions_remembered_empty_subtitle))
        }
        rules.forEachIndexed { i, rule ->
            if (i > 0) GroupDivider(inset = Insets.iconRow)
            val (accent, icon) = accentFor(rule.tool)
            ListRow(
                title = rememberedTitle(LocalResources.current, rule),
                subtitle = rememberedSubtitle(LocalResources.current, rule, System.currentTimeMillis()),
                titleMaxLines = 2,
                leading = { AccentTile(accent, icon, size = IconSize.tile) },
                trailing = { TextButton(onClick = { onRevoke(rule.id) }) { Text(stringResource(R.string.settings_permissions_revoke)) } },
            )
        }
    }
}

/**
 * What a remembered rule lets Locus do, in the tool's own words (toolLabel): "Run ./gradlew test …"
 * with the command in the code font, "Write files in src/main/", "Open github.com".
 */
internal fun rememberedTitle(res: Resources, rule: RememberedApproval): AnnotatedString {
    val text = rememberedTitleText(res, rule)
    // toolLabel marks the command with backticks; only a command's, since a folder or site could hold one.
    return if (rule.kind == ApprovalScopeKind.COMMAND) codeSpans(text) else AnnotatedString(text)
}

// The tool's verb comes from toolLabel (core/agent, English); the words put into it stay English with
// it, so a title doesn't mix languages. A calendar rule's title is ours, from resources.
private fun rememberedTitleText(res: Resources, rule: RememberedApproval): String {
    fun label(vararg args: Pair<String, String>): String =
        toolLabel(rule.tool, JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) }).toString(), done = false)
    return when (rule.kind) {
        ApprovalScopeKind.COMMAND -> label("command" to "${rule.value}\u00A0…", "host" to "any machine")
        ApprovalScopeKind.FOLDER -> {
            val folder = if (rule.value.isEmpty()) "the top folder" else "${rule.value}/"
            // The tool's verb ("Write", "Edit", "Delete") with the folder in place of one path.
            label("path" to FOLDER_SLOT, "from" to FOLDER_SLOT).replace(FOLDER_SLOT, "files in $folder")
        }
        ApprovalScopeKind.SITE -> {
            // fetch_url's rules carry the method first ("GET https://api.github.com").
            val method = rule.value.substringBefore(' ', "").takeIf { it.isNotEmpty() && it.all(Char::isUpperCase) }
            val site = if (method != null) rule.value.substringAfter(' ') else rule.value
            label("url" to site) + (method?.let { " ($it)" } ?: "")
        }
        ApprovalScopeKind.RECIPIENT -> label("to" to rule.value, "number" to rule.value)
        // "12:Personal": the calendar's name after its id.
        ApprovalScopeKind.CALENDAR -> {
            val name = rule.value.substringAfter(':', "").ifEmpty { res.getString(R.string.settings_permissions_remembered_your_calendar) }
            res.getString(
                if (rule.tool == dev.locus.core.model.DeviceIntegrations.CALENDAR_UPDATE) R.string.settings_permissions_remembered_calendar_change else R.string.settings_permissions_remembered_calendar_add,
                name,
            )
        }
    }
}

private const val FOLDER_SLOT = "\u0000"

/** Where and how long it applies: "This chat only", "Always · 12 days left", plus "in its project" when made in one. */
internal fun rememberedSubtitle(res: Resources, rule: RememberedApproval, now: Long): String {
    val expires = rule.expiresAt
    val span = when {
        rule.conversationId != null -> res.getString(R.string.settings_permissions_remembered_this_chat)
        expires == null -> res.getString(R.string.settings_permissions_remembered_always)
        else -> {
            val days = (expires - now) / (24L * 60 * 60 * 1000)
            if (days < 1) {
                res.getString(R.string.settings_permissions_remembered_ends_today)
            } else {
                res.getQuantityString(R.plurals.settings_permissions_remembered_days_left, days.toInt(), days.toInt())
            }
        }
    }
    return if (rule.projectId != null) res.getString(R.string.settings_permissions_remembered_in_project, span) else span
}

@Composable
private fun reviewerLabels() = listOf(
    stringResource(R.string.settings_permissions_reviewer_off),
    stringResource(R.string.settings_permissions_reviewer_asks),
    stringResource(R.string.settings_permissions_reviewer_all),
)

/** The reviewer's mode, the model it runs on, and whether it may approve outward-facing actions. */
@Composable
private fun ReviewerCard(state: SettingsUiState, onSetMode: (ReviewerMode) -> Unit, onSetOutward: (Boolean) -> Unit) {
    CardGroup {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Segmented(
                reviewerLabels(),
                selected = state.reviewerMode.ordinal,
                onSelect = { onSetMode(ReviewerMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            val resources = LocalResources.current
            val reviewerHint = reviewerModeHint(resources, state.reviewerMode, reviewerModelLabel(resources, state))
            SupportingText(
                reviewerHint,
            )
            // "… set a helper model in Settings → Helper model & extras": a link there.
            SettingsLinkIn(reviewerHint, destination = SettingsDestination.Extras.takeIf { state.reviewerMode != ReviewerMode.OFF && reviewerHelper(state) == null })
        }
        if (state.reviewerMode != ReviewerMode.OFF) {
            GroupDivider(inset = Spacing.lg)
            SwitchRow(
                title = stringResource(R.string.settings_permissions_outward_title),
                checked = state.reviewerApprovesOutward,
                onCheckedChange = onSetOutward,
                subtitle = stringResource(R.string.settings_permissions_outward_subtitle),
                icon = LocusIcons.Globe,
            )
        }
    }
}

private fun reviewerModeHint(res: Resources, mode: ReviewerMode, model: String): String = when (mode) {
    ReviewerMode.OFF -> res.getString(R.string.settings_permissions_reviewer_hint_off)
    ReviewerMode.REVIEW_ASKS -> res.getString(R.string.settings_permissions_reviewer_hint_asks, model)
    ReviewerMode.REVIEW_ALL_BUT_READS -> res.getString(R.string.settings_permissions_reviewer_hint_all, model)
}

/** The helper model's provider, or null when there is none and each chat's own model reviews. */
private fun reviewerHelper(state: SettingsUiState) = state.providers.firstOrNull { it.config.id == state.helperProviderId }?.config

/** The model that does the reviewing: the helper model, or each chat's own. */
internal fun reviewerModelLabel(res: Resources, state: SettingsUiState): String {
    val helper = reviewerHelper(state) ?: return res.getString(R.string.settings_permissions_reviewer_own_model)
    return res.getString(R.string.settings_summary_join, helper.name, dev.locus.core.model.ModelNames.short(state.helperModel.ifBlank { helper.model }))
}

@Composable
private fun groupTitle(accent: Accent) = stringResource(
    when (accent) {
        Accent.Files -> R.string.settings_permissions_group_files
        Accent.Web -> R.string.settings_permissions_group_web
        Accent.Linux -> R.string.settings_permissions_group_linux
        Accent.Memory -> R.string.settings_permissions_group_memory
        Accent.Device -> R.string.settings_permissions_group_device
        Accent.Connector -> R.string.settings_permissions_group_connector
        Accent.Agent -> R.string.settings_permissions_group_other
    },
)

@Composable
private fun policyLabels() = listOf(
    stringResource(R.string.settings_permissions_policy_allow),
    stringResource(R.string.settings_permissions_policy_ask),
    stringResource(R.string.settings_permissions_policy_deny),
)

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
            leading = { AccentTile(accent, accentFor(tools.first().name).second, size = IconSize.tile) },
            trailing = {
                Icon(
                    LocusIcons.ChevronRight,
                    contentDescription = stringResource(if (expanded) R.string.settings_permissions_collapse else R.string.settings_permissions_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            },
        )
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val shared = tools.map { it.policy }.distinct().singleOrNull()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_permissions_all_tools, tools.size), style = LocusType.label, modifier = Modifier.weight(1f))
                    Segmented(
                        policyLabels(),
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

private val SEGMENT_WIDTH = Sizes.policySegmented

/** "All 8 allowed", or "6 allowed · 2 ask first". */
@Composable
internal fun policySummary(total: Int, counts: Map<ToolPolicy, Int>): String =
    counts.keys.singleOrNull()?.let {
        stringResource(
            when (it) {
                ToolPolicy.ALLOW -> R.string.settings_permissions_all_allowed
                ToolPolicy.ASK -> R.string.settings_permissions_all_ask
                ToolPolicy.DENY -> R.string.settings_permissions_all_blocked
            },
            total,
        )
    } ?: summaryJoin(
        ToolPolicy.entries.mapNotNull { p ->
            counts[p]?.let {
                stringResource(
                    when (p) {
                        ToolPolicy.ALLOW -> R.string.settings_permissions_count_allowed
                        ToolPolicy.ASK -> R.string.settings_permissions_count_ask
                        ToolPolicy.DENY -> R.string.settings_permissions_count_blocked
                    },
                    it,
                )
            }
        },
    )

@Composable
private fun ToolPolicyRow(tool: ToolRow, onSetPolicy: (ToolRow, ToolPolicy) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tool.name, style = LocusType.mono, modifier = Modifier.weight(1f).padding(end = Spacing.sm), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Segmented(
            policyLabels(),
            selected = tool.policy.ordinal,
            onSelect = { onSetPolicy(tool, ToolPolicy.entries[it]) },
            modifier = Modifier.width(SEGMENT_WIDTH),
            compact = true,
            selectedTint = { policyTint(it) },
        )
    }
}
