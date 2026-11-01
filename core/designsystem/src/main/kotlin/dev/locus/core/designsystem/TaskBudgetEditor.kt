package dev.locus.core.designsystem

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * disabled with [costNote] shown under it when prices for the model aren't known.
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
            Text(costNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text("Off") }, enabled = enabled || selected != null)
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onPick(option) }, label = { Text(label(option)) }, enabled = enabled)
            }
        }
    }
}
