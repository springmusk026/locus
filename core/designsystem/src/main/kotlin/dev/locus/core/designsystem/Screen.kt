package dev.locus.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection

// The frame of a screen and of a section. Every top-level screen is a LocusScreen, so the top bar, the
// system-bar insets, the background and the content inset are the same everywhere; every group of
// settings or facts is a LocusSection, so its header lines up with its card.

/**
 * A top-level screen: [LocusTopBar] with [title], the app background, and the status- and
 * navigation-bar insets (the top bar takes the status bar, the content gets the rest). [content] gets
 * the space left by the bars; pass it to [screenContentPadding] for the standard inset, or use it as it
 * is for edge-to-edge content (a chat transcript, a terminal, a web page).
 *
 * The background is a Surface, so text and icons inside take `onBackground` without being told:
 * nothing on a screen can fall back to black on the dark theme.
 */
@Composable
fun LocusScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    status: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { LocusTopBar(title, onBack = onBack, subtitle = subtitle, status = status, actions = actions) },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/**
 * A [LocusScreen] whose content is one scrolling list at the standard inset ([screenContentPadding]):
 * Settings pages, Insights, Scheduled tasks.
 */
@Composable
fun LocusListScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    status: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LocusScreen(
        title = title,
        modifier = modifier,
        onBack = onBack,
        subtitle = subtitle,
        status = status,
        actions = actions,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = screenContentPadding(padding), content = content)
    }
}

/**
 * The standard inset inside a [LocusScreen]: [Spacing.lg] at the sides, [Spacing.sm] under the top bar,
 * and [Spacing.xxl] above the navigation bar, so the last card never sits under it.
 */
@Composable
fun screenContentPadding(bars: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = bars.calculateStartPadding(direction) + Spacing.lg,
        end = bars.calculateEndPadding(direction) + Spacing.lg,
        top = bars.calculateTopPadding() + Spacing.sm,
        bottom = bars.calculateBottomPadding() + Spacing.xxl,
    )
}

/**
 * A titled group on a screen or in a sheet: [SectionLabel] (with an optional action), an optional
 * [note], then [content], usually one [CardGroup] or [LocusCard]. The header's text starts where the
 * text in the card's rows does, so a section reads as one block.
 */
@Composable
fun LocusSection(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel(title, action = action, onAction = onAction)
        if (note != null) SectionNote(note)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
    }
}

/** [LocusSection] in a lazy list: the header (and [note]) as one item, then whatever [content] adds. */
fun LazyListScope.locusSection(
    title: String,
    key: Any? = null,
    note: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    item(key = key ?: "section:$title", contentType = "section-header") {
        Column(Modifier.fillMaxWidth()) {
            SectionLabel(title, action = action, onAction = onAction)
            if (note != null) SectionNote(note)
        }
    }
    content()
}
