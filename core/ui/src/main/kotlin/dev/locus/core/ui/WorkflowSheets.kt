package dev.locus.core.ui

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.InlineSpinner
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowParam
import dev.locus.core.model.WorkflowParamType

/** Where the workflow editor is: drafting (the helper model is working), or ready to edit. */
sealed interface WorkflowDraftState {
    data object Drafting : WorkflowDraftState

    /** [replacing]: the id it was opened with, when editing a saved one. [projectId]: where it's kept. */
    data class Ready(val workflow: Workflow, val projectId: String?, val replacing: String? = null, val error: String? = null) : WorkflowDraftState
}

/**
 * The review sheet: everything Locus drafted from a task, editable before it's saved. Tools and
 * approval points are one list, since each tool either runs on its own or asks first.
 */
@Composable
fun WorkflowEditorSheet(
    state: WorkflowDraftState,
    /** The chat's project, offered as where to keep it; null outside projects. */
    projectName: String?,
    onSave: (workflow: Workflow, inProject: Boolean, replacing: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is WorkflowDraftState.Drafting) {
        LocusSheet(title = stringResource(R.string.ui_workflow_save_title), onDismiss = onDismiss) {
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                InlineSpinner()
                Text(stringResource(R.string.ui_workflow_drafting), Modifier.padding(start = Spacing.md), style = LocusType.rowSupporting)
            }
        }
        return
    }
    val ready = state as WorkflowDraftState.Ready
    val w = ready.workflow
    var name by remember(w) { mutableStateOf(w.name) }
    var description by remember(w) { mutableStateOf(w.description) }
    var goal by remember(w) { mutableStateOf(w.goal) }
    var steps by remember(w) { mutableStateOf(w.steps.joinToString("\n")) }
    var expected by remember(w) { mutableStateOf(w.expectedOutput) }
    val params = remember(w) { mutableStateListOf(*w.parameters.toTypedArray()) }
    val tools = remember(w) { mutableStateListOf(*(w.tools + w.approvals.filter { it !in w.tools }).toTypedArray()) }
    val asks = remember(w) { mutableStateMapOf(*w.approvals.map { it to true }.toTypedArray()) }
    val checks = remember(w) { mutableStateListOf(*w.checks.toTypedArray()) }
    var newCheck by remember(w) { mutableStateOf("") }
    var inProject by remember(w) { mutableStateOf(ready.projectId != null || (ready.replacing == null && projectName != null)) }

    fun build(): Workflow = w.copy(
        id = if (ready.replacing != null) w.id else dev.locus.core.agent.workflow.WorkflowDraft.idFor(name),
        name = name.trim(),
        description = description.trim(),
        goal = goal.trim(),
        parameters = params.toList(),
        steps = steps.lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() },
        tools = tools.toList(),
        approvals = tools.filter { asks[it] == true },
        checks = checks.toList() + listOfNotNull(newCheck.trim().takeIf { it.isNotEmpty() }?.let { WorkflowCheck(WorkflowCheck.CheckKind.AGENT, it) }),
        expectedOutput = expected.trim(),
    )

    LocusSheet(
        title = stringResource(if (ready.replacing != null) R.string.ui_workflow_edit_title else R.string.ui_workflow_save_title),
        subtitle = stringResource(R.string.ui_workflow_review_subtitle),
        onDismiss = onDismiss,
        action = stringResource(DesignR.string.common_save),
        actionEnabled = name.isNotBlank() && goal.isNotBlank(),
        onAction = { onSave(build(), inProject && (projectName != null || ready.projectId != null), ready.replacing) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ready.error?.let { Text(it, color = Locus.colors.critical, style = LocusType.supporting) }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_name)) }, singleLine = true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_description)) })
            OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_goal)) }, minLines = 2,
                supportingText = { Text(stringResource(R.string.ui_workflow_goal_hint)) })

            Header(stringResource(R.string.ui_workflow_inputs))
            params.forEachIndexed { i, p -> ParamEditor(p, onChange = { params[i] = it }, onRemove = { params.removeAt(i) }) }
            TextButton(onClick = { params.add(WorkflowParam(name = "input_${params.size + 1}")) }, enabled = params.size < Workflow.MAX_PARAMS) {
                Text(stringResource(R.string.ui_workflow_add_input))
            }

            OutlinedTextField(steps, { steps = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_steps)) }, minLines = 3,
                supportingText = { Text(stringResource(R.string.ui_workflow_steps_hint)) })

            Header(stringResource(R.string.ui_workflow_tools))
            if (tools.isEmpty()) {
                SectionNote(stringResource(R.string.ui_workflow_tools_no_limit))
            } else {
                SectionNote(stringResource(R.string.ui_workflow_tools_note))
                CardGroup {
                    tools.forEachIndexed { i, key ->
                        if (i > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(start = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                            // The plain name, with the tool's id under it for anyone matching it to settings.
                            Column(Modifier.weight(1f).padding(vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                Text(workflowToolName(key).replaceFirstChar { it.uppercase() }, style = LocusType.rowSupporting)
                                SupportingText(key, style = LocusType.code)
                            }
                            SupportingText(stringResource(R.string.ui_workflow_ask), style = LocusType.chip)
                            Checkbox(checked = asks[key] == true, onCheckedChange = { asks[key] = it })
                            IconButton(onClick = { tools.removeAt(i); asks.remove(key) }) {
                                Icon(LocusIcons.Close, contentDescription = stringResource(R.string.ui_workflow_remove_tool, workflowToolName(key)), Modifier.size(IconSize.small))
                            }
                        }
                    }
                }
            }

            Header(stringResource(R.string.ui_workflow_checks))
            checks.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = LocusType.rowSupporting)
                        SupportingText(checkHow(c))
                    }
                    IconButton(onClick = { checks.removeAt(i) }) { Icon(LocusIcons.Close, contentDescription = stringResource(R.string.ui_workflow_remove_check), Modifier.size(IconSize.small)) }
                }
            }
            OutlinedTextField(newCheck, { newCheck = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_add_check)) }, singleLine = true)
            OutlinedTextField(expected, { expected = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_expected)) })

            if (projectName != null && ready.replacing == null) {
                Header(stringResource(R.string.ui_workflow_keep_in))
                Segmented(listOf(stringResource(R.string.ui_workflow_this_project), stringResource(R.string.ui_workflow_all_chats)), selected = if (inProject) 0 else 1, onSelect = { inProject = it == 0 }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** How a check is judged, in a few words. */
@Composable
fun checkHow(c: WorkflowCheck): String = when (c.kind) {
    WorkflowCheck.CheckKind.TESTS_PASS -> stringResource(R.string.ui_workflow_check_tests)
    WorkflowCheck.CheckKind.BUILD_PASS -> stringResource(R.string.ui_workflow_check_build)
    WorkflowCheck.CheckKind.CHECK_PASS -> stringResource(R.string.ui_workflow_check_lint)
    WorkflowCheck.CheckKind.FILE_CHANGED -> stringResource(R.string.ui_workflow_check_file, c.value.ifBlank { stringResource(R.string.ui_workflow_check_a_file) })
    WorkflowCheck.CheckKind.REPLY_CONTAINS -> stringResource(R.string.ui_workflow_check_reply, c.value)
    WorkflowCheck.CheckKind.AGENT -> stringResource(R.string.ui_workflow_check_agent)
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(top = Spacing.sm), style = LocusType.cardTitle)
}

@Composable
private fun ParamEditor(p: WorkflowParam, onChange: (WorkflowParam) -> Unit, onRemove: () -> Unit) {
    var typeMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                p.name, { onChange(p.copy(name = it.lowercase().replace(Regex("[^a-z0-9_]"), "_"))) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.ui_workflow_slot)) }, singleLine = true,
                isError = !WorkflowParam.NAME.matches(p.name),
            )
            IconButton(onClick = onRemove) { Icon(LocusIcons.Trash, contentDescription = stringResource(R.string.ui_workflow_remove_input, p.name), Modifier.size(IconSize.medium)) }
        }
        // Type beside Required: wide enough for every type's name. At a large font, Required goes under it.
        val typeField = @Composable { modifier: Modifier ->
            // A picker that looks like one, the same width in every row: the type's field, tap to choose.
            val typeName = paramTypeName(p.type)
            val typeSpoken = stringResource(R.string.ui_workflow_type_spoken, p.name, typeName)
            val typeChoose = stringResource(R.string.ui_workflow_type_choose)
            Box(modifier) {
                OutlinedTextField(
                    typeName, {}, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ui_workflow_type)) }, singleLine = true, readOnly = true,
                    trailingIcon = { Icon(LocusIcons.ChevronRight, contentDescription = null, Modifier.size(IconSize.small).rotate(90f)) },
                )
                Box(
                    Modifier.matchParentSize().clickable(role = Role.DropdownList, onClickLabel = typeChoose) { typeMenu = true }
                        .semantics { contentDescription = typeSpoken },
                )
                DropdownMenu(typeMenu, onDismissRequest = { typeMenu = false }) {
                    WorkflowParamType.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(paramTypeName(t)) }, onClick = { typeMenu = false; onChange(p.copy(type = t)) })
                    }
                }
            }
        }
        val required = @Composable {
            Checkbox(checked = p.required, onCheckedChange = { onChange(p.copy(required = it)) })
            Text(stringResource(R.string.ui_workflow_required), style = LocusType.supporting)
        }
        if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
            typeField(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { required() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                typeField(Modifier.weight(1f))
                Box(Modifier.padding(start = Spacing.sm))
                required()
            }
        }
        OutlinedTextField(p.default.orEmpty(), { onChange(p.copy(default = it.ifEmpty { null })) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_default)) }, singleLine = true)
        if (p.type == WorkflowParamType.CHOICE) {
            OutlinedTextField(
                p.options.joinToString(", "), { onChange(p.copy(options = it.split(',').map(String::trim).filter(String::isNotEmpty))) },
                Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_workflow_options)) }, singleLine = true,
            )
        }
    }
}

