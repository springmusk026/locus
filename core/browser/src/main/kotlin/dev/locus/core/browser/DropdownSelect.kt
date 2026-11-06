package dev.locus.core.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The page steps [DropdownSelect] drives, each one script call (or tap) on the page. [AgentBrowser]
 * runs them against the WebView; tests run them against a fake or a real WebView.
 */
interface DropdownPage {
    /** `__locus.selectStart`: sets a native select, or says what kind of control [ref] is. */
    suspend fun start(ref: String, value: String): JsonObject?

    /** `__locus.selectFind`: looks for the option in the popup [ref] opened (types or scrolls when it can't see it). */
    suspend fun find(ref: String, value: String, fresh: Boolean): JsonObject?

    /** Taps [ref] the way browser_click does (actionability checks, then a real or scripted tap) and lets the page settle. Null or why not. */
    suspend fun click(ref: String): String?

    /** `__locus.selectCheck`: what [ref] shows now. */
    suspend fun check(ref: String, text: String): JsonObject?

    /** Lets the page settle after typing (filtering, suggestions fetched from the server). */
    suspend fun settle()

    suspend fun pause(ms: Long)
}

/**
 * browser_select for every kind of dropdown (docs/design/browser.md, "Forms and dropdowns"):
 * - a native `<select>` is set in the page, as Playwright's selectOption does;
 * - an option's own ref is clicked;
 * - anything else (an ARIA combobox, a listbox button, a menu button, an autocomplete field) is opened
 *   with a tap, then its popup is found wherever it is (aria-controls/aria-owns, the active option's
 *   list, or what appeared), the option is looked for by text, the list's search field is typed into
 *   or the list scrolled (virtualised lists render only what is visible) until it shows up, it is
 *   tapped, and what the control shows afterwards is checked.
 */
object DropdownSelect {
    sealed interface Outcome {
        data class Done(val note: String) : Outcome
        data class Failed(val error: String) : Outcome
    }

    /** Most steps of finding an option: 60 scrolls of a long list plus waiting for a filter. */
    const val MAX_STEPS = 90

    /** How long to wait for a popup to appear, or for a typed filter to show results. */
    const val WAIT_MS = 4_000L
    const val STEP_MS = 250L

    suspend fun run(page: DropdownPage, ref: String, value: String): Outcome {
        if (value.isBlank()) return Outcome.Failed("Give the option's text or value in 'value'.")
        val start = page.start(ref, value) ?: return Outcome.Failed("Could not reach $ref. Take a new snapshot.")
        start.str("error")?.let { return Outcome.Failed(it) }
        if (start.flag("native")) {
            return Outcome.Done("Selected “${start.str("selected") ?: value}” in [$ref].")
        }
        start.str("option")?.let { option ->
            page.click(option)?.let { return Outcome.Failed(it) }
            return Outcome.Done("Clicked the option “${start.str("text") ?: value}” [$option].")
        }
        if (!start.flag("custom")) return Outcome.Failed("Could not tell what kind of control $ref is. Take a new snapshot.")
        var opened = false
        if (!start.flag("open")) {
            page.click(ref)?.let { return Outcome.Failed("Could not open $ref: $it") }
            opened = true
        }
        var waited = 0L
        var fresh = true
        var typed = false
        var scrolls = 0
        repeat(MAX_STEPS) {
            val r = page.find(ref, value, fresh) ?: return Outcome.Failed("The page stopped answering while looking for “$value”. Take a new snapshot.")
            fresh = false
            r.str("error")?.let { return Outcome.Failed(it) }
            r.str("found")?.let { option -> return pick(page, ref, option, r.str("text") ?: value, opened, typed, scrolls) }
            when {
                r.flag("typed") -> { typed = true; page.settle() }
                r.flag("scrolled") -> { scrolls++; page.pause(STEP_MS) }
                else -> {
                    if (waited >= WAIT_MS) {
                        return Outcome.Failed(
                            if (r.flag("none")) {
                                (if (opened) "Tapped $ref, but" else "$ref is open, but") +
                                    " no list of options appeared. Take a snapshot: it may need a different control (an arrow button next to it), or it may be a plain text field (use browser_type)."
                            } else {
                                "Typed “$value” into $ref's search field, but no matching option appeared within ${WAIT_MS / 1000} s. Take a snapshot to see what it offers."
                            },
                        )
                    }
                    page.pause(STEP_MS)
                    waited += STEP_MS
                }
            }
        }
        return Outcome.Failed("Stopped looking for “$value” in the list $ref opened after $scrolls scrolls. Type part of it with browser_type into its search field, or take a snapshot.")
    }

    private suspend fun pick(page: DropdownPage, ref: String, option: String, text: String, opened: Boolean, typed: Boolean, scrolls: Int): Outcome {
        page.click(option)?.let { return Outcome.Failed("Found the option “$text” [$option] but couldn't tap it: $it") }
        val how = buildList {
            if (opened) add("opened [$ref]")
            if (typed) add("typed to filter")
            if (scrolls > 0) add("scrolled the list $scrolls×")
        }.let { if (it.isEmpty()) "" else " (" + it.joinToString(", ") + ")" }
        val check = page.check(ref, text)
        return when {
            check == null || check.flag("gone") -> Outcome.Done("Picked “$text”$how. [$ref] was re-rendered; the snapshot below shows the result.")
            check.flag("ok") -> Outcome.Done("Selected “$text” in [$ref]$how.")
            else -> Outcome.Done(
                "Tapped the option “$text” [$option]$how, but [$ref] shows “${check.str("shows").orEmpty()}”" +
                    (if (check.flag("open")) " and its list is still open" else "") + ". Check the snapshot below; the page may need a different option or a confirm button.",
            )
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull == "true"
}
