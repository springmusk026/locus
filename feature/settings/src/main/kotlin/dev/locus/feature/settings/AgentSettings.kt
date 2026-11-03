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
                    title = "Continue when a helper reports back",
                    checked = state.subagentReportsContinue,
                    onCheckedChange = onSetHelperReports,
                    subtitle = "When a background helper finishes after the reply, Locus picks the chat up again with its report.",
                    icon = LocusIcons.Sparkle,
                )
            }
        }
        item { SectionLabel("Helper time limit", SectionPadding) }
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
                "How long one helper may work, including time waiting for your approval. When it runs out, or you stop a helper, it " +
                    "hands back what it had: its last reply, its tool results and the files it changed, marked as a partial result. The " +
                    "agent can continue it from there. Applies to helpers started from now on.",
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
            placeholder = "e.g. Reply briefly. Keep notes in notes/. I work in UTC+5:45.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The note under the card says what this is; here only the button.
            Box(Modifier.weight(1f))
            TextButton(onClick = { onSave(text) }, enabled = text != saved) { Text("Save") }
        }
    }
}
