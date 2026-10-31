package dev.locus.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Meaning
// ---------------------------------------------------------------------------------------------

/** What a status colour says. Brand is "Locus / your choice"; the rest are states. */
enum class Tone { Neutral, Brand, Live, Caution, Critical }

@Composable
fun Tone.color(): Color = when (this) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Brand -> MaterialTheme.colorScheme.primary
    Tone.Live -> Locus.colors.live
    Tone.Caution -> Locus.colors.caution
    Tone.Critical -> Locus.colors.critical
}

// ---------------------------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------------------------

object Motion {
    /** The one spring: settles quickly without bounce. */
    fun <T> spring() = spring<T>(dampingRatio = 0.86f, stiffness = 420f)
    const val QUICK_MS = 220
}

/**
 * Fades and lifts content in the first time it appears, staggered by [order]. Only the first
 * screenful animates; anything further down appears as-is so scrolling never waits on motion.
 */
fun Modifier.entrance(order: Int): Modifier = composed {
    val progress = remember { Animatable(if (order > 8) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(order * 40L)
            progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    val lift = with(LocalDensity.current) { 6.dp.toPx() }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * lift
        val scale = 0.985f + 0.015f * progress.value
        scaleX = scale
        scaleY = scale
    }
}

/** A slight press-in for custom tappable surfaces. */
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(Motion.QUICK_MS), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick)
}

// ---------------------------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------------------------

/**
 * The one card. Neutral fill and a hairline edge; [glow] adds a soft light in the top-left corner
 * in the colour of what the card is about (one per screen at most). [outline] marks selection or
 * a card that needs attention.
 */
@Composable
fun LocusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    glow: Color? = null,
    outline: Color? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    val dark = isSystemInDarkTheme()
    val glowAlpha = if (dark) 0.20f else 0.10f
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Locus.colors.card)
            .then(
                if (glow != null) Modifier.drawBehind {
                    drawRect(Brush.radialGradient(listOf(glow.copy(alpha = glowAlpha), Color.Transparent), center = Offset.Zero, radius = size.maxDimension * 0.9f))
                } else Modifier,
            )
            .border(if (outline != null) 1.dp else 0.5.dp, outline ?: Locus.colors.hairline, shape)
            .then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** A card holding a list of [ListRow]s separated by inset hairlines. */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LocusCard(modifier, contentPadding = PaddingValues(0.dp), content = content)
}

@Composable
fun GroupDivider(inset: Dp = Spacing.lg) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(Locus.colors.hairline))
}

/**
 * The one list row: optional leading tile, a title, a secondary line, and a trailing slot for a
 * status, value or chevron. 56dp minimum so every list has the same rhythm.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitleMaxLines: Int = 1,
    meta: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.width(Spacing.md))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta?.invoke()
        }
        if (trailing != null) {
            Box(Modifier.width(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = trailing)
        }
    }
}

/** A neutral square tile for an icon, used where colour would carry no meaning. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier.size(size).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(size / 3.5f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f)) }
}

/** Section title above a card: sentence case, quiet, with an optional action on the right. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = Spacing.lg + Spacing.xs, end = Spacing.sm, top = Spacing.xl, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onAction).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
    }
}

/** Kept for older call sites. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) = SectionLabel(text, modifier)

/** One line of help under a section label. */
@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = Spacing.lg + Spacing.xs, end = Spacing.lg, bottom = Spacing.sm),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

/** A state dot. Only a live dot moves, and only while something is actually running. */
@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val color = tone.color()
    if (tone == Tone.Live) {
        val t = rememberInfiniteTransition(label = "live")
        val halo by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "halo")
        Box(modifier.size(size * 2), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(size * (1f + halo)).alpha(0.45f * (1f - halo)).background(color, CircleShape),
            )
            Box(Modifier.size(size).background(color, CircleShape))
        }
    } else {
        Box(modifier.size(size).background(color, CircleShape))
    }
}

