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
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.compactCount
import dev.locus.core.model.HelperStop
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentStatus
import dev.locus.core.model.SubagentTools
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
 * One helper (an `agent_spawn` call, or an `agent_send` that continued one) in the parent's steps
 * card: its model, label, live step count, current tool, tokens and status, with Steer and Stop while
 * it works. Tap to read its transcript.
 */
@Composable
internal fun SubagentRow(item: ChatItem.ToolCall) {
    val runner = rememberSubagents()
    val callId = item.key.substringAfter(':')
    val args = remember(item.argumentsJson) { runCatching { Json.parseToJsonElement(item.argumentsJson) as JsonObject }.getOrNull() }
    val continuing = item.name == SubagentTools.SEND
    val agentId = (args?.get("agent_id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val flow = remember(callId, agentId) { if (continuing) runner.observeById(agentId) else runner.observeByCall(callId) }
    val view by flow.collectAsState(initial = null)
    val task = (args?.get(if (continuing) "message" else "task") as? JsonPrimitive)?.contentOrNull.orEmpty()
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    var steering by rememberSaveable(item.key) { mutableStateOf(false) }

    val helper = view
    // A refused agent_send (still working, another chat's, a limit) answers with an error that isn't a report.
    val refusedSend = continuing && item.result?.let { it.isError && !it.content.startsWith("<subagent_report") } == true
    if (helper == null || refusedSend) {
        // Not started (refused, or still being set up): a plain step with the reason.
        RefusedSpawn(item)
        return
    }
    // Why it stopped early: live in this process, else from its report's `stopped` attribute (a foreground spawn's result).
    val stop = helper.stop ?: HelperStop.fromReport(item.result?.content)
    val partial = helper.partial || (stop != null && stop != HelperStop.CANCELLED && helper.steps > 0)
    if (open) SubagentTranscriptSheet(helper, task, runner, partialNote = partialNote(stop).takeIf { partial }, onSteer = { steering = true }, onDismiss = { open = false })
    if (steering) SteerDialog(helper, runner, onDismiss = { steering = false })
    val running = helper.status == SubagentStatus.RUNNING
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderBadge(helper.providerName, helper.providerBaseUrl, onDevice = false, size = IconSize.large, model = helper.model)
            Column(Modifier.weight(1f).padding(start = Spacing.sm)) {
                Text(helper.label, style = LocusType.rowSupporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (task.isNotBlank()) {
                    SupportingText((if (continuing) "Continued: " else "") + task.lineSequence().first(), maxLines = 1)
                }
            }
            when {
                running -> {
                    StatusDot(if (helper.waitingApproval) Tone.Caution else Tone.Live)
                    TextButton(onClick = { steering = true }, modifier = Modifier.padding(start = Spacing.xs)) { Text("Steer") }
                    TextButton(onClick = { runner.stop(helper.agentId) }) { Text("Stop") }
                }
                helper.status == SubagentStatus.COMPLETED ->
                    Icon(LocusIcons.Check, contentDescription = "Done", modifier = Modifier.size(IconSize.small), tint = Locus.colors.live)
                // Stopped early but handed back what it had: a result to use, not a failure.
                partial -> LocusChip("Partial result", tone = Tone.Caution)
                else -> Text(
                    helper.status.label,
                    style = LocusType.chip,
                    color = if (helper.status == SubagentStatus.FAILED) Locus.colors.critical else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            buildString {
                append(if (helper.steps == 1) "1 step" else "${helper.steps} steps")
                if (helper.tokens > 0) append(" · ${compactCount(helper.tokens)} tokens")
                if (running && helper.pendingNotes > 0) append(" · your note is queued: sent when its current step ends")
                if (partial) append(" · ${partialReason(stop)}, returned what it had")
                when {
                    running && helper.waitingApproval -> append(" · waiting for your approval")
                    running && helper.currentTool != null -> append(" · ${toolActivity(helper.currentTool.orEmpty()).lowercase()}")
                    running && helper.background -> append(" · in the background")
                }
            },
            style = LocusType.mono,
            color = if ((running && helper.waitingApproval) || partial) Locus.colors.caution else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = IconSize.large + Spacing.sm, top = Spacing.xxs),
        )
    }
}

/** Why a helper stopped early, in a few words for its row: "timed out". */
internal fun partialReason(stop: HelperStop?): String = when (stop) {
    HelperStop.TIMEOUT -> "timed out"
    HelperStop.CANCELLED -> "stopped"
    HelperStop.TOKEN_BUDGET -> "used its token allowance"
    HelperStop.STEP_LIMIT -> "reached its step limit"
    HelperStop.TASK_BUDGET -> "task budget reached"
    null -> "stopped early"
}

/** The note at the top of a partial helper's transcript: why it stopped and what the main agent got. */
internal fun partialNote(stop: HelperStop?): String {
    val why = when (stop) {
        HelperStop.TIMEOUT -> "It reached the helper time limit (Settings → Agent → Helpers) before finishing."
        HelperStop.CANCELLED -> "It was stopped before finishing."
        HelperStop.TOKEN_BUDGET -> "It used its token allowance before finishing."
        HelperStop.STEP_LIMIT -> "It reached its step limit before finishing."
        HelperStop.TASK_BUDGET -> "The task reached its budget before the helper finished."
        null -> "It stopped before finishing."
    }
    return "$why The main agent got what it had: its last reply, its tool results and the files it changed, marked as partial. " +
        "The agent can continue it from here."
}

@Composable
private fun RefusedSpawn(item: ChatItem.ToolCall) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(item.name)
            AccentTile(accent, icon, size = IconSize.large)
            Text(
                toolLabel(item.name, item.argumentsJson, done = item.result != null),
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = LocusType.rowSupporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                item.result == null -> StatusDot(Tone.Live)
                item.result.isError -> SupportingText("Not started", style = LocusType.chip)
            }
        }
        AnimatedVisibility(expanded && item.result != null) {
            SupportingText(item.result?.content.orEmpty().take(2_000), Modifier.padding(top = Spacing.xs))
        }
    }
}

