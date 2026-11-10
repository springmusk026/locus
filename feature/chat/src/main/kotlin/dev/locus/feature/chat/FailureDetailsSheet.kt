package dev.locus.feature.chat

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.RunFailure
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.llm.LlmFailure
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.model.ModelNames
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
    val resources = LocalResources.current
    val rows = remember(failure, resources) { failureRows(failure).map { it.shown(resources) } }
    LocusSheet(
        title = stringResource(R.string.chat_failure_title),
        subtitle = "${failure.provider} · ${ModelNames.short(failure.model)}",
        onDismiss = onDismiss,
        action = stringResource(if (copied) R.string.chat_failure_copied else R.string.chat_failure_copy),
        onAction = {
            scope.launch {
                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Locus error", failureReport(failure, message))))
                copied = true
            }
        },
    ) {
        // In words: the ids are in the table below, and the next step follows when the advice doesn't give one.
        SupportingText(failureMessageInWords(resources, message), style = LocusType.rowSupporting)
        (failure.advice ?: failureNextStep(failure, message)?.asString())?.let { SupportingText(it, style = LocusType.rowSupporting) }
        SelectionContainer {
            CardGroup {
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
                    rows.forEach { (label, value) -> KeyValueRow(label, value, dense = true, mono = true) }
                }
            }
        }
        GroupDivider(inset = 0.dp)
        SupportingText(stringResource(R.string.chat_failure_response), style = LocusType.label)
        val body = failure.rawBody
        if (body.isNullOrBlank()) {
            SupportingText(stringResource(R.string.chat_failure_no_details))
        } else {
            SelectionContainer {
                CodeSurface(Modifier.fillMaxWidth()) {
                    Text(prettyFailureBody(body), Modifier.padding(Spacing.sm), style = LocusType.code)
                }
            }
            SupportingText(stringResource(R.string.chat_failure_scrubbed))
        }
        SettingsLink(SettingsDestination.Diagnostics, hint = stringResource(R.string.chat_failure_saved_hint), beforeOpen = onDismiss)
    }
}

/**
 * The error as the chat shows it, minus what belongs in the details table: "(request id req_…)" goes,
 * and a dropped connection's system words ("Software caused connection abort") become what happened.
 * The dropped-connection check reads core/agent's own message ([dev.locus.core.agent.AgentLoop.CONNECTION_DROPPED]).
 */
internal fun failureMessageInWords(resources: Resources, message: String): String {
    val plain = message.replace(IdInText, "")
    return if (plain.startsWith(dev.locus.core.agent.AgentLoop.CONNECTION_DROPPED)) {
        resources.getString(R.string.chat_failure_connection_dropped)
    } else {
        plain
    }
}

private val CloudflareRay = Regex("[0-9a-f]{16}-[A-Z]{3}")

private val IdInText = Regex("""\s*\((request|response) id [^)]*\)""")

/** What to try next, for a failure whose own advice doesn't say. */
internal fun failureNextStep(failure: RunFailure, message: String): UiText? = when {
    message.startsWith(dev.locus.core.agent.AgentLoop.CONNECTION_DROPPED) -> UiText.Res(R.string.chat_failure_next_network)
    failure.kind == LlmFailure.RATE_LIMIT -> UiText.Res(R.string.chat_failure_next_rate_limit)
    failure.kind == LlmFailure.OVERLOADED -> UiText.Res(R.string.chat_failure_next_overloaded)
    else -> null
}

/**
 * A field of the details table. The label and what its absence means are looked up in the screen's
 * language; the Copy report keeps the English ([reportLabel], [reportAbsent]), since it goes into bug
 * reports.
 */
internal enum class FailureField(
    @param:StringRes val label: Int,
    val reportLabel: String,
    @param:StringRes val absent: Int? = null,
    val reportAbsent: String = "",
) {
    PROVIDER(R.string.chat_failure_field_provider, "Provider"),
    MODEL(R.string.chat_failure_field_model, "Model"),
    MODEL_ID(R.string.chat_failure_field_model_id, "Model id"),
    HTTP_STATUS(R.string.chat_failure_field_http_status, "HTTP status", R.string.chat_failure_absent_http_status, "None (the stream had started)"),
    ERROR_CODE(R.string.chat_failure_field_error_code, "Error code", R.string.chat_failure_absent_error_code, "None given"),
    REQUEST_ID(R.string.chat_failure_field_request_id, "Request id", R.string.chat_failure_absent_request_id, "Not sent"),
    GATEWAY_ID(R.string.chat_failure_field_gateway_id, "Gateway id", R.string.chat_failure_absent_request_id, "Not sent"),
    RESPONSE_ID(R.string.chat_failure_field_response_id, "Response id", R.string.chat_failure_absent_response_id, "None"),
    TIME(R.string.chat_failure_field_time, "Time"),
    TIMES(R.string.chat_failure_field_times, "Times in a row"),
}

/** One row of the details table: [value] is null when the provider didn't give one. */
internal data class FailureRow(val field: FailureField, val value: String?) {
    /** Label and value as the sheet shows them, in the screen's language. */
    fun shown(resources: Resources): Pair<String, String> =
        resources.getString(field.label) to (value ?: field.absent?.let(resources::getString).orEmpty())

    /** Label and value as the Copy report writes them: always English. */
    fun inReport(): Pair<String, String> = field.reportLabel to (value ?: field.reportAbsent)
}

/** The sheet's rows: every field shown (with what its absence means, see [FailureRow.shown]). */
internal fun failureRows(
    failure: RunFailure,
    /** For Copy: the exact model id even when screens don't show it (an imported GGUF file's). */
    forReport: Boolean = false,
    timeFormat: (Long) -> String = ::formatFailureTime,
): List<FailureRow> = listOfNotNull(
    FailureRow(FailureField.PROVIDER, failure.provider),
    // The model by name; its exact id too when that reads differently, since a bug report needs it.
    FailureRow(FailureField.MODEL, ModelNames.short(failure.model)),
    ModelNames.idIfDifferent(failure.model, forReport)?.let { FailureRow(FailureField.MODEL_ID, it) },
    FailureRow(FailureField.HTTP_STATUS, failure.httpStatus?.toString()),
    FailureRow(FailureField.ERROR_CODE, failure.errorCode),
    // A Cloudflare ray id ("8f2a1c9e4b7d03a1-FRA") names the gateway's log line, not the provider's request.
    FailureRow(if (failure.requestId?.matches(CloudflareRay) == true) FailureField.GATEWAY_ID else FailureField.REQUEST_ID, failure.requestId),
    FailureRow(FailureField.RESPONSE_ID, failure.responseId),
    FailureRow(FailureField.TIME, timeFormat(failure.at)),
    FailureRow(FailureField.TIMES, failure.occurrences.toString()),
)

/** What Copy puts on the clipboard: the chat's message, the rows and the scrubbed payload. English, for bug reports. */
internal fun failureReport(failure: RunFailure, message: String, timeFormat: (Long) -> String = ::formatFailureTime): String = buildString {
    append("Locus model error\n").append(message).append("\n\n")
    failureRows(failure, forReport = true, timeFormat = timeFormat).forEach { row ->
        val (label, value) = row.inReport()
        append(label).append(": ").append(value).append('\n')
    }
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