/** A short state label: "Needs review", "Connected", "Failed". Soft fill, never a solid block. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = false) {
    val color = tone.color()
    Row(
        modifier
            .background(color.copy(alpha = if (tone == Tone.Neutral) 0.10f else 0.14f), RoundedCornerShape(50))
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** "What's happening right now" in one line: a dot and a sentence. */
@Composable
fun StatusLine(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone, size = 7.dp)
        Text(
            text,
            Modifier.padding(start = if (tone == Tone.Live) 2.dp else Spacing.sm),
            style = MaterialTheme.typography.labelLarge,
            color = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else tone.color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A banner that needs the user: tinted edge and corner glow in the tone, actions on the right. */
@Composable
fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val color = tone.color()
    LocusCard(modifier, glow = color, outline = color.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (actions != null) Row(Modifier.padding(start = Spacing.sm), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

/**
 * A pill segmented control with a sliding selection. Used for tabs and for three-way choices
 * like Allow / Ask / Deny, so every switcher in the app looks and moves the same.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selectedTint: @Composable (Int) -> Color? = { null },
) {
    // Grows with the user's font size so labels never clip.
    val height = (if (compact) 32.dp else 40.dp) * LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    BoxWithConstraints(modifier.height(height).clip(RoundedCornerShape(50)).background(track).padding(3.dp)) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selected, Motion.spring(), label = "segment")
        Box(
            Modifier.offset { IntOffset(offset.roundToPx(), 0) }.width(segment).fillMaxHeight()
                .background(Locus.colors.raised, RoundedCornerShape(50))
                .border(0.5.dp, Locus.colors.hairline, RoundedCornerShape(50)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val isSelected = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                        .semantics { role = Role.Tab; this.selected = isSelected }
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        color = if (isSelected) selectedTint(i) ?: MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The standard top bar: back, a title, an optional status line under it, and actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocusTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(LocusIcons.Back, contentDescription = "Back") } },
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.invoke()
            }
        },
        actions = actions,
    )
}

// ---------------------------------------------------------------------------------------------
// Empty and loading
// ---------------------------------------------------------------------------------------------

/** Centered message for empty lists, with an optional icon above and action below. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) IconTile(icon, Modifier.padding(bottom = Spacing.lg).entrance(0), size = 56.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.entrance(1))
        Text(
            body,
            modifier = Modifier.padding(top = Spacing.sm).widthIn(max = 320.dp).entrance(2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) Column(Modifier.padding(top = Spacing.xl).entrance(3)) { action() }
    }
}

/**
 * For long lazy lists that should still look like one [CardGroup]: each row draws its own slice of
 * the card, rounded only at the group's first and last row.
 */
fun Modifier.cardSegment(index: Int, count: Int): Modifier = composed {
    val r = Radius.card
    val shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp,
        topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp,
        bottomEnd = if (index == count - 1) r else 0.dp,
    )
    clip(shape).background(Locus.colors.card).border(0.5.dp, Locus.colors.hairline, shape)
}

// ---------------------------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------------------------

/**
 * The one sheet, used instead of dialogs for forms, pickers and confirmations: a title, an
 * optional primary action in the header, and scrolling content that stays above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocusSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    actionEnabled: Boolean = true,
    actionTone: Tone = Tone.Brand,
    onAction: (() -> Unit)? = null,
    /** False when the content is its own lazy list (long content), which must not sit in a scrolling column. */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().imePaddingCompat()) {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.lg, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (action != null && onAction != null) {
                    androidx.compose.material3.Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        colors = if (actionTone == Tone.Critical) {
                            androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Locus.colors.critical, contentColor = MaterialTheme.colorScheme.surface)
                        } else {
                            androidx.compose.material3.ButtonDefaults.buttonColors()
                        },
                    ) { Text(action) }
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).then(if (scrollable) Modifier.verticalScrollCompat() else Modifier)
                    .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.sm, bottom = if (scrollable) Spacing.xxl else 0.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

private fun Modifier.imePaddingCompat() = this.then(Modifier.imePadding())

@Composable
private fun Modifier.verticalScrollCompat() = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

// ---------------------------------------------------------------------------------------------
// Tokens
// ---------------------------------------------------------------------------------------------

/** 942 → "942", 12_400 → "12.4k", 1_000_000 → "1M". */
fun compactCount(n: Long): String = when {
    n < 1_000 -> "$n"
    n < 100_000 -> "%.1fk".format(n / 1_000.0).replace(".0k", "k")
    n < 1_000_000 -> "${n / 1_000}k"
    else -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
}

/** Colour for how full something is: brand, then caution from 80%, critical from 95%. */
@Composable
fun levelColor(fraction: Float): Color = when {
    fraction >= 0.95f -> Locus.colors.critical
    fraction >= 0.80f -> Locus.colors.caution
    else -> MaterialTheme.colorScheme.primary
}

/** A small ring showing how much of the context window is used; an empty track when unknown. */
@Composable
fun ContextRing(fraction: Float?, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val track = Locus.colors.track
    val color = fraction?.let { levelColor(it) } ?: track
    val sweep by animateFloatAsState((fraction ?: 0f) * 360f, tween(600), label = "ring")
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = this.size.minDimension * 0.18f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke.width, this.size.height - stroke.width)
        drawArc(track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
        if (sweep > 0f) drawArc(color, -90f, sweep, false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
    }
}
