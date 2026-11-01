package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Spacing
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
        LocusSheet(title = "Save as workflow", onDismiss = onDismiss) {
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Drafting from the task…", Modifier.padding(start = Spacing.md), style = MaterialTheme.typography.bodyMedium)
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
        title = if (ready.replacing != null) "Edit workflow" else "Save as workflow",
        subtitle = "Locus drafted this from the task. Check it before saving: it runs again with these tools and approvals.",
        onDismiss = onDismiss,
        action = "Save",
        actionEnabled = name.isNotBlank() && goal.isNotBlank(),
        onAction = { onSave(build(), inProject && (projectName != null || ready.projectId != null), ready.replacing) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ready.error?.let { Text(it, color = Locus.colors.critical, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") })
            OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("What to do") }, minLines = 2,
                supportingText = { Text("Inputs go in braces, like {folder}.") })

            Header("Inputs")
            params.forEachIndexed { i, p -> ParamEditor(p, onChange = { params[i] = it }, onRemove = { params.removeAt(i) }) }
            TextButton(onClick = { params.add(WorkflowParam(name = "input_${params.size + 1}")) }, enabled = params.size < Workflow.MAX_PARAMS) {
                Text("Add an input")
            }

            OutlinedTextField(steps, { steps = it }, Modifier.fillMaxWidth(), label = { Text("Steps, one per line") }, minLines = 3,
                supportingText = { Text("A plan the agent adapts, not calls it replays.") })

            Header("Tools")
            if (tools.isEmpty()) {
                SectionNote("No limit: the workflow may use any tool your settings allow.")
            } else {
                SectionNote("Anything else that changes something asks you first. “Ask” keeps a step's approval even when your settings allow it.")
                CardGroup {
                    tools.forEachIndexed { i, key ->
                        if (i > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(start = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("Ask", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Checkbox(checked = asks[key] == true, onCheckedChange = { asks[key] = it })
                            IconButton(onClick = { tools.removeAt(i); asks.remove(key) }) { Icon(LocusIcons.Close, contentDescription = "Remove $key", Modifier.size(16.dp)) }
                        }
                    }
                }
            }

            Header("Success checks")
            checks.forEachIndexed { i, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.bodyMedium)
                        Text(checkHow(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { checks.removeAt(i) }) { Icon(LocusIcons.Close, contentDescription = "Remove check", Modifier.size(16.dp)) }
                }
            }
            OutlinedTextField(newCheck, { newCheck = it }, Modifier.fillMaxWidth(), label = { Text("Add a check the agent confirms") }, singleLine = true)
            OutlinedTextField(expected, { expected = it }, Modifier.fillMaxWidth(), label = { Text("Expected output") })

            if (projectName != null && ready.replacing == null) {
                Header("Keep it in")
                Segmented(listOf("This project", "All chats"), selected = if (inProject) 0 else 1, onSelect = { inProject = it == 0 }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** How a check is judged, in a few words. */
fun checkHow(c: WorkflowCheck): String = when (c.kind) {
    WorkflowCheck.CheckKind.TESTS_PASS -> "Locus checks the last test run"
    WorkflowCheck.CheckKind.BUILD_PASS -> "Locus checks the last build"
    WorkflowCheck.CheckKind.CHECK_PASS -> "Locus checks the last lint or type check"
    WorkflowCheck.CheckKind.FILE_CHANGED -> "Locus checks that ${c.value.ifBlank { "a file" }} changed"
    WorkflowCheck.CheckKind.REPLY_CONTAINS -> "Locus checks the reply mentions “${c.value}”"
    WorkflowCheck.CheckKind.AGENT -> "The agent confirms it"
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun ParamEditor(p: WorkflowParam, onChange: (WorkflowParam) -> Unit, onRemove: () -> Unit) {
    var typeMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                p.name, { onChange(p.copy(name = it.lowercase().replace(Regex("[^a-z0-9_]"), "_"))) }, Modifier.weight(1f),
                label = { Text("Slot") }, singleLine = true,
                isError = !WorkflowParam.NAME.matches(p.name),
            )
            Box {
                TextButton(onClick = { typeMenu = true }) { Text(p.type.name.lowercase()) }
                DropdownMenu(typeMenu, onDismissRequest = { typeMenu = false }) {
                    WorkflowParamType.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(t.name.lowercase()) }, onClick = { typeMenu = false; onChange(p.copy(type = t)) })
                    }
                }
            }
            IconButton(onClick = onRemove) { Icon(LocusIcons.Trash, contentDescription = "Remove input ${p.name}", Modifier.size(18.dp)) }
        }
        OutlinedTextField(p.default.orEmpty(), { onChange(p.copy(default = it.ifEmpty { null })) }, Modifier.fillMaxWidth(), label = { Text("Default") }, singleLine = true)
        if (p.type == WorkflowParamType.CHOICE) {
            OutlinedTextField(
                p.options.joinToString(", "), { onChange(p.copy(options = it.split(',').map(String::trim).filter(String::isNotEmpty))) },
                Modifier.fillMaxWidth(), label = { Text("Options, comma-separated") }, singleLine = true,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = p.required, onCheckedChange = { onChange(p.copy(required = it)) })
            Text("Required", style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
    actionLabel: String = "Run",
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
            error?.let { Text(it, color = Locus.colors.critical, style = MaterialTheme.typography.bodySmall) }
            workflow.parameters.forEach { p ->
                when (p.type) {
                    WorkflowParamType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = values[p.name] == "true", onCheckedChange = { values[p.name] = it.toString() })
                    }
                    WorkflowParamType.CHOICE -> if (p.options.isNotEmpty()) {
                        Text(p.title, style = MaterialTheme.typography.bodyMedium)
                        if (p.options.size <= 3) {
                            Segmented(p.options, selected = p.options.indexOf(values[p.name]).coerceAtLeast(0), onSelect = { values[p.name] = p.options[it] }, modifier = Modifier.fillMaxWidth())
                        } else {
                            var open by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { open = true }) { Text(values[p.name].orEmpty().ifBlank { "Choose" }) }
                                DropdownMenu(open, onDismissRequest = { open = false }) {
                                    p.options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; values[p.name] = o }) }
                                }
                            }
                        }
                    }
                    else -> OutlinedTextField(
                        values[p.name].orEmpty(), { values[p.name] = it }, Modifier.fillMaxWidth(),
                        label = { Text(p.title + if (p.required) "" else " (optional)") },
                        supportingText = p.description.takeIf { it.isNotBlank() }?.let { d -> { Text(d) } },
                        singleLine = true,
                    )
                }
            }
            if (workflow.steps.isNotEmpty()) {
                Text("Plan", Modifier.padding(top = Spacing.sm), style = MaterialTheme.typography.titleSmall)
                workflow.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
            }
            if (workflow.approvals.isNotEmpty()) {
                SectionNote("Asks you before: ${workflow.approvals.joinToString()}.")
            }
            if (workflow.tools.isNotEmpty()) {
                SectionNote("Runs on its own with: ${workflow.tools.filter { it !in workflow.approvals }.joinToString().ifEmpty { "reading only" }}. Anything else asks first.")
            }
            extra?.invoke()
        }
    }
}

/** Pick a saved workflow to run in this chat. */
@Composable
fun WorkflowPickerSheet(workflows: List<SavedWorkflow>, projectName: String?, onPick: (SavedWorkflow) -> Unit, onDismiss: () -> Unit) {
    LocusSheet(title = "Run a workflow", onDismiss = onDismiss) {
        if (workflows.isEmpty()) {
            SectionNote("No workflows yet. When a task goes well, open its receipt and tap Save as workflow.")
        } else {
            CardGroup {
                workflows.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        title = s.workflow.name,
                        subtitle = listOfNotNull(
                            s.workflow.description.ifBlank { null },
                            if (s.projectId != null) projectName ?: "This project" else null,
                        ).joinToString(" · ").ifBlank { s.workflow.goal },
                        onClick = { onPick(s) },
                        leading = { Icon(LocusIcons.Play, contentDescription = null) },
                    )
                }
            }
        }
    }
}
