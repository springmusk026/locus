package dev.locus.core.designsystem

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one channel for short-lived messages: "Saved", "Couldn't save", "Scheduled", "Copied". It
 * replaces Toasts and per-screen SnackbarHosts. Persistent problems that need the user stay in a
 * [Banner]; a screen that couldn't load shows an [ErrorState].
 *
 * Get it with [Locus.feedback] and call [show]. Messages queue and show one at a time in the host's
 * snackbar. An [action] ("Undo", "Open") runs [onAction] in the host's scope, so it still runs if the
 * screen that asked has gone. A ViewModel doesn't hold this: it exposes an event and the screen shows it.
 *
 * Until the app root provides a host ([ProvideLocusFeedback]), messages fall back to a Toast (and an
 * action is dropped), so callers can move over before the host exists.
 */
@Stable
interface LocusFeedback {
    fun show(message: String, tone: Tone = Tone.Neutral, action: String? = null, onAction: (() -> Unit)? = null)
}

/**
 * How long a message stays: long when it has an action (time to reach it) or reports a problem
 * (time to read it), short otherwise.
 */
fun feedbackDuration(tone: Tone, hasAction: Boolean): SnackbarDuration = when {
    hasAction -> SnackbarDuration.Long
    tone == Tone.Critical || tone == Tone.Caution -> SnackbarDuration.Long
    else -> SnackbarDuration.Short
}

/** The icon a message of [tone] leads with. */
internal fun feedbackIcon(tone: Tone): ImageVector = when (tone) {
    Tone.Live -> LocusIcons.Check
    Tone.Caution, Tone.Critical -> LocusIcons.Alert
    Tone.Neutral, Tone.Brand, Tone.Info -> LocusIcons.Info
}

/** A [LocusFeedback] backed by a snackbar host. Create with [rememberLocusFeedbackState]. */
@Stable
class LocusFeedbackState internal constructor(
    internal val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) : LocusFeedback {
    override fun show(message: String, tone: Tone, action: String?, onAction: (() -> Unit)?) {
        scope.launch {
            val visuals = FeedbackVisuals(message, tone, action, feedbackDuration(tone, action != null))
            if (hostState.showSnackbar(visuals) == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

@Composable
fun rememberLocusFeedbackState(): LocusFeedbackState {
    val scope = rememberCoroutineScope()
    return remember(scope) { LocusFeedbackState(SnackbarHostState(), scope) }
}

/** Makes [state] the [Locus.feedback] for [content]. The app root does this once. */
@Composable
fun ProvideLocusFeedback(state: LocusFeedbackState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLocusFeedback provides state, content = content)
}

/** Shows [state]'s messages. Put it in the root Scaffold's `snackbarHost` (or a Box at the bottom). */
@Composable
fun LocusFeedbackHost(state: LocusFeedbackState, modifier: Modifier = Modifier) {
    SnackbarHost(state.hostState, modifier) { data -> FeedbackSnackbar(data) }
}

private class FeedbackVisuals(
    override val message: String,
    val tone: Tone,
    override val actionLabel: String?,
    override val duration: SnackbarDuration,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

@Composable
private fun FeedbackSnackbar(data: SnackbarData) {
    val tone = (data.visuals as? FeedbackVisuals)?.tone ?: Tone.Neutral
    FeedbackCard(
        message = data.visuals.message,
        tone = tone,
        action = data.visuals.actionLabel,
        onAction = data::performAction,
    )
}

/**
 * One message, styled like the app's cards rather than Material's dark bar: a raised surface, a
 * hairline edge, and a small tinted icon that says what kind of message it is.
 */
@Composable
internal fun FeedbackCard(message: String, tone: Tone, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.control)
    Surface(
        modifier
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = shape,
        color = Locus.colors.raised,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = FeedbackElevation,
    ) {
        Row(
            Modifier
                .border(Stroke.hairline, Locus.colors.hairline, shape)
                .heightIn(min = Sizes.touchTarget)
                .padding(start = Spacing.md, end = if (action != null) Spacing.xs else Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(IconSize.large).background(tone.container(), CircleShape), contentAlignment = Alignment.Center) {
                Icon(feedbackIcon(tone), contentDescription = null, tint = tone.onContainer(), modifier = Modifier.size(IconSize.small))
            }
            Text(
                message,
                Modifier.weight(1f).padding(horizontal = Spacing.md),
                style = LocusType.rowSupporting,
                maxLines = 3,
            )
            if (action != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = Sizes.touchTarget)) {
                    Text(action, style = LocusType.label)
                }
            }
        }
    }
}

private val FeedbackElevation = 6.dp

/**
 * Feedback for code that runs outside composition and can't read [Locus.feedback]: an Intent helper,
 * a menu callback. It's the same Toast the host-less fallback uses, so messages still go through one
 * channel. Composables use [Locus.feedback] instead.
 */
fun toastFeedback(context: Context): LocusFeedback = ToastFeedback(context.applicationContext)

/** Used when no host is provided: a Toast, without the action. */
private class ToastFeedback(private val context: Context) : LocusFeedback {
    override fun show(message: String, tone: Tone, action: String?, onAction: (() -> Unit)?) {
        val length = if (feedbackDuration(tone, hasAction = false) == SnackbarDuration.Long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context, message, length).show()
    }
}

internal val LocalLocusFeedback = staticCompositionLocalOf<LocusFeedback?> { null }

@Composable
internal fun currentFeedback(): LocusFeedback {
    val context = LocalContext.current.applicationContext
    val fallback = remember(context) { ToastFeedback(context) }
    return LocalLocusFeedback.current ?: fallback
}
