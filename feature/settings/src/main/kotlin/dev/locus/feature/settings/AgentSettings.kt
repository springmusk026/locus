package dev.locus.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.RowChevron

/** Who the agent is and how it should work with you. */
@Composable
internal fun AgentSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSaveInstructions: (String) -> Unit,
    onOpenAgentSetup: () -> Unit,
    onSetTaskBudget: (dev.locus.core.model.TaskBudget) -> Unit = {},
    onSetHelperReports: (Boolean) -> Unit = {},
) {
    SettingsPageScaffold("Agent", onBack) {
        item { SectionLabel("Identity", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Agent identity",
                    subtitle = "Name and personality, from setup",
                    onClick = onOpenAgentSetup,
                    leading = { IconTile(LocusIcons.Sparkle) },
                    trailing = { RowChevron() },
                )
            }
        }
        item { SectionLabel("Custom instructions", SectionPadding) }
        item { InstructionsEditor(state.customInstructions, onSaveInstructions) }
        item { SectionNote("Added to every new chat. Keep it short; Locus also learns from memory and skills.", NotePadding) }
        item { SectionLabel("Task budget", SectionPadding) }
        item {
            LocusCard {
                dev.locus.core.designsystem.TaskBudgetEditor(
                    state.taskBudget,
                    onChange = onSetTaskBudget,
                    costNote = "Cost needs model prices: turn on model details (models.dev) in Settings → Models.".takeIf { !state.modelCatalog },
                )
            }
        }
        item {
            SectionNote(
                "The default for each task: one message and the helpers it starts. Off by default. When a task reaches a limit, Locus pauses " +
                    "between steps (never in the middle of an action) and asks whether to continue for one more of the same. Tokens are input " +
                    "plus output, with cached input counted at a tenth. Cost is an estimate from models.dev prices and applies only to models " +
                    "whose prices are listed. Time doesn't count while Locus waits for you. A chat can set its own in the composer's options.",
                NotePadding,
            )
        }
        item { SectionLabel("Helpers", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    "Continue when a helper reports back",
                    "When a background helper finishes after the reply, Locus picks the chat up again with its report.",
                    LocusIcons.Sparkle,
                    state.subagentReportsContinue,
                    onSetHelperReports,
                )
            }
        }
    }
}

@Composable
private fun InstructionsEditor(saved: String, onSave: (String) -> Unit) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    LocusCard {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text("e.g. Reply briefly. Keep notes in notes/. I work in UTC+5:45.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The note under the card says what this is; here only the button.
            Box(Modifier.weight(1f))
            TextButton(onClick = { onSave(text) }, enabled = text != saved) { Text("Save") }
        }
    }
}
