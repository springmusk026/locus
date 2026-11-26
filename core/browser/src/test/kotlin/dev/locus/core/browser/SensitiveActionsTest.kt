package dev.locus.core.browser

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensitiveActionsTest {
    private val host = "shop.example"
    // Facts from the helper that ran at document start (PageReply.verified).
    private fun button(label: String) = ElementFacts(label = label, role = "button", tag = "button", verified = true)
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
        val field = ElementFacts(role = "textbox", tag = "input", editable = true, inForm = true, verified = true)
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

    private fun obj(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject

    private val fullFacts = """{"label":"Sign in","role":"button","tag":"button","inputType":"submit","sensitive":false,"submits":true,"inForm":true,""" +
        """"searchLike":false,"formHasPassword":true,"formHasPayment":false,"pageHasPassword":true,"editable":false,"extra":1}"""

    @Test
    fun parsesScriptFacts() {
        val reply = PageReply(obj(fullFacts), verified = true, doc = "d1")
        assertEquals(
            ElementFacts(
                label = "Sign in", role = "button", tag = "button", inputType = "submit", submits = true, inForm = true,
                formHasPassword = true, pageHasPassword = true, verified = true, doc = "d1",
            ),
            ElementFacts.parse(obj(fullFacts), reply),
        )
        // Without a verified reply the same facts are kept, unverified.
        assertEquals(false, ElementFacts.parse(obj(fullFacts))!!.verified)
    }

    @Test
    fun missingOrInvalidFieldsMeanUnknown() {
        // A missing boolean used to read as false (harmless); now the whole answer is unknown.
        assertNull(ElementFacts.parse(obj(fullFacts.replace(""""formHasPayment":false,""", ""))))
        // Wrong types: a string "false", a number, a label that isn't a string.
        assertNull(ElementFacts.parse(obj(fullFacts.replace(""""sensitive":false""", """"sensitive":"false""""))))
        assertNull(ElementFacts.parse(obj(fullFacts.replace(""""submits":true""", """"submits":1"""))))
        assertNull(ElementFacts.parse(obj(fullFacts.replace(""""label":"Sign in"""", """"label":{"x":1}"""))))
        assertNull(ElementFacts.parse(obj("""{"label":"Pay","role":"button","tag":"button"}""")))
        // And the gates ask for an unknown answer.
        assertEquals(SensitiveActions.unverified(host), SensitiveActions.clickGate(ElementFacts.parse(obj("{}")), host))
    }

    @Test
    fun unverifiedFactsCantClearAnAction() {
        // A page that answered the check itself (fake __locus, or the helper didn't run first): a "Pay"
        // button described as a harmless link still asks.
        val disguised = ElementFacts(label = "Next", role = "link", tag = "a")
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.clickGate(disguised, host))
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.submitGate(disguised.copy(searchLike = true), host))
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.keyGate("Enter", disguised, host))
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.typeGate(disguised.copy(editable = true), host))
        assertNull(SensitiveActions.keyGate("Tab", disguised, host))
        // Verified, the same facts go through.
        assertNull(SensitiveActions.clickGate(disguised.copy(verified = true), host))
        assertNull(SensitiveActions.typeGate(disguised.copy(editable = true, verified = true), host))
    }

    @Test
    fun typingFailsClosed() {
        // No facts (element gone, page didn't answer): refused, not let through.
        assertNotNull(SensitiveActions.refuseTyping(null, "hello"))
        // A password field, verified or not.
        assertEquals(SensitiveActions.SENSITIVE_FIELD, SensitiveActions.refuseTyping(ElementFacts(sensitive = true, verified = true), "hunter2"))
        assertEquals(SensitiveActions.SENSITIVE_FIELD, SensitiveActions.refuseTyping(ElementFacts(sensitive = true), "hunter2"))
        assertNull(SensitiveActions.refuseTyping(ElementFacts(editable = true, verified = true), "hello"))
        // A dialog's prompt answer has no element: only the card check applies.
        assertNull(SensitiveActions.refuseText("hello"))
        assertNotNull(SensitiveActions.refuseText("4111 1111 1111 1111"))
    }

    @Test
    fun cardNumbersAreRefusedButOtherLongNumbersAreNot() {
        // Test card numbers of the main networks, together, spaced, dashed or inside other text (#318).
        listOf(
            "4111 1111 1111 1111", "4111111111111111", "5555-5555-5555-4444", "378282246310005", "6011111111111117",
            "3530111333300000", "30569309025904", "2223003122003222", "6200000000000005", "4222222222222",
            "card 4012 8888 8888 1881 exp", "4111 1111 1111 1111 12/27", "pay with 4111 1111 1111 1111 2025",
        ).forEach { assertNotNull(SensitiveActions.refuseText(it), it) }
        listOf(
            "7489203456781", // a 13-digit parcel tracking number
            "9400100000002", // one that passes the Luhn check but no card network starts with 9
            "1732600000000", "1732600000001", // millisecond timestamps, the second passing the Luhn check
            "+49 1512 3456 789", // a phone number written with spaces
            "Order 2024-1121-0000-77",
            "DE89 3704 0044 0532 0130 00", // an IBAN
        ).forEach { assertNull(SensitiveActions.refuseText(it), it) }
    }

    @Test
    fun missingFactsFailClosed() {
        // The page didn't answer the check (or a hostile page broke the helper): the user confirms.
        assertEquals(SensitiveActions.unverified(host), SensitiveActions.clickGate(null, host))
        assertEquals(SensitiveActions.unverified(host), SensitiveActions.submitGate(null, host))
        assertEquals(SensitiveActions.unverified(host), SensitiveActions.keyGate("Enter", null, host))
        assertEquals(SensitiveActions.unverified(host), SensitiveActions.keyGate("Space", null, host))
        // Keys that can't activate or submit anything don't need the facts.
        assertNull(SensitiveActions.keyGate("Tab", null, host))
        assertNull(SensitiveActions.keyGate("ArrowDown", null, host))
    }

    @Test
    fun presentFactsUseTheNormalRules() {
        assertNull(SensitiveActions.clickGate(button("Next page"), host))
        assertNotNull(SensitiveActions.clickGate(button("Pay now"), host))
        assertNull(SensitiveActions.submitGate(submit("Search").copy(searchLike = true), host))
        assertNotNull(SensitiveActions.keyGate("Enter", submit("Go"), host))
    }

    // ---- Other languages (#362): the app ships es, pt and zh, and so do the sites its users visit. ----

    private fun assertOutward(labels: List<String>) = labels.forEach {
        assertNotNull(SensitiveActions.click(button(it), host), it)
        assertNotNull(SensitiveActions.dialog("confirm", "¿$it?", accept = true), it)
    }

    private fun assertLogin(labels: List<String>) = labels.forEach {
        assertNotNull(SensitiveActions.click(button(it).copy(pageHasPassword = true), host), it)
        assertNull(SensitiveActions.click(button(it), host), it)
    }

    @Test
    fun spanishOutwardLabelsAsk() {
        assertOutward(listOf("Pagar ahora", "Comprar", "Enviar", "Eliminar cuenta", "Finalizar compra", "Realizar pedido", "Suscribirse", "Regístrate"))
        assertOutward(listOf("ELIMINAR CUENTA", "Envía el mensaje", "Cancelar suscripción"))
        assertNotNull(SensitiveActions.dialog("confirm", "¿Eliminar cuenta?", accept = true))
    }

    @Test
    fun portugueseOutwardLabelsAsk() {
        assertOutward(listOf("Excluir", "Finalizar compra", "Pagar", "Enviar", "Assinar", "Cadastrar-se", "Fechar pedido", "Apagar conta"))
    }

    @Test
    fun chineseOutwardLabelsAsk() {
        // No spaces between Chinese words, so they match inside longer labels.
        assertOutward(listOf("支付", "立即支付", "删除", "发送", "提交订单", "购买", "刪除", "確認付款"))
    }

    @Test
    fun loginsAskInEveryLanguage() {
        assertLogin(listOf("Iniciar sesión", "INICIAR SESIÓN", "Iniciar sesion", "Entrar", "Acessar", "Iniciar sessão", "登录", "登錄"))
    }

    @Test
    fun ordinaryLocalisedClicksDoNot() {
        // Whole words only: "Pedidos", "Posts" and "Compartimentos" aren't actions.
        listOf("Siguiente página", "Aceptar cookies", "Mostrar más", "Configuración", "Próxima página", "Ver mais", "Configurações",
            "下一页", "更多", "设置", "Mis pedidos", "Compartimentos", "Reservas anteriores").forEach {
            assertNull(SensitiveActions.click(button(it), host), it)
        }
    }
}