@Composable
private fun paramTypeName(type: WorkflowParamType): String = stringResource(
    when (type) {
        WorkflowParamType.TEXT -> R.string.ui_workflow_type_text
        WorkflowParamType.URL -> R.string.ui_workflow_type_url
        WorkflowParamType.PATH -> R.string.ui_workflow_type_path
        WorkflowParamType.NUMBER -> R.string.ui_workflow_type_number
        WorkflowParamType.BOOLEAN -> R.string.ui_workflow_type_boolean
        WorkflowParamType.CHOICE -> R.string.ui_workflow_type_choice
    },
)

/**
 * Fill in a workflow's inputs and run it. Says up front which steps will ask, and, for a scheduled
 * run, that those can't happen with nobody there.
 */
@Composable
fun WorkflowRunSheet(
    workflow: Workflow,
    error: String?,
    onRun: (values: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    /** Extra controls under the inputs (a schedule's interval); null for none. */
    actionLabel: String = stringResource(R.string.ui_workflow_run),
    extra: (@Composable () -> Unit)? = null,
) {
    val values = remember(workflow) { mutableStateMapOf(*workflow.parameters.map { it.name to it.default.orEmpty() }.toTypedArray()) }
    LocusSheet(
        title = workflow.name,
        subtitle = workflow.description.ifBlank { null },
        onDismiss = onDismiss,
        action = actionLabel,
        onAction = { onRun(values.toMap()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            error?.let { Text(it, color = Locus.colors.critical, style = LocusType.supporting) }
            workflow.parameters.forEach { p ->
                when (p.type) {
                    WorkflowParamType.BOOLEAN -> CardGroup {
                        SwitchRow(
                            title = p.title,
                            subtitle = p.description.ifBlank { null },
                            checked = values[p.name] == "true",
                            onCheckedChange = { values[p.name] = it.toString() },
                        )
                    }
                    WorkflowParamType.CHOICE -> if (p.options.isNotEmpty()) {
                        Text(p.title, style = LocusType.rowSupporting)
                        if (p.options.size <= 3) {
                            Segmented(p.options, selected = p.options.indexOf(values[p.name]).coerceAtLeast(0), onSelect = { values[p.name] = p.options[it] }, modifier = Modifier.fillMaxWidth())
                        } else {
                            var open by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { open = true }) { Text(values[p.name].orEmpty().ifBlank { stringResource(R.string.ui_workflow_choose) }) }
                                DropdownMenu(open, onDismissRequest = { open = false }) {
                                    p.options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; values[p.name] = o }) }
                                }
                            }
                        }
                    }
                    else -> OutlinedTextField(
                        values[p.name].orEmpty(), { values[p.name] = it }, Modifier.fillMaxWidth(),
                        label = { Text(if (p.required) p.title else stringResource(R.string.ui_workflow_optional, p.title)) },
                        supportingText = p.description.takeIf { it.isNotBlank() }?.let { d -> { Text(d) } },
                        singleLine = true,
                    )
                }
            }
            if (workflow.steps.isNotEmpty()) {
                Text(stringResource(R.string.ui_workflow_plan), Modifier.padding(top = Spacing.sm), style = LocusType.cardTitle)
                workflow.steps.forEachIndexed { i, s -> Text(stringResource(R.string.ui_workflow_plan_step, i + 1, s), style = LocusType.supporting) }
            }
            if (workflow.approvals.isNotEmpty()) {
                SectionNote(stringResource(R.string.ui_workflow_asks_before, workflow.approvals.map { workflowToolName(it) }.distinct().joinToString()))
            }
            if (workflow.tools.isNotEmpty()) {
                SectionNote(
                    workflow.tools.filter { it !in workflow.approvals }.map { workflowToolName(it) }.distinct()
                        .let { own -> if (own.isEmpty()) stringResource(R.string.ui_workflow_only_reads) else stringResource(R.string.ui_workflow_can_on_its_own, own.joinToString()) },
                )
            }
            extra?.invoke()
        }
    }
}

/** Pick a saved workflow to run in this chat. */
@Composable
fun WorkflowPickerSheet(workflows: List<SavedWorkflow>, projectName: String?, onPick: (SavedWorkflow) -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = stringResource(R.string.ui_workflow_run_title), onDismiss = onDismiss) {
        if (workflows.isEmpty()) {
            SectionNote(stringResource(R.string.ui_workflow_none))
        } else {
            CardGroup {
                workflows.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        title = s.workflow.name,
                        subtitle = listOfNotNull(
                            s.workflow.description.ifBlank { null },
                            if (s.projectId != null) projectName ?: stringResource(R.string.ui_workflow_this_project) else null,
                        ).joinToString(" · ").ifBlank { s.workflow.goal },
                        onClick = { onPick(s) },
                        leading = { Icon(LocusIcons.Play, contentDescription = null) },
                    )
                }
            }
        }
    }
}
