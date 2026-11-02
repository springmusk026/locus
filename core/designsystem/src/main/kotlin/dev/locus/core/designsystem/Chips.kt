package dev.locus.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What a [LocusChip] is for. */
enum class ChipVariant {
    /**
     * A state or fact in the tone's soft tint: "Connected", "Paused", "Remote". Read-only, or a small
     * action when given an onClick ("3 steps" that opens Activity).
     */
    Status,

    /** One option in a filter row: outlined when off, tinted with a check when on. Toggles. */
    Filter,

    /** A number on its own ("3"): the tab count, unread items. Give it a contentDescription. */
    Count,
}

/** The chip's visual height. Anything tappable still gets a 48dp touch target around it. */
private val ChipHeight = 24.dp
private val FilterChipHeight = 32.dp

/**
 * The one chip. Replaces StatusPill's look, the hand-made pills (RemoteChip, PausedChip, ReadingChip,
 * LinkChip, ProjectChip, FilterPill, ModelStatePill, the home Pill) and Material's FilterChip.
 *
 * - [ChipVariant.Status] (default): [tone]'s container and text colour; [dot] adds a state dot.
 * - [ChipVariant.Filter]: [selected] state, toggled by [onClick]; TalkBack reads it as a checkbox.
 * - [ChipVariant.Count]: [text] is a number; use [chipCount] to cap it, and say what it counts in
 *   [contentDescription].
 *
 * With [onClick] the chip is a button (or a toggle for Filter) with a 48dp touch target; with
 * [enabled] false it fades and can't be used.
 */
@Composable
fun LocusChip(
    text: String,
    modifier: Modifier = Modifier,
    variant: ChipVariant = ChipVariant.Status,
    tone: Tone = Tone.Neutral,
    icon: ImageVector? = null,
    dot: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    val filter = variant == ChipVariant.Filter
    // A selected filter uses the brand unless told otherwise; an unselected one is quiet.
    val activeTone = if (filter && tone == Tone.Neutral) Tone.Brand else tone
    val fill: Color = when {
        filter && !selected -> Color.Transparent
        filter -> activeTone.container()
        else -> tone.container()
    }
    val content: Color = when {
        filter && !selected -> MaterialTheme.colorScheme.onSurfaceVariant
        filter -> activeTone.onContainer()
        else -> tone.onContainer()
    }
    val interaction = when {
        onClick == null -> Modifier.semantics(mergeDescendants = true) { if (!enabled) disabled() }
        filter -> Modifier.minimumInteractiveComponentSize().clip(Radius.pill)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        else -> Modifier.minimumInteractiveComponentSize().clip(Radius.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    // Replaces the visible text for TalkBack ("3" → "3 tabs"); the click, toggle and disabled state
    // above stay, because they sit on an outer node.
    val described = if (contentDescription != null) {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Row(
        modifier
            .then(interaction)
            .then(described)
            .alpha(if (enabled) 1f else Emphasis.disabled)
            .heightIn(min = if (filter) FilterChipHeight else ChipHeight)
            .defaultMinSize(minWidth = if (variant == ChipVariant.Count) ChipHeight else 0.dp)
            .background(fill, Radius.pill)
            .then(if (filter && !selected) Modifier.border(Stroke.hairline, MaterialTheme.colorScheme.outline, Radius.pill) else Modifier)
            .padding(horizontal = if (variant == ChipVariant.Count) Spacing.xs + Spacing.xxs else if (filter) Spacing.md else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
    ) {
        if (filter && selected) Icon(LocusIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(IconSize.small))
        if (dot) Box(Modifier.size(DotSize).background(content, CircleShape))
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(IconSize.small))
        Text(
            text,
            style = if (variant == ChipVariant.Count) LocusType.mono else LocusType.chip,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DotSize = 6.dp

/** A count for a [ChipVariant.Count] chip: the number, or "99+" past [max]. */
fun chipCount(n: Int, max: Int = 99): String = when {
    n < 0 -> "0"
    n > max -> "$max+"
    else -> n.toString()
}
