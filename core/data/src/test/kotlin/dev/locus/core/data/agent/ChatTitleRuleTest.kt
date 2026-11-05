package dev.locus.core.data.agent

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A chat that opens with "hi" isn't titled from the greeting, but once from its first real request. */
class ChatTitleRuleTest {
    private fun should(vararg texts: String, title: String? = HelperModel.fallbackTitle(texts.first())) =
        HelperModel.shouldTitle(texts.toList(), title)

    @Test
    fun `small talk is recognised, requests aren't`() {
        listOf("hi", "Hi!", "hey there 👋", "Good morning, Locus", "thanks", "how are you?", "hello").forEach { assertTrue(HelperModel.isSmallTalk(it), it) }
        listOf("hi, redesign my portfolio", "fix the test", "hello world in rust", "(attachment)", "").forEach { assertFalse(HelperModel.isSmallTalk(it), it) }
    }

    @Test
    fun `a first real request is titled, a greeting isn't`() {
        assertTrue(should("Redesign the portfolio site"))
        assertFalse(should("hi"))
        assertTrue(should("(attachment)", title = "photo.jpg"))
    }

    @Test
    fun `after small talk, the first real request is titled once`() {
        assertTrue(should("hi", "Redesign the portfolio site with a dark theme"))
        assertTrue(should("hi", "thanks!", "Now build me a portfolio"))
        // Once: the next request finds a real request earlier in the chat.
        assertFalse(should("hi", "Redesign the portfolio", "Also add a blog", title = "Portfolio Redesign"))
        // More small talk doesn't trigger it.
        assertFalse(should("hi", "how are you?"))
    }

    @Test
    fun `a chat the user renamed keeps its name`() {
        assertFalse(should("hi", "Redesign the portfolio", title = "My site"))
        // Still the automatic name (from "hi", or the empty chat's): it may be replaced.
        assertTrue(should("hi", "Redesign the portfolio", title = "New chat"))
        // The old behaviour titled the greeting itself ("A Friendly Greeting"); that title isn't the automatic
        // name, so it's treated like the user's own and left alone.
        assertFalse(should("hi", "Redesign the portfolio", title = "A Friendly Greeting"))
    }
}
