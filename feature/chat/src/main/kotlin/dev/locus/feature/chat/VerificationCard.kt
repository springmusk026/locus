package dev.locus.feature.chat

import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.onContainer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.RunDots
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.StatusDot
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.model.Envelopes
import dev.locus.core.model.Verification

/** "1.2 s", "84 s", "3 min 5 s". */
internal fun durationLabel(resources: Resources, ms: Long): String = when {
    ms < 10_000 -> resources.getString(R.string.chat_duration_tenths, ms / 1000.0)
    ms < 60_000 -> resources.getString(R.string.chat_duration_seconds, ms / 1000)
    else -> resources.getString(R.string.chat_duration_minutes, ms / 60_000, (ms % 60_000) / 1000)
}

/**
 * The line under the title: the runner's tally (its own words, from the command's output), or how many
 * tests failed, or the exit code.
 */
internal fun verificationDetail(resources: Resources, o: Verification.Outcome): String? = when {
    o.summary != null -> o.summary
    o.failureCount > 0 -> resources.getQuantityString(R.plurals.chat_checks_failing_tests, o.failureCount, o.failureCount)
    o.passed == false && o.exitCode != null -> resources.getString(R.string.chat_checks_exit_code, o.exitCode)
    o.verdict == Verification.Verdict.UNCERTAIN -> resources.getString(R.string.chat_checks_exit_unreliable)
    else -> null
}

/** The tone a run's dot and icon take. */
internal fun RunState.tone(): Tone = when (this) {
    RunState.PASSED -> Tone.Live
    RunState.FAILED -> Tone.Critical
    RunState.UNCLEAR, RunState.TIMED_OUT -> Tone.Caution
    RunState.RUNNING -> Tone.Brand
    RunState.STOPPED -> Tone.Neutral
}

/** The lines a run that didn't pass shows under its row: failing tests, else the first errors. */
private fun problemLines(o: Verification.Outcome): List<String> = o.failures.ifEmpty { o.errors }

/** At most this many failure lines show in the transcript; the rest are in Output. */
private const val INLINE_LINES = 3

/** The command a run ran, for display. */
internal fun runCommand(call: ChatItem.ToolCall): String = runCatching {
    (kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson) as kotlinx.serialization.json.JsonObject)["command"]
        ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
}.getOrNull().orEmpty()

/**
 * One kind of check in a turn's work (every test run, say), as one compact row: the latest result in
 * words and colour, a dot per run, and the latest duration. A run that didn't pass keeps its first
 * few failure lines in view, with Output for the rest. A tap opens every run with its output.
 */
@Composable
internal fun CheckGroupRow(group: CheckGroup, running: (ChatItem.ToolCall) -> Boolean, modifier: Modifier = Modifier) {
    var open by rememberSaveable(group.key) { mutableStateOf(false) }
    val latest = group.latest
    val state = runState(latest, running(latest.call))
    val tone = state.tone()
    val resources = LocalResources.current
    val label = checkGroupLabel(resources, group, running(latest.call))
    val showLabel = stringResource(if (group.runs.size > 1) R.string.chat_checks_show_runs else R.string.chat_checks_show_output)
    val o = latest.outcome
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget)
                .clickable(role = Role.Button, onClickLabel = showLabel) { open = true }
                .padding(horizontal = Spacing.md)
                .semantics { contentDescription = label + (o?.durationMs?.let { ", " + durationLabel(resources, it) }.orEmpty()) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(IconSize.medium), contentAlignment = Alignment.Center) {
                when (state) {
                    RunState.RUNNING -> StatusDot(Tone.Live)
                    RunState.PASSED -> Icon(LocusIcons.Check, null, tint = tone.color(), modifier = Modifier.size(IconSize.medium))
                    RunState.FAILED -> Icon(LocusIcons.Close, null, tint = tone.color(), modifier = Modifier.size(IconSize.medium))
                    else -> StatusDot(tone)
                }
            }
            Text(
                label,
                Modifier.padding(start = Spacing.sm).weight(1f),
                style = LocusType.rowSupporting,
                color = if (state == RunState.FAILED) tone.onContainer() else MaterialTheme.colorScheme.onSurface,
                // Two lines: "running (run 3)" or "failed on run 3 of 3" is the point of the row.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.runs.size > 1) RunDots(group.runs.map { runState(it, running(it.call)).tone() }, Modifier.padding(horizontal = Spacing.sm))
            o?.durationMs?.let { SupportingText(durationLabel(resources, it), style = LocusType.mono) }
        }
        // Only a run that didn't pass says more in the transcript, and only a few lines of it.
        if (o != null && state != RunState.PASSED) {
            val lines = problemLines(o)
            Column(Modifier.fillMaxWidth().padding(start = Spacing.md + IconSize.medium + Spacing.sm, end = Spacing.md, bottom = Spacing.xs)) {
                // The reason in full: "Exit status not reliable: …" says why the result can't be trusted.
                verificationDetail(resources, o)?.let { SupportingText(it) }
                if (lines.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = Spacing.xs)
                            .background(Locus.colors.critical.copy(alpha = Emphasis.hairline), MaterialTheme.shapes.small)
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    ) {
                        // A failing test's name is what to fix: two lines rather than cut mid-name.
                        lines.take(INLINE_LINES).forEach { line -> Text(line, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        val more = maxOf(lines.size, o.failureCount) - INLINE_LINES
                        if (more > 0) SupportingText(stringResource(R.string.chat_checks_and_more, more), style = LocusType.mono)
                    }
                }
                // Pulled back by the button's own padding, so "Output" starts on the column's edge.
                TextButton(onClick = { open = true }, modifier = Modifier.offset(x = -Spacing.md)) { Text(stringResource(R.string.chat_checks_output)) }
            }
        }
    }
    if (open) CheckRunsSheet(group, label, running, onDismiss = { open = false })
}

