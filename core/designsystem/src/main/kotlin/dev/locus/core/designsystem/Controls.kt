package dev.locus.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

// Rows that pair a label with a control or a value, and the link that sends the user somewhere.
// They sit in a CardGroup like ListRow does (and are built on it where they can be).

/**
 * A setting that is on or off. The whole row toggles (TalkBack reads it as a switch with its title
 * and subtitle), and the title wraps rather than being cut short, because a switch's text is what
 * it does.
 *
 * [busy] shows a spinner instead of the switch while a change is applied, and blocks another tap.
 * [error] shows under the subtitle in the critical colour, for a change that didn't stick.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    error: String? = null,
) {
    ListRow(
        title = title,
        modifier = modifier
            .toggleable(value = checked, enabled = enabled && !busy, role = Role.Switch, onValueChange = onCheckedChange)
            .then(if (busy) Modifier.semantics { stateDescription = switchState(checked, busy = true) } else Modifier),
        subtitle = subtitle,
        titleMaxLines = 2,
        // A switch's subtitle says what it does; it wraps instead of being cut off.
        subtitleMaxLines = Int.MAX_VALUE,
        enabled = enabled,
        leading = icon?.let { { IconTile(it) } },
        meta = error?.let {
            {
                Text(
                    it,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = LocusType.supporting,
                    color = Tone.Critical.onContainer(),
                )
            }
        },
        trailing = {
            // The row is the control; the switch only shows the state, so TalkBack finds one target.
            if (busy) InlineSpinner(color = MaterialTheme.colorScheme.primary) else Switch(checked = checked, onCheckedChange = null)
        },
    )
}

/**
 * A switch beside a row whose own tap does something else (opens or expands it), so [SwitchRow]'s
 * whole-row toggle doesn't fit: a scheduled task's on/off next to its details. [contentDescription]
 * says what it turns on ("Run “Backup” on its schedule"), because the row's title is a separate
 * target for TalkBack. Material's switch keeps its 48dp touch target.
 */
@Composable
fun InlineSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        enabled = enabled,
    )
}

/** What TalkBack says for a switch row's state. */
internal fun switchState(checked: Boolean, busy: Boolean): String = buildString {
    append(if (checked) "On" else "Off")
    if (busy) append(", updating")
}

/**
 * A label and its value: "Version  1.4.0", "Model  claude-haiku-4-5", "Cost  $0.04".
 *
 * The default layout is a full [ListRow]-height row: label on the left, the value muted on the right.
 * [dense] is the compact form for a receipt or a details card: label in a narrow column, value beside
 * it in full colour, several to a card.
 *
 * [tone] colours the value (a failed status in critical); [mono] sets it in mono for ids, paths and
 * numbers. With [onClick] the row is a button with a chevron, at least 48dp tall. TalkBack reads
 * label and value as one item.
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    mono: Boolean = false,
    dense: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    valueMaxLines: Int = 3,
) {
    val interaction = when {
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier.semantics(mergeDescendants = true) { if (!enabled) disabled() }
    }
    val valueColor = tone?.onContainer() ?: if (dense) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val baseValueStyle = if (dense) LocusType.supporting else LocusType.rowSupporting
    val valueStyle = if (mono) LocusType.mono else baseValueStyle
    val chevron: @Composable () -> Unit = {
        if (onClick != null) {
            Icon(
                LocusIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs).size(if (dense) IconSize.small else IconSize.medium),
            )
        }
    }
    val outer = modifier
        .fillMaxWidth()
        .then(if (!dense) Modifier.heightIn(min = Sizes.row) else if (onClick != null) Modifier.heightIn(min = Sizes.touchTarget) else Modifier)
        .then(interaction)
        // A dense row sits inside a card or sheet that already sets the edge: no inset of its own, so
        // its label starts where the card's title and the rows around it do.
        .padding(horizontal = if (dense) 0.dp else Spacing.lg, vertical = if (dense) Spacing.xs else Spacing.md)
        .alpha(if (enabled) 1f else Emphasis.disabled)
    if (!dense) {
        Row(outer, verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.rowTitle().padding(end = Spacing.md), style = LocusType.rowTitle, maxLines = rowTitleLines(2), overflow = TextOverflow.Ellipsis)
            Text(
                value,
                Modifier.weight(1f),
                style = valueStyle,
                color = valueColor,
                textAlign = TextAlign.End,
                maxLines = valueMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            chevron()
        }
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val largeFont = isLargeFont()
    // Centred in the row's height: a tappable dense row is 48dp tall, and its text sits in the middle.
    BoxWithConstraints(outer, contentAlignment = Alignment.CenterStart) {
        // The label column holds whole words. When one doesn't fit ("output_config.effort",
        // "Quantisation" at a large font) the label goes over the value instead of breaking mid-word.
        val labelColumn = with(density) { (maxWidth * DenseLabelWeight - Spacing.sm).toPx() }
        val labelStyle = LocusType.supporting
        val widestWord = remember(label, density, labelStyle) {
            label.split(' ', '\n').filter { it.isNotEmpty() }.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0
        }
        if (largeFont || widestWord > labelColumn) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SupportingText(label, Modifier.rowTitle())
                    Text(value, style = valueStyle, color = valueColor, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis)
                }
                chevron()
            }
        } else {
            // On one baseline: a mono value has a shorter line than its label and would sit higher. The
            // chevron sits at the end of the value's column, so every value starts on the same line.
            Row(Modifier.fillMaxWidth()) {
                SupportingText(label, Modifier.rowTitle().weight(DenseLabelWeight).padding(end = Spacing.sm).alignByBaseline(), maxLines = rowTitleLines(2))
                Row(Modifier.weight(1f - DenseLabelWeight).alignByBaseline(), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), style = valueStyle, color = valueColor, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis)
                    chevron()
                }
            }
        }
    }
}

/** How much of a dense [KeyValueRow] the label takes; the value gets the rest. */
private const val DenseLabelWeight = 0.32f

/**
 * A tappable way to the place a message talks about: "Settings → Models", "Library → Connectors".
 * Use it wherever text would otherwise tell the user to go somewhere by hand. [hint] is the sentence
 * before it ("No API key for this provider. Add one in"), shown muted above the link.
 *
 * [onNavigate] opens the destination (a route lambda from the screen's caller). The link is a button
 * with a 48dp touch target; TalkBack reads the path without the arrow ("Settings, Models") and
 * announces "Open".
 */
@Composable
fun DestinationLink(
    destination: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        if (hint != null) SupportingText(hint, style = LocusType.rowSupporting)
        Row(
            Modifier
                .heightIn(min = Sizes.touchTarget)
                .clip(RoundedCornerShape(Radius.chip))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Open", onClick = onNavigate)
                .clearAndSetSemantics { contentDescription = destinationSpeech(destination) }
                .padding(horizontal = Spacing.xs)
                .alpha(if (enabled) 1f else Emphasis.disabled),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(destination, style = LocusType.label, color = MaterialTheme.colorScheme.primary)
            Icon(LocusIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.small))
        }
    }
}

/** "Settings", "Models" → "Settings → Models", the way every destination is written. */
fun destinationPath(vararg parts: String): String = parts.joinToString(" → ")

/** What TalkBack reads for a destination: the path without arrows, "Settings, Models". */
fun destinationSpeech(destination: String): String =
    destination.split('→', '>').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
