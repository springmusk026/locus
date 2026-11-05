package dev.locus.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Voice mode's one big control, and the sizes around it.

/** Voice mode's sizes. */
object VoiceSizes {
    /** The orb's tap target, around the disc. */
    val orb = 196.dp

    /** The orb's disc. */
    val orbCore = 128.dp

    /** The icon in the core. */
    val orbIcon = 44.dp

    /** The row that holds "Microphone on", kept when it's hidden so the orb doesn't jump. */
    val indicatorRow = 32.dp

    /** The tallest the phase's detail (what was heard, the reply) gets before it scrolls. */
    val detail = 160.dp

    /** The orb at a large font with an approval waiting: small, so the card and its buttons fit. */
    val orbCompact = 96.dp
}

/**
 * The big circle voice mode is about: one tinted disc with [icon], in the middle of a tap target the
 * size of [VoiceSizes.orb]. [color] says the phase (brand listening, live speaking, caution waiting for
 * a tap, neutral otherwise) and tints the icon; the disc is the same colour at [Emphasis.toneContainer],
 * the way a chip is, so the icon reads in both themes. [scale] grows it with the voice or breathes it
 * while working. TalkBack reads [contentDescription]. [size] is the tap target; the disc and icon keep
 * their proportions ([VoiceSizes.orbCompact] when the screen needs the room).
 */
@Composable
fun VoiceOrb(
    color: Color,
    icon: ImageVector,
    contentDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    size: Dp = VoiceSizes.orb,
) {
    val ratio = size / VoiceSizes.orb
    Box(
        modifier.size(size).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onTap)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(VoiceSizes.orbCore * ratio).scale(scale).clip(CircleShape).background(color.copy(alpha = Emphasis.toneContainer)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(VoiceSizes.orbIcon * ratio))
        }
    }
}

@LocusPreviews
@Composable
internal fun VoiceOrbPreview() {
    LocusTheme {
        Row(Modifier.background(MaterialTheme.colorScheme.background).padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            VoiceOrb(MaterialTheme.colorScheme.primary, LocusIcons.Mic, "Tap to finish", onTap = {})
            VoiceOrb(Locus.colors.live, LocusIcons.Speaker, "Tap to interrupt", onTap = {}, scale = 0.8f)
        }
    }
}
