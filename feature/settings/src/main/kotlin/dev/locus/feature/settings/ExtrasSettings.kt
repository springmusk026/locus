package dev.locus.feature.settings

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.ProviderBadge
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.ProviderConfig
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SwitchRow

/** The helper model and the small chat extras: titles, follow-ups, lessons after tasks, and their prompts. */
@Composable
internal fun ExtrasSettings(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetHelper: (String?, String, Boolean) -> Unit,
    onSetAutoTitle: (Boolean) -> Unit,
    onSetSuggest: (Boolean) -> Unit,
    onSetReflect: (Boolean) -> Unit,
    onSetPrompt: (HelperPrompt, String?) -> Unit,
    onListModels: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
) {
    var editingHelper by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<HelperPrompt?>(null) }
    val helper = state.providers.firstOrNull { it.config.id == state.helperProviderId }?.config

    SettingsPageScaffold("Helper model & extras", onBack) {
        item {
            CardGroup {
                ListRow(
                    title = "Helper model",
                    subtitle = helper?.let { "${it.name} · ${dev.locus.core.model.ModelNames.short(state.helperModel.ifBlank { it.model })}" }
                        ?: if (state.helperAutomatic) "Automatic · $AUTOMATIC_SHORT" else "Same as each chat",
                    subtitleMaxLines = 2,
                    onClick = { editingHelper = true },
                    leading = {
                        when {
                            helper != null -> ProviderBadge(helper.name, helper.baseUrl, false, size = IconSize.tile)
                            state.helperAutomatic -> IconTile(LocusIcons.Sparkle)
                            else -> IconTile(LocusIcons.Chats)
                        }
                    },
                    trailing = { RowChevron() },
                )
            }
        }
        item {
            SectionNote(
                "Titles, suggestions and the researcher and explorer helpers run on the helper model; a cheaper one saves cost. " +
                    "The safety reviewer uses it only when you choose one here.",
                NotePadding,
            )
        }
        item { SectionLabel("Chat extras", SectionPadding) }
        item {
            CardGroup {
                SwitchRow(
                    title = "Name chats automatically",
                    checked = state.autoTitle,
                    onCheckedChange = onSetAutoTitle,
                    subtitle = "Gives each chat a short title after the first reply",
                    icon = LocusIcons.Chats,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "Suggest follow-ups",
                    checked = state.suggestFollowUps,
                    onCheckedChange = onSetSuggest,
                    subtitle = "Shows a few things you might say next under each reply",
                    icon = LocusIcons.Send,
                )
                GroupDivider(inset = Insets.iconRow)
                SwitchRow(
                    title = "Learn after tasks",
                    checked = state.reflectAfterTasks,
                    onCheckedChange = onSetReflect,
                    subtitle = "After a longer task, Locus may save a short lesson to memory",
                    icon = LocusIcons.Note,
                )
            }
        }
        item { SectionLabel("Prompts", SectionPadding) }
        item {
            CardGroup {
                HelperPrompt.entries.forEachIndexed { i, prompt ->
                    if (i > 0) GroupDivider(inset = Insets.iconRow)
                    ListRow(
                        title = prompt.label,
                        subtitle = if (prompt in state.helperPrompts) "Edited" else "Default",
                        onClick = { editingPrompt = prompt },
                        leading = { IconTile(prompt.icon) },
                        trailing = { RowChevron() },
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
            selectedAutomatic = state.helperAutomatic,
            onListModels = onListModels,
            onSave = { id, model, automatic -> onSetHelper(id, model, automatic); editingHelper = false },
            onDismiss = { editingHelper = false },
        )
    }
}

/** What "Automatic" picks, in a few words for the row and the sheet. */
private const val AUTOMATIC_SHORT = "a cheaper model from each chat's provider"

/** Picks the helper model: Automatic, the same as each chat, or a provider and, optionally, a cheaper model on it. */
@Composable
private fun HelperSheet(
    providers: List<ProviderConfig>,
    selectedId: String?,
    selectedModel: String,
    selectedAutomatic: Boolean,
    onListModels: (ProviderConfig, String?, (Result<List<RemoteModel>>) -> Unit) -> Unit,
    onSave: (String?, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var id by remember { mutableStateOf(selectedId) }
    var model by remember { mutableStateOf(selectedModel) }
    // Only read while no provider is chosen.
    var automatic by remember { mutableStateOf(selectedAutomatic) }
    LocusSheet(
        title = "Helper model",
        subtitle = "Writes chat titles and follow-up suggestions. Researcher and explorer helpers run on it too, and the safety reviewer when you choose a model here.",
        onDismiss = onDismiss,
        action = "Save",
        onAction = { onSave(id, if (id == null) "" else model, id == null && automatic) },
    ) {
        CardGroup {
            HelperOption(
                "Automatic",
                "A cheaper model of the chat's provider (Haiku for Claude), when one is known",
                selected = id == null && automatic,
                onClick = { id = null; model = ""; automatic = true },
            ) { IconTile(LocusIcons.Sparkle) }
            GroupDivider(inset = Insets.iconRow)
            HelperOption("Same as each chat", "Side jobs and helpers use the chat's own model", selected = id == null && !automatic, onClick = { id = null; model = ""; automatic = false }) {
                IconTile(LocusIcons.Chats)
            }
            providers.forEach { p ->
                GroupDivider(inset = Insets.iconRow)
                HelperOption(p.name, dev.locus.core.model.ModelNames.short(p.model), selected = id == p.id, onClick = { if (id != p.id) { id = p.id; model = "" } }) {
                    ProviderBadge(p.name, p.baseUrl, false, size = IconSize.tile)
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
            SupportingText(
                "Leave empty to use ${provider.model}. A small, fast model (a mini, flash or haiku tier) is plenty here.",
            )
        }
    }
}

@Composable
private fun HelperOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit) {
    ListRow(
        title = title,
        subtitle = subtitle,
        titleMaxLines = 2,
        subtitleMaxLines = 6,
        onClick = onClick,
        leading = leading,
        trailing = { RadioButton(selected = selected, onClick = onClick) },
    )
}
