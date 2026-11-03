package dev.locus.feature.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.RunFailure
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.cardSegment
import dev.locus.core.model.SettingsDestination
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The error banner's Details: what failed and how, for the user or a bug report. Only the failure
 * itself: provider, model, status, the provider's code, the request and response ids, when, and the
 * provider's error payload, scrubbed of keys, tokens and the vault's values before it got here
 * ([RunFailure.rawBody]). Never the request, the auth header or the chat's messages.
 */
@Composable
internal fun FailureDetailsSheet(failure: RunFailure, message: String, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val rows = remember(failure) { failureRows(failure) }
    LocusSheet(
        title = "What went wrong",
        subtitle = "${failure.provider} · ${failure.model}",
        onDismiss = onDismiss,
        action = if (copied) "Copied" else "Copy",
        onAction = {
            scope.launch {
                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Locus error", failureReport(failure, message))))
                copied = true
            }
        },
    ) {
        SupportingText(message, style = LocusType.rowSupporting)
        failure.advice?.let { SupportingText(it, style = LocusType.rowSupporting) }
        androidx.compose.foundation.layout.Column {
            rows.forEachIndexed { i, (label, value) ->
                Row(
                    Modifier.cardSegment(i, rows.size).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(label, Modifier.weight(0.4f), style = LocusType.rowSupporting)
                    SelectionContainer(Modifier.weight(0.6f)) { SupportingText(value, style = LocusType.mono) }
                }
            }
        }
        GroupDivider(inset = 0.dp)
        SupportingText("Provider's response", style = LocusType.label)
        val body = failure.rawBody
        if (body.isNullOrBlank()) {
            SupportingText("The provider sent no error details beyond the above.")
        } else {
            SelectionContainer {
                CodeSurface(Modifier.fillMaxWidth()) {
                    Text(prettyFailureBody(body), Modifier.padding(Spacing.sm), style = LocusType.code)
                }
            }
            SupportingText("Keys, sign-in tokens and saved secrets are removed. Your messages and files are never part of it.")
        }
        SettingsLink(SettingsDestination.Diagnostics, hint = "Also saved, with earlier ones, in", beforeOpen = onDismiss)
    }
}

/** The sheet's rows: label and value, every field shown (with what its absence means). */
internal fun failureRows(failure: RunFailure, timeFormat: (Long) -> String = ::formatFailureTime): List<Pair<String, String>> = listOf(
    "Provider" to failure.provider,
    "Model" to failure.model,
    "HTTP status" to (failure.httpStatus?.toString() ?: "None (the stream had started)"),
    "Error code" to (failure.errorCode ?: "None given"),
    "Request id" to (failure.requestId ?: "Not sent"),
    "Response id" to (failure.responseId ?: "None"),
    "Time" to timeFormat(failure.at),
    "Times in a row" to failure.occurrences.toString(),
)

/** What Copy puts on the clipboard: the chat's message, the rows and the scrubbed payload. */
internal fun failureReport(failure: RunFailure, message: String, timeFormat: (Long) -> String = ::formatFailureTime): String = buildString {
    append("Locus model error\n").append(message).append("\n\n")
    failureRows(failure, timeFormat).forEach { (label, value) -> append(label).append(": ").append(value).append('\n') }
    failure.rawBody?.takeIf { it.isNotBlank() }?.let { append("\nProvider's response:\n").append(it).append('\n') }
}

/** The payload indented when it's JSON, as it came otherwise. */
internal fun prettyFailureBody(body: String): String = runCatching {
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body)
    // Only objects and arrays: the parser also takes a bare word ("<html>") as a string, which would gain quotes.
    if (parsed !is kotlinx.serialization.json.JsonObject && parsed !is kotlinx.serialization.json.JsonArray) return@runCatching body
    kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), parsed)
}.getOrDefault(body)

private fun formatFailureTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(at))
