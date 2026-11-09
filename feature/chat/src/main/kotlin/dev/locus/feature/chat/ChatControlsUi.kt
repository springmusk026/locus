package dev.locus.feature.chat

import androidx.compose.ui.res.stringResource
import dev.locus.core.ui.largeFont
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ContextRing
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.SettingsLinkIn
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Stroke
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.model.SettingsDestination
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.ChatGlyphs
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.ChatMode
import dev.locus.core.model.Effort
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.model.ToolGroup
import kotlinx.coroutines.delay

/** Which composer sheet is open. */
internal enum class ControlSheet { Model, Effort, Mode, Tools, More, Budget, Temporary }

/** "Off", "200k tokens · 30 min (this chat)", "Default: 200k tokens". */
internal fun budgetSubtitle(controls: ChatControls): String {
    val own = controls.ownBudget
    return when {
        own != null -> "${dev.locus.core.designsystem.TaskBudgetPresets.summary(own)} for this chat"
        controls.defaultBudget.isOff -> "Off"
        else -> "Default: ${dev.locus.core.designsystem.TaskBudgetPresets.summary(controls.defaultBudget)}"
    }
}

/**
 * The chat's task budget: the default from Settings, or its own limits. Applies from the next task;
 * a task that reaches a limit pauses between steps and asks. While a task runs, [meter] shows its
 * use so far and its limits on top.
 */
@Composable
internal fun BudgetSheet(
    controls: ChatControls,
    onSet: (dev.locus.core.model.TaskBudget?) -> Unit,
    onDismiss: () -> Unit,
    meter: dev.locus.core.agent.BudgetMeter? = null,
) {
    val own = controls.ownBudget
    LocusSheet(title = stringResource(R.string.chat_budget_title), subtitle = stringResource(R.string.chat_budget_subtitle), onDismiss = onDismiss) {
        if (meter != null) {
            SectionLabel(stringResource(R.string.chat_budget_this_task))
            LocusCard {
                Text(stringResource(R.string.chat_budget_used, dev.locus.core.agent.BudgetText.used(meter)), style = LocusType.rowTitle)
                dev.locus.core.agent.BudgetText.limits(meter)?.let {
                    SupportingText(stringResource(R.string.chat_budget_limits, it))
                }
            }
            SectionNote(stringResource(R.string.chat_budget_next_task))
        }
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            EffortRow(
                stringResource(R.string.chat_budget_use_default),
                stringResource(R.string.chat_budget_default_for_every, dev.locus.core.designsystem.TaskBudgetPresets.summary(controls.defaultBudget)),
                selected = own == null,
            ) { onSet(null) }
            GroupDivider(inset = Spacing.md)
            EffortRow(stringResource(R.string.chat_budget_set_chat), stringResource(R.string.chat_budget_set_chat_hint), selected = own != null) {
                if (own == null) onSet(controls.defaultBudget)
            }
        }
        SettingsLink(SettingsDestination.Agent, hint = stringResource(R.string.chat_budget_change_default))
        if (own != null) {
            LocusCard {
                dev.locus.core.designsystem.TaskBudgetEditor(own, onChange = { onSet(it) }, costNote = controls.costNote)
            }
        } else if (controls.costNote != null && controls.defaultBudget.costUsd != null) {
            SectionNote(controls.costNote)
            SettingsLinkIn(controls.costNote)
        }
        SectionNote(stringResource(R.string.chat_budget_limit_note))
        SectionNote(stringResource(R.string.chat_budget_tokens_note))
    }
}

/** Thinking effort for this chat. */
@Composable
internal fun EffortSheet(provider: ChatProvider?, controls: ChatControls, onPick: (Effort?) -> Unit, onDismiss: () -> Unit) {
    val model = provider?.model?.ifBlank { null }?.let(dev.locus.core.model.ModelNames::short)
    LocusSheet(
        title = stringResource(R.string.chat_effort_title),
        subtitle = if (model != null) stringResource(R.string.chat_effort_subtitle_model, model) else stringResource(R.string.chat_effort_subtitle),
        onDismiss = onDismiss,
    ) {
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            val note = provider?.defaultEffortNote?.ifBlank { null } ?: stringResource(R.string.chat_effort_provider_setting, (provider?.defaultEffort ?: Effort.HIGH).label)
            EffortRow(stringResource(R.string.chat_effort_provider_default), note, selected = !controls.effortOverridden) { onPick(null) }
            Effort.entries.forEach { e ->
                GroupDivider(inset = Spacing.md)
                EffortRow(e.label, e.description, selected = controls.effortOverridden && controls.effort == e) { onPick(e) }
            }
        }
        SectionNote(stringResource(R.string.chat_effort_note))
        SettingsLink(SettingsDestination.Models, hint = stringResource(R.string.chat_effort_hint))
    }
}

