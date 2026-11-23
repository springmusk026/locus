package dev.locus.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.content.res.Resources
import dev.locus.core.data.chatimport.ExportProblem
import dev.locus.core.data.chatimport.ImportTexts
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.CardGroup
import dev.locus.core.designsystem.ChipFlow
import dev.locus.core.designsystem.ChipVariant
import dev.locus.core.designsystem.GroupDivider
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Insets
import dev.locus.core.designsystem.ListRow
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Meter
import dev.locus.core.designsystem.RowChevron
import dev.locus.core.designsystem.SectionLabel
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.Segmented
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.meterFraction
import java.text.NumberFormat

/**
 * Settings → Backup & restore → Import chats (docs/design/import-chats.md): the row that opens the
 * system's file picker, the outcome of the last import, and the sheet that reads, previews and imports.
 */
internal fun LazyListScope.importChatsSection(
    state: ImportChatsUiState,
    enabled: Boolean,
    onImport: () -> Unit,
    onDismissOutcome: () -> Unit,
    /** Opens Library → Memory's "Suggest memories from imported chats"; offered once chats were written. Never runs by itself. */
    onSuggestMemories: () -> Unit = {},
) {
    item { SectionLabel(stringResource(R.string.settings_import_section), SectionPadding) }
    item {
        CardGroup {
            ListRow(
                title = stringResource(R.string.settings_import_title),
                subtitle = stringResource(R.string.settings_import_subtitle),
                titleMaxLines = 3,
                subtitleMaxLines = 4,
                onClick = onImport,
                enabled = enabled && state.step == ImportStep.Idle,
                leading = { IconTile(LocusIcons.Chats) },
                trailing = { RowChevron() },
            )
        }
    }
    state.outcome?.let { outcome ->
        item {
            Banner(
                text = outcomeText(LocalResources.current, outcome),
                tone = if (outcome.cancelled || outcome.result.failed > 0) Tone.Caution else Tone.Live,
                modifier = NotePadding,
                // Two actions after an import: below the text, so it keeps the width.
                actionsBelow = outcome.result.imported + outcome.result.updated > 0,
                actions = {
                    if (outcome.result.imported + outcome.result.updated > 0) {
                        TextButton(onClick = onSuggestMemories) { Text(stringResource(R.string.settings_import_suggest_memories)) }
                    }
                    TextButton(onClick = onDismissOutcome) { Text(stringResource(R.string.settings_import_ok)) }
                },
            )
        }
    }
    item { SectionNote(stringResource(R.string.settings_import_note), NotePadding) }
}

/** "Imported 1,240 chats · 3 updated", or how many were in before Cancel. */
internal fun outcomeText(res: Resources, outcome: ImportOutcome): String {
    val r = outcome.result
    val format = NumberFormat.getIntegerInstance(res.configuration.locales[0])
    fun n(id: Int, count: Int) = res.getQuantityString(id, count, format.format(count))
    if (outcome.cancelled) return n(R.plurals.settings_import_outcome_cancelled, r.imported)
    if (r.imported == 0 && r.updated == 0 && r.failed == 0) return res.getString(R.string.settings_import_outcome_nothing)
    return listOfNotNull(
        n(R.plurals.settings_import_outcome_imported, r.imported),
        r.updated.takeIf { it > 0 }?.let { n(R.plurals.settings_import_outcome_updated, it) },
        r.failed.takeIf { it > 0 }?.let { n(R.plurals.settings_import_outcome_failed, it) },
    ).let { summaryJoin(res, it) }
}

/** The words an import writes into chats, in the app's language. */
internal fun importTexts(res: Resources): ImportTexts = ImportTexts(
    untitled = res.getString(R.string.settings_import_untitled),
    attached = { name -> res.getString(R.string.settings_import_attached, name) },
    notImported = { names, images ->
        val parts = names + listOfNotNull(images.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.settings_import_images, it, it) })
        res.getString(R.string.settings_import_not_imported_line, parts.joinToString(", "))
    },
)

