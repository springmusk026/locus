package dev.locus.feature.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

/**
 * Keeps a chat transcript pinned to its newest content, the way messaging apps do:
 * - when the chat opens, it lands on the end without animating;
 * - while the user is at (or near) the bottom, growing content (new rows, streamed text, a taller
 *   last item, a smaller viewport when the keyboard or a panel opens) keeps the end in view;
 * - once the user drags away from the bottom it stops following, and [hasUnseen] turns on when
 *   something new arrives, for a "jump to latest" button;
 * - dragging (or flinging) back to the bottom resumes following.
 */
@Stable
internal class TranscriptFollower(val list: LazyListState, private val nearPx: Float) {
    /** Whether new content should keep the end in view. */
    var following by mutableStateOf(true)
        private set

    /** Something arrived below while the user was reading further up. */
    var hasUnseen by mutableStateOf(false)
        private set

    internal var userScrolling by mutableStateOf(false)
    internal var landed = false

    /** How far the end of the content is below the visible area, in px (0 at the bottom). */
    fun distanceToEnd(): Float {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        if (last.index < info.totalItemsCount - 1) return Float.MAX_VALUE
        // Item offsets are relative to the start of the content area; the end of the visible content
        // area is the viewport end minus the bottom content padding.
        val visibleEnd = info.viewportEndOffset - info.afterContentPadding
        return (last.offset + last.size - visibleEnd).toFloat().coerceAtLeast(0f)
    }

    fun nearBottom(): Boolean = !list.canScrollForward || distanceToEnd() <= nearPx

    /** Snaps to the very end (no animation, so streaming doesn't fight a running animation). */
    suspend fun snapToEnd() {
        val count = list.layoutInfo.totalItemsCount
        if (count == 0) return
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (lastVisible != count - 1) list.scrollToItem(count - 1)
        // The last item can be taller than the screen; scroll past its start to its end. The list only
        // consumes what is actually left to scroll.
        if (list.canScrollForward) list.scrollBy(LARGE_SCROLL)
    }

    /** "Jump to latest", or the user sent something: follow again from here. */
    suspend fun resume() {
        following = true
        hasUnseen = false
        snapToEnd()
    }

    /** Opened somewhere other than the end: stay put until the user scrolls back down. */
    internal fun holdHere() {
        following = false
        landed = true
    }

    internal fun onUserScrollSettled() {
        following = nearBottom()
        if (following) hasUnseen = false
    }

    internal fun onNewContent() {
        if (landed && !following) hasUnseen = true
    }

    private companion object {
        const val LARGE_SCROLL = 1_000_000f
    }
}

/**
 * Remembers a [TranscriptFollower] and runs its effects.
 * [ready] is true once the transcript has loaded; [contentVersion] changes whenever rows are added;
 * [streamLength] is read in a snapshot flow (not in composition) to notice the live turn growing; [lastUserKey] is the newest user message, so sending one brings the user down.
 */
@Composable
internal fun rememberTranscriptFollower(
    list: LazyListState,
    ready: Boolean,
    contentVersion: Any,
    streamLength: () -> Int,
    lastUserKey: String?,
    /** Opening at a particular message (from search): don't snap to the end, and don't follow. */
    holdPosition: Boolean = false,
): TranscriptFollower {
    val nearPx = with(LocalDensity.current) { 64.dp.toPx() }
    val follower = remember(list) { TranscriptFollower(list, nearPx) }

    // Open on the newest message, once, without animating. A second snap after a frame catches rows
    // whose height settled after the first layout (images, code blocks).
    LaunchedEffect(follower, ready) {
        if (!ready || follower.landed) return@LaunchedEffect
        if (holdPosition) {
            follower.holdHere()
            return@LaunchedEffect
        }
        follower.snapToEnd()
        withFrameNanos { }
        follower.snapToEnd()
        follower.landed = true
    }

    // Only the user's own drags decide whether we stop or resume following.
    LaunchedEffect(follower) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follower.userScrolling = true }
    }
    LaunchedEffect(follower) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && follower.userScrolling) {
                follower.userScrolling = false
                follower.onUserScrollSettled()
            }
        }
    }

    // The user just sent something: always bring them down to it.
    LaunchedEffect(follower, lastUserKey) {
        if (follower.landed) follower.resume()
    }
    // Something new arrived: if they're reading further up, offer "jump to latest" instead of yanking them.
    LaunchedEffect(follower, contentVersion) { follower.onNewContent() }
    LaunchedEffect(follower) { snapshotFlow(streamLength).collect { follower.onNewContent() } }

    // Keep the end in view whenever it moves away while following (content grew or the viewport shrank).
    LaunchedEffect(follower) {
        snapshotFlow { follower.distanceToEnd() }.collectLatest { distance ->
            if (follower.landed && follower.following && !follower.userScrolling && distance > 0f) {
                follower.snapToEnd()
            }
        }
    }
    return follower
}
