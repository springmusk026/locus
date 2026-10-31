package dev.locus.core.browser

import dev.locus.core.agent.ToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserArgsTest {
    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun refsAreValidatedAndBracketsTolerated() {
        assertEquals("e12", BrowserArgs.ref(args("""{"ref":"[e12]"}""")))
        assertTrue("latest snapshot" in assertFailsWith<ToolException> { BrowserArgs.ref(args("""{"ref":"#submit"}""")) }.message!!)
        assertFailsWith<ToolException> { BrowserArgs.ref(args("{}")) }
    }

    @Test
    fun formFieldsAcceptStringsNumbersAndBooleans() {
        val fields = BrowserArgs.formFields(args("""{"fields":[{"ref":"e1","value":"Ann"},{"ref":"e2","value":true},{"ref":"e3","value":3}]}"""))
        assertEquals(listOf("e1" to "Ann", "e2" to "true", "e3" to "3"), fields)
    }

    @Test
    fun formFieldsExplainMistakes() {
        assertTrue("fields[0]" in assertFailsWith<ToolException> { BrowserArgs.formFields(args("""{"fields":[{"ref":"x","value":"a"}]}""")) }.message!!)
        assertTrue("no value" in assertFailsWith<ToolException> { BrowserArgs.formFields(args("""{"fields":[{"ref":"e1"}]}""")) }.message!!)
        assertTrue("twice" in assertFailsWith<ToolException> { BrowserArgs.formFields(args("""{"fields":[{"ref":"e1","value":"a"},{"ref":"e1","value":"b"}]}""")) }.message!!)
        assertFailsWith<ToolException> { BrowserArgs.formFields(args("""{"fields":[]}""")) }
        val many = (1..31).joinToString(",") { """{"ref":"e$it","value":"x"}""" }
        assertTrue("At most" in assertFailsWith<ToolException> { BrowserArgs.formFields(args("""{"fields":[$many]}""")) }.message!!)
    }

    @Test
    fun waitNeedsSomethingAndIsCapped() {
        assertEquals(BrowserArgs.Wait("Done", null, 10), BrowserArgs.waitSpec(args("""{"text":"Done"}""")))
        assertEquals(BrowserArgs.Wait(null, "Loading", 30), BrowserArgs.waitSpec(args("""{"gone":"Loading","seconds":120}""")))
        assertEquals(BrowserArgs.Wait(null, null, 2), BrowserArgs.waitSpec(args("""{"seconds":2}""")))
        assertFailsWith<ToolException> { BrowserArgs.waitSpec(args("{}")) }
        assertFailsWith<ToolException> { BrowserArgs.waitSpec(args("""{"text":"a","gone":"b"}""")) }
    }

    @Test
    fun keysAreNormalised() {
        assertEquals("Escape", BrowserArgs.key("esc"))
        assertEquals("ArrowDown", BrowserArgs.key("Arrow Down"))
        assertEquals("Enter", BrowserArgs.key("RETURN"))
        assertEquals("a", BrowserArgs.key("a"))
        assertNull(BrowserArgs.key("Ctrl+C"))
    }

    @Test
    fun hostsForApprovalCards() {
        assertEquals("shop.example.com", BrowserArgs.host("https://user@shop.example.com/cart?x=1"))
        assertEquals("localhost:3000", BrowserArgs.host("http://localhost:3000"))
        assertEquals("this page", BrowserArgs.host(""))
    }

    @Test
    fun loadErrorsSayWhatToDo() {
        val refused = LoadErrors.explain("http://localhost:3000/", "net::ERR_CONNECTION_REFUSED")
        assertTrue("connection was refused" in refused && "linux_service_list" in refused, refused)
        val dns = LoadErrors.explain("https://exampel.com/", "net::ERR_NAME_NOT_RESOLVED")
        assertTrue("could not be found" in dns && "linux_service" !in dns, dns)
        assertTrue("404 Not Found" in LoadErrors.httpStatus("https://a.b/x", 404, "Not Found"))
    }
}
