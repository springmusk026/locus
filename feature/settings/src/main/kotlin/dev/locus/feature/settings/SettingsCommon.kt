package dev.locus.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.locus.core.designsystem.LocusTopBar
import dev.locus.core.designsystem.Spacing

/** The frame every Settings page shares: a top bar with back, and a padded list. */
@Composable
internal fun SettingsPageScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { LocusTopBar(title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding(), bottom = Spacing.xxl),
            content = content,
        )
    }
}

/** Summary parts side by side, "A · B". The separator is a resource so a language can change it. */
@Composable
internal fun summaryJoin(first: String, second: String): String = stringResource(R.string.settings_summary_join, first, second)

/** [summaryJoin] for any number of parts. */
@Composable
internal fun summaryJoin(parts: List<String>): String = parts.reduce { acc, part -> summaryJoin(acc, part) }

internal val SectionPadding = Modifier.padding(start = 0.dp)
internal val NotePadding = Modifier.padding(top = Spacing.sm, start = 0.dp)
