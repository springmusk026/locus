package dev.locus.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Diff colours, from the status tokens so they mean the same thing everywhere: an added line is the
 * live green, a removed one the critical red. Lines get a soft tint and a coloured sign; the text
 * itself stays the normal ink, so long diffs stay readable in both themes.
 */
@Immutable
data class DiffColors(
    val added: Color,
    val addedBackground: Color,
    val removed: Color,
    val removedBackground: Color,
    /** Hunk headers (`@@ -1,3 +1,4 @@`) and file headers: quiet. */
    val meta: Color,
)

/** Shorthand: `Locus.diff.added`. */
val Locus.diff: DiffColors
    @Composable @ReadOnlyComposable get() {
        val status = LocalStatusColors.current
        return DiffColors(
            added = status.live,
            addedBackground = status.live.copy(alpha = 0.12f),
            removed = status.critical,
            removedBackground = status.critical.copy(alpha = 0.12f),
            meta = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

/** Kinds of line in a unified diff. */
enum class DiffLineKind { Added, Removed, Context, Hunk, Header }

/** Which kind [line] of a unified diff is. */
fun diffLineKind(line: String): DiffLineKind = when {
    line.startsWith("+++ ") || line.startsWith("--- ") -> DiffLineKind.Header
    line.startsWith("@@") -> DiffLineKind.Hunk
    line.startsWith("+") -> DiffLineKind.Added
    line.startsWith("-") -> DiffLineKind.Removed
    else -> DiffLineKind.Context
}

/**
 * One line of a unified diff, monospace, tinted by kind. [wrap] false keeps each line on one row
 * (the caller scrolls sideways); true wraps long lines.
 */
@Composable
fun DiffLine(line: String, modifier: Modifier = Modifier, wrap: Boolean = true) {
    val colors = Locus.diff
    val kind = diffLineKind(line)
    val background = when (kind) {
        DiffLineKind.Added -> colors.addedBackground
        DiffLineKind.Removed -> colors.removedBackground
        else -> Color.Transparent
    }
    val signColor = when (kind) {
        DiffLineKind.Added -> colors.added
        DiffLineKind.Removed -> colors.removed
        else -> colors.meta
    }
    Row(modifier.fillMaxWidth().background(background).padding(horizontal = Spacing.sm)) {
        when (kind) {
            DiffLineKind.Hunk, DiffLineKind.Header -> Text(
                line,
                style = CodeTextStyle,
                color = colors.meta,
                maxLines = if (wrap) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(vertical = if (kind == DiffLineKind.Hunk) 2.dp else 0.dp),
            )
            else -> {
                Text(line.take(1).ifEmpty { " " }, style = CodeTextStyle, color = signColor, modifier = Modifier.width(14.dp))
                Text(
                    line.drop(1),
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
