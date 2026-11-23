package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipFlow
import dev.locus.core.designsystem.ConfirmSheet
import dev.locus.core.designsystem.EmptyState
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.InlineSwitch
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LoadingState
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusListScreen
import dev.locus.core.designsystem.LocusScreen
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.TextArea
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.locusSection
import dev.locus.core.model.ProjectRule
import dev.locus.core.model.RuleProblem
import dev.locus.core.model.RuleTrigger

/**
 * Project → Rules (docs/design/rules.md): commands Locus runs after an edit or before the agent
 * finishes, and commands it blocks. Only here can rules be made or changed; each rule's command is
 * shown in full.
 */
@Composable
fun ProjectRulesRoute(
    projectId: String,
    onBack: () -> Unit,
    /** Opens where Linux is installed (Files → Linux). */
    onOpenLinux: () -> Unit,
    viewModel: ProjectRulesViewModel = hiltViewModel<ProjectRulesViewModel, ProjectRulesViewModel.Factory>(key = "project-rules-$projectId") { it.create(projectId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.chat_rules_title)
    when {
        state.loading -> LocusScreen(title, onBack = onBack) { padding -> LoadingState(Modifier.padding(padding)) }
        state.missing -> LocusScreen(title, onBack = onBack) { padding ->
            EmptyState(stringResource(R.string.chat_project_screen_not_found), stringResource(R.string.chat_project_screen_not_found_body), Modifier.padding(padding), icon = LocusIcons.Folder)
        }
        else -> RulesList(state, onBack, onOpenLinux, viewModel)
    }
    state.editing?.let { draft ->
        RuleEditorSheet(
            draft = draft,
            linuxReady = state.linuxReady,
            onChange = viewModel::change,
            onSave = viewModel::save,
            onDelete = { id -> viewModel.delete(id) },
            onDismiss = viewModel::close,
        )
    }
}

@Composable
private fun RulesList(state: ProjectRulesState, onBack: () -> Unit, onOpenLinux: () -> Unit, viewModel: ProjectRulesViewModel) {
    val addLabel = stringResource(R.string.chat_rules_add)
    val titles = RuleTrigger.entries.associateWith { triggerTitle(it) }
    val notes = RuleTrigger.entries.associateWith { triggerNote(it) }
    LocusListScreen(
        title = stringResource(R.string.chat_rules_title),
        subtitle = state.projectName,
        onBack = onBack,
        actions = {
            if (state.rules.isNotEmpty() && !state.full) {
                IconButton(onClick = viewModel::add) { Icon(LocusIcons.Add, contentDescription = addLabel) }
            }
        },
    ) {
        if (state.suspended > 0) {
            item(key = "suspended") {
                Banner(
                    stringResource(R.string.chat_rules_suspended_banner),
                    Tone.Caution,
                    Modifier.padding(bottom = Spacing.sm),
                    title = stringResource(R.string.chat_rules_suspended_title),
                )
            }
        }
        if (!state.linuxReady && state.rules.any { it.trigger.runsCommand }) {
            item(key = "linux") {
                Banner(
                    stringResource(R.string.chat_rules_no_linux),
                    Tone.Info,
                    Modifier.padding(bottom = Spacing.sm),
                    actions = { TextButton(onClick = onOpenLinux) { Text(stringResource(R.string.chat_rules_open_linux)) } },
                )
            }
        }
        if (state.rules.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.chat_rules_empty_title),
                    body = stringResource(R.string.chat_rules_empty_body),
                    icon = LocusIcons.Shield,
                    action = { TextButton(onClick = viewModel::add) { Text(addLabel) } },
                )
            }
            return@LocusListScreen
        }
        RuleTrigger.entries.forEach { trigger ->
            val rules = state.rules.filter { it.trigger == trigger }
            if (rules.isEmpty()) return@forEach
            locusSection(title = titles.getValue(trigger), key = "section-${trigger.wire}", note = notes.getValue(trigger)) {
                item(key = "rules-${trigger.wire}") {
                    CardGroup {
                        rules.forEachIndexed { i, rule ->
                            if (i > 0) GroupDivider()
                            RuleRow(rule, inactive = trigger.runsCommand && !state.linuxReady, onOpen = { viewModel.edit(rule) }, onEnabled = { viewModel.setEnabled(rule.id, it) })
                        }
                    }
                }
            }
        }
        item(key = "note") { SectionNote(stringResource(R.string.chat_rules_footer), Modifier.padding(top = Spacing.md)) }
    }
}

