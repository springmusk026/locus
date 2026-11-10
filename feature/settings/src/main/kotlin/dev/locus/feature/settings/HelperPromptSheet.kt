package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.TextArea
import dev.locus.core.designsystem.TextAreaSize
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.labelRes
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.HelperPrompts

internal val HelperPrompt.icon: ImageVector
    get() = when (this) {
        HelperPrompt.TITLE -> LocusIcons.Chats
        HelperPrompt.SUGGESTIONS -> LocusIcons.Send
        HelperPrompt.COMPACTION -> LocusIcons.Note
    }

@Composable
private fun HelperPrompt.explanation(): String = stringResource(
    when (this) {
        HelperPrompt.TITLE -> R.string.settings_helper_prompt_explain_title
        HelperPrompt.SUGGESTIONS -> R.string.settings_helper_prompt_explain_suggestions
        HelperPrompt.COMPACTION -> R.string.settings_helper_prompt_explain_compaction
    },
)

/**
 * Edits one helper prompt: a multiline editor, the placeholders it understands, and Reset, which
 * puts the default back in the editor. Save is off while a check fails, so a prompt without its
 * required placeholders is never stored.
 */
@Composable
internal fun HelperPromptSheet(
    prompt: HelperPrompt,
    saved: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val default = HelperPrompts.default(prompt)
    val current = saved ?: default
    var text by rememberSaveable(prompt, saved) { mutableStateOf(current) }
    val check = HelperPrompts.check(prompt, text)
    LocusSheet(
        title = stringResource(prompt.labelRes),
        subtitle = prompt.explanation(),
        onDismiss = onDismiss,
        action = stringResource(R.string.settings_helper_prompt_save),
        actionEnabled = check.ok && text.trim() != current.trim(),
        onAction = { onSave(HelperPrompts.normalize(prompt, text)) },
    ) {
        LocusCard {
            TextArea(
                value = text,
                onValueChange = { text = it },
                size = TextAreaSize.Tall,
                mono = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_helper_prompt_length, text.length, HelperPrompts.MAX_LENGTH),
                    Modifier.weight(1f).padding(start = Spacing.xs),
                    style = LocusType.mono,
                    color = if (text.length > HelperPrompts.MAX_LENGTH) Tone.Critical.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { text = default }, enabled = text != default) { Text(stringResource(R.string.settings_helper_prompt_reset)) }
            }
        }
        check.errors.forEach { Text(it, style = LocusType.rowSupporting, color = Tone.Critical.color()) }
        check.warnings.forEach { Text(it, style = LocusType.rowSupporting, color = Tone.Caution.color()) }

        SectionLabel(stringResource(R.string.settings_helper_prompt_placeholders))
        val placeholders = HelperPrompts.placeholders(prompt)
        if (placeholders.isEmpty()) {
            Hint(stringResource(R.string.settings_helper_prompt_placeholders_none))
        } else {
            placeholders.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        if (p.required) stringResource(R.string.settings_helper_prompt_required, p.token) else p.token,
                        style = LocusType.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Hint(p.description)
                }
            }
            Hint(stringResource(R.string.settings_helper_prompt_placeholders_note))
        }
        Hint(stringResource(R.string.settings_helper_prompt_reset_note))
    }
}

@Composable
private fun Hint(text: String) = SupportingText(text)
