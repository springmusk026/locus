package dev.locus.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * A question that needs a yes or no, on the one sheet ([LocusSheet]) instead of an AlertDialog:
 * "Clear browsing data?", "Send anyway?", "This page says …".
 *
 * [message] says what will happen. [tone] [Tone.Critical] makes the confirm button red, for actions
 * that delete or can't be undone. [content] holds anything the answer needs (a text field for a
 * page's prompt()). Swiping the sheet away is the same as [dismissLabel].
 *
 * While [busy] the confirm button shows a spinner and can't be pressed again; [error] shows above the
 * buttons (and is announced) when the action failed, so the user can try again or cancel.
 */
@Composable
fun ConfirmSheet(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    tone: Tone = Tone.Brand,
    dismissLabel: String = "Cancel",
    confirmEnabled: Boolean = true,
    busy: Boolean = false,
    error: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    LocusSheet(title = title, onDismiss = onDismiss, modifier = modifier, subtitle = message) {
        ConfirmSheetBody(
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            tone = tone,
            dismissLabel = dismissLabel,
            confirmEnabled = confirmEnabled,
            busy = busy,
            error = error,
            content = content,
        )
    }
}

/** The part of [ConfirmSheet] under the title: the content, the error, and the two buttons. */
@Composable
internal fun ConfirmSheetBody(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tone: Tone,
    dismissLabel: String,
    confirmEnabled: Boolean,
    busy: Boolean,
    error: String?,
    content: (@Composable ColumnScope.() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        content?.invoke(this)
        if (error != null) {
            Text(
                error,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = LocusType.rowSupporting,
                color = Tone.Critical.onContainer(),
            )
        }
        // Side by side at equal width while both labels fit; stacked full width otherwise (a long
        // label, a large font), with the confirm button on top, so no label wraps inside its pill.
        EqualOrStacked(Modifier.fillMaxWidth().padding(top = Spacing.sm), gap = Spacing.sm) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                Text(dismissLabel, style = LocusType.label)
            }
            val colors = if (tone == Tone.Critical) {
                ButtonDefaults.buttonColors(containerColor = Locus.colors.critical, contentColor = MaterialTheme.colorScheme.surface)
            } else {
                ButtonDefaults.buttonColors()
            }
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled && !busy,
                colors = colors,
                modifier = Modifier.heightIn(min = Sizes.touchTarget),
            ) {
                if (busy) InlineSpinner(Modifier.padding(end = Spacing.sm), size = IconSize.small)
                Text(confirmLabel, style = LocusType.label, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Two buttons (dismiss, then confirm) at equal width side by side when both labels fit in half the
 * width; otherwise stacked full width, confirm on top.
 */
@Composable
private fun EqualOrStacked(modifier: Modifier, gap: Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val half = (width - gapPx) / 2
        val fits = measurables.all { it.maxIntrinsicWidth(constraints.maxHeight) <= half }
        if (fits) {
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = half, maxWidth = half, minHeight = 0)) }
            val h = placeables.maxOf { it.height }
            layout(width, h) { placeables.forEachIndexed { i, p -> p.place(i * (half + gapPx), (h - p.height) / 2) } }
        } else {
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = width, minHeight = 0)) }.reversed()
            val h = placeables.sumOf { it.height } + gapPx * (placeables.size - 1)
            layout(width, h) {
                var y = 0
                placeables.forEach { p -> p.place(0, y); y += p.height + gapPx }
            }
        }
    }
}
