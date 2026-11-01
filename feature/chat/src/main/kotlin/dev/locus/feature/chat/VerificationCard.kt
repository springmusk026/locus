package dev.locus.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.MonoCaption
import dev.locus.core.designsystem.Radius
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.model.Verification

/** "1.2 s", "84 s", "3 min 5 s". */
internal fun durationLabel(ms: Long): String = when {
    ms < 10_000 -> "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
    ms < 60_000 -> "${ms / 1000} s"
    else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
}

/** The line under the title: the runner's tally, or how many tests failed, or the exit code. */
internal fun verificationDetail(o: Verification.Outcome): String? = when {
    o.summary != null -> o.summary
    o.failureCount > 0 -> "${o.failureCount} failing ${if (o.failureCount == 1) "test" else "tests"}"
    o.passed == false && o.exitCode != null -> "Exit code ${o.exitCode}"
    else -> null
}

/**
 * A build or test run at a glance: passed or failed (in the status colours), how long it took, the
 * runner's tally, and the failing tests or first error lines. "Output" opens the raw output.
 */
@Composable
internal fun VerificationCard(item: ChatItem.Verification, modifier: Modifier = Modifier) {
    val o = item.outcome
    var showOutput by rememberSaveable(item.key) { mutableStateOf(false) }
    val tone = when (o.passed) {
        true -> Tone.Live
        false -> Tone.Critical
        null -> Tone.Caution
    }
    val command = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(item.call.argumentsJson) as kotlinx.serialization.json.JsonObject)["command"]
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }.getOrNull().orEmpty()
    val title = Verification.title(o)

    LocusCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs)
                .semantics { contentDescription = "$title${o.durationMs?.let { ", " + durationLabel(it) }.orEmpty()}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (o.passed) {
                    true -> LocusIcons.Check
                    else -> LocusIcons.Close
                },
                contentDescription = null,
                tint = tone.color(),
                modifier = Modifier.size(18.dp),
            )
            Text(title, Modifier.padding(start = Spacing.sm).weight(1f), style = MaterialTheme.typography.titleSmall, color = tone.color())
            o.durationMs?.let { Text(durationLabel(it), style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { showOutput = true }) { Text("Output") }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(command, style = MonoCaption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            verificationDetail(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val lines = o.failures.ifEmpty { o.errors }
            if (lines.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().background(Locus.colors.critical.copy(alpha = 0.08f), RoundedCornerShape(Radius.chip))
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                ) {
                    lines.forEach { line -> Text(line, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    val more = o.failureCount - o.failures.size
                    if (o.failures.isNotEmpty() && more > 0) {
                        Text("and $more more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (showOutput) {
        LocusSheet(title = title, subtitle = command, onDismiss = { showOutput = false }) {
            SelectionContainer {
                Text(
                    item.call.result?.content.orEmpty(),
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(Radius.chip)).padding(Spacing.sm),
                    style = CodeTextStyle,
                )
            }
        }
    }
}
