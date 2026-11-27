package dev.locus.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.locus.core.agent.DenyReason
import dev.locus.core.designsystem.ConfirmSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.locus.core.agent.ApprovalAnswer
import dev.locus.core.agent.ApprovalPreview
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ApprovalScope
import dev.locus.core.agent.MemoryChange
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
import dev.locus.core.designsystem.localizedApprovalSummary
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
    /** Needs you (docs/design/needs-you.md): the card's title instead of "Allow this action?" ("Morning inbox proposes"). */
    title: String? = null,
    /** One muted line under the title: which chat asks, or when a proposal was made and until when it can run. */
    byline: String? = null,
    /** The answer buttons' labels instead of Allow and Deny (Approve and Decline for a proposal). */
    allowLabel: String? = null,
    denyLabel: String? = null,
    /** False for a card that can't be answered any more (an expired proposal): no answer buttons, no "Don't ask again". */
    answerable: Boolean = true,
    /** The caution outline; off for a card that no longer needs the user. */
    outlined: Boolean = true,
    /** Scrolls the answer buttons into view when the card appears: in the chat's dock, not in a list the user scrolls. */
    bringIntoView: Boolean = true,
    /** Under the answers, or in their place on a card that can't be answered: Needs you's Dismiss. */
    footer: (@Composable () -> Unit)? = null,
    /**
     * Deny with a short reason the agent reads ("Don't email them, use Slack"): a long press on Deny opens
     * [DenyReasonSheet] (TalkBack offers it as Deny's long-press action). Null: plain Deny only.
     */
    onDenyWithReason: ((String, String) -> Unit)? = null,
) {
    var showDetails by remember(request.callId) { mutableStateOf(false) }
    var reasonSheet by remember(request.callId) { mutableStateOf(false) }
    if (reasonSheet && onDenyWithReason != null) {
        DenyReasonSheet(
            request,
            onDeny = { reason -> reasonSheet = false; onDenyWithReason(request.callId, reason) },
            onDismiss = { reasonSheet = false },
        )
    }
    val openReason: (() -> Unit)? = onDenyWithReason?.let { { reasonSheet = true } }
    val caution = Locus.colors.caution
    val large = largeFont()
    // The caution outline marks it; no glow, which would be decoration on top.
    // The answer buttons scroll into view in the dock when the card is taller than its room.
    val answers = remember { BringIntoViewRequester() }
    LaunchedEffect(request.callId) { if (bringIntoView) answers.bringIntoView() }
    LocusCard(modifier, outline = if (outlined) caution.copy(alpha = Emphasis.toneOutline) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // At a large font the text gets the tile's width: the address is what's being decided on.
            if (!large) {
                val (accent, icon) = accentFor(request.toolName)
                AccentTile(accent, icon, size = IconSize.tileSmall)
            }
            Column(Modifier.weight(1f).then(if (large) Modifier else Modifier.padding(start = Spacing.md))) {
                // A helper's request says which helper is asking.
                Text(title ?: request.origin?.let { stringResource(R.string.ui_approval_origin_wants, it.label) } ?: stringResource(R.string.ui_approval_title), style = LocusType.cardTitle)
                byline?.let { SupportingText(it, style = LocusType.rowSupporting, maxLines = 2) }
                // Whole at a large font: the address or command is what's being decided on.
                SupportingText(localizedApprovalSummary(request.summary, request.summaryText), style = LocusType.rowSupporting, maxLines = if (large) Int.MAX_VALUE else 3)
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
                Text(stringResource(if (showDetails && onDetails == null) R.string.ui_approval_hide_details else R.string.ui_approval_details))
            }
        }
        val allow = allowLabel ?: stringResource(R.string.ui_approval_allow)
        val deny = denyLabel ?: stringResource(R.string.ui_approval_deny)
        if (!answerable) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                details()
                Box(Modifier.weight(1f))
                footer?.invoke()
            }
        } else if (large) {
            // Stacked at a large font, full width, so no label breaks inside its button.
            Column(Modifier.fillMaxWidth().padding(top = Spacing.sm).bringIntoViewRequester(answers), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Button(onClick = { onAnswer(request.callId, true) }, modifier = Modifier.fillMaxWidth()) { Text(allow) }
                DenyButton(onDeny = { onAnswer(request.callId, false) }, onDenyWithReason = openReason, label = deny, modifier = Modifier.fillMaxWidth())
                details()
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm).bringIntoViewRequester(answers), verticalAlignment = Alignment.CenterVertically) {
                details()
                Box(Modifier.weight(1f))
                DenyButton(onDeny = { onAnswer(request.callId, false) }, onDenyWithReason = openReason, label = deny)
                Button(onClick = { onAnswer(request.callId, true) }, modifier = Modifier.padding(start = Spacing.sm)) { Text(allow) }
            }
        }
        if (answerable) footer?.invoke()
        // "Don't ask again", only where Locus offers it: a scope it can name, never for payments, flagged or
        // tainted calls, or dangerous commands (docs/design/remembered-approvals.md).
        request.remember?.takeIf { answerable }?.let { offer ->
            // The scope wraps whole; at a large font the buttons go under it.
            SideOrBelow(Modifier.fillMaxWidth(), main = {
                SupportingText(dontAskAgainText(offer.scope), modifier = Modifier.padding(start = Spacing.sm))
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onRemember(request.callId, ApprovalAnswer.ALLOW_FOR_CHAT) },
                        contentPadding = PaddingValues(horizontal = Spacing.sm),
                    ) { Text(stringResource(R.string.ui_approval_this_chat)) }
                    if (offer.always) {
                        TextButton(
                            onClick = { onRemember(request.callId, ApprovalAnswer.ALLOW_ALWAYS) },
                            contentPadding = PaddingValues(horizontal = Spacing.sm),
                        ) { Text(stringResource(R.string.ui_approval_always)) }
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
        request.siteForTask?.takeIf { answerable }?.let { host ->
            SideOrBelow(Modifier.fillMaxWidth(), main = {
                SupportingText(siteForTaskText(host), modifier = Modifier.padding(start = Spacing.sm))
            }) {
                TextButton(
                    onClick = { onRemember(request.callId, ApprovalAnswer.ALLOW_SITE_FOR_TASK) },
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                ) { Text(stringResource(R.string.ui_approval_allow_site)) }
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
    LocusSheet(title = stringResource(R.string.ui_approval_details), subtitle = localizedApprovalSummary(request.summary, request.summaryText), onDismiss = onDismiss) {
        // The sheet gives its content the title's left edge already.
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xl)) {
            request.origin?.let { SupportingText(stringResource(R.string.ui_approval_asked_by, it.label), modifier = Modifier.padding(bottom = Spacing.sm)) }
            request.preview?.let { preview -> ApprovalPreviewRows(preview) }
            request.reviewerNote?.let { note ->
                Text(note, style = LocusType.supporting, color = Locus.colors.onCautionContainer, modifier = Modifier.padding(vertical = Spacing.sm))
            }
            Column(Modifier.padding(top = Spacing.sm)) { ApprovalArguments(request) }
        }
    }
}

/**
 * Deny: a tap denies at once; with [onDenyWithReason], a long press opens the reason sheet (TalkBack
 * offers it as the button's long-press action). Drawn as Material's outlined button, which has no long
 * press of its own: the same shape, outline, colour, text style, padding and 48dp target.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DenyButton(onDeny: () -> Unit, onDenyWithReason: (() -> Unit)?, modifier: Modifier = Modifier, label: String = stringResource(R.string.ui_approval_deny)) {
    if (onDenyWithReason == null) {
        OutlinedButton(onClick = onDeny, modifier = modifier) { Text(label) }
        return
    }
    val longLabel = stringResource(R.string.ui_approval_deny_reason)
    androidx.compose.material3.Surface(
        modifier = modifier.minimumInteractiveComponentSize(),
        shape = ButtonDefaults.outlinedShape,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = ButtonDefaults.outlinedButtonColors().contentColor,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
    ) {
        Row(
            Modifier
                .combinedClickable(role = Role.Button, onLongClickLabel = longLabel, onLongClick = onDenyWithReason, onClick = onDeny)
                .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
                .padding(ButtonDefaults.ContentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideTextStyle(LocusType.label) { Text(label) }
        }
    }
}

/**
 * Deny, with a short reason the agent reads in the call's result ("Don't email them, use Slack"). One
 * field, capped at [DenyReason.MAX_CHARS]; Deny stays off until something is typed (plain Deny is on
 * the card). Closing the sheet leaves the card waiting.
 */
@Composable
fun DenyReasonSheet(request: ApprovalRequest, onDeny: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var reason by androidx.compose.runtime.saveable.rememberSaveable(request.callId) { mutableStateOf(initial.take(DenyReason.MAX_CHARS)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ConfirmSheet(
        title = stringResource(R.string.ui_approval_reason_title),
        message = stringResource(R.string.ui_approval_reason_message),
        confirmLabel = stringResource(R.string.ui_approval_deny),
        confirmEnabled = reason.isNotBlank(),
        onConfirm = { if (reason.isNotBlank()) onDeny(reason.trim()) },
        onDismiss = onDismiss,
    ) {
        // What is being denied, as the card says it.
        KeyValueRow(stringResource(R.string.ui_approval_reason_denying), localizedApprovalSummary(request.summary, request.summaryText), dense = true, valueMaxLines = 3)
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(DenyReason.MAX_CHARS) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(stringResource(R.string.ui_approval_reason_label)) },
            placeholder = { Text(stringResource(R.string.ui_approval_reason_placeholder)) },
            supportingText = { Text(stringResource(R.string.ui_approval_reason_count, reason.length, DenyReason.MAX_CHARS)) },
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
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
    // A memory write is shown in the app's language, with its note in full (docs/design/memory-approval.md).
    preview.memory?.let { change ->
        MemoryChangeRows(change)
        return
    }
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

/**
 * A memory write ([MemoryChange]): which memory, the topic, whether it's new, replaces a note (caution) or
 * forgets one (critical), the index line every new chat reads, and the whole note, selectable. Approving
 * saves exactly this; the card can't edit it (an approval answers yes or no to the call as the model made it).
 */
@Composable
fun MemoryChangeRows(change: MemoryChange) {
    val projectName = change.projectName
    val memory = when {
        change.projectId == null -> stringResource(R.string.ui_approval_memory_general)
        projectName.isNullOrBlank() -> stringResource(R.string.ui_approval_memory_this_project)
        else -> stringResource(R.string.ui_approval_memory_project, projectName)
    }
    val (kind, tone) = when (change.kind) {
        MemoryChange.Kind.NEW -> stringResource(R.string.ui_approval_memory_new) to null
        MemoryChange.Kind.REPLACE -> stringResource(R.string.ui_approval_memory_replace) to Tone.Caution
        MemoryChange.Kind.FORGET -> stringResource(R.string.ui_approval_memory_forget) to Tone.Critical
    }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        KeyValueRow(stringResource(R.string.ui_approval_memory_label), memory, dense = true)
        KeyValueRow(stringResource(R.string.ui_approval_memory_topic), change.topic, dense = true)
        KeyValueRow(stringResource(R.string.ui_approval_memory_change), kind, dense = true, tone = tone)
        change.replacedSummary?.takeIf { it.isNotBlank() }?.let { KeyValueRow(stringResource(R.string.ui_approval_memory_replaces), it, dense = true) }
        if (change.summary.isNotBlank()) {
            KeyValueRow(stringResource(R.string.ui_approval_memory_summary), change.summary, dense = true, valueMaxLines = Int.MAX_VALUE)
        }
        SupportingText(stringResource(R.string.ui_approval_memory_note), modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xxs))
        CodeSurface(Modifier.fillMaxWidth()) {
            SelectionContainer { Text(change.content, Modifier.padding(Spacing.sm), style = LocusType.rowSupporting) }
        }
    }
}

/** "Don't ask again for npm test …", a command in the code font as everywhere else it's named. */
@Composable
fun dontAskAgainText(scope: ApprovalScope): AnnotatedString {
    // One string with the scope as its argument, so a translation can move it; the marker shows where it landed.
    val text = stringResource(R.string.ui_approval_dont_ask_again, SCOPE_MARKER)
    val at = text.indexOf(SCOPE_MARKER)
    return buildAnnotatedString {
        if (at < 0) {
            append(text)
            return@buildAnnotatedString
        }
        append(text.substring(0, at))
        if (scope.kind == ApprovalScopeKind.COMMAND) withStyle(InlineCode) { append(scope.label) } else append(scope.label)
        append(text.substring(at + SCOPE_MARKER.length))
    }
}

private const val SCOPE_MARKER = "\uFFFC"

/** The approval card's line for a site the user may allow until the task ends. */
@Composable
fun siteForTaskText(host: String): String = stringResource(R.string.ui_approval_site_for_task, host)

private val prettyPrinter = Json { prettyPrint = true }

private fun prettyJson(json: String): String =
    runCatching { prettyPrinter.encodeToString(JsonObject.serializer(), Json.parseToJsonElement(json) as JsonObject) }.getOrDefault(json)
