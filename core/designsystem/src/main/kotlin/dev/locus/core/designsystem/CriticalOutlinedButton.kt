package dev.locus.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * The one destructive action at the end of a sheet or page ("Remove provider", "Remove machine"):
 * full width, outlined and labelled in the critical colour, so it reads as deliberate rather than as a
 * stray red link in a corner. It should open a [ConfirmSheet] with `tone = Tone.Critical`.
 */
@Composable
fun CriticalOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val critical = Locus.colors.critical
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget),
        border = BorderStroke(Stroke.regular, if (enabled) critical else critical.copy(alpha = Emphasis.disabled)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = critical),
    ) {
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun CriticalOutlinedButtonPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CriticalOutlinedButton("Remove provider", onClick = {})
                CriticalOutlinedButton("Remove machine", onClick = {}, enabled = false)
            }
        }
    }
}
