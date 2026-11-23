package dev.locus.core.browser

import dev.locus.core.model.ModeNote
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * An element the user picked on the page in the full-screen browser ("Pick an element"), to point the
 * agent at it in their next message (docs/design/browser.md, "Element picker").
 *
 * [ref] is a ref in the page helper's own namespace, the one snapshots use: the same element has the same
 * ref in every snapshot for as long as it is on the page, so `browser_click` and the other tools take it
 * as they take a snapshot's. Everything else ([role], [name], [text], [title], …) was read from the page
 * and is untrusted: it reaches the model only inside an `<untrusted_web_content>` block ([forModel]).
 * [url] is the WebView's own record of the tab's address, never the page's word.
 *
 * [left], [top], [width] and [height] are fractions of the viewport, for drawing the highlight over the
 * page; they say nothing to the model.
 */
data class PickedElement(
    val ref: String,
    val role: String,
    val name: String,
    val tag: String,
    val text: String = "",
    val href: String? = null,
    val value: String? = null,
    val checked: Boolean = false,
    val disabled: Boolean = false,
    /** A password, card or one-time-code field, or a field the user typed into: never its value. */
    val hidden: Boolean = false,
    /** Inside a same-origin frame of the page. */
    val inFrame: Boolean = false,
    val url: String = "",
    val title: String = "",
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
) {
    /** What the user reads in the composer and the sent message: `button “Reserve”`, or the role alone. */
    val label: String get() = label(role, name)

    /** The page's host, as in the browser's address bar. */
    val host: String get() = hostOf(url)

    /**
     * The note that goes with the user's next message: Locus's own words and the ref outside the block,
     * everything the page said inside it. [scrub] takes saved-login secrets out of the page's text, as
     * every browser result does. Wrapped by [ModeNote.result] where the message is built, so it never
     * counts as the user's typing (no host in it becomes "named") and the run counts as tainted.
     */
    fun forModel(scrub: (String) -> String = { it }): String = buildString {
        append(ModeNote.PICKED_ELEMENT_OPEN).append('\n')
        append("The user picked an element in this chat's browser to point you at it. Its ref is ").append(ref)
        append(if (inFrame) ", inside a frame of the page" else "").append('.')
        append(" Browser tools take it like a ref from a snapshot while the element is on the page. ")
        append("If a tool says the refs are out of date, take a snapshot: the element keeps the same ref there.\n")
        val page = buildString {
            append("Page: ")
            if (title.isNotBlank()) append('"').append(clip(title, MAX_TITLE)).append("\" ")
            append('(').append(clip(url, MAX_URL)).append(")\n")
            append("Element: [").append(ref).append("] ").append(label)
            if (href != null) append(" href=").append(clip(href, MAX_URL))
            if (checked) append(" checked")
            if (disabled) append(" disabled")
            when {
                hidden -> append(" value=<hidden>")
                value != null -> append(" value=\"").append(clip(value, MAX_VALUE)).append('"')
            }
            if (text.isNotBlank() && text != name) append("\nText: \"").append(clip(text, MAX_TEXT)).append('"')
        }
        append(Untrusted.wrap(scrub(page))).append('\n')
        append(ModeNote.PICKED_ELEMENT_CLOSE)
    }

    companion object {
        const val MAX_TEXT = 300
        const val MAX_TITLE = 120
        const val MAX_URL = 200
        const val MAX_VALUE = 60
        private const val MAX_NAME = 80

        /** `button “Reserve”`; the role alone when the element has no name. */
        fun label(role: String, name: String): String = if (name.isBlank()) role else "$role “${clip(name, MAX_NAME)}”"

        /**
         * The page helper's answer to `__locus.pickAt` (locus-agent.js), with the tab's [url] and [title] from
         * the WebView. Null when it isn't a pick (no ref, or an error), so a broken answer never becomes an attachment.
         */
        fun parse(obj: JsonObject, url: String, title: String): PickedElement? {
            if (obj["error"] != null) return null
            val ref = obj.str("ref")?.takeIf { REF.matches(it) } ?: return null
            val rect = obj["rect"] as? JsonObject
            return PickedElement(
                ref = ref,
                role = obj.str("role").orEmpty().ifBlank { obj.str("tag").orEmpty() }.take(40),
                name = obj.str("name").orEmpty().take(MAX_NAME),
                tag = obj.str("tag").orEmpty().take(40),
                text = obj.str("text").orEmpty().take(MAX_TEXT),
                href = obj.str("href"),
                value = obj.str("value"),
                checked = obj.bool("checked"),
                disabled = obj.bool("disabled"),
                // Whatever the page sent with it, a field marked sensitive, typed by the user or filled from a saved login never carries a value.
                hidden = obj.bool("sensitive") || obj.bool("entered") || obj.bool("filled"),
                inFrame = obj.bool("frame"),
                url = url,
                title = title,
                left = rect.num("l"),
                top = rect.num("t"),
                width = rect.num("w"),
                height = rect.num("h"),
            ).let { if (it.hidden) it.copy(value = null) else it }
        }

        /** Why `__locus.pickAt` found nothing to pick, from its `error` field; null when it picked something. */
        fun failure(obj: JsonObject?): PickFailure? = when ((obj?.get("error") as? JsonPrimitive)?.contentOrNull) {
            null -> if (obj == null) PickFailure.NoAnswer else null
            "nothing" -> PickFailure.Nothing
            "whole" -> PickFailure.WholePage
            else -> PickFailure.NoAnswer
        }

        /**
         * The label of the element a sent message pointed at ([forModel], inside [ModeNote.result]), for showing
         * it in the user's bubble: `button “Reserve” · booking.com`. Null when [text] holds no picked element.
         */
        fun shownIn(text: String): String? {
            if (ModeNote.PICKED_ELEMENT_OPEN !in text) return null
            val element = LINE_ELEMENT.find(text)?.groupValues?.get(1)?.trim() ?: return null
            val host = LINE_PAGE.find(text)?.groupValues?.get(1)?.let(::hostOf).orEmpty()
            return if (host.isBlank()) element else "$element · $host"
        }

        private val LINE_ELEMENT = Regex("""(?m)^Element: \[e\d+] (.*?)(?: href=.*| checked.*| disabled.*| value=.*)?$""")
        private val LINE_PAGE = Regex("""(?m)^Page: .*\((\S+)\)$""")

        private val REF = Regex("e\\d{1,7}")

        internal fun hostOf(url: String): String =
            runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: ""

        private fun clip(s: String, n: Int): String {
            val one = s.replace(Regex("\\s+"), " ").trim()
            return if (one.length > n) one.take(n - 1) + "…" else one
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
        private fun JsonObject?.num(key: String): Float =
            ((this?.get(key) as? JsonPrimitive)?.floatOrNull ?: 0f).takeIf { it.isFinite() }?.coerceIn(-1f, 2f) ?: 0f
    }
}

/** Why a tap picked nothing. */
enum class PickFailure {
    /** An empty spot, or something the page draws without elements (a canvas's background). */
    Nothing,

    /** Only the page as a whole is there (its background): nothing smaller to point at. */
    WholePage,

    /** The page didn't answer, or its answer couldn't be checked (another document, a dialog, no helper). */
    NoAnswer,

    /** Locus is browsing now, or no page is open. */
    NotNow,
}

/** What a tap in pick mode found. */
sealed interface PickOutcome {
    data class Picked(val element: PickedElement) : PickOutcome
    data class Failed(val reason: PickFailure) : PickOutcome
}
