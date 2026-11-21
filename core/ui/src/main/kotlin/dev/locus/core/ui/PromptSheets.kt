package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.locus.core.agent.prompt.PromptField
import dev.locus.core.agent.prompt.PromptIssue
import dev.locus.core.agent.prompt.PromptSearch
import dev.locus.core.agent.prompt.PromptTemplate
import dev.locus.core.agent.prompt.PromptVariable
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipFlow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.designsystem.SearchField
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.model.SavedPrompt
import dev.locus.core.model.WorkflowParam

/**
 * Create or edit a saved prompt (docs/design/saved-prompts.md). The text is checked as it's typed:
 * a variable that can't be filled in is listed under it and Save stays off until it's fixed.
 * [prompt] null starts a new one; [error] says why the last save failed.
 */
@Composable
fun PromptEditorSheet(prompt: SavedPrompt?, error: String?, onSave: (SavedPrompt) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(prompt?.id) { mutableStateOf(prompt?.name.orEmpty()) }
    var body by remember(prompt?.id) { mutableStateOf(TextFieldValue(prompt?.text.orEmpty(), TextRange(prompt?.text.orEmpty().length))) }
    val template = remember(body.text) { PromptTemplate.parse(body.text) }
    val nameTooLong = name.trim().length > SavedPrompt.MAX_NAME
    val textTooLong = body.text.length > SavedPrompt.MAX_TEXT
    val labelWord = stringResource(R.string.ui_prompt_field_label)

    fun insert(token: String, select: IntRange? = null) {
        val start = minOf(body.selection.start, body.selection.end)
        val end = maxOf(body.selection.start, body.selection.end)
        val text = body.text.substring(0, start) + token + body.text.substring(end)
        val selection = select?.let { TextRange(start + it.first, start + it.last + 1) } ?: TextRange(start + token.length)
        body = TextFieldValue(text, selection)
    }

    LocusSheet(
        title = stringResource(if (prompt == null || prompt.id.isBlank()) R.string.ui_prompt_new_title else R.string.ui_prompt_edit_title),
        subtitle = stringResource(R.string.ui_prompt_editor_subtitle),
        onDismiss = onDismiss,
        action = stringResource(DesignR.string.common_save),
        actionEnabled = name.isNotBlank() && !nameTooLong && body.text.isNotBlank() && !textTooLong && template.isValid,
        onAction = { onSave(SavedPrompt(id = prompt?.id.orEmpty(), name = name.trim(), text = body.text, createdAt = prompt?.createdAt ?: 0)) },
    ) {
        error?.let { Text(it, color = Locus.colors.critical, style = LocusType.supporting) }
        OutlinedTextField(
            name, { name = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_prompt_name)) },
            singleLine = true,
            isError = nameTooLong,
            supportingText = if (nameTooLong) ({ Text(stringResource(R.string.ui_prompt_name_too_long, SavedPrompt.MAX_NAME)) }) else null,
        )
        OutlinedTextField(
            body, { body = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ui_prompt_text)) },
            minLines = 4,
            maxLines = 12,
            textStyle = LocusType.code,
            isError = !template.isValid || textTooLong,
            supportingText = when {
                textTooLong -> ({ Text(stringResource(R.string.ui_prompt_text_too_long, SavedPrompt.MAX_TEXT)) })
                !template.isValid -> ({ Text(issueCount(template.issues.size)) })
                else -> null
            },
        )
        template.issues.forEach { issue -> Text(promptIssueText(issue), color = Locus.colors.critical, style = LocusType.supporting) }

        SectionLabel(stringResource(R.string.ui_prompt_insert_variable))
        ChipFlow {
            LocusChip(stringResource(R.string.ui_prompt_var_clipboard), icon = LocusIcons.Add, onClick = { insert("{{clipboard}}") })
            LocusChip(stringResource(R.string.ui_prompt_var_date), icon = LocusIcons.Add, onClick = { insert("{{date}}") })
            LocusChip(stringResource(R.string.ui_prompt_var_time), icon = LocusIcons.Add, onClick = { insert("{{time}}") })
            LocusChip(stringResource(R.string.ui_prompt_var_selection), icon = LocusIcons.Add, onClick = { insert("{{selection}}") })
            // The label is selected, ready to type over.
            LocusChip(stringResource(R.string.ui_prompt_var_field), icon = LocusIcons.Add, onClick = { insert("{{field:$labelWord}}", 8 until 8 + labelWord.length) })
        }
        if (template.isValid && !template.isPlain) SectionNote(promptUses(template))
        SectionNote(stringResource(R.string.ui_prompt_syntax_note))
    }
}

/** "2 variables can't be filled in". */
@Composable
private fun issueCount(count: Int): String = androidx.compose.ui.res.pluralStringResource(R.plurals.ui_prompt_issues, count, count)