/** How much this chat may change: Read-only, Workspace or Full. */
@Composable
internal fun ModeSheet(controls: ChatControls, onPick: (ChatMode) -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = stringResource(R.string.chat_mode_title), subtitle = stringResource(R.string.chat_mode_subtitle), onDismiss = onDismiss) {
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            ChatMode.entries.forEachIndexed { i, m ->
                if (i > 0) GroupDivider(inset = Spacing.md)
                EffortRow(m.label, m.description, selected = controls.mode == m) { onPick(m) }
            }
        }
        SettingsLink(
            SettingsDestination.Permissions,
            hint = stringResource(R.string.chat_mode_hint),
        )
    }
}

@Composable
private fun EffortRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LocusType.rowTitle)
            SupportingText(subtitle)
        }
        if (selected) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_controls_selected), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.medium))
    }
}

/** Tool groups and connectors this chat may use. */
@Composable
internal fun ToolsSheet(
    controls: ChatControls,
    connectors: List<ChatConnector>,
    onSetEnabled: (key: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    LocusSheet(title = stringResource(R.string.chat_tools_title), onDismiss = onDismiss) {
        if (controls.chatOnly) ChatOnlyTools(controls.empty) else ToolSwitches(controls, connectors, onSetEnabled)
    }
}

/** A chat-only chat's tools sheet: nothing to switch, and why ([ChatControls.chatOnly]). */
@Composable
private fun ChatOnlyTools(empty: Boolean) {
    LocusCard {
        Text(stringResource(R.string.chat_tools_none_title), style = LocusType.rowTitle)
        SupportingText(
            stringResource(if (empty) R.string.chat_tools_none_empty else R.string.chat_tools_none_started),
            Modifier.padding(top = Spacing.xs),
        )
    }
}

/** The switches for the chat's tool groups and connectors, in the sheet's own column. */
@Composable
@Suppress("UnusedReceiverParameter") // Placed in the sheet's column, like the rest of its content.
private fun ColumnScope.ToolSwitches(controls: ChatControls, connectors: List<ChatConnector>, onSetEnabled: (key: String, enabled: Boolean) -> Unit) {
    SectionLabel(stringResource(R.string.chat_tools_label))
    CardGroup {
        ToolGroup.entries.forEachIndexed { i, g ->
            if (i > 0) GroupDivider()
            SwitchRow(g.label, checked = g.key !in controls.disabledTools, onCheckedChange = { onSetEnabled(g.key, it) }, subtitle = g.description)
        }
    }
    if (connectors.isNotEmpty()) {
        SectionLabel(stringResource(R.string.chat_tools_connectors))
        CardGroup {
            connectors.forEachIndexed { i, c ->
                if (i > 0) GroupDivider()
                val key = ToolGroup.connectorKey(c.id)
                SwitchRow(c.name, checked = key !in controls.disabledTools, onCheckedChange = { onSetEnabled(key, it) }, subtitle = stringResource(if (c.mcp) R.string.chat_tools_mcp else R.string.chat_tools_http))
            }
        }
    }
    SettingsLink(SettingsDestination.Permissions, hint = stringResource(R.string.chat_tools_hint))
}

/** A proposed plan. While it waits, it asks: run it, change it, or drop it. */
@Composable
internal fun PlanCard(item: ChatItem.Plan, actionable: Boolean, onApprove: () -> Unit, onEdit: () -> Unit, onCancel: () -> Unit) {
    val brand = MaterialTheme.colorScheme.primary
    // While it waits, the brand outline marks it; no glow on top.
    LocusCard(outline = brand.copy(alpha = Emphasis.toneOutline).takeIf { actionable }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LocusIcons.Note, contentDescription = null, tint = brand, modifier = Modifier.size(IconSize.medium))
            SupportingText(
                stringResource(R.string.chat_plan_label),
                Modifier.padding(start = Spacing.sm).weight(1f),
                style = LocusType.label,
            )
            val status = when (item.status) {
                PlanStatus.Waiting -> if (actionable) null else stringResource(R.string.chat_plan_not_run)
                PlanStatus.Approved -> stringResource(R.string.chat_plan_approved)
                PlanStatus.Answered -> stringResource(R.string.chat_plan_changed)
            }
            if (status != null) SupportingText(status, style = LocusType.chip)
        }
        Text(item.plan.title, Modifier.padding(top = Spacing.xs), style = LocusType.stateTitle)
        Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            item.plan.steps.forEachIndexed { i, step ->
                Row {
                    SupportingText("${i + 1}.", Modifier.widthIn(min = Spacing.xl), style = LocusType.mono.copy(fontSize = LocusType.rowSupporting.fontSize))
                    Text(step, style = LocusType.rowSupporting)
                }
            }
        }
        item.plan.notes?.let { notes -> Box(Modifier.padding(top = Spacing.sm)) { ThoughtMarkdown(notes) } }
        if (actionable && largeFont()) {
            // Stacked at a large font, full width, so "Approve and run" never breaks inside its button.
            Column(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Button(onClick = onApprove, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_plan_approve)) }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_plan_edit)) }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_plan_cancel)) }
            }
        } else if (actionable) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = Spacing.sm)) { Text(stringResource(R.string.chat_plan_cancel)) }
                Box(Modifier.weight(1f))
                OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.chat_plan_edit)) }
                Button(onClick = onApprove, modifier = Modifier.padding(start = Spacing.sm)) { Text(stringResource(R.string.chat_plan_approve)) }
            }
        }
    }
}

