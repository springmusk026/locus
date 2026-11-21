package dev.locus.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.agent.prompt.PromptSearch
import dev.locus.core.agent.prompt.PromptTemplate
import dev.locus.core.data.repository.PromptRepository
import dev.locus.core.designsystem.ActionRow
import dev.locus.core.designsystem.AddRow
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.ExpandableRow
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.UiText
import dev.locus.core.designsystem.asString
import dev.locus.core.model.SavedPrompt
import dev.locus.core.ui.PromptEditorSheet
import dev.locus.core.ui.promptUses
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which prompt sheet Library has open. */
sealed interface LibraryPromptSheet {
    /** [prompt] null: a new one. [error] says why the last save failed. */
    data class Edit(val prompt: SavedPrompt?, val error: UiText? = null) : LibraryPromptSheet
    data class Remove(val prompt: SavedPrompt) : LibraryPromptSheet
}

/** Library's saved prompts: make, edit, reorder and remove them (docs/design/saved-prompts.md). */
@HiltViewModel
class LibraryPromptsViewModel @Inject constructor(private val repository: PromptRepository) : ViewModel() {
    val all: StateFlow<List<SavedPrompt>> = repository.all
    private val _sheet = MutableStateFlow<LibraryPromptSheet?>(null)
    val sheet: StateFlow<LibraryPromptSheet?> = _sheet.asStateFlow()
    private val _error = MutableStateFlow<UiText?>(null)

    /** Why the last remove or move failed, shown above the prompts until dismissed. */
    val error: StateFlow<UiText?> = _error.asStateFlow()

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun open(sheet: LibraryPromptSheet?) {
        _sheet.value = sheet
    }

    fun dismissError() {
        _error.value = null
    }

    fun save(prompt: SavedPrompt) {
        viewModelScope.launch {
            try {
                repository.save(prompt)
                _sheet.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sheet.value = LibraryPromptSheet.Edit(prompt, UiText.Res(R.string.library_prompts_save_failed))
            }
        }
    }

    fun remove(prompt: SavedPrompt) {
        _sheet.value = null
        guarded(prompt) { repository.delete(prompt.id) }
    }

    /** Moves [prompt] one place up ([by] -1) or down (1). The `/` list and the picker follow this order. */
    fun move(prompt: SavedPrompt, by: Int) = guarded(prompt) { repository.move(prompt.id, by) }

    private fun guarded(prompt: SavedPrompt, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = UiText.Res(R.string.library_prompts_change_failed, prompt.name)
            }
        }
    }
}

/**
 * The prompts card in Library → Skills, with its sheets. Always shown once the tab has anything, so the
 * first prompt can be made here; [sheetsOnly] shows just the sheets (from the empty tab's New prompt).
 */
@Composable
internal fun PromptsCard(viewModel: LibraryPromptsViewModel, sheetsOnly: Boolean = false) {
    val all by viewModel.all.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    when (val s = sheet) {
        is LibraryPromptSheet.Edit -> PromptEditorSheet(
            prompt = s.prompt,
            error = s.error?.asString(),
            onSave = viewModel::save,
            onDismiss = { viewModel.open(null) },
        )
        is LibraryPromptSheet.Remove -> ConfirmRemove(
            title = stringResource(R.string.library_prompts_remove_title, s.prompt.name),
            body = stringResource(R.string.library_prompts_remove_body),
            action = stringResource(R.string.library_action_remove),
            onDismiss = { viewModel.open(null) },
            onConfirm = { viewModel.remove(s.prompt) },
        )
        null -> Unit
    }
    if (sheetsOnly) return
    Column {
        error?.let { message ->
            Banner(message.asString(), Tone.Critical, Modifier.padding(bottom = Spacing.sm)) {
                TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.library_action_dismiss)) }
            }
        }
        CardGroup {
            all.forEachIndexed { i, prompt ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                PromptLibraryRow(prompt, first = i == 0, last = i == all.lastIndex, viewModel)
            }
            if (all.isNotEmpty()) GroupDivider(inset = Insets.iconRow)
            AddRow(stringResource(R.string.library_prompts_new)) { viewModel.open(LibraryPromptSheet.Edit(null)) }
        }
    }
}

@Composable
private fun PromptLibraryRow(prompt: SavedPrompt, first: Boolean, last: Boolean, viewModel: LibraryPromptsViewModel) {
    val template = remember(prompt.text) { PromptTemplate.parse(prompt.text) }
    ExpandableRow(
        title = prompt.name,
        subtitle = PromptSearch.preview(prompt.text),
        icon = LocusIcons.Prompt,
        iconDescription = stringResource(R.string.library_prompts_icon),
    ) {
        CodeSurface(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
            Text(prompt.text, Modifier.padding(Spacing.md), style = LocusType.code)
        }
        if (!template.isPlain) SupportingText(promptUses(template), Modifier.padding(top = Spacing.xs))
        ActionRow(contentPadding = androidx.compose.foundation.layout.PaddingValues()) {
            if (!first) TextButton(onClick = { viewModel.move(prompt, -1) }) { Text(stringResource(R.string.library_prompts_move_up)) }
            if (!last) TextButton(onClick = { viewModel.move(prompt, 1) }) { Text(stringResource(R.string.library_prompts_move_down)) }
            TextButton(onClick = { viewModel.open(LibraryPromptSheet.Remove(prompt)) }) { Text(stringResource(R.string.library_action_remove), color = Locus.colors.critical) }
            TextButton(onClick = { viewModel.open(LibraryPromptSheet.Edit(prompt)) }) { Text(stringResource(R.string.library_action_edit)) }
        }
    }
}
