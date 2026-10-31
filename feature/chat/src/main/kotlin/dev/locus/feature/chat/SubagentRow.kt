package dev.locus.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.locus.core.data.agent.SubagentRunner
import dev.locus.core.data.agent.SubagentView
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.compactCount
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentStatus
import dev.locus.core.model.toolActivity
import dev.locus.core.model.toolLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reaches the app-wide helper runner from a row, without threading it through the chat's view model. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SubagentEntryPoint {
    fun subagents(): SubagentRunner
}

@Composable
private fun rememberSubagents(): SubagentRunner {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, SubagentEntryPoint::class.java).subagents() }
}

/**
 * One helper (an `agent_spawn` call) in the parent's steps card: its model, label, live step count,
 * current tool, tokens and status, with Stop while it works. Tap to read its transcript.
 */
@Composable
internal fun SubagentRow(item: ChatItem.ToolCall) {
    val runner = rememberSubagents()
    val callId = item.key.substringAfter(':')
    val flow = remember(callId) { runner.observeByCall(callId) }
    val view by flow.collectAsState(initial = null)
    val args = remember(item.argumentsJson) { runCatching { Json.parseToJsonElement(item.argumentsJson) as JsonObject }.getOrNull() }
    val task = (args?.get("task") as? JsonPrimitive)?.contentOrNull.orEmpty()
    var open by rememberSaveable(item.key) { mutableStateOf(false) }

    val helper = view
    if (helper == null) {
        // Not started (refused, or still being set up): a plain step with the reason.
        RefusedSpawn(item)
        return
    }
    if (open) SubagentTranscriptSheet(helper, task, runner, onDismiss = { open = false })
    val running = helper.status == SubagentStatus.RUNNING
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderBadge(helper.providerName, helper.providerBaseUrl, onDevice = false, size = 24.dp, model = helper.model)
            Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                Text(helper.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (task.isNotBlank()) {
                    Text(
                        task.lineSequence().first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                running -> {
                    StatusDot(if (helper.waitingApproval) Tone.Caution else Tone.Live)
                    TextButton(onClick = { runner.stop(helper.agentId) }, modifier = Modifier.padding(start = Spacing.xs)) { Text("Stop") }
                }
                helper.status == SubagentStatus.COMPLETED ->
                    Icon(LocusIcons.Check, contentDescription = "Done", modifier = Modifier.size(16.dp), tint = Locus.colors.live)
                else -> Text(
                    helper.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (helper.status == SubagentStatus.FAILED) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            buildString {
                append(if (helper.steps == 1) "1 step" else "${helper.steps} steps")
                if (helper.tokens > 0) append(" · ${compactCount(helper.tokens)} tokens")
                when {
                    running && helper.waitingApproval -> append(" · waiting for your approval")
                    running && helper.currentTool != null -> append(" · ${toolActivity(helper.currentTool.orEmpty()).lowercase()}")
                    running && helper.background -> append(" · in the background")
                }
            },
            style = MonoCaption,
            color = if (running && helper.waitingApproval) Locus.colors.caution else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp + Spacing.sm, top = 2.dp),
        )
    }
}

@Composable
private fun RefusedSpawn(item: ChatItem.ToolCall) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(item.name)
            AccentTile(accent, icon, size = 24.dp)
            Text(
                toolLabel(item.name, item.argumentsJson, done = item.result != null),
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                item.result == null -> StatusDot(Tone.Live)
                item.result.isError -> Text("Not started", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(expanded && item.result != null) {
            Text(
                item.result?.content.orEmpty().take(2_000),
                Modifier.padding(top = Spacing.xs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A helper's whole run, read-only: its task, what it said, and each tool call with its result. */
@Composable
private fun SubagentTranscriptSheet(helper: SubagentView, task: String, runner: SubagentRunner, onDismiss: () -> Unit) {
    val flow = remember(helper.agentId) { runner.observeTranscript(helper.agentId) }
    val messages by flow.collectAsState(initial = emptyList())
    val entries = remember(messages) { transcriptEntries(messages) }
    val subtitle = buildString {
        append(helper.status.label)
        append(" · ${helper.model}")
        if (helper.tokens > 0) append(" · ${compactCount(helper.tokens)} tokens")
    }
    LocusSheet(title = helper.label, subtitle = subtitle, onDismiss = onDismiss) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = maxHeight),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (entries.isEmpty() && task.isNotBlank()) item { TaskBlock(task) }
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is Entry.Task -> TaskBlock(entry.text)
                    is Entry.Said -> AssistantMarkdown(entry.text)
                    is Entry.Call -> TranscriptCall(entry)
                }
            }
            if (helper.status == SubagentStatus.RUNNING) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.xs)) {
                        StatusDot(Tone.Live)
                        Text(
                            helper.currentTool?.let { toolActivity(it) } ?: "Working",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.sm),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        TextButton(onClick = { runner.stop(helper.agentId) }) { Text("Stop") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskBlock(text: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(Radius.control))
            .padding(Spacing.md),
    ) {
        Text("Task from the main agent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs)) }
    }
}

@Composable
private fun TranscriptCall(entry: Entry.Call) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(Locus.colors.card, RoundedCornerShape(Radius.control))
            .clickable { expanded = !expanded }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(entry.name)
            AccentTile(accent, icon, size = 22.dp)
            Text(
                toolLabel(entry.name, entry.argumentsJson, done = entry.result != null),
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                entry.result == null -> StatusDot(Tone.Live)
                entry.result.isError -> Text("Failed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Icon(LocusIcons.Check, contentDescription = "Done", modifier = Modifier.size(14.dp), tint = Locus.colors.live)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = Spacing.xs)) {
                GroupDivider()
                SelectionContainer {
                    Text(
                        (entry.result?.content ?: entry.argumentsJson).take(4_000),
                        style = CodeTextStyle,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }
}

private sealed interface Entry {
    val key: String

    data class Task(override val key: String, val text: String) : Entry
    data class Said(override val key: String, val text: String) : Entry
    data class Call(override val key: String, val name: String, val argumentsJson: String, val result: Part.ToolResult?) : Entry
}

private fun transcriptEntries(messages: List<Message>): List<Entry> {
    val results = messages.filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
    return buildList {
        messages.forEach { m ->
            when (m.role) {
                Role.USER -> m.userText.takeIf { it.isNotBlank() }?.let { add(Entry.Task(m.id, it)) }
                Role.ASSISTANT -> {
                    if (m.text.isNotBlank()) add(Entry.Said(m.id, m.text))
                    m.toolCalls.forEach { c -> add(Entry.Call("${m.id}:${c.id}", c.name, c.argumentsJson, results[c.id])) }
                }
                Role.TOOL -> Unit
            }
        }
    }
}
