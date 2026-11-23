package dev.locus.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.locus.core.browser.PickFailure
import dev.locus.core.browser.PickedElement
import dev.locus.core.designsystem.Banner
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.Stroke
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import dev.locus.core.designsystem.container
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pick mode over the page: a tap reports where it landed as fractions of the page's size ([onTap]), a drag
 * scrolls the page ([onScroll]), and nothing reaches the page itself. The element picked last is outlined
 * where the page helper said it is, until the page scrolls.
 */
@Composable
internal fun PickOverlay(pick: PickMode, onTap: (fx: Float, fy: Float) -> Unit, onScroll: (dy: Int) -> Unit) {
    val description = stringResource(R.string.browser_pick_overlay)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Initial)
                        down.changes.forEach { it.consume() }
                        if (down.type != PointerEventType.Press) continue
                        val start = down.changes.first().position
                        var last = start.y
                        var dragging = false
                        var carry = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val at = event.changes.first().position
                            if (!dragging && (abs(at.y - start.y) > slop || abs(at.x - start.x) > slop)) {
                                dragging = true
                                last = at.y
                            }
                            if (dragging) {
                                // Finger up, page down, as a scroll does; whole pixels, the remainder kept.
                                carry += last - at.y
                                last = at.y
                                val step = carry.roundToInt()
                                if (step != 0) {
                                    carry -= step
                                    onScroll(step)
                                }
                            }
                            if (event.changes.none { it.pressed }) {
                                if (!dragging && size.width > 0 && size.height > 0) onTap(start.x / size.width, start.y / size.height)
                                break
                            }
                        }
                    }
                }
            },
    ) {
        val selection = pick.selection
        if (selection != null && !pick.stale && selection.width > 0f && selection.height > 0f) {
            val outline = Tone.Brand.color()
            Box(
                Modifier
                    .offset(x = maxWidth * selection.left, y = maxHeight * selection.top)
                    .size(width = maxWidth * selection.width, height = maxHeight * selection.height)
                    .background(Tone.Brand.container(), MaterialTheme.shapes.small)
                    .border(Stroke.bold, outline, MaterialTheme.shapes.small),
            )
        }
    }
}

/**
 * The bar at the bottom while picking: what to do, what the last tap found (or why it found nothing),
 * Cancel, and Attach once something is picked.
 */
@Composable
internal fun PickPanel(pick: PickMode, onCancel: () -> Unit, onAttach: () -> Unit) {
    val selection = pick.selection
    val failure = pick.failure
    val (title, message, tone) = when {
        pick.busy -> Triple(stringResource(R.string.browser_pick_title), stringResource(R.string.browser_pick_checking), Tone.Live)
        pick.attachFailed -> Triple(stringResource(R.string.browser_pick_title), stringResource(R.string.browser_pick_attach_failed), Tone.Critical)
        failure != null -> Triple(stringResource(R.string.browser_pick_title), failureText(failure), Tone.Caution)
        selection != null -> Triple(selection.label, selectionText(selection), Tone.Live)
        else -> Triple(stringResource(R.string.browser_pick_title), stringResource(R.string.browser_pick_hint), Tone.Live)
    }
    Banner(message, tone, Modifier.padding(Spacing.lg), title = title, actionsBelow = true) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.browser_pick_cancel)) }
        Button(onClick = onAttach, enabled = selection != null && !pick.busy, modifier = Modifier.padding(start = Spacing.sm)) {
            Text(stringResource(R.string.browser_pick_attach))
        }
    }
}

@Composable
private fun failureText(failure: PickFailure): String = stringResource(
    when (failure) {
        PickFailure.Nothing -> R.string.browser_pick_nothing
        PickFailure.WholePage -> R.string.browser_pick_whole
        PickFailure.NoAnswer -> R.string.browser_pick_no_answer
        PickFailure.NotNow -> R.string.browser_pick_not_now
    },
)

/** The element's own words (or its address), then how to pick something else. */
@Composable
private fun selectionText(selection: PickedElement): String {
    val words = selection.text.takeIf { it.isNotBlank() && it != selection.name }
        ?: selection.href
        ?: selection.host
    val shown = if (words.length > SHOWN_CHARS) words.take(SHOWN_CHARS - 1) + "…" else words
    val again = stringResource(R.string.browser_pick_again)
    return if (shown.isBlank()) again else stringResource(R.string.browser_pick_selected, shown, again)
}

private const val SHOWN_CHARS = 140
