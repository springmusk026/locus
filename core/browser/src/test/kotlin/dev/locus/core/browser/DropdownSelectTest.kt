package dev.locus.core.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The browser_select flow against a scripted page: what it asks the page, in what order, and what it reports. */
class DropdownSelectTest {
    private class FakePage(
        val start: String,
        val finds: List<String> = emptyList(),
        val check: String = """{"ok":true,"shows":"Nepal"}""",
        val clickError: (String) -> String? = { null },
    ) : DropdownPage {
        val log = mutableListOf<String>()
        private var next = 0
        var paused = 0L

        private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

        override suspend fun start(ref: String, value: String): JsonObject { log += "start $ref"; return obj(start) }
        override suspend fun find(ref: String, value: String, fresh: Boolean): JsonObject {
            log += "find" + if (fresh) " fresh" else ""
            return obj(finds.getOrElse(next++) { finds.last() })
        }
        override suspend fun click(ref: String): String? { log += "click $ref"; return clickError(ref) }
        override suspend fun check(ref: String, text: String): JsonObject { log += "check $ref"; return obj(check) }
        override suspend fun settle() { log += "settle" }
        override suspend fun pause(ms: Long) { paused += ms }
    }

    private fun run(page: FakePage, value: String = "Nepal") = runBlocking { DropdownSelect.run(page, "e12", value) }

    @Test
    fun nativeSelectIsSetInOneStep() {
        val page = FakePage("""{"ok":true,"native":true,"selected":"Nepal"}""")
        val out = run(page)
        assertEquals(DropdownSelect.Outcome.Done("Selected “Nepal” in [e12]."), out)
        assertEquals(listOf("start e12"), page.log)
    }

    @Test
    fun nativeSelectErrorsPassThrough() {
        val page = FakePage("""{"error":"No option \"Mars\" in e12 (3 options). Options: A | B | C"}""")
        val out = assertIs<DropdownSelect.Outcome.Failed>(run(page, "Mars"))
        assertTrue("Options: A | B | C" in out.error)
    }

    @Test
    fun closedCustomDropdownIsOpenedThenTheOptionTappedAndChecked() {
        val page = FakePage(
            """{"custom":true,"open":false}""",
            listOf("""{"wait":true,"none":true}""", """{"found":"e40","text":"Nepal"}"""),
        )
        val out = assertIs<DropdownSelect.Outcome.Done>(run(page))
        assertEquals(listOf("start e12", "click e12", "find fresh", "find", "click e40", "check e12"), page.log)
        assertEquals("Selected “Nepal” in [e12] (opened [e12]).", out.note)
    }

    @Test
    fun anOpenAutocompleteIsTypedIntoAndNotClicked() {
        val page = FakePage(
            """{"custom":true,"open":true}""",
            listOf("""{"typed":true}""", """{"wait":true}""", """{"found":"e41","text":"Nepal"}"""),
        )
        val out = assertIs<DropdownSelect.Outcome.Done>(run(page))
        assertEquals(listOf("start e12", "find fresh", "settle", "find", "find", "click e41", "check e12"), page.log)
        assertTrue("typed to filter" in out.note, out.note)
    }

    @Test
    fun longListsAreScrolledUntilTheOptionRenders() {
        val page = FakePage(
            """{"custom":true,"open":true}""",
            List(5) { """{"scrolled":true}""" } + """{"found":"e90","text":"Zimbabwe"}""",
        )
        val out = assertIs<DropdownSelect.Outcome.Done>(run(page, "Zimbabwe"))
        assertTrue("scrolled the list 5×" in out.note, out.note)
        assertEquals(5 * DropdownSelect.STEP_MS, page.paused)
    }

    @Test
    fun noPopupAfterOpeningSaysWhatToTry() {
        val page = FakePage("""{"custom":true,"open":false}""", listOf("""{"wait":true,"none":true}"""))
        val out = assertIs<DropdownSelect.Outcome.Failed>(run(page))
        assertTrue(out.error.startsWith("Tapped e12, but no list of options appeared"), out.error)
        assertTrue(page.paused >= DropdownSelect.WAIT_MS)
    }

    @Test
    fun aMismatchAfterTappingIsReportedNotHidden() {
        val page = FakePage(
            """{"custom":true,"open":true}""",
            listOf("""{"found":"e40","text":"Nepal"}"""),
            check = """{"ok":false,"shows":"Choose a country","open":true}""",
        )
        val out = assertIs<DropdownSelect.Outcome.Done>(run(page))
        assertTrue("shows “Choose a country” and its list is still open" in out.note, out.note)
    }

    @Test
    fun anOptionsOwnRefIsJustClicked() {
        val page = FakePage("""{"option":"e40","text":"Nepal"}""")
        assertIs<DropdownSelect.Outcome.Done>(run(page))
        assertEquals(listOf("start e12", "click e40"), page.log)
    }

    @Test
    fun aCoveredOptionReportsWhy() {
        val page = FakePage(
            """{"custom":true,"open":true}""",
            listOf("""{"found":"e40","text":"Nepal"}"""),
            clickError = { if (it == "e40") "e40 is covered by div \"Cookies\"." else null },
        )
        val out = assertIs<DropdownSelect.Outcome.Failed>(run(page))
        assertTrue("couldn't tap it: e40 is covered" in out.error, out.error)
    }

    @Test
    fun aBlankValueIsRefusedBeforeTouchingThePage() {
        val page = FakePage("""{"custom":true}""")
        assertIs<DropdownSelect.Outcome.Failed>(run(page, " "))
        assertTrue(page.log.isEmpty())
    }
}
