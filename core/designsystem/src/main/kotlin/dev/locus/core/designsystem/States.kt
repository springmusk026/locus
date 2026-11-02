package dev.locus.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small spinner that sits in a row, a button or a chip while something works: [IconSize.medium]
 * by default, [Stroke.bold] wide, in the surrounding content colour. Give it a [contentDescription]
 * when nothing next to it says what is loading.
 */
@Composable
fun InlineSpinner(
    modifier: Modifier = Modifier,
    size: Dp = IconSize.medium,
    color: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size).then(
            if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier,
        ),
        color = color,
        strokeWidth = Stroke.bold,
        trackColor = Color.Transparent,
    )
}

/**
 * A screen (or a tab) whose content couldn't load: what went wrong, in words, and a way to try again.
 * The full-screen counterpart of a critical [Banner]; use it where [EmptyState] or [LoadingState]
 * would otherwise be, never as a blank screen.
 *
 * While [retrying], the button shows a spinner and can't be pressed again. TalkBack announces the
 * message when the state appears. Without [onRetry] there is no button (for errors a retry can't fix;
 * say what will in [message]).
 */
@Composable
fun ErrorState(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    retrying: Boolean = false,
    retryLabel: String = "Try again",
    icon: ImageVector = LocusIcons.Alert,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(bottom = Spacing.lg).size(IconSize.hero)
                .background(Tone.Critical.container(), RoundedCornerShape(IconSize.hero / 3.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Tone.Critical.onContainer(), modifier = Modifier.size(IconSize.large + Spacing.xs))
        }
        Text(
            title,
            Modifier.semantics { heading() },
            style = LocusType.stateTitle,
            textAlign = TextAlign.Center,
        )
        SupportingText(
            message,
            Modifier.padding(top = Spacing.sm).widthIn(max = ReadableWidth).semantics { liveRegion = LiveRegionMode.Polite },
            style = LocusType.rowSupporting,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null || action != null) {
            Row(
                Modifier.padding(top = Spacing.xl),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onRetry != null) {
                    Button(onClick = onRetry, enabled = !retrying, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                        if (retrying) {
                            InlineSpinner(Modifier.padding(end = Spacing.sm), size = IconSize.small)
                        } else {
                            Icon(LocusIcons.Regenerate, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm).size(IconSize.small))
                        }
                        Text(retryLabel, style = LocusType.label)
                    }
                }
                action?.invoke()
            }
        }
    }
}

/** The widest a centred paragraph gets, so state messages stay readable on tablets. */
internal val ReadableWidth = 320.dp