/** The checklist's one-line summary: "1 of 4 done · Redesign the portfolio". */
internal fun checklistLine(todos: List<TodoItem>): String {
    val done = todos.count { it.status == TodoStatus.COMPLETED }
    val current = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS } ?: todos.firstOrNull { it.status == TodoStatus.PENDING }
    return "$done of ${todos.size} done" + (current?.let { " · ${it.shown}" } ?: "")
}

/**
 * The current turn's checklist, docked into the composer's top edge as one slim row: progress and
 * the item being worked on. A tap opens every item above the row, still inside the composer.
 */
@Composable
internal fun ChecklistStrip(todos: List<TodoItem>, running: Boolean, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val done = todos.count { it.status == TodoStatus.COMPLETED }
    val current = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS } ?: todos.firstOrNull { it.status == TodoStatus.PENDING }
    val checklistDescription = stringResource(R.string.chat_checklist_description, checklistLine(todos))
    val expandedDescription = stringResource(if (open) R.string.chat_changes_expanded else R.string.chat_changes_collapsed)
    Column(modifier.fillMaxWidth().animateContentSize()) {
        if (open) {
            Column(Modifier.heightIn(max = Sizes.inlineScroll).verticalScroll(rememberScrollState()).padding(start = Spacing.lg, end = Spacing.md, top = Spacing.sm)) {
                todos.forEach { TodoRow(it, running) }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = stringResource(if (open) R.string.chat_checklist_collapse else R.string.chat_checklist_expand)) { open = !open }
                .padding(start = Spacing.lg, end = Spacing.md)
                .semantics {
                    contentDescription = checklistDescription
                    stateDescription = expandedDescription
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(done, todos.size)
            Text(
                checklistLine(todos),
                Modifier.weight(1f).padding(start = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
                style = LocusType.rowSupporting,
                // Two lines, so the step being worked on is still named at a large font.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (running && current?.status == TodoStatus.IN_PROGRESS && !open) StatusDot(Tone.Live, Modifier.padding(horizontal = Spacing.xs))
            Icon(
                LocusIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(IconSize.small).rotate(if (open) 90f else 270f),
            )
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, running: Boolean) {
    val doneLabel = stringResource(R.string.chat_checklist_done)
    val inProgress = stringResource(R.string.chat_checklist_in_progress)
    val toDo = stringResource(R.string.chat_checklist_to_do)
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(IconSize.medium), contentAlignment = Alignment.Center) {
            when (item.status) {
                TodoStatus.COMPLETED -> Icon(LocusIcons.Check, contentDescription = doneLabel, tint = Locus.colors.live, modifier = Modifier.size(IconSize.small))
                TodoStatus.IN_PROGRESS -> if (running) {
                    StatusDot(Tone.Live, Modifier.semantics { contentDescription = inProgress })
                } else {
                    Box(Modifier.size(TodoMark).border(Stroke.bold, MaterialTheme.colorScheme.primary, CircleShape).semantics { contentDescription = inProgress })
                }
                TodoStatus.PENDING -> Box(
                    Modifier.size(TodoMark).border(Stroke.regular, MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.disabled), CircleShape)
                        .semantics { contentDescription = toDo },
                )
            }
        }
        Text(
            item.shown,
            Modifier.padding(start = Spacing.sm),
            style = LocusType.rowSupporting,
            fontWeight = if (item.status == TodoStatus.IN_PROGRESS) FontWeight.Medium else null,
            color = if (item.status == TodoStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProgressRing(done: Int, total: Int) {
    ContextRing(if (total == 0) 0f else done.toFloat() / total, size = IconSize.medium, color = Locus.colors.live)
}

/** A to-do's open ring: a little larger than a dot, so it reads as an empty box rather than a state. */
private val TodoMark = IconSize.dot + Stroke.bold

/** A step's kept reasoning: "Thought for 12s", tap to read it. */
@Composable
internal fun ThoughtRow(item: ChatItem.Thought, modifier: Modifier = Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = stringResource(if (open) R.string.chat_thought_hide else R.string.chat_thought_show)) { open = !open }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ChatGlyphs.Thought, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.small))
            SupportingText(
                thoughtLabel(item.durationMs),
                Modifier.padding(start = Spacing.xs),
                style = LocusType.chip,
            )
            ExpandChevron(open)
        }
        AnimatedVisibility(open) {
            Box(Modifier.padding(start = ThoughtIndent, top = Spacing.xs, bottom = Spacing.xs)) { ThoughtMarkdown(item.text) }
        }
    }
}