@Composable
private fun triggerTitle(trigger: RuleTrigger) = stringResource(
    when (trigger) {
        RuleTrigger.AFTER_EDIT -> R.string.chat_rules_trigger_after_edit
        RuleTrigger.BEFORE_FINISH -> R.string.chat_rules_trigger_before_finish
        RuleTrigger.BEFORE_COMMAND -> R.string.chat_rules_trigger_before_command
    },
)

@Composable
private fun triggerNote(trigger: RuleTrigger) = stringResource(
    when (trigger) {
        RuleTrigger.AFTER_EDIT -> R.string.chat_rules_trigger_after_edit_note
        RuleTrigger.BEFORE_FINISH -> R.string.chat_rules_trigger_before_finish_note
        RuleTrigger.BEFORE_COMMAND -> R.string.chat_rules_trigger_before_command_note
    },
)

/** One rule: its name, its whole command (or what it blocks), and its state. */
@Composable
private fun RuleRow(rule: ProjectRule, inactive: Boolean, onOpen: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val icon = when (rule.trigger) {
        RuleTrigger.AFTER_EDIT -> LocusIcons.Terminal
        RuleTrigger.BEFORE_FINISH -> LocusIcons.Check
        RuleTrigger.BEFORE_COMMAND -> LocusIcons.Shield
    }
    ListRow(
        title = rule.name,
        titleMaxLines = 2,
        onClick = onOpen,
        leading = { IconTile(icon, tint = if (rule.suspended) Locus.colors.caution else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = {
            InlineSwitch(
                checked = rule.enabled,
                onCheckedChange = onEnabled,
                contentDescription = stringResource(R.string.chat_rules_switch, rule.name),
                enabled = !rule.suspended,
            )
        },
        meta = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val shown = if (rule.trigger.runsCommand) rule.command else rule.pattern.ifBlank { stringResource(R.string.chat_rules_any_command) }
                // Commands are shown in full, never cut: what runs is exactly what's written here.
                Text(shown, style = LocusType.code)
                if (!rule.trigger.runsCommand) SupportingText(stringResource(R.string.chat_rules_tells_agent, rule.message), style = LocusType.rowSupporting)
                ChipFlow {
                    when {
                        rule.suspended -> LocusChip(stringResource(R.string.chat_rules_chip_suspended), tone = Tone.Caution, dot = true)
                        !rule.enabled -> LocusChip(stringResource(R.string.chat_rules_chip_off))
                        inactive -> LocusChip(stringResource(R.string.chat_rules_chip_inactive), tone = Tone.Info)
                    }
                    if (rule.trigger == RuleTrigger.AFTER_EDIT) LocusChip(rule.globs.joinToString(", ").ifEmpty { stringResource(R.string.chat_rules_any_file) })
                    if (rule.trigger.runsCommand) LocusChip(stringResource(R.string.chat_rules_chip_timeout, rule.timeout))
                }
            }
        },
    )
}

