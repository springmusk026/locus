package dev.locus.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.Tone
import dev.locus.core.model.Effort
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.model.ToolGroup
import kotlinx.coroutines.delay

/** Which composer sheet is open. */
internal enum class ControlSheet { Model, Effort, Tools, More }

/** Thinking effort for this chat. */
@Composable
internal fun EffortSheet(provider: ChatProvider?, controls: ChatControls, onPick: (Effort?) -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = "Thinking", subtitle = "How hard ${provider?.model?.ifBlank { null } ?: "the model"} thinks in this chat.", onDismiss = onDismiss) {
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            val default = provider?.defaultEffort ?: Effort.HIGH
            EffortRow("Provider default", "${default.label}, as set in Settings → Models.", selected = !controls.effortOverridden) { onPick(null) }
            Effort.entries.forEach { e ->
                GroupDivider(inset = Spacing.md)
                EffortRow(e.label, e.description, selected = controls.effortOverridden && controls.effort == e) { onPick(e) }
            }
        }
        SectionNote(
            "Changing this mid-chat makes the next reply re-read the conversation instead of using the prompt cache, once; later replies are cached again. " +
                "On current Claude models “Off” means the lowest effort, since their thinking can't be switched off.",
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(LocusIcons.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
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
    LocusSheet(title = "Tools for this chat", onDismiss = onDismiss) {
        SectionLabel("Tools")
        LocusCard(contentPadding = PaddingValues(0.dp)) {
            ToolGroup.entries.forEachIndexed { i, g ->
                if (i > 0) GroupDivider(inset = Spacing.md)
                SwitchRow(g.label, g.description, checked = g.key !in controls.disabledTools) { onSetEnabled(g.key, it) }
            }
        }
        if (connectors.isNotEmpty()) {
            SectionLabel("Connectors")
            LocusCard(contentPadding = PaddingValues(0.dp)) {
                connectors.forEachIndexed { i, c ->
                    if (i > 0) GroupDivider(inset = Spacing.md)
                    val key = ToolGroup.connectorKey(c.id)
                    SwitchRow(c.name, if (c.mcp) "MCP server" else "HTTP connector", checked = key !in controls.disabledTools) { onSetEnabled(key, it) }
                }
            }
        }
        SectionNote("Switched-off tools stay known to Locus but refuse to run in this chat, so the conversation's prompt cache is kept. Settings → Permissions still applies.")
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.md)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A proposed plan. While it waits, it asks: run it, change it, or drop it. */
@Composable
internal fun PlanCard(item: ChatItem.Plan, actionable: Boolean, onApprove: () -> Unit, onEdit: () -> Unit, onCancel: () -> Unit) {
    val brand = MaterialTheme.colorScheme.primary
    LocusCard(glow = brand.takeIf { actionable }, outline = brand.copy(alpha = 0.4f).takeIf { actionable }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LocusIcons.Note, contentDescription = null, tint = brand, modifier = Modifier.size(18.dp))
            Text(
                "Plan",
                Modifier.padding(start = Spacing.sm).weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val status = when (item.status) {
                PlanStatus.Waiting -> if (actionable) null else "Not run"
                PlanStatus.Approved -> "Approved"
                PlanStatus.Answered -> "Changed"
            }
            if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(item.plan.title, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            item.plan.steps.forEachIndexed { i, step ->
                Row {
                    Text("${i + 1}.", Modifier.widthIn(min = 22.dp), style = MonoCaption.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item.plan.notes?.let { notes -> Box(Modifier.padding(top = Spacing.sm)) { ThoughtMarkdown(notes) } }
        if (actionable) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = Spacing.sm)) { Text("Cancel") }
                Box(Modifier.weight(1f))
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                Button(onClick = onApprove, modifier = Modifier.padding(start = Spacing.sm)) { Text("Approve and run") }
            }
        }
    }
}

/**
 * The current turn's checklist, pinned above the composer. Collapsed it shows progress and the item
 * being worked on; expanded, every item.
 */
@Composable
internal fun ChecklistCard(todos: List<TodoItem>, running: Boolean, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val done = todos.count { it.status == TodoStatus.COMPLETED }
    val current = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS } ?: todos.firstOrNull { it.status == TodoStatus.PENDING }
    LocusCard(modifier.animateContentSize(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Collapse checklist" else "Expand checklist") { open = !open }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgressRing(done, todos.size)
            Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                Text("$done of ${todos.size} done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!open && current != null) {
                    Text(current.shown, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (running && current?.status == TodoStatus.IN_PROGRESS && !open) StatusDot(Tone.Live, Modifier.padding(end = Spacing.sm), size = 6.dp)
            Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (open) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm)) {
                todos.forEach { TodoRow(it, running) }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, running: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                TodoStatus.COMPLETED -> Icon(LocusIcons.Check, contentDescription = "Done", tint = Locus.colors.live, modifier = Modifier.size(16.dp))
                TodoStatus.IN_PROGRESS -> if (running) StatusDot(Tone.Live, size = 8.dp) else Box(Modifier.size(10.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape))
                TodoStatus.PENDING -> Box(Modifier.size(10.dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape))
            }
        }
        Text(
            item.shown,
            Modifier.padding(start = Spacing.sm),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (item.status == TodoStatus.IN_PROGRESS) FontWeight.Medium else null,
            color = if (item.status == TodoStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProgressRing(done: Int, total: Int) {
    androidx.compose.material3.CircularProgressIndicator(
        progress = { if (total == 0) 0f else done.toFloat() / total },
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.5.dp,
        color = Locus.colors.live,
        trackColor = Locus.colors.track,
        gapSize = 0.dp,
    )
}

/** A step's kept reasoning: "Thought for 12s", tap to read it. */
@Composable
internal fun ThoughtRow(item: ChatItem.Thought, modifier: Modifier = Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(onClickLabel = if (open) "Hide reasoning" else "Show reasoning") { open = !open }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LocusIcons.Sparkle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(
                thoughtLabel(item.durationMs),
                Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(if (open) "  ▴" else "  ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Box(Modifier.padding(start = 20.dp, top = Spacing.xs, bottom = Spacing.xs)) { ThoughtMarkdown(item.text) }
        }
    }
}

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
    val label = if (thinking) "Thinking · ${((now - since).coerceAtLeast(0) / 1000)}s" else thoughtLabel(thoughtMs ?: 0)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(onClickLabel = if (open) "Hide reasoning" else "Show reasoning") { userChoice = !open }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thinking) StatusDot(Tone.Live, size = 6.dp) else Icon(LocusIcons.Sparkle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (open) "  ▴" else "  ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            val scroll = rememberScrollState()
            val text = reasoning()
            // Keep the newest line in view as text arrives.
            LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
            Text(
                text.takeLast(LIVE_TAIL).trimStart(),
                Modifier.padding(start = 20.dp, top = Spacing.xs).heightIn(max = 132.dp).verticalScroll(scroll),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val LIVE_TAIL = 2_000