/** Points down when folded, up when open; the row's click label says what tapping does. */
@Composable
private fun ExpandChevron(open: Boolean) {
    Icon(
        LocusIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xs).size(IconSize.small).rotate(if (open) -90f else 90f),
    )
}

/** Reasoning text lines up with the label above it: past the icon and its gap. */
private val ThoughtIndent = IconSize.small + Spacing.xs

internal fun thoughtLabel(durationMs: Long): String {
    val s = (durationMs / 1000).toInt()
    return when {
        durationMs <= 0 -> "Thought"
        s < 1 -> "Thought for a moment"
        s < 60 -> "Thought for ${s}s"
        else -> "Thought for ${s / 60}m ${s % 60}s"
    }
}

/**
 * Reasoning while it streams: open with the latest lines while the model thinks, then folded to
 * "Thought for Ns" once the answer or a tool call begins. Plain text, so growing text never re-lays
 * out Markdown blocks (no flicker); the saved turn shows it as Markdown.
 */
@Composable
internal fun LiveThinking(reasoning: () -> String, since: Long, thoughtMs: Long?) {
    val thinking = thoughtMs == null
    // Follows the phase until the user taps; then their choice sticks for this step.
    var userChoice by remember(since) { mutableStateOf<Boolean?>(null) }
    val open = userChoice ?: thinking
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(thinking) {
        while (thinking) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val label = if (thinking) stringResource(R.string.chat_thought_thinking, ((now - since).coerceAtLeast(0) / 1000)) else thoughtLabel(thoughtMs ?: 0)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = stringResource(if (open) R.string.chat_thought_hide else R.string.chat_thought_show)) { userChoice = !open }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thinking) StatusDot(Tone.Live) else Icon(ChatGlyphs.Thought, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.small))
            SupportingText(label, Modifier.padding(start = Spacing.xs), style = LocusType.chip)
            ExpandChevron(open)
        }
        AnimatedVisibility(open) {
            val scroll = rememberScrollState()
            val text = reasoning()
            // Keep the newest line in view as text arrives.
            LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
            SupportingText(
                text.takeLast(LIVE_TAIL).trimStart(),
                // Half the inline cap: the newest few lines, not the whole thought.
                Modifier.padding(start = ThoughtIndent, top = Spacing.xs).heightIn(max = Sizes.inlineScroll / 2).verticalScroll(scroll),
            )
        }
    }
}

private const val LIVE_TAIL = 2_000
