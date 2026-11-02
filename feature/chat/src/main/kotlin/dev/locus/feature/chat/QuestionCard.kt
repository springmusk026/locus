package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.tools.QuestionAnswer
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.accentFor
import dev.locus.core.model.AgentControlTools

/**
 * A question the agent is waiting on (`ask_user`), above the composer like the approval card. One
 * choice: a tap on a chip answers. Several: checkboxes and Send. "Other…" opens a text field; typing
 * a normal message in the composer answers too.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun QuestionCard(question: UserQuestion, onAnswer: (String, QuestionAnswer) -> Unit, modifier: Modifier = Modifier) {
    var selected by remember(question.callId) { mutableStateOf(listOf<String>()) }
    var other by rememberSaveable(question.callId) { mutableStateOf(false) }
    var otherText by rememberSaveable(question.callId) { mutableStateOf("") }
    val brand = MaterialTheme.colorScheme.primary
    val hasOptions = question.options.isNotEmpty()
    fun send() = onAnswer(question.callId, QuestionAnswer(question.options.filter { it in selected }, otherText.takeIf { other }))

    LocusCard(modifier, glow = brand, outline = brand.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(AgentControlTools.ASK_USER)
            AccentTile(accent, icon, size = 32.dp)
            Column(Modifier.weight(1f).padding(start = Spacing.md)) {
                // A helper's question says which helper is asking.
                Text(question.origin?.let { "${it.label} asks" } ?: "Locus asks", style = MaterialTheme.typography.titleSmall)
                Text(question.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            !hasOptions -> Unit
            !question.multi -> FlowRow(
                Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                question.options.forEach { option ->
                    SuggestionChip(onClick = { onAnswer(question.callId, QuestionAnswer(chosen = listOf(option))) }, label = { Text(option) })
                }
                if (question.allowOther) FilterChip(selected = other, onClick = { other = !other }, label = { Text("Other…") })
            }
            else -> Column(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                question.options.forEach { option ->
                    CheckRow(option, checked = option in selected) { on -> selected = if (on) selected + option else selected - option }
                }
                if (question.allowOther) CheckRow("Other…", checked = other) { other = it }
            }
        }
        if (other) {
            OutlinedTextField(
                value = otherText,
                onValueChange = { otherText = it },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                placeholder = { Text("Your answer") },
                maxLines = 4,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (hasOptions) "Or type your answer below" else "Type your answer below",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // One choice is answered by its chip; Send is for several picks or the user's own words.
            if (question.multi || other) {
                Box(Modifier.padding(start = Spacing.sm)) {
                    Button(onClick = { send() }, enabled = selected.isNotEmpty() || (other && otherText.isNotBlank())) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = Spacing.sm), style = MaterialTheme.typography.bodyMedium)
    }
}
