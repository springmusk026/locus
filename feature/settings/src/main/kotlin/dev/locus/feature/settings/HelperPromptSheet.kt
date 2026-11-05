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
import dev.locus.core.model.HelperPrompt
import dev.locus.core.model.HelperPrompts

internal val HelperPrompt.icon: ImageVector
    get() = when (this) {
        HelperPrompt.TITLE -> LocusIcons.Chats
        HelperPrompt.SUGGESTIONS -> LocusIcons.Send
        HelperPrompt.COMPACTION -> LocusIcons.Note
    }

private val HelperPrompt.explanation: String
    get() = when (this) {
        HelperPrompt.TITLE -> "Sent to the helper model after a chat's first reply. The first line of its answer becomes the chat's title."
        HelperPrompt.SUGGESTIONS -> "Sent to the helper model after each reply. Each line of its answer becomes a suggestion; up to three are shown."
        HelperPrompt.COMPACTION -> "Sent to the chat's own model when a long chat is summarised, as the last message after the chat itself. " +
            "The summary replaces the earlier messages in what the model sees."
    }

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
        title = prompt.label,
        subtitle = prompt.explanation,
        onDismiss = onDismiss,
        action = "Save",
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
                    "${text.length} / ${HelperPrompts.MAX_LENGTH}",
                    Modifier.weight(1f).padding(start = Spacing.xs),
                    style = LocusType.mono,
                    color = if (text.length > HelperPrompts.MAX_LENGTH) Tone.Critical.color() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { text = default }, enabled = text != default) { Text("Reset to default") }
            }
        }
        check.errors.forEach { Text(it, style = LocusType.rowSupporting, color = Tone.Critical.color()) }
        check.warnings.forEach { Text(it, style = LocusType.rowSupporting, color = Tone.Caution.color()) }

        SectionLabel("Placeholders")
        val placeholders = HelperPrompts.placeholders(prompt)
        if (placeholders.isEmpty()) {
            Hint("None: the chat itself comes right before this prompt. Locus always starts the message with a line saying it's an automatic request and the model must not call tools.")
        } else {
            placeholders.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        p.token + if (p.required) "  ·  required" else "",
                        style = LocusType.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Hint(p.description)
                }
            }
            Hint("Replaced when the prompt is sent. Saving needs every required placeholder.")
        }
        Hint("Reset puts the built-in prompt back in the editor; Save to apply it.")
    }
}

@Composable
private fun Hint(text: String) = SupportingText(text)
