package dev.locus.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.locus.core.model.TaskBudget
import java.util.Locale

/** Preset sizes the budget editor offers; a value set elsewhere is shown beside them. */
object TaskBudgetPresets {
    val tokens: List<Long> = listOf(100_000, 200_000, 500_000, 1_000_000, 2_000_000)
    val dollars: List<Double> = listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0)
    val minutes: List<Int> = listOf(5, 15, 30, 60, 120)

    fun tokens(n: Long): String = if (n >= 1_000_000 && n % 1_000_000 == 0L) "${n / 1_000_000}M" else if (n >= 1_000) "${n / 1_000}k" else "$n"

    fun dollars(d: Double): String = if (d < 1) String.format(Locale.US, "$%.2f", d) else if (d % 1.0 == 0.0) "$${d.toLong()}" else String.format(Locale.US, "$%.2f", d)

    fun minutes(m: Int): String = if (m >= 60 && m % 60 == 0) "${m / 60} h" else "$m min"

    /** "Off", or "200k tokens · $2 · 30 min". */
    fun summary(b: TaskBudget): String = if (b.isOff) {
        "Off"
    } else {
        listOfNotNull(b.tokens?.let { "${tokens(it)} tokens" }, b.costUsd?.let(::dollars), b.minutes?.let(::minutes)).joinToString(" · ")
    }
}

/**
 * Edits a [TaskBudget]: tokens, estimated cost and time, each Off or a preset. The cost row is
 * disabled with [costNote] shown under it when prices for the model aren't known, with a link to the
 * Settings page it names.
 */
@Composable
fun TaskBudgetEditor(budget: TaskBudget, onChange: (TaskBudget) -> Unit, costNote: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BudgetRow(
            "Tokens",
            (TaskBudgetPresets.tokens + listOfNotNull(budget.tokens)).distinct().sorted(),
            budget.tokens,
            TaskBudgetPresets::tokens,
        ) { onChange(budget.copy(tokens = it)) }
        BudgetRow(
            "Estimated cost",
            (TaskBudgetPresets.dollars + listOfNotNull(budget.costUsd)).distinct().sorted(),
            budget.costUsd,
            TaskBudgetPresets::dollars,
            enabled = costNote == null,
        ) { onChange(budget.copy(costUsd = it)) }
        if (costNote != null) {
            // "… turn on model details (models.dev) in Settings → Models.": the sentence leads into a link to
            // that page, as SettingsLink's hint, instead of naming the path and then repeating it as a link.
            val destination = dev.locus.core.model.SettingsDestination.inText(costNote)
            val lead = costNote.substringBefore(" in Settings →", missingDelimiterValue = "")
            if (destination != null && lead.isNotEmpty()) {
                SettingsLink(destination, hint = "$lead in")
            } else {
                Text(costNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsLinkIn(costNote)
            }
        }
        BudgetRow(
            "Time",
            (TaskBudgetPresets.minutes + listOfNotNull(budget.minutes)).distinct().sorted(),
            budget.minutes,
            TaskBudgetPresets::minutes,
        ) { onChange(budget.copy(minutes = it)) }
    }
}

@Composable
private fun <T : Any> BudgetRow(title: String, options: List<T>, selected: T?, label: (T) -> String, enabled: Boolean = true, onPick: (T?) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The options wrap onto more lines rather than running off the card's edge.
        ChipFlow {
            LocusChip("Off", variant = ChipVariant.Filter, selected = selected == null, enabled = enabled || selected != null, onClick = { onPick(null) })
            options.forEach { option ->
                // A row that can't be used shows no choice as made: a saved value doesn't apply while it's off.
                LocusChip(label(option), variant = ChipVariant.Filter, selected = enabled && option == selected, enabled = enabled, onClick = { onPick(option) })
            }
        }
    }
}
