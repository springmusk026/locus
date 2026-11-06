package dev.locus.browser

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.locus.core.browser.DropdownPage
import dev.locus.core.browser.DropdownSelect
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The page helper (core/browser's locus-agent.js) on every common dropdown pattern, in a real WebView:
 * hand-written fixture pages in androidTest/assets/dropdowns (native selects, a portal listbox in a
 * modal, an async autocomplete, a virtualised list, Bootstrap-style and hover menus, shadow DOM and a
 * same-origin frame, overlays, re-rendered options, a date field). browser_select's flow
 * ([DropdownSelect]) runs against it with scripted taps, the path the agent takes when the browser
 * isn't on screen. docs/design/browser.md, "Forms and dropdowns".
 *
 * Not Hilt: the WebView is created here, laid out at the screen's size as AgentBrowser does for a tab
 * that isn't on screen.
 */
@RunWith(AndroidJUnit4::class)
class DropdownsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var web: WebView? = null
    private val helper: String by lazy {
        instrumentation.targetContext.assets.open("locus-agent.js").bufferedReader().use { it.readText() }.replace("__LOCUS_SECRET__", "")
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { web?.destroy() }
    }

    // ---- Native <select> ----------------------------------------------------------------------------

    @Test
    fun nativeSelectsListTheirOptions() {
        load("native.html")
        val country = element("Country")
        assertEquals("combobox", country.getString("role"))
        assertEquals(6, country.getInt("optionCount"))
        assertTrue("Asia: North Korea (disabled)" in strings(country.getJSONArray("options")), country.toString())
        val toppings = element("Toppings")
        assertTrue(toppings.optBoolean("multiple"))
        assertEquals("Cheese", toppings.getString("selected"))
        assertEquals(listOf("Kathmandu", "Pokhara"), strings(element("City").getJSONArray("options")))
    }

    @Test
    fun nativeSelectIsSetWithInputThenChange() {
        load("native.html")
        val ref = element("Country").getString("ref")
        val out = assertIs<DropdownSelect.Outcome.Done>(select(ref, "nepal"))
        assertEquals("Selected “Nepal” in [$ref].", out.note)
        assertEquals("np", js("document.getElementById('country').value"))
        assertEquals(listOf("country:input:true", "country:change:true"), strings(js("window.events") as JSONArray))
    }

    @Test
    fun disabledAndMissingOptionsAreRefusedWithReasons() {
        load("native.html")
        val ref = element("Country").getString("ref")
        assertTrue("disabled" in assertIs<DropdownSelect.Outcome.Failed>(select(ref, "North Korea")).error)
        assertTrue("disabled" in assertIs<DropdownSelect.Outcome.Failed>(select(ref, "France")).error)
        val missing = assertIs<DropdownSelect.Outcome.Failed>(select(ref, "Mars")).error
        assertTrue("Options:" in missing && "Asia: Nepal" in missing, missing)
        assertEquals("", js("document.getElementById('country').value"))
    }

    @Test
    fun multiSelectTakesSeveralOptions() {
        load("native.html")
        assertIs<DropdownSelect.Outcome.Done>(select(element("Toppings").getString("ref"), "Ham | Olives"))
        assertEquals("Ham,Olives", js("Array.from(document.getElementById('toppings').selectedOptions).map(o => o.text).join()"))
    }

    // ---- Portals, modals, long pages ------------------------------------------------------------------

    @Test
    fun aPortalListOutsideAModalIsListedUnderItsControl() {
        load("portal.html")
        val snap = snapshot()
        assertEquals("Edit address", snap.getString("modal"))
        val trigger = element("Country", snap)
        assertNull(click(trigger.getString("ref")))
        val items = items(snapshot())
        val at = items.indexOfFirst { it.optString("ref") == trigger.getString("ref") }
        val head = items[at + 1]
        assertEquals("listbox", head.getString("role"))
        assertEquals(trigger.getString("ref"), head.getString("from"))
        assertEquals(5, head.getInt("count"))
        assertEquals("Afghanistan", items[at + 2].getString("name"))
        assertEquals(head.getString("popup"), items[at + 2].getString("in"))
    }

    @Test
    fun aPortalAfterTheElementCapIsStillListed() {
        load("portal.html")
        js("document.querySelector('[role=dialog]').removeAttribute('aria-modal'), true")
        assertNull(click(element("Country").getString("ref")))
        val snap = snapshot()
        assertTrue(items(snap).any { it.optString("role") == "listbox" && it.has("popup") }, snap.toString())
        assertTrue(items(snap).any { it.optString("name") == "Nepal" && it.has("in") })
    }

    @Test
    fun aPortalListboxThatOpensOnMousedownIsSelected() {
        load("portal.html")
        val ref = element("Country").getString("ref")
        val out = assertIs<DropdownSelect.Outcome.Done>(select(ref, "Nepal"))
        assertTrue(out.note.startsWith("Selected “Nepal” in [$ref]"), out.note)
        assertEquals("Nepal", js("document.getElementById('value').textContent"))
        assertEquals("false", js("document.getElementById('trigger').getAttribute('aria-expanded')"))
    }

    // ---- Autocomplete -----------------------------------------------------------------------------------

    @Test
    fun anAutocompleteIsTypedIntoAndItsAsyncSuggestionPicked() {
        load("autocomplete.html")
        val ref = element("Place").getString("ref")
        val out = assertIs<DropdownSelect.Outcome.Done>(select(ref, "Kathmandu"))
        assertTrue("typed to filter" in out.note, out.note)
        // The list keeps focus in the field on mousedown, so the field's blur never closed it first.
        assertEquals("Kathmandu", js("window.picked"))
        assertEquals("Kathmandu", js("document.getElementById('place').value"))
    }

    @Test
    fun anAmbiguousSuggestionAsksForTheExactText() {
        load("autocomplete.html")
        val out = assertIs<DropdownSelect.Outcome.Failed>(select(element("Place").getString("ref"), "Kath"))
        assertTrue(out.error.startsWith("Several options match \"Kath\": Kathmandu | Kathmandu Durbar Square"), out.error)
    }

    // ---- Virtualised lists ------------------------------------------------------------------------------

    @Test
    fun aVirtualisedListIsScrolledUntilTheOptionExists() {
        load("virtual.html")
        val ref = element("Item: none").getString("ref")
        val out = assertIs<DropdownSelect.Outcome.Done>(select(ref, "Item 377"))
        assertTrue("scrolled the list" in out.note, out.note)
        assertEquals("Item: Item 377", js("document.getElementById('open').textContent"))
    }

    @Test
    fun aVirtualisedListReportsItsFullSize() {
        load("virtual.html")
        assertNull(click(element("Item: none").getString("ref")))
        val head = items(snapshot()).first { it.has("popup") }
        assertEquals(500, head.getInt("count"))
        assertTrue(head.getInt("listed") < 20)
        assertTrue(head.getBoolean("scrolls"))
    }

    // ---- Menus --------------------------------------------------------------------------------------------

    @Test
    fun aBootstrapStyleMenuWithoutAriaControlsIsFoundNextToItsToggle() {
        load("menus.html")
        val ref = element("Sort: Relevance").getString("ref")
        assertIs<DropdownSelect.Outcome.Done>(select(ref, "Price: high to low"))
        assertEquals("Sort: Price: high to low", js("document.getElementById('sort').textContent"))
    }

    @Test
    fun aHoverMenuOpensOnTheTapsEnterEvents() {
        load("menus.html")
        assertNull(click(element("Products").getString("ref")))
        val items = items(snapshot())
        val head = items.first { it.has("popup") && it.optString("role") == "menu" }
        val laptops = items.first { it.optString("name") == "Laptops" }
        assertEquals(head.getString("popup"), laptops.getString("in"))
        assertNull(click(laptops.getString("ref")))
        assertEquals("Laptops", js("window.picked"))
    }

    // ---- Shadow DOM and frames ----------------------------------------------------------------------------

    @Test
    fun aComboboxInsideAShadowRootIsSelected() {
        load("shadow.html")
        val ref = element("Colour: none").getString("ref")
        assertNull(click(ref))
        assertTrue(items(snapshot()).any { it.has("popup") && it.optString("role") == "listbox" })
        // Open already: the flow picks from the list without closing it first.
        assertIs<DropdownSelect.Outcome.Done>(select(ref, "Blue"))
        assertEquals("Colour: Blue", js("document.getElementById('fancy').shadowRoot.getElementById('t').textContent"))
    }

    @Test
    fun aSelectInASameOriginFrameIsSet() {
        load("shadow.html")
        val ref = element("Size").getString("ref")
        assertIs<DropdownSelect.Outcome.Done>(select(ref, "Large"))
        assertEquals("Large", js("document.getElementById('frame').contentDocument.getElementById('s').value"))
    }

    // ---- Overlays, re-renders, dates -------------------------------------------------------------------------

    @Test
    fun aCoveredElementIsReportedAndAPartlyCoveredOneTappedWhereItIsFree() {
        load("overlays.html")
        val covered = js("__locus.rect(${q(element("Covered").getString("ref"))})") as JSONObject
        assertTrue(covered.optString("covered").isNotEmpty(), covered.toString())
        val partly = element("Partly covered").getString("ref")
        val rect = js("__locus.rect(${q(partly)})") as JSONObject
        assertTrue(rect.isNull("covered"), rect.toString())
        assertNull(click(partly))
        assertEquals(1, (js("window.partlyTaps") as Number).toInt())
    }

    @Test
    fun aReRenderedOptionKeepsItsRef() {
        load("overlays.html")
        val mango = element("Mango").getString("ref")
        js("build(), true")
        assertNull(click(mango))
        assertEquals("Mango", js("window.fruit"))
    }

    @Test
    fun aDateFieldSaysWhichFormatItTakes() {
        load("overlays.html")
        val ref = element("When").getString("ref")
        val wrong = js("__locus.type(${q(ref)}, '06/11/2026', false)") as JSONObject
        assertTrue("YYYY-MM-DD" in wrong.getString("error"), wrong.toString())
        val right = js("__locus.type(${q(ref)}, '2026-11-06', false)") as JSONObject
        assertTrue(right.optBoolean("ok"), right.toString())
        assertEquals("2026-11-06", js("document.getElementById('when').value"))
    }

    // ---- Plumbing ----------------------------------------------------------------------------------------------

    private fun load(name: String) {
        val html = instrumentation.context.assets.open("dropdowns/$name").bufferedReader().use { it.readText() }
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = loaded.countDown()
            }
            val metrics = instrumentation.targetContext.resources.displayMetrics
            view.measure(
                View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            view.loadDataWithBaseURL("https://fixture.locus.test/$name", html, "text/html", "utf-8", null)
            web = view
        }
        assertTrue(loaded.await(20, TimeUnit.SECONDS), "$name didn't load")
        waitSettled()
    }

    /** Runs [expression] with the helper in place, as AgentBrowser does, and returns its result. */
    private fun js(expression: String): Any? {
        val done = CountDownLatch(1)
        var raw: String? = null
        val script = "$helper\n;(function(){var r=(function(){ return $expression; })();return window.__locus.seal(r);})()"
        instrumentation.runOnMainSync { web!!.evaluateJavascript(script) { raw = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS), "no answer to $expression")
        val envelope = JSONTokener(raw).nextValue() as? String ?: error("not an envelope: $raw")
        return JSONObject(envelope).opt("r").takeUnless { it == JSONObject.NULL }
    }

    private fun q(s: String) = JSONObject.quote(s)

    private fun snapshot() = js("__locus.snapshot(false)") as JSONObject

    private fun items(snap: JSONObject) = (0 until snap.getJSONArray("items").length()).map { snap.getJSONArray("items").getJSONObject(it) }

    /** The element named [name] (or "[name] …", as a label followed by the value it shows). */
    private fun element(name: String, snap: JSONObject = snapshot()): JSONObject = assertNotNull(
        items(snap).firstOrNull { it.has("ref") && (it.optString("name") == name || it.optString("name").startsWith("$name ")) },
        "no element \"$name\" in $snap",
    )

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun waitSettled() {
        Thread.sleep(300)
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && js("__locus.settled()") != true) Thread.sleep(100)
    }

    /** A scripted tap with the checks AgentBrowser makes first (not covered, not hidden), then a settle. */
    private fun click(ref: String): String? {
        val rect = js("__locus.rect(${q(ref)})") as? JSONObject ?: return "no answer"
        rect.optString("error").takeIf { it.isNotEmpty() }?.let { return it }
        if (!rect.isNull("covered")) return "$ref is covered by ${rect.getString("covered")}"
        (js("__locus.click(${q(ref)})") as? JSONObject)?.optString("error")?.takeIf { it.isNotEmpty() }?.let { return it }
        waitSettled()
        return null
    }

    private fun select(ref: String, value: String): DropdownSelect.Outcome = runBlocking {
        val page = object : DropdownPage {
            fun obj(x: Any?): JsonObject? = (x as? JSONObject)?.let { Json.parseToJsonElement(it.toString()).jsonObject }
            override suspend fun start(ref: String, value: String) = obj(js("__locus.selectStart(${q(ref)}, ${q(value)})"))
            override suspend fun find(ref: String, value: String, fresh: Boolean) = obj(js("__locus.selectFind(${q(ref)}, ${q(value)}, $fresh)"))
            override suspend fun click(ref: String) = this@DropdownsTest.click(ref)
            override suspend fun check(ref: String, text: String) = obj(js("__locus.selectCheck(${q(ref)}, ${q(text)})"))
            override suspend fun settle() = waitSettled()
            override suspend fun pause(ms: Long) = delay(ms)
        }
        DropdownSelect.run(page, ref, value)
    }
}