/** A helper's whole run, read-only: its task, what it said, and each tool call with its result. */
@Composable
private fun SubagentTranscriptSheet(
    helper: SubagentView,
    task: String,
    runner: SubagentRunner,
    partialNote: String?,
    onSteer: () -> Unit,
    onDismiss: () -> Unit,
) {
    val flow = remember(helper.agentId) { runner.observeTranscript(helper.agentId) }
    val messages by flow.collectAsState(initial = emptyList())
    val entries = remember(messages) { transcriptEntries(messages) }
    val subtitle = buildString {
        append(if (partialNote != null) "Partial result" else helper.status.label)
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
            if (partialNote != null) item(key = "partial") { Banner(partialNote, Tone.Caution, title = "Partial result") }
            if (entries.isEmpty() && task.isNotBlank()) item { TaskBlock(task, "Task from the main agent") }
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is Entry.Task -> TaskBlock(entry.text, if (entry.followUp) "Follow-up from the main agent" else "Task from the main agent")
                    is Entry.Note -> TaskBlock(entry.text, "Your note")
                    is Entry.Continued -> ContinuedDivider()
                    is Entry.Said -> AssistantMarkdown(entry.text)
                    is Entry.Call -> TranscriptCall(entry)
                }
            }
            if (helper.status == SubagentStatus.RUNNING) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.xs)) {
                        StatusDot(Tone.Live)
                        SupportingText(helper.currentTool?.let { toolActivity(it) } ?: "Working", modifier = Modifier.padding(start = Spacing.sm))
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        TextButton(onClick = onSteer) { Text("Steer") }
                        TextButton(onClick = { runner.stop(helper.agentId) }) { Text("Stop") }
                    }
                }
            }
        }
    }
}

