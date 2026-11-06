package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * A shelf: one row of small cards that scrolls sideways, for a handful of things a person goes back
 * to (Home's projects). One row's height whatever the count; the next card peeks at the edge, which
 * says the row scrolls. Material's uncontained carousel, without snapping: the cards hold text.
 */

/** A [ShelfCard]'s sizes. */
object ShelfSizes {
    /**
     * A card's width at 100% font: at 360dp, a narrow [ShelfAddCard] and two cards fit, with the third
     * peeking. It grows with the font up to [MAX_FONT_SCALE], so a large font keeps a readable line.
     */
    val card = 148.dp

    /** The narrowest a [ShelfAddCard] gets: a 48dp touch target plus its padding. */
    val addCard = 72.dp

    const val MAX_FONT_SCALE = 1.5f
}

/**
 * The row the cards sit in: [Spacing.sm] apart, every card as tall as the tallest. [bleed] lets it
 * run to the screen edges past a list's side padding (pass that padding), while the first card still
 * starts on the list's edge, so a card scrolls off the screen rather than being cut at the margin.
 */
@Composable
fun Shelf(modifier: Modifier = Modifier, bleed: Dp = 0.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .layout { measurable, constraints ->
                val extra = bleed.roundToPx()
                if (extra == 0 || !constraints.hasBoundedWidth) {
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, 0) }
                } else {
                    val p = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra))
                    layout(constraints.maxWidth, p.height) { p.place(-extra, 0) }
                }
            }
            .horizontalScroll(rememberScrollState())
            .height(IntrinsicSize.Min)
            .padding(horizontal = bleed),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
}

/**
 * A small card on a [Shelf]: a [title] and one line under it, either a muted [meta] ("12 chats · 2h
 * ago") or, when something in it needs a look, a [status] in its [statusTone] with the state dot, as a
 * chat row shows it. [Tone.Caution] also outlines the card. TalkBack reads it as one button:
 * [contentDescription] when given (say the name, the meta and the state), else the title and the line.
 */
@Composable
fun ShelfCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    status: String? = null,
    statusTone: Tone = Tone.Neutral,
    contentDescription: String? = null,
) {
    val width = ShelfSizes.card * LocalDensity.current.fontScale.coerceIn(1f, ShelfSizes.MAX_FONT_SCALE)
    val spoken = contentDescription ?: listOfNotNull(title, status ?: meta).joinToString(", ")
    LocusCard(
        modifier
            .width(width)
            .fillMaxHeight()
            .clearAndSetSemantics {
                this.contentDescription = spoken
                role = Role.Button
                onClick { onClick(); true }
            },
        onClick = onClick,
        outline = if (status != null && statusTone == Tone.Caution) Tone.Caution.color().copy(alpha = Emphasis.toneOutline) else null,
        // Text starts Spacing.lg in, where a SectionLabel's and every other card's does.
        contentPadding = CardPadding,
    ) {
        Text(title, style = LocusType.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The second line may wrap once at a large font rather than lose its end ("Needs approv…").
        when {
            status != null -> WrappingStatusLine(status, statusTone, Modifier.padding(top = Spacing.xxs), maxLines = 2)
            meta != null -> SupportingText(meta, Modifier.padding(top = Spacing.xxs), maxLines = 2)
        }
    }
}

/**
 * The shelf's "make one" card, first in the row so it's in reach however many cards follow (as a
 * "New project" row heads a sidebar's list): a plus over a short [label] in the accent, as tall as
 * the cards. TalkBack reads [contentDescription] ("New project").
 */
@Composable
fun ShelfAddCard(label: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LocusCard(
        modifier
            .widthIn(min = ShelfSizes.addCard)
            .width(IntrinsicSize.Max)
            .fillMaxHeight()
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Button
                onClick { onClick(); true }
            },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(LocusIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.large))
            Text(label, style = LocusType.label, color = MaterialTheme.colorScheme.primary, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun ShelfPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Shelf(bleed = Spacing.lg, modifier = Modifier.padding(horizontal = Spacing.lg)) {
                    ShelfAddCard("New", "New project", onClick = {})
                    ShelfCard("garden-app", {}, status = "Needs approval", statusTone = Tone.Caution)
                    ShelfCard("Portfolio site", {}, status = "Working", statusTone = Tone.Live)
                    ShelfCard("Tax return 2026", {}, meta = "4 chats · 3d ago")
                }
                Shelf(bleed = Spacing.lg, modifier = Modifier.padding(horizontal = Spacing.lg)) {
                    ShelfAddCard("New", "New project", onClick = {})
                    ShelfCard("A very long project name that will not fit", {}, meta = "12 chats · 2h ago")
                    ShelfCard("Empty", {}, meta = "No chats yet")
                }
            }
        }
    }
}
