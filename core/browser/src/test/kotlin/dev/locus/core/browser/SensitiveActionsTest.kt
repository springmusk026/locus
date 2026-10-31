package dev.locus.core.browser

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitiveActionsTest {
    private val host = "shop.example"
    private fun button(label: String) = ElementFacts(label = label, role = "button", tag = "button")
    private fun submit(label: String = "Go") = button(label).copy(submits = true, inForm = true)

    @Test
    fun outwardLabelsAlwaysAsk() {
        listOf("Pay now", "Place order", "Delete repository", "Send", "Transfer funds", "Publish", "Subscribe").forEach {
            assertNotNull(SensitiveActions.click(button(it), host), it)
        }
    }

    @Test
    fun ordinaryClicksDoNot() {
        listOf("Next page", "Accept all cookies", "Show more", "Menu", "Posts", "Settings").forEach {
            assertNull(SensitiveActions.click(button(it), host), it)
        }
    }

    @Test
    fun loginsAsk() {
        val login = submit("Sign in").copy(formHasPassword = true)
        assertTrue("signs in" in SensitiveActions.click(login, host)!!)
        // A script-driven login button outside any form, on a page with a password field.
        assertNotNull(SensitiveActions.click(button("Log in").copy(pageHasPassword = true), host))
        // Without a password on the page, "Sign in" just navigates to the login page.
        assertNull(SensitiveActions.click(button("Sign in"), host))
    }

    @Test
    fun formSubmitsAskButSearchDoesNot() {
        assertNotNull(SensitiveActions.click(submit("Go"), host))
        assertNull(SensitiveActions.click(submit("Go").copy(searchLike = true), host))
        assertTrue("payment" in SensitiveActions.submit(submit().copy(formHasPayment = true), host)!!)
    }

    @Test
    fun enterInAFieldIsASubmit() {
        val field = ElementFacts(role = "textbox", tag = "input", editable = true, inForm = true)
        assertNotNull(SensitiveActions.key("Enter", field, host))
        assertNull(SensitiveActions.key("Enter", field.copy(searchLike = true), host))
        // A chat box outside a form: Enter usually sends.
        assertNotNull(SensitiveActions.key("Enter", field.copy(inForm = false), host))
        assertNull(SensitiveActions.key("Tab", field, host))
        assertNull(SensitiveActions.key("Enter", ElementFacts(role = "page", tag = "body"), host))
        assertNotNull(SensitiveActions.key("Space", button("Buy now"), host))
    }

    @Test
    fun outwardConfirmDialogsAsk() {
        assertNotNull(SensitiveActions.dialog("confirm", "Delete this file permanently?", accept = true))
        assertNull(SensitiveActions.dialog("confirm", "Delete this file permanently?", accept = false))
        assertNull(SensitiveActions.dialog("confirm", "Leave this page?", accept = true))
    }

    @Test
    fun secretsAreNeverTyped() {
        assertNotNull(SensitiveActions.refuseTyping(null, "4111 1111 1111 1111"))
        assertNotNull(SensitiveActions.refuseTyping(ElementFacts(sensitive = true), "hunter2"))
        assertNull(SensitiveActions.refuseTyping(ElementFacts(), "hello"))
    }

    @Test
    fun parsesScriptFacts() {
        val facts = ElementFacts.parse(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"label":"Sign in","role":"button","tag":"button","submits":true,"inForm":true,"formHasPassword":true,"extra":1}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertEquals(ElementFacts(label = "Sign in", role = "button", tag = "button", submits = true, inForm = true, formHasPassword = true), facts)
    }
}
