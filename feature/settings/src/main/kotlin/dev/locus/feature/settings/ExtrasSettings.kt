package dev.locus.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.ProviderConfig

/** The helper model and the small extras it powers: titles, follow-ups, lessons after tasks, and their prompts. */
@Composable
internal fun ExtrasSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetHelper: (String?, String) -> Unit,
    onSetAutoTitle: (Boolean) -> Unit,
    onSetSuggest: (Boolean) -> Unit,
    onSetReflect: (Boolean) -> Unit,
    onSetHelperReports: (Boolean) -> Unit,
    onSetPrompt: (HelperPrompt, String?) -> Unit,
    onListModels: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
) {
    var editingHelper by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<HelperPrompt?>(null) }
    val helper = state.providers.firstOrNull { it.config.id == state.helperProviderId }?.config

    SettingsPageScaffold("Helper model & extras", onBack) {
        item { SectionLabel("Helper model", SectionPadding) }
        item {
            CardGroup {
                ListRow(
                    title = "Helper model",
                    subtitle = helper?.let { "${it.name} · ${state.helperModel.ifBlank { it.model }}" } ?: "Same as each chat",
                    onClick = { editingHelper = true },
                    leading = {
                        if (helper != null) ProviderBadge(helper.name, helper.baseUrl, false, size = 36.dp, model = state.helperModel.ifBlank { helper.model }) else IconTile(LocusIcons.Sparkle)
                    },
                    trailing = { Chevron() },
                )
            }
        }
        item {
            SectionNote(
                "Titles and suggestions are small extra requests. Point them at a cheap, fast model to save cost; each chat's own model is used otherwise.",
                NotePadding,
            )
        }
        item { SectionLabel("Chat extras", SectionPadding) }
        item {
            CardGroup {
                SwitchRow("Name chats automatically", "Gives each chat a short title after the first reply.", LocusIcons.Chats, state.autoTitle, onSetAutoTitle)
                GroupDivider(inset = ROW_INSET)
                SwitchRow("Suggest follow-ups", "Shows a few things you might say next under each reply.", LocusIcons.Sparkle, state.suggestFollowUps, onSetSuggest)
                GroupDivider(inset = ROW_INSET)
                SwitchRow("Learn after tasks", "After a longer task, Locus may save a short lesson to memory.", LocusIcons.Note, state.reflectAfterTasks, onSetReflect)
            }
        }
        item { SectionLabel("Prompts", SectionPadding) }
        item {
            CardGroup {
                HelperPrompt.entries.forEachIndexed { i, prompt ->
                    if (i > 0) GroupDivider(inset = ROW_INSET)
                    ListRow(
                        title = prompt.label,
                        subtitle = if (prompt in state.helperPrompts) "Edited" else "Default",
                        onClick = { editingPrompt = prompt },
                        leading = { IconTile(prompt.icon) },
                        trailing = { Chevron() },
                    )
                }
            }
        }
        item {
            SectionNote(
                "What Locus asks for titles, suggestions and summaries of long chats. " +
                    "The safety reviewer's prompt can't be edited: it decides which actions run without asking you, so a changed prompt could quietly weaken approvals.",
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

    editingPrompt?.let { prompt ->
        HelperPromptSheet(
            prompt = prompt,
            saved = state.helperPrompts[prompt],
            onSave = { text -> onSetPrompt(prompt, text); editingPrompt = null },
            onDismiss = { editingPrompt = null },
        )
    }

    if (editingHelper) {
        HelperSheet(
            providers = state.providers.map { it.config }.filter { it.format != ApiFormat.ON_DEVICE },
            selectedId = state.helperProviderId,
            selectedModel = state.helperModel,
            onListModels = onListModels,
            onSave = { id, model -> onSetHelper(id, model); editingHelper = false },
            onDismiss = { editingHelper = false },
        )
    }
}

/** Picks the helper model: a provider (or "same as each chat") and, optionally, a cheaper model on it. */
@Composable
private fun HelperSheet(
    providers: List<ProviderConfig>,
    selectedId: String?,
    selectedModel: String,
    onListModels: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
    onSave: (String?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var id by remember { mutableStateOf(selectedId) }
    var model by remember { mutableStateOf(selectedModel) }
    LocusSheet(
        title = "Helper model",
        subtitle = "Writes chat titles, follow-up suggestions and summaries of long chats.",
        onDismiss = onDismiss,
        action = "Save",
        onAction = { onSave(id, if (id == null) "" else model) },
    ) {
        CardGroup {
            HelperOption("Same as each chat", "No extra setup", selected = id == null, onClick = { id = null; model = "" }) { IconTile(LocusIcons.Chats) }
            providers.forEach { p ->
                GroupDivider(inset = ROW_INSET)
                HelperOption(p.name, p.model, selected = id == p.id, onClick = { if (id != p.id) { id = p.id; model = "" } }) {
                    ProviderBadge(p.name, p.baseUrl, false, size = 36.dp, model = p.model)
                }
            }
        }
        val provider = providers.firstOrNull { it.id == id }
        if (provider != null) {
            ModelField(
                model = model,
                onModel = { m, _ -> model = m },
                canLoad = true,
                load = { callback -> onListModels(provider, null, callback) },
                reloadKey = provider.id,
            )
            Text(
                "Leave empty to use ${provider.model}. A small, fast model (a mini, flash or haiku tier) is plenty here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HelperOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = leading,
        trailing = { RadioButton(selected = selected, onClick = onClick) },
    )
}
