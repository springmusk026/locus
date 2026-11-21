package dev.locus.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.locus.core.agent.workflow.ResearchWorkflow
import dev.locus.core.agent.workflow.ResearchWorkflow.Depth
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.R as DesignR
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.TaskBudgetEditor
import dev.locus.core.designsystem.TaskBudgetPresets
import dev.locus.core.model.SavedWorkflow
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowParam

/** Whether [saved] is the built-in research workflow (docs/design/deep-research.md), not a saved one that shares its id. */
fun isBuiltInResearch(saved: SavedWorkflow): Boolean = saved.builtIn && ResearchWorkflow.isResearch(saved.workflow)

/**
 * [saved] as the app shows it: a built-in workflow with its name, inputs and phases in the app's
 * language (what the model reads stays English, in core/agent); a saved one as the user wrote it.
 */
@Composable
fun shownWorkflow(saved: SavedWorkflow): Workflow = if (isBuiltInResearch(saved)) shownResearch() else saved.workflow

@Composable
private fun shownResearch(): Workflow {
    val w = ResearchWorkflow.workflow
    val question = stringResource(R.string.ui_research_question)
    val scope = stringResource(R.string.ui_research_scope)
    val scopeHint = stringResource(R.string.ui_research_scope_hint)
    val depth = stringResource(R.string.ui_research_depth)
    return w.copy(
        name = stringResource(R.string.ui_research_name),
        description = stringResource(R.string.ui_research_description),
        parameters = w.parameters.map { p ->
            when (p.name) {
                ResearchWorkflow.QUESTION -> p.copy(label = question, description = "")
                ResearchWorkflow.SCOPE -> p.copy(label = scope, description = scopeHint)
                ResearchWorkflow.DEPTH -> p.copy(label = depth, description = "")
                else -> p
            }
        },
        steps = listOf(
            stringResource(R.string.ui_research_phase_plan),
            stringResource(R.string.ui_research_phase_research),
            stringResource(R.string.ui_research_phase_verify),
            stringResource(R.string.ui_research_phase_report),
        ),
    )
}

/** "Quick", "Standard", "Thorough": a depth's name in the app's language. */
@Composable
fun researchDepthName(key: String): String = stringResource(
    when (Depth.of(key)) {
        Depth.QUICK -> R.string.ui_research_depth_quick
        Depth.STANDARD -> R.string.ui_research_depth_standard
        Depth.THOROUGH -> R.string.ui_research_depth_thorough
    },
)

/** "3 researchers · about 15 sources". */
@Composable
fun researchDepthSummary(depth: Depth): String =
    pluralStringResource(R.plurals.ui_research_helpers, depth.helpers, depth.helpers) + " · " +
        pluralStringResource(R.plurals.ui_research_sources, depth.sources, depth.sources)

/**
 * Run (or schedule) a workflow: the built-in research workflow gets its own sheet ([ResearchRunSheet]),
 * any other the plain [WorkflowRunSheet]. [lacksTools]: the model has no tool calling, so a workflow
 * that uses tools can't run; the sheet says why and Run is off. [onRun] gets the inputs and, for
 * research, the task budget chosen (null for other workflows).
 */
@Composable
fun RunWorkflowSheet(
    saved: SavedWorkflow,
    error: String?,
    lacksTools: Boolean,
    onRun: (values: Map<String, String>, budget: TaskBudget?) -> Unit,
    onDismiss: () -> Unit,
    actionLabel: String = stringResource(R.string.ui_workflow_run),
    /** A schedule's sheet: no budget choice (a scheduled run has its own limits) and what it can't do unattended. */
    scheduled: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    if (isBuiltInResearch(saved)) {
        ResearchRunSheet(error, lacksTools, onRun, onDismiss, actionLabel, scheduled, extra)
        return
    }
    WorkflowRunSheet(
        saved.workflow, error,
        onRun = { onRun(it, null) },
        onDismiss = onDismiss,
        actionLabel = actionLabel,
        extra = extra,
        unavailable = if (lacksTools && saved.workflow.tools.isNotEmpty()) stringResource(R.string.ui_workflow_unavailable_no_tools) else null,
    )
}

/**
 * The research workflow's run sheet: the question, scope notes and depth; what each depth does; the
 * task budget with what a run usually uses; and what outside content means for approvals.
 */
@Composable
fun ResearchRunSheet(
    error: String?,
    lacksTools: Boolean,
    onRun: (values: Map<String, String>, budget: TaskBudget?) -> Unit,
    onDismiss: () -> Unit,
    actionLabel: String = stringResource(R.string.ui_workflow_run),
    scheduled: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
    /** Inputs to start from (screenshots); empty with the default depth otherwise. */
    initial: Map<String, String> = emptyMap(),
    /** Start with the budget editor open (screenshots). */
    initialEditing: Boolean = false,
) {
    val shown = shownResearch()
    val values = remember {
        mutableStateMapOf(*ResearchWorkflow.workflow.parameters.map { it.name to (initial[it.name] ?: it.default.orEmpty()) }.toTypedArray())
    }
    val depth = Depth.of(values[ResearchWorkflow.DEPTH])
    // Null follows the depth's default until the user picks a budget of their own.
    var chosen by remember { mutableStateOf<TaskBudget?>(null) }
    var editing by rememberSaveable { mutableStateOf(initialEditing) }
    val budget = chosen ?: depth.defaultBudget
    val resources = LocalResources.current
    WorkflowRunSheet(
        shown, error,
        onRun = { onRun(it, if (scheduled) null else budget) },
        onDismiss = onDismiss,
        actionLabel = actionLabel,
        values = values,
        unavailable = if (lacksTools) stringResource(R.string.ui_workflow_unavailable_no_tools) else null,
        optionLabel = { p: WorkflowParam, option: String -> if (p.name == ResearchWorkflow.DEPTH) researchDepthName(option) else option },
        multiLine = true,
        planTitle = stringResource(R.string.ui_research_phases),
        showLimits = false,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SupportingText(researchDepthSummary(depth))
                if (!scheduled) {
                    Text(stringResource(R.string.ui_research_budget_title), Modifier.padding(top = Spacing.sm), style = LocusType.cardTitle)
                    val usual = TaskBudgetPresets.tokens(resources, depth.estimateTokens)
                    Text(
                        if (budget.isOff) stringResource(R.string.ui_research_budget_off, usual)
                        else stringResource(R.string.ui_research_budget_line, TaskBudgetPresets.summary(resources, budget), usual),
                        style = LocusType.rowSupporting,
                    )
                    val tokens = budget.tokens
                    if (tokens != null && tokens < depth.estimateTokens) {
                        Text(stringResource(R.string.ui_research_budget_low), style = LocusType.supporting, color = Locus.colors.onCautionContainer)
                    }
                    SupportingText(stringResource(R.string.ui_research_budget_note))
                    TextButton(onClick = { editing = !editing }) {
                        Text(stringResource(if (editing) DesignR.string.common_done else R.string.ui_research_budget_change))
                    }
                    if (editing) TaskBudgetEditor(budget, onChange = { chosen = it })
                } else {
                    Text(stringResource(R.string.ui_research_scheduled_note), style = LocusType.supporting, color = Locus.colors.onCautionContainer)
                }
                SectionNote(stringResource(R.string.ui_research_outside), Modifier.padding(top = Spacing.sm))
            }
            extra?.invoke()
        },
    )
}
