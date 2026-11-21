package dev.locus.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.locus.core.designsystem.AccentTile
import dev.locus.core.designsystem.CodeSurface
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.accentFor
import dev.locus.core.model.ProjectRules
import dev.locus.core.model.RuleNote
import dev.locus.core.model.RuleTrigger

/** A rule run's line in the user's language (docs/design/rules.md): which rule, when, and how it went. */
internal fun ruleLine(resources: android.content.res.Resources, info: RuleNote.Info): String = when {
    info.nowPasses -> resources.getString(R.string.chat_rule_now_passes, info.name)
    info.exitCode == null && !info.passed -> resources.getString(R.string.chat_rule_timed_out, info.name)
    info.passed && info.trigger == RuleTrigger.AFTER_EDIT -> resources.getString(R.string.chat_rule_after_edit_passed, info.name)
    info.passed -> resources.getString(R.string.chat_rule_finish_passed, info.name)
    info.trigger == RuleTrigger.AFTER_EDIT -> resources.getString(R.string.chat_rule_after_edit_failed, info.name, info.exitCode ?: -1)
    info.finalRound -> resources.getString(R.string.chat_rule_finish_failed_final, info.name, info.exitCode ?: -1)
    else -> resources.getString(R.string.chat_rule_finish_failed, info.name, info.exitCode ?: -1)
}

/** The same line in English, for exports. */
internal fun ruleEnglish(info: RuleNote.Info): String = when {
    info.nowPasses -> "Rule “${info.name}” passes now"
    info.exitCode == null && !info.passed -> "Rule “${info.name}” timed out"
    info.passed && info.trigger == RuleTrigger.AFTER_EDIT -> "Rule “${info.name}” passed after an edit"
    info.passed -> "Rule “${info.name}” passed before finishing"
    info.trigger == RuleTrigger.AFTER_EDIT -> "Rule “${info.name}” failed after an edit (exit code ${info.exitCode})"
    info.finalRound -> "Rule “${info.name}” still fails (exit code ${info.exitCode}); Locus stopped sending the agent back"
    else -> "Rule “${info.name}” failed before finishing (exit code ${info.exitCode})"
}

/** A project rule's run among a turn's steps: passed or failed, and a tap shows what it printed. */
@Composable
internal fun RuleNoteRow(item: ChatItem.Note) {
    val info = item.rule ?: return
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val output = item.ruleOutput?.takeIf { it.isNotBlank() }
    val expandedText = stringResource(if (expanded) R.string.chat_changes_expanded else R.string.chat_changes_collapsed)
    Column(
        Modifier.fillMaxWidth()
            .then(if (output != null) Modifier.clickable(role = Role.Button) { expanded = !expanded }.semantics { stateDescription = expandedText } else Modifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (accent, icon) = accentFor(ProjectRules.ENTRY)
            AccentTile(accent, icon, size = IconSize.large)
            Text(
                item.shown(LocalResources.current),
                modifier = Modifier.padding(start = Spacing.sm).weight(1f),
                style = LocusType.rowSupporting,
            )
            if (info.passed) {
                Icon(LocusIcons.Check, contentDescription = stringResource(R.string.chat_rule_passed), modifier = Modifier.size(IconSize.small), tint = Locus.colors.live)
            } else {
                Text(stringResource(R.string.chat_rule_failed), style = LocusType.chip, color = Locus.colors.critical)
            }
        }
        if (output != null) {
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = Spacing.sm)) {
                    SupportingText(stringResource(R.string.chat_rule_output), style = LocusType.chip)
                    CodeSurface(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                        SelectionContainer { Text(output, Modifier.padding(Spacing.sm), style = LocusType.code) }
                    }
                }
            }
        }
    }
}
