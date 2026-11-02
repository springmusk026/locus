package dev.locus.testing

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import dev.locus.feature.chat.COMPOSER_TAG

/** The composer's send button, by what it says it does (LocusComposer). */
object SendButton {
    /** Nothing running: a new message. */
    const val SEND = "Send"

    /** While the agent works: a mid-task note, read after the current step. */
    const val TELL = "Tell Locus: read after the current step"

    /** `/btw` or the Aside switch: a side question. */
    const val SIDE = "Ask side question"
}

/**
 * Waits until a node matching [matcher] exists (in any window: sheets and dialogs too).
 *
 * The chat is a lazy list: an item scrolled out of view isn't composed, so it has no semantics node.
 * On a small screen with the keyboard up, the reply sits above the Changes card and the receipt, out
 * of view. So while waiting, this also scrolls any lazy list to a matching item. On a timeout it logs
 * the screen (tag LocusTest) before failing.
 */
fun ComposeTestRule.awaitNode(matcher: SemanticsMatcher, timeoutMs: Long = 20_000) = awaitIn(matcher, timeoutMs, unmerged = true)

/** Waits until some text containing [text] is on screen. */
fun ComposeTestRule.awaitText(text: String, timeoutMs: Long = 20_000) = awaitNode(hasText(text, substring = true), timeoutMs)

/** Whether some text containing [text] is on screen now. */
fun ComposeTestRule.shows(text: String): Boolean = exists(hasText(text, substring = true), unmerged = true)

/** Types [text] into the chat's composer and taps the button described by [button] (see [SendButton]). */
fun ComposeTestRule.send(text: String, button: String = SendButton.SEND) {
    awaitNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, COMPOSER_TAG))
    onNodeWithTag(COMPOSER_TAG).performTextReplacement(text)
    val matcher = hasContentDescription(button, substring = true) and hasClickAction()
    awaitNode(matcher)
    onAllNodes(matcher).onFirst().performClick()
}

/**
 * Taps the button labelled exactly [label], dragged fully into view first: a click lands at the
 * node's centre, and on a button partly scrolled out of its list it would miss.
 */
fun ComposeTestRule.tap(label: String, timeoutMs: Long = 20_000) {
    val matcher = hasText(label) and hasClickAction()
    awaitIn(matcher, timeoutMs, unmerged = false)
    dragIntoView(matcher)
    onAllNodes(matcher).onFirst().performClick()
}

/** Logs every window's semantics tree (tag LocusTest), so a failed wait shows what was on screen. */
fun ComposeTestRule.dumpScreen(why: String) {
    Log.w(TAG, "Screen when $why:")
    runCatching { onAllNodes(isRoot(), useUnmergedTree = true).printToLog(TAG, maxDepth = Int.MAX_VALUE) }
        .onFailure { Log.w(TAG, "Couldn't dump the screen", it) }
}

private fun ComposeTestRule.awaitIn(matcher: SemanticsMatcher, timeoutMs: Long, unmerged: Boolean) {
    val start = SystemClock.uptimeMillis()
    val deadline = start + timeoutMs
    while (true) {
        if (exists(matcher, unmerged)) return
        // Give it a moment to show up where it is before dragging anything: a drag stops the chat
        // following new output.
        if (SystemClock.uptimeMillis() - start > SEARCH_AFTER_MS && dragListsTo(matcher, unmerged)) return
        if (SystemClock.uptimeMillis() > deadline) {
            dumpScreen("waiting for ${matcher.description}")
            throw AssertionError("Not on screen after ${timeoutMs}ms: ${matcher.description}")
        }
        Thread.sleep(POLL_MS)
    }
}

private fun ComposeTestRule.exists(matcher: SemanticsMatcher, unmerged: Boolean): Boolean =
    onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

/** The lazy lists on screen that can scroll at all. */
private fun ComposeTestRule.scrollableLists(): List<SemanticsNodeInteraction> {
    val lists = onAllNodes(hasScrollToNodeAction())
    val nodes = runCatching { lists.fetchSemanticsNodes(atLeastOneRootRequired = false) }.getOrDefault(emptyList())
    return nodes.indices.filter { i -> nodes[i].verticalRange()?.let { it.maxValue() > 0f } == true }.map { lists[it] }
}

private fun SemanticsNode.verticalRange() = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)

/**
 * Looks through each lazy list for an item matching [matcher] by dragging it, up towards older
 * items and then back down, the way a person would. Not performScrollToNode: the chat follows its
 * newest content and only a drag (a user's scroll) makes it stop, so a programmatic scroll is
 * undone at once (TranscriptFollower).
 */
private fun ComposeTestRule.dragListsTo(matcher: SemanticsMatcher, unmerged: Boolean): Boolean {
    for (list in scrollableLists()) {
        for (towardsStart in listOf(true, false)) {
            repeat(MAX_DRAGS) {
                if (exists(matcher, unmerged)) return true
                val range = runCatching { list.fetchSemanticsNode().verticalRange() }.getOrNull() ?: return@repeat
                val atEnd = if (towardsStart) range.value() <= 0f else range.value() >= range.maxValue()
                if (atEnd) return@repeat
                list.performTouchInput {
                    val from = if (towardsStart) height * 0.3f else height * 0.7f
                    val to = if (towardsStart) height * 0.7f else height * 0.3f
                    // Slow enough not to fling, so each drag moves the list by about its distance.
                    swipe(Offset(centerX, from), Offset(centerX, to), durationMillis = 400)
                }
                waitForIdle()
            }
        }
    }
    return exists(matcher, unmerged)
}

/** Drags the list holding [matcher]'s first node until that node is wholly inside it. */
private fun ComposeTestRule.dragIntoView(matcher: SemanticsMatcher) {
    val list = scrollableLists().firstOrNull() ?: return
    repeat(MAX_DRAGS) {
        val node = onAllNodes(matcher).onFirst().fetchSemanticsNode().boundsInRoot
        val box = list.fetchSemanticsNode().boundsInRoot
        val dy = when {
            node.top < box.top -> box.top - node.top + EDGE_PX // Above the list: drag the content down.
            node.bottom > box.bottom -> box.bottom - node.bottom - EDGE_PX // Below: drag it up.
            else -> return
        }
        if (node.left >= box.right || node.right <= box.left) return // Not in this list.
        list.performTouchInput {
            val y = if (dy > 0) height * 0.2f else height * 0.8f
            val to = (y + dy).coerceIn(1f, height - 1f)
            swipe(Offset(centerX, y), Offset(centerX, to), durationMillis = 400)
        }
        waitForIdle()
    }
}

private const val TAG = "LocusTest"
private const val POLL_MS = 200L
private const val SEARCH_AFTER_MS = 2_000L
private const val MAX_DRAGS = 30
private const val EDGE_PX = 16f