/** Every run of a check, newest first: its result, command, tally and output (the latest's open). */
@Composable
private fun CheckRunsSheet(group: CheckGroup, label: String, running: (ChatItem.ToolCall) -> Boolean, onDismiss: () -> Unit) {
    val n = group.runs.size
    LocusSheet(title = label, subtitle = if (n == 1) runCommand(group.latest.call) else pluralStringResource(R.plurals.chat_checks_runs_in_turn, n, n), onDismiss = onDismiss) {
        group.runs.withIndex().reversed().forEach { (i, run) ->
            if (i < n - 1) GroupDivider(inset = 0.dp)
            RunEntry(run, number = i + 1, total = n, latest = i == n - 1, running = running(run.call))
        }
    }
}

@Composable
private fun RunEntry(run: CheckRun, number: Int, total: Int, latest: Boolean, running: Boolean) {
    var showOutput by rememberSaveable(run.call.key) { mutableStateOf(latest) }
    val resources = LocalResources.current
    val state = runState(run, running)
    val o = run.outcome
    val title = when {
        o != null -> Verification.title(o)
        running -> stringResource(R.string.chat_checks_running)
        else -> stringResource(R.string.chat_checks_stopped)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (total > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.tone())
                Text(
                    stringResource(R.string.chat_checks_run_title, number, title),
                    Modifier.padding(start = Spacing.sm).weight(1f),
                    style = LocusType.cardTitle,
                    color = if (state == RunState.FAILED) state.tone().onContainer() else MaterialTheme.colorScheme.onSurface,
                )
                o?.durationMs?.let { SupportingText(durationLabel(resources, it), style = LocusType.mono) }
            }
            SupportingText(runCommand(run.call), style = LocusType.mono, maxLines = 3)
        } else {
            // One run: the sheet's title and subtitle already say its result and command.
            o?.durationMs?.let { SupportingText(stringResource(R.string.chat_checks_took, durationLabel(resources, it))) }
        }
        o?.host?.let { SupportingText(stringResource(R.string.chat_checks_on_host, it)) }
        o?.let { verificationDetail(resources, it) }?.let { SupportingText(it) }
        val result = run.call.result
        if (result != null) {
            TextButton(onClick = { showOutput = !showOutput }, modifier = Modifier.offset(x = -Spacing.md)) { Text(stringResource(if (showOutput) R.string.chat_checks_hide_output else R.string.chat_checks_show_output)) }
            if (showOutput) {
                CodeSurface(Modifier.fillMaxWidth()) {
                    // The safety envelope is for the model; the user sees the output itself.
                    SelectionContainer { Text(Envelopes.strip(result.content).take(OUTPUT_MAX), Modifier.padding(Spacing.sm), style = LocusType.code) }
                }
            }
        }
    }
}

/** Output shown per run; the full output stays in the terminal panel and exports. */
private const val OUTPUT_MAX = 20_000
