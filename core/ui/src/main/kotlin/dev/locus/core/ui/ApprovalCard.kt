package dev.locus.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.agent.ApprovalPreview
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ApprovalScope
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineCode
import dev.locus.core.designsystem.KeyValueRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SettingsLink
import dev.locus.core.designsystem.SideOrBelow
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.accentFor
import dev.locus.core.designsystem.codeSpans
import dev.locus.core.model.ApprovalScopeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The one decision the user has to make right now; caution-toned so it can't be missed. Shown above the
 * chat's composer and at the bottom of the full-screen browser, for the same request
 * ([dev.locus.core.data.agent.RunState.approvals]): answering in either resolves it everywhere.
 *
 * [onDetails] opens the call's arguments somewhere else (the browser's sheet, over the page); left null,
 * Details expands them inside the card, as in the chat.
 */
@Composable
fun ApprovalCard(
    request: ApprovalRequest,
    onAnswer: (String, Boolean) -> Unit,
    onRemember: (String, ApprovalAnswer) -> Unit,
    modifier: Modifier = Modifier,
    onDetails: (() -> Unit)? = null,
) {
    var showDetails by remember(request.callId) { mutableStateOf(false) }
    val caution = Locus.colors.caution
    val large = largeFont()
    // The caution outline marks it; no glow, which would be decoration on top.
    // The answer buttons scroll into view in the dock when the card is taller than its room.
    val answers = remember { BringIntoViewRequester() }
    LaunchedEffect(request.callId) { answers.bringIntoView() }
    LocusCard(modifier, outline = caution.copy(alpha = Emphasis.toneOutline)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // At a large font the text gets the tile's width: the address is what's being decided on.
            if (!large) {
                val (accent, icon) = accentFor(request.toolName)
                AccentTile(accent, icon, size = IconSize.tileSmall)
            }
            Column(Modifier.weight(1f).then(if (large) Modifier else Modifier.padding(start = Spacing.md))) {
                // A helper's request says which helper is asking.
                Text(request.origin?.let { "${it.label} wants to…" } ?: "Allow this?", style = LocusType.cardTitle)
                // Whole at a large font: the address or command is what's being decided on.
                SupportingText(request.summary, style = LocusType.rowSupporting, maxLines = if (large) Int.MAX_VALUE else 3)
                // What the safety reviewer made of it, when it was asked: read whole, in the readable caution shade.
                request.reviewerNote?.let { note ->
                    Text(note, style = LocusType.supporting, color = Locus.colors.onCautionContainer, modifier = Modifier.padding(top = Spacing.xxs))
                }
                // The reviewer couldn't help: the Settings page that would fix it ("Choose one in Settings → Helper model & extras").
                request.reviewerFix?.let { fix -> SettingsLink(fix.destination, Modifier.padding(top = Spacing.xs), hint = fix.hint) }
            }
        }
        // Exactly what the call writes or deletes, field by field (an event's title, time and guests); guests in caution.
        request.preview?.let { preview -> ApprovalPreviewRows(preview) }
        if (onDetails == null) {
            AnimatedVisibility(showDetails) {
                Column(Modifier.padding(top = Spacing.sm)) { ApprovalArguments(request) }
            }
        }
        val details = @Composable {
            TextButton(onClick = { onDetails?.invoke() ?: run { showDetails = !showDetails } }, contentPadding = PaddingValues(horizontal = Spacing.sm)) {
                Text(if (showDetails && onDetails == null) "Hide details" else "Details")
            }
        }
        if (large) {
            // Stacked at a large font, full width, so no label breaks inside its button.
            Column(Modifier.fillMaxWidth().padding(top = Spacing.sm).bringIntoViewRequester(answers), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Button(onClick = { onAnswer(request.callId, true) }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
                OutlinedButton(onClick = { onAnswer(request.callId, false) }, modifier = Modifier.fillMaxWidth()) { Text("Deny") }
                details()
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm).bringIntoViewRequester(answers), verticalAlignment = Alignment.CenterVertically) {
                details()
                Box(Modifier.weight(1f))
                OutlinedButton(onClick = { onAnswer(request.callId, false) }) { Text("Deny") }
                Button(onClick = { onAnswer(request.callId, true) }, modifier = Modifier.padding(start = Spacing.sm)) { Text("Allow") }
            }
        }
        // "Don't ask again", only where Locus offers it: a scope it can name, never for payments, flagged or
        // tainted calls, or dangerous commands (docs/design/remembered-approvals.md).
        request.remember?.let { offer ->
            // The scope wraps whole; at a large font the buttons go under it.
            SideOrBelow(Modifier.fillMaxWidth(), main = {
                SupportingText(dontAskAgainText(offer.scope), modifier = Modifier.padding(start = Spacing.sm))
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onRemember(request.callId, ApprovalAnswer.ALLOW_FOR_CHAT) },
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                    ) { Text("This chat") }
                    if (offer.always) {
                        TextButton(
                            onClick = { onRemember(request.callId, ApprovalAnswer.ALLOW_ALWAYS) },
                            contentPadding = PaddingValues(horizontal = Spacing.sm),
                        ) { Text("Always") }
                    }
                }
            }
        }
        // Where it can't be offered, one line says why, so a missing "Don't ask again" isn't a mystery.
        if (request.remember == null) {
            request.rememberNote?.let { note ->
                // Locus's own words, with `python3` and the like in the code font.
                SupportingText(codeSpans(note), modifier = Modifier.padding(start = Spacing.sm, top = Spacing.xs))
            }
        }
        // A site the address gates asked about: allow it until this task ends, never longer (docs/design/taint.md).
        request.siteForTask?.let { host ->
            SideOrBelow(Modifier.fillMaxWidth(), main = {
                SupportingText(siteForTaskText(host), modifier = Modifier.padding(start = Spacing.sm))
            }) {
                TextButton(
                    onClick = { onRemember(request.callId, ApprovalAnswer.ALLOW_SITE_FOR_TASK) },
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                ) { Text(SITE_FOR_TASK_ACTION) }
            }
        }
    }
}

