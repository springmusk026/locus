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
import androidx.compose.ui.unit.dp

// Voice mode's one big control, and the sizes around it.

/** Voice mode's sizes. */
object VoiceSizes {
    /** The orb's halo: the whole tap target. */
    val orb = 196.dp

    /** The orb's solid core. */
    val orbCore = 128.dp

    /** The icon in the core. */
    val orbIcon = 44.dp

    /** The row that holds "Microphone on", kept when it's hidden so the orb doesn't jump. */
    val indicatorRow = 32.dp

    /** The tallest the phase's detail (what was heard, the reply) gets before it scrolls. */
    val detail = 160.dp
}

/** The core's alpha: nearly solid, so the icon reads, but still tinted by the halo behind it. */
private const val CoreAlpha = 0.85f

/**
 * The big circle voice mode is about: a tinted halo the size of the tap target and a solid core with
 * [icon]. [color] says the phase (brand listening, live speaking, critical failed, caution waiting for a
 * tap); [scale] grows it with the voice or breathes it while working. TalkBack reads [contentDescription].
 */
@Composable
fun VoiceOrb(color: Color, icon: ImageVector, contentDescription: String, onTap: () -> Unit, modifier: Modifier = Modifier, scale: Float = 1f) {
    Box(
        modifier.size(VoiceSizes.orb).scale(scale).clip(CircleShape).background(color.copy(alpha = Emphasis.toneContainer))
            .clickable(role = Role.Button, onClick = onTap)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(VoiceSizes.orbCore).clip(CircleShape).background(color.copy(alpha = CoreAlpha)), contentAlignment = Alignment.Center) {
            // White on every phase colour, in both themes: the core is always a saturated or mid-grey fill.
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(VoiceSizes.orbIcon))
        }
    }
}

@LocusPreviews
@Composable
private fun VoiceOrbPreview() {
    LocusTheme {
        Row(Modifier.background(MaterialTheme.colorScheme.background).padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            VoiceOrb(MaterialTheme.colorScheme.primary, LocusIcons.Mic, "Tap to finish", onTap = {})
            VoiceOrb(Locus.colors.live, LocusIcons.Speaker, "Tap to interrupt", onTap = {}, scale = 0.8f)
        }
    }
}
