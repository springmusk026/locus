package dev.locus.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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

/** Waits until a node matching [matcher] exists (in any window: sheets and dialogs too). */
fun ComposeTestRule.awaitNode(matcher: SemanticsMatcher, timeoutMs: Long = 20_000) {
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
}

/** Waits until some text containing [text] is on screen. */
fun ComposeTestRule.awaitText(text: String, timeoutMs: Long = 20_000) = awaitNode(hasText(text, substring = true), timeoutMs)

/** Whether some text containing [text] is on screen now. */
fun ComposeTestRule.shows(text: String): Boolean = onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/** Types [text] into the chat's composer and taps the button described by [button] (see [SendButton]). */
fun ComposeTestRule.send(text: String, button: String = SendButton.SEND) {
    awaitNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, COMPOSER_TAG))
    onNodeWithTag(COMPOSER_TAG).performTextReplacement(text)
    val matcher = hasContentDescription(button, substring = true) and hasClickAction()
    awaitNode(matcher)
    onAllNodes(matcher).onFirst().performClick()
}

/** Taps the button labelled exactly [label]. */
fun ComposeTestRule.tap(label: String, timeoutMs: Long = 20_000) {
    val matcher = hasText(label) and hasClickAction()
    waitUntil(timeoutMs) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    onAllNodes(matcher).onFirst().performClick()
}