/** What's wrong with one variable, naming it. */
@Composable
fun promptIssueText(issue: PromptIssue): String = when (issue.kind) {
    PromptIssue.Kind.UNKNOWN_VARIABLE -> stringResource(R.string.ui_prompt_issue_unknown, issue.token)
    PromptIssue.Kind.UNCLOSED -> stringResource(R.string.ui_prompt_issue_unclosed)
    PromptIssue.Kind.EMPTY -> stringResource(R.string.ui_prompt_issue_empty)
    PromptIssue.Kind.BAD_FIELD -> stringResource(R.string.ui_prompt_issue_bad_field)
    PromptIssue.Kind.TOO_LONG -> stringResource(R.string.ui_prompt_issue_too_long, PromptTemplate.MAX_LABEL, PromptTemplate.MAX_DEFAULT)
    PromptIssue.Kind.CONFLICTING_DEFAULTS -> stringResource(R.string.ui_prompt_issue_defaults, issue.token)
    PromptIssue.Kind.TOO_MANY_FIELDS -> stringResource(R.string.ui_prompt_issue_too_many, PromptTemplate.MAX_FIELDS)
}

/** What picking a prompt will do, in one short line: the boxes it asks for and what it reads. */
@Composable
fun promptUses(template: PromptTemplate): String {
    val parts = buildList {
        if (template.fields.isNotEmpty()) add(stringResource(R.string.ui_prompt_uses_fields, template.fields.joinToString { it.label }))
        if (PromptVariable.CLIPBOARD in template.variables) add(stringResource(R.string.ui_prompt_uses_clipboard))
        if (PromptVariable.SELECTION in template.variables) add(stringResource(R.string.ui_prompt_uses_selection))
        if (PromptVariable.DATE in template.variables || PromptVariable.TIME in template.variables) add(stringResource(R.string.ui_prompt_uses_date))
    }
    return parts.joinToString(" · ")
}

/**
 * The saved prompts to pick from, with a search box (docs/design/saved-prompts.md). Picking one fills
 * the composer; nothing is sent. [onNew] opens the editor for a new prompt.
 */
@Composable
fun PromptPickerSheet(prompts: List<SavedPrompt>, onPick: (SavedPrompt) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit, initialQuery: String = "") {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    LocusSheet(title = stringResource(R.string.ui_prompt_picker_title), subtitle = stringResource(R.string.ui_prompt_picker_subtitle), onDismiss = onDismiss) {
        if (prompts.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.ui_prompt_none_title), style = LocusType.cardTitle)
                SupportingText(stringResource(R.string.ui_prompt_none_body), style = LocusType.rowSupporting)
                Button(onClick = onNew) { Text(stringResource(R.string.ui_prompt_new)) }
            }
            return@LocusSheet
        }
        SearchField(query, { query = it }, stringResource(R.string.ui_prompt_search))
        val shown = remember(prompts, query) { PromptSearch.filter(prompts, query) }
        if (shown.isEmpty()) SectionNote(stringResource(R.string.ui_prompt_no_match, query.trim()))
        CardGroup {
            shown.forEachIndexed { i, p ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                PromptRow(p, onClick = { onPick(p) })
            }
            if (shown.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
            AddRow(stringResource(R.string.ui_prompt_new), onClick = onNew)
        }
    }
}

/** One saved prompt in a list: its name, and its first line. */
@Composable
fun PromptRow(prompt: SavedPrompt, onClick: () -> Unit) {
    ListRow(
        title = prompt.name,
        subtitle = PromptSearch.preview(prompt.text),
        onClick = onClick,
        leading = { IconTile(LocusIcons.Prompt) },
    )
}

/** The form's key for the clipboard's box, when the clipboard was empty. */
const val PROMPT_CLIPBOARD_KEY = "{clipboard}"

/** The form's key for the shared text's box, when there was none. */
const val PROMPT_SELECTION_KEY = "{selection}"

/**
 * The boxes a picked prompt needs before it goes into the composer: its fields, and the clipboard or
 * the shared text when it uses them and they were empty. Built from the workflow run sheet's inputs
 * ([WorkflowInputFields]), text-only. Insert stays off until every box without a default is filled.
 * [onInsert] gets the values by [PromptField.key], plus [PROMPT_CLIPBOARD_KEY] and [PROMPT_SELECTION_KEY].
 */
@Composable
fun PromptFormSheet(
    name: String,
    fields: List<PromptField>,
    askClipboard: Boolean,
    askSelection: Boolean,
    onInsert: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboardLabel = stringResource(R.string.ui_prompt_form_clipboard)
    val clipboardNote = stringResource(R.string.ui_prompt_form_clipboard_empty)
    val selectionLabel = stringResource(R.string.ui_prompt_form_selection)
    val selectionNote = stringResource(R.string.ui_prompt_form_selection_empty)
    val params = remember(fields, askClipboard, askSelection, clipboardLabel, selectionLabel) {
        buildList {
            if (askSelection) add(WorkflowParam(PROMPT_SELECTION_KEY, selectionLabel, description = selectionNote))
            if (askClipboard) add(WorkflowParam(PROMPT_CLIPBOARD_KEY, clipboardLabel, description = clipboardNote))
            fields.forEach { f -> add(WorkflowParam(f.key, f.label, default = f.default, required = f.required)) }
        }
    }
    val values = remember(params) { mutableStateMapOf(*params.map { it.name to it.default.orEmpty() }.toTypedArray()) }
    LocusSheet(
        title = name,
        subtitle = stringResource(R.string.ui_prompt_form_subtitle),
        onDismiss = onDismiss,
        action = stringResource(R.string.ui_prompt_insert),
        actionEnabled = params.all { !it.required || values[it.name].orEmpty().isNotBlank() },
        onAction = { onInsert(values.toMap()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            WorkflowInputFields(params, values, multiLine = true)
        }
    }
}
