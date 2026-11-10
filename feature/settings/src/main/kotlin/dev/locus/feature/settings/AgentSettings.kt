package dev.locus.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.SwitchRow
import dev.locus.core.designsystem.TextArea
import dev.locus.core.model.HelperTimeLimit

/** Who the agent is and how it should work with you. */
@Composable
internal fun AgentSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSaveInstructions: (String) -> Unit,
    onOpenAgentSetup: () -> Unit,
    onSetTaskBudget: (dev.locus.core.model.TaskBudget) -> Unit = {},
    onSetHelperReports: (Boolean) -> Unit = {},
    onSetHelperTimeout: (Int) -> Unit = {},
) {
    SettingsPageScaffold(stringResource(R.string.settings_agent_title), onBack) {
        item { SectionLabel(stringResource(R.string.settings_agent_identity_section), SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = stringResource(R.string.settings_agent_identity_title),
                    subtitle = stringResource(R.string.settings_agent_identity_subtitle),
                    subtitleMaxLines = 2,
                    onClick = onOpenAgentSetup,
                    leading = { IconTile(LocusIcons.Edit) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_agent_instructions_section), SectionPadding) }
        item { InstructionsEditor(state.customInstructions, onSaveInstructions) }
        item { SectionNote(stringResource(R.string.settings_agent_instructions_note), NotePadding) }
        item { SectionLabel(stringResource(R.string.settings_agent_budget_section), SectionPadding) }
        item {
            LocusCard {
                dev.locus.core.designsystem.TaskBudgetEditor(
                    state.taskBudget,
                    onChange = onSetTaskBudget,
                    costNote = stringResource(R.string.settings_agent_cost_note).takeIf { !state.modelCatalog },
                    costNoteDestination = dev.locus.core.model.SettingsDestination.Models,
                )
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_agent_budget_note),
                NotePadding,
            )
        }
        item { SectionLabel(stringResource(R.string.settings_agent_helpers_section), SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_agent_helper_reports_title),
                    checked = state.subagentReportsContinue,
                    onCheckedChange = onSetHelperReports,
                    subtitle = stringResource(R.string.settings_agent_helper_reports_subtitle),
                    icon = LocusIcons.Branch,
                )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_agent_time_limit_section), SectionPadding) }
        item {
            val choices = HelperTimeLimit.CHOICES
            LocusCard {
                Segmented(
                    options = choices.map(HelperTimeLimit::label),
                    selected = choices.indexOf(HelperTimeLimit.normalized(state.helperTimeoutMinutes)).coerceAtLeast(0),
                    onSelect = { onSetHelperTimeout(choices[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SectionNote(
                stringResource(R.string.settings_agent_time_limit_note),
                NotePadding,
            )
        }
    }
}

@Composable
private fun InstructionsEditor(saved: String, onSave: (String) -> Unit) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    LocusCard {
        TextArea(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.settings_agent_instructions_placeholder),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The note under the card says what this is; here only the button.
            Box(Modifier.weight(1f))
            TextButton(onClick = { onSave(text) }, enabled = text != saved) { Text(stringResource(R.string.settings_agent_instructions_save)) }
        }
    }
}
