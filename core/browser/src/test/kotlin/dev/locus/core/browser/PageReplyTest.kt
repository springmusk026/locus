package dev.locus.core.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageReplyTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val host = "shop.example"

    /** What evaluateJavascript hands back for a script whose result is the string [inner]: its JSON encoding. */
    private fun raw(inner: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(inner))

    // A "Pay now" button described as harmless: what a page answering the check itself would say.
    private val harmless = """{"label":"Next","role":"link","tag":"a","inputType":null,"sensitive":false,"submits":false,"inForm":false,""" +
        """"searchLike":false,"formHasPassword":false,"formHasPayment":false,"pageHasPassword":false,"editable":false}"""

    private fun facts(reply: PageReply) = ElementFacts.parse(reply.value as JsonObject, reply)

    @Test
    fun theDocumentStartHelperIsVerified() {
        val reply = PageReply.open(raw("""{"k":"$secret","d":"doc1","r":$harmless}"""), secret)
        assertTrue(reply.verified)
        assertEquals("doc1", reply.doc)
        assertNull(SensitiveActions.clickGate(facts(reply), host))
    }

    @Test
    fun aFakeLocusWithVersion2IsNotTrusted() {
        // The page defined window.__locus = {v: 2, inspect: …, seal: …} before the helper: it doesn't know the secret.
        val guessed = PageReply.open(raw("""{"k":"guess","d":"doc1","r":$harmless}"""), secret)
        assertFalse(guessed.verified)
        assertNull(guessed.doc)
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.clickGate(facts(guessed), host))
        // A fake without seal: the wrapper's plain JSON.stringify({r: …}).
        val unsealed = PageReply.open(raw("""{"r":$harmless}"""), secret)
        assertFalse(unsealed.verified)
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.clickGate(facts(unsealed), host))
        // A fake that answers with something that isn't an envelope at all.
        val bare = PageReply.open(raw(harmless), secret)
        assertFalse(bare.verified)
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.clickGate(facts(bare), host))
    }

    @Test
    fun aFailedInjectionIsNotTrusted() {
        // addDocumentStartJavaScript unsupported or threw: only the fallback copy runs, with an empty secret.
        val fallback = PageReply.open(raw("""{"k":"","d":"doc1","r":$harmless}"""), secret)
        assertFalse(fallback.verified)
        assertEquals(SensitiveActions.untrusted(host), SensitiveActions.clickGate(facts(fallback), host))
        // And a tab with no secret never verifies anything, even an empty key.
        assertFalse(PageReply.open(raw("""{"k":"","d":"doc1","r":1}"""), "").verified)
    }

    @Test
    fun aMaliciousButtonWithSafeLookingFactsAsksUnlessVerified() {
        val payButton = """{"k":"%s","d":"doc1","r":$harmless}"""
        // The secret from another tab doesn't count either.
        val otherTab = PageReply.open(raw(payButton.format(PageReply.newSecret())), secret)
        assertNotNull(SensitiveActions.clickGate(facts(otherTab), host))
        assertNotNull(SensitiveActions.typeGate(facts(otherTab)!!.copy(editable = true), host))
        assertNotNull(SensitiveActions.keyGate("Enter", facts(otherTab), host))
    }

    @Test
    fun noAnswerIsUnanswered() {
        listOf(null, "null", "", "not json", raw("{broken")).forEach {
            val reply = PageReply.open(it, secret)
            assertFalse(reply.verified, "$it")
        }
        assertEquals(JsonNull, PageReply.open(null, secret).value)
        // Text results (outerHTML) stay text.
        assertEquals(JsonPrimitive("<html>"), PageReply.open(raw("""{"k":"$secret","d":"d","r":"<html>"}"""), secret).value)
    }

    @Test
    fun secretsAreFreshHex() {
        val a = PageReply.newSecret()
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(a, PageReply.newSecret())
    }

    @Test
    fun wrapSealsTheExpression() {
        val script = PageReply.wrap("__locus.inspect(\"e1\")")
        assertTrue("return __locus.inspect(\"e1\");" in script)
        assertTrue("L.seal(r)" in script)
    }

    private fun assertNotNull(value: Any?) = kotlin.test.assertNotNull(value)
}