/**
 * The approval card's Details as a sheet, where the card sits over something the user is looking at (the
 * full-screen browser): what the call is, field by field, and its arguments as sent. Answering stays on
 * the card, so the sheet only informs.
 */
@Composable
fun ApprovalDetailsSheet(request: ApprovalRequest, onDismiss: () -> Unit) {
    LocusSheet(title = "Details", subtitle = request.summary, onDismiss = onDismiss) {
        // The sheet gives its content the title's left edge already.
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xl)) {
            request.origin?.let { SupportingText("Asked by ${it.label}", modifier = Modifier.padding(bottom = Spacing.sm)) }
            request.preview?.let { preview -> ApprovalPreviewRows(preview) }
            request.reviewerNote?.let { note ->
                Text(note, style = LocusType.supporting, color = Locus.colors.onCautionContainer, modifier = Modifier.padding(vertical = Spacing.sm))
            }
            Column(Modifier.padding(top = Spacing.sm)) { ApprovalArguments(request) }
        }
    }
}

/** The tool's name and its arguments, pretty-printed and selectable. */
@Composable
private fun ApprovalArguments(request: ApprovalRequest) {
    SupportingText(request.toolName, style = LocusType.chip)
    CodeSurface(Modifier.fillMaxWidth()) {
        SelectionContainer { Text(prettyJson(request.argumentsJson).take(3000), Modifier.padding(Spacing.sm), style = LocusType.code) }
    }
}

/** The approval card's field list ([dev.locus.core.agent.Tool.preview]): label and value, rows reaching other people in caution. */
@Composable
private fun ApprovalPreviewRows(preview: ApprovalPreview) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        preview.rows.forEach { row ->
            KeyValueRow(
                row.label,
                row.value,
                dense = true,
                tone = if (row.outward) Tone.Caution else null,
                valueMaxLines = 8,
            )
        }
    }
}

/** "Don't ask again for npm test …", a command in the code font as everywhere else it's named. */
fun dontAskAgainText(scope: ApprovalScope): AnnotatedString = buildAnnotatedString {
    append("Don't ask again for ")
    if (scope.kind == ApprovalScopeKind.COMMAND) withStyle(InlineCode) { append(scope.label) } else append(scope.label)
}

/** The approval card's line for a site the user may allow until the task ends. */
fun siteForTaskText(host: String): String = "Allow $host for the rest of this task"

/** The button beside [siteForTaskText]: it allows this call too. */
const val SITE_FOR_TASK_ACTION = "Allow site"

private val prettyPrinter = Json { prettyPrint = true }

private fun prettyJson(json: String): String =
    runCatching { prettyPrinter.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(json) as JsonObject) }.getOrDefault(json)