/**
 * Steer: a short instruction for a working helper. It reaches the helper at its next step boundary
 * (after its current tool calls), the same way a chat's queued messages do.
 */
@Composable
private fun SteerDialog(helper: SubagentView, runner: SubagentRunner, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    val feedback = Locus.feedback
    LocusSheet(
        title = "Steer ${helper.label}",
        subtitle = "It reads this after its current step and carries on with it in mind.",
        onDismiss = onDismiss,
        action = "Send",
        actionEnabled = note.isNotBlank(),
        onAction = {
            if (!runner.steer(helper.agentId, note)) {
                feedback.show("${helper.label} already finished. Send it in the chat instead.", Tone.Caution)
            }
            onDismiss()
        },
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(MAX_NOTE_CHARS) },
            placeholder = { Text("e.g. Only look at official sources") },
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MAX_NOTE_CHARS = 2_000

/** Where an `agent_send` picked the helper up again. */
@Composable
private fun ContinuedDivider() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Column(Modifier.weight(1f)) { GroupDivider(inset = 0.dp) }
        SupportingText(
            "Continued",
            modifier = Modifier.padding(horizontal = Spacing.sm),
            style = LocusType.chip,
        )
        Column(Modifier.weight(1f)) { GroupDivider(inset = 0.dp) }
    }
}

@Composable
private fun TaskBlock(text: String, title: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill), MaterialTheme.shapes.medium)
            .padding(Spacing.md),
    ) {
        SupportingText(title, style = LocusType.chip)
        SelectionContainer { Text(text, style = LocusType.rowSupporting, modifier = Modifier.padding(top = Spacing.xs)) }
    }
}

@Composable
private fun TranscriptCall(entry: Entry.Call) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .background(Locus.colors.card, MaterialTheme.shapes.medium)
            .clickable { expanded = !expanded }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(entry.name)
            AccentTile(accent, icon, size = IconSize.large)
            Text(
                toolLabel(entry.name, entry.argumentsJson, done = entry.result != null),
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = LocusType.rowSupporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                entry.result == null -> StatusDot(Tone.Live)
                entry.result.isError -> SupportingText("Failed", style = LocusType.chip)
                else -> Icon(LocusIcons.Check, contentDescription = "Done", modifier = Modifier.size(IconSize.small), tint = Locus.colors.live)
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

internal sealed interface Entry {
    val key: String

    /** The task, or (after a [Continued] divider) a follow-up the main agent sent with agent_send. */
    data class Task(override val key: String, val text: String, val followUp: Boolean = false) : Entry
    data class Continued(override val key: String) : Entry

    /** A note the user sent with Steer while the helper worked. */
    data class Note(override val key: String, val text: String) : Entry
    data class Said(override val key: String, val text: String) : Entry
    data class Call(override val key: String, val name: String, val argumentsJson: String, val result: Part.ToolResult?) : Entry
}

internal fun transcriptEntries(messages: List<Message>): List<Entry> {
    val results = messages.filter { it.role == Role.TOOL }.flatMap { it.toolResults }.associateBy { it.callId }
    var tasks = 0
    return buildList {
        messages.forEach { m ->
            when (m.role) {
                Role.USER -> m.userText.takeIf { it.isNotBlank() }?.let {
                    if (dev.locus.core.agent.tools.SubagentPrompts.isSteer(m)) {
                        add(Entry.Note(m.id, it))
                        return@let
                    }
                    // Every other message after the task is a follow-up (agent_send).
                    if (tasks++ > 0) add(Entry.Continued("${m.id}:continued"))
                    add(Entry.Task(m.id, it, followUp = tasks > 1))
                }
                Role.ASSISTANT -> {
                    if (m.text.isNotBlank()) add(Entry.Said(m.id, m.text))
                    m.toolCalls.forEach { c -> add(Entry.Call("${m.id}:${c.id}", c.name, c.argumentsJson, results[c.id])) }
                }
                Role.TOOL -> Unit
            }
        }
    }
}