/** Make or change one rule. A suspended rule says so and is resumed by saving it. */
@Composable
private fun RuleEditorSheet(
    draft: RuleDraft,
    linuxReady: Boolean,
    onChange: (RuleDraft.() -> RuleDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val problem = draft.problem
    LocusSheet(
        title = stringResource(if (draft.id == null) R.string.chat_rules_new else R.string.chat_rules_edit),
        onDismiss = onDismiss,
        action = stringResource(if (draft.suspended) R.string.chat_rules_resume else R.string.chat_rules_save),
        actionEnabled = problem == null,
        onAction = onSave,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (draft.suspended) {
                Banner(stringResource(R.string.chat_rules_suspended_editor), Tone.Caution)
            }
            OutlinedTextField(
                draft.name, { v -> onChange { copy(name = v.take(ProjectRule.MAX_NAME)) } }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.chat_rules_name)) },
                placeholder = { Text(stringResource(R.string.chat_rules_name_hint)) },
                singleLine = true,
            )
            SectionLabel(stringResource(R.string.chat_rules_when))
            CardGroup {
                RuleTrigger.entries.forEachIndexed { i, trigger ->
                    if (i > 0) GroupDivider()
                    ListRow(
                        title = triggerTitle(trigger),
                        subtitle = triggerNote(trigger),
                        subtitleMaxLines = 3,
                        onClick = { onChange { copy(trigger = trigger) } },
                        trailing = {
                            if (draft.trigger == trigger) Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_remote_selected), tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
            when (draft.trigger) {
                RuleTrigger.AFTER_EDIT, RuleTrigger.BEFORE_FINISH -> {
                    if (draft.trigger == RuleTrigger.AFTER_EDIT) {
                        OutlinedTextField(
                            draft.pattern, { v -> onChange { copy(pattern = v) } }, Modifier.fillMaxWidth().padding(top = Spacing.sm),
                            label = { Text(stringResource(R.string.chat_rules_files)) },
                            placeholder = { Text(stringResource(R.string.chat_rules_files_example)) },
                            supportingText = { Text(stringResource(R.string.chat_rules_files_help)) },
                            singleLine = true,
                        )
                    }
                    SectionLabel(stringResource(R.string.chat_rules_command))
                    LocusCard {
                        TextArea(draft.command, { v -> onChange { copy(command = v) } }, mono = true, placeholder = stringResource(R.string.chat_rules_command_hint))
                    }
                    SupportingText(
                        stringResource(if (draft.trigger == RuleTrigger.AFTER_EDIT) R.string.chat_rules_command_help_edit else R.string.chat_rules_command_help_finish),
                        style = LocusType.supporting,
                    )
                    OutlinedTextField(
                        draft.timeout, { v -> onChange { copy(timeout = v.filter(Char::isDigit).take(3)) } }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.chat_rules_timeout)) },
                        supportingText = { Text(stringResource(R.string.chat_rules_timeout_help, ProjectRule.MIN_TIMEOUT, ProjectRule.MAX_TIMEOUT)) },
                        isError = problem == RuleProblem.TIMEOUT,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    if (!linuxReady) SupportingText(stringResource(R.string.chat_rules_no_linux_short), style = LocusType.supporting)
                }
                RuleTrigger.BEFORE_COMMAND -> {
                    OutlinedTextField(
                        draft.pattern, { v -> onChange { copy(pattern = v) } }, Modifier.fillMaxWidth().padding(top = Spacing.sm),
                        label = { Text(stringResource(R.string.chat_rules_pattern)) },
                        placeholder = { Text(stringResource(R.string.chat_rules_pattern_example)) },
                        supportingText = { Text(stringResource(R.string.chat_rules_pattern_help)) },
                        singleLine = true,
                    )
                    SectionLabel(stringResource(R.string.chat_rules_message))
                    LocusCard {
                        TextArea(draft.message, { v -> onChange { copy(message = v) } }, placeholder = stringResource(R.string.chat_rules_message_hint))
                    }
                    SupportingText(stringResource(R.string.chat_rules_message_help), style = LocusType.supporting)
                }
            }
            problem?.let { p ->
                SupportingText(
                    stringResource(
                        when (p) {
                            RuleProblem.NAME -> R.string.chat_rules_problem_name
                            RuleProblem.COMMAND -> R.string.chat_rules_problem_command
                            RuleProblem.MESSAGE -> R.string.chat_rules_problem_message
                            RuleProblem.TIMEOUT -> R.string.chat_rules_problem_timeout
                            RuleProblem.TOO_LONG -> R.string.chat_rules_problem_too_long
                        },
                    ),
                    style = LocusType.supporting,
                )
            }
            if (draft.id != null) {
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.chat_rules_delete), color = Locus.colors.critical) }
            }
        }
    }
    if (confirmDelete && draft.id != null) {
        ConfirmSheet(
            title = stringResource(R.string.chat_rules_delete_title, draft.name),
            confirmLabel = stringResource(R.string.chat_rules_delete_confirm),
            tone = Tone.Critical,
            onConfirm = { confirmDelete = false; onDelete(draft.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}