/** The import flow in one sheet: reading, a problem, the preview, importing. */
@Composable
internal fun ImportChatsSheet(
    step: ImportStep,
    onChoose: (Set<Int>?) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onChooseAnother: () -> Unit,
) {
    when (step) {
        ImportStep.Idle -> Unit
        is ImportStep.Reading -> LocusSheet(
            title = stringResource(R.string.settings_import_reading_title),
            subtitle = stringResource(R.string.settings_import_reading_subtitle),
            onDismiss = onCancel,
        ) {
            ProgressBlock(
                fraction = step.progress.totalBytes?.let { meterFraction(step.progress.bytes, it) },
                text = pluralStringResource(R.plurals.settings_import_reading_found, step.progress.chats, count(step.progress.chats)),
                onCancel = onCancel,
            )
        }
        is ImportStep.Failed -> LocusSheet(
            title = stringResource(problemTitle(step.problem)),
            onDismiss = onClose,
            action = stringResource(R.string.settings_import_choose_another),
            onAction = onChooseAnother,
        ) {
            Banner(text = stringResource(problemText(step.problem)), tone = Tone.Critical)
        }
        is ImportStep.Preview -> PreviewSheet(step, onChoose, onImport, onClose)
        is ImportStep.Importing -> LocusSheet(
            title = stringResource(R.string.settings_import_importing_title),
            subtitle = stringResource(R.string.settings_import_importing_subtitle),
            onDismiss = onCancel,
        ) {
            ProgressBlock(
                fraction = meterFraction(step.progress.chats.toLong(), step.total.toLong()),
                text = stringResource(R.string.settings_import_importing_amount, count(step.progress.chats), count(step.total)),
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun ProgressBlock(fraction: Float?, text: String, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Meter(fraction, tone = Tone.Live, contentDescription = text)
        Text(text, style = LocusType.rowSupporting)
        TextButton(onClick = onCancel) { Text(stringResource(R.string.settings_import_cancel)) }
    }
}

@Composable
private fun PreviewSheet(step: ImportStep.Preview, onChoose: (Set<Int>?) -> Unit, onImport: () -> Unit, onClose: () -> Unit) {
    val summary = step.summary
    val plan = step.plan
    LocusSheet(title = stringResource(R.string.settings_import_preview_title), onDismiss = onClose) {
        if (summary.truncated) Banner(text = stringResource(R.string.settings_import_truncated), tone = Tone.Caution)
        CardGroup {
            summary.sources.forEachIndexed { i, source ->
                if (i > 0) GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = pluralStringResource(R.plurals.settings_import_found, source.chats, count(source.chats), source.name),
                    subtitle = years(source.firstYear, source.lastYear),
                    titleMaxLines = 3,
                    leading = { IconTile(LocusIcons.Chats) },
                )
            }
            if (summary.sources.isEmpty()) {
                ListRow(title = stringResource(R.string.settings_import_none_found), titleMaxLines = 3, leading = { IconTile(LocusIcons.Chats) })
            }
            val skipped = summary.unreadable
            if (skipped > 0) {
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = pluralStringResource(R.plurals.settings_import_skipped, skipped, count(skipped)),
                    subtitle = stringResource(R.string.settings_import_skipped_subtitle),
                    titleMaxLines = 3,
                    subtitleMaxLines = 4,
                    leading = { IconTile(LocusIcons.Skip) },
                )
            }
            if (summary.empty > 0) {
                GroupDivider(inset = Insets.iconRow)
                ListRow(title = pluralStringResource(R.plurals.settings_import_empty, summary.empty, count(summary.empty)), titleMaxLines = 3, leading = { IconTile(LocusIcons.Skip) })
            }
            if (plan.unchanged > 0) {
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = pluralStringResource(R.plurals.settings_import_already, plan.unchanged, count(plan.unchanged)),
                    subtitle = stringResource(R.string.settings_import_already_subtitle),
                    titleMaxLines = 3,
                    subtitleMaxLines = 4,
                    leading = { IconTile(LocusIcons.Check) },
                )
            }
            if (plan.notImported > 0) {
                GroupDivider(inset = Insets.iconRow)
                ListRow(
                    title = pluralStringResource(R.plurals.settings_import_files_left, plan.notImported, count(plan.notImported)),
                    subtitle = stringResource(R.string.settings_import_files_left_subtitle),
                    titleMaxLines = 3,
                    subtitleMaxLines = 4,
                    leading = { IconTile(LocusIcons.Image) },
                )
            }
        }
        if (summary.perYear.size > 1) {
            SectionLabel(stringResource(R.string.settings_import_which))
            Segmented(
                listOf(stringResource(R.string.settings_import_which_all), stringResource(R.string.settings_import_which_years)),
                selected = if (step.years == null) 0 else 1,
                onSelect = { i -> onChoose(if (i == 0) null else summary.perYear.map { it.first }.toSet()) },
                modifier = Modifier.fillMaxWidth(),
            )
            step.years?.let { chosen ->
                ChipFlow {
                    summary.perYear.forEach { (year, n) ->
                        LocusChip(
                            stringResource(R.string.settings_import_year_chip, year.toString(), count(n)),
                            variant = ChipVariant.Filter,
                            selected = year in chosen,
                            onClick = { onChoose(if (year in chosen) chosen - year else chosen + year) },
                        )
                    }
                }
            }
        }
        if (plan.updated > 0) {
            SupportingText(pluralStringResource(R.plurals.settings_import_updated_note, plan.updated, count(plan.updated)))
        }
        Button(
            onClick = onImport,
            enabled = plan.toWrite > 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget),
        ) {
            Text(
                if (plan.toWrite > 0) pluralStringResource(R.plurals.settings_import_action, plan.toWrite, count(plan.toWrite))
                else stringResource(R.string.settings_import_action_nothing),
            )
        }
        SupportingText(stringResource(R.string.settings_import_read_only_note))
    }
}

@Composable
private fun years(first: Int?, last: Int?): String? = when {
    first == null || last == null -> null
    first == last -> first.toString()
    else -> stringResource(R.string.settings_import_years, first.toString(), last.toString())
}

/** A count written the language's way ("1,284", "1.284"). */
@Composable
private fun count(n: Int): String = NumberFormat.getIntegerInstance(LocalResources.current.configuration.locales[0]).format(n)

private fun problemTitle(problem: ExportProblem) = when (problem) {
    ExportProblem.NotAnExport -> R.string.settings_import_problem_not_export_title
    ExportProblem.NoConversations -> R.string.settings_import_problem_no_chats_title
    ExportProblem.Unreadable -> R.string.settings_import_problem_unreadable_title
}

private fun problemText(problem: ExportProblem) = when (problem) {
    ExportProblem.NotAnExport -> R.string.settings_import_problem_not_export_text
    ExportProblem.NoConversations -> R.string.settings_import_problem_no_chats_text
    ExportProblem.Unreadable -> R.string.settings_import_problem_unreadable_text
}
