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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
 * Taps the button labelled exactly [label], scrolled fully into view first: a click lands at the
 * node's centre, and on one partly scrolled out of its list it would miss.
 */
fun ComposeTestRule.tap(label: String, timeoutMs: Long = 20_000) {
    val matcher = hasText(label) and hasClickAction()
    awaitIn(matcher, timeoutMs, unmerged = false)
    runCatching { onAllNodes(matcher).onFirst().performScrollTo() }
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
        // Give it a moment to show up where it is before scrolling anything: scrolling the chat
        // stops it following new output.
        if (SystemClock.uptimeMillis() - start > SCROLL_AFTER_MS) scrollListsTo(matcher)
        if (exists(matcher, unmerged)) return
        if (SystemClock.uptimeMillis() > deadline) {
            dumpScreen("waiting for ${matcher.description}")
            throw AssertionError("Not on screen after ${timeoutMs}ms: ${matcher.description}")
        }
        Thread.sleep(POLL_MS)
    }
}

private fun ComposeTestRule.exists(matcher: SemanticsMatcher, unmerged: Boolean): Boolean =
    onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

/** Scrolls each lazy list on screen to its first item matching [matcher], if it has one. */
private fun ComposeTestRule.scrollListsTo(matcher: SemanticsMatcher) {
    val lists = onAllNodes(hasScrollToNodeAction())
    val count = runCatching { lists.fetchSemanticsNodes(atLeastOneRootRequired = false).size }.getOrDefault(0)
    for (i in 0 until count) {
        // Fails when this list has no such item, the common case.
        runCatching { lists[i].performScrollToNode(matcher) }
    }
}

private const val TAG = "LocusTest"
private const val POLL_MS = 200L
private const val SCROLL_AFTER_MS = 2_000L
