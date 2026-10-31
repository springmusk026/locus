package dev.locus.core.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** What the page script knows about an element, for deciding whether an action must be confirmed. */
data class ElementFacts(
    val label: String = "",
    val role: String = "",
    val tag: String = "",
    val inputType: String? = null,
    /** A password, card or one-time-code field. */
    val sensitive: Boolean = false,
    /** A submit button (or submit/image input) of a form. */
    val submits: Boolean = false,
    val inForm: Boolean = false,
    /** A search form or search field: submitting it only looks things up. */
    val searchLike: Boolean = false,
    val formHasPassword: Boolean = false,
    val formHasPayment: Boolean = false,
    val pageHasPassword: Boolean = false,
    /** A text field or contenteditable. */
    val editable: Boolean = false,
) {
    companion object {
        fun parse(o: JsonObject) = ElementFacts(
            label = o.s("label").orEmpty(),
            role = o.s("role").orEmpty(),
            tag = o.s("tag").orEmpty(),
            inputType = o.s("inputType"),
            sensitive = o.b("sensitive"),
            submits = o.b("submits"),
            inForm = o.b("inForm"),
            searchLike = o.b("searchLike"),
            formHasPassword = o.b("formHasPassword"),
            formHasPayment = o.b("formHasPayment"),
            pageHasPassword = o.b("pageHasPassword"),
            editable = o.b("editable"),
        )

        private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.b(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    }
}

/**
 * Decides which browser actions must be confirmed by the user even when browser tools are set to
 * Allow: logins, payments, form submits and anything labelled like an outward action (pay, send,
 * delete, …). Searching stays fluid. Returns the reason shown on the approval card, or null.
 */
object SensitiveActions {
    private val OUTWARD = Regex(
        "\\b(pay|buy|purchase|order|checkout|check out|confirm|delete|remove|send|transfer|withdraw|donate|book|reserve|subscribe|" +
            "unsubscribe|publish|post|submit|sign up|register|place|share|invite|approve|cancel (?:my |your )?(?:order|subscription|account))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN = Regex("\\b(log ?in|sign ?in|sign on|continue)\\b", RegexOption.IGNORE_CASE)
    private val CARD_NUMBER = Regex("\\b(?:\\d[ -]?){13,19}\\b")

    /** A tap or click on [f]. */
    fun click(f: ElementFacts, host: String): String? = when {
        OUTWARD.containsMatchIn(f.label) -> "The button says “${f.label.take(60)}” on $host."
        f.submits -> submit(f, host)
        f.pageHasPassword && LOGIN.containsMatchIn(f.label) -> "This signs in on $host."
        else -> null
    }

    /** Submitting through [f]: its submit button, Enter in one of its fields, or browser_type with submit. */
    fun submit(f: ElementFacts, host: String): String? = when {
        f.formHasPassword || (f.pageHasPassword && !f.inForm && !f.searchLike) -> "This signs in or creates an account on $host."
        f.formHasPayment -> "This submits payment details on $host."
        OUTWARD.containsMatchIn(f.label) -> "This submits a form (“${f.label.take(60)}”) on $host."
        f.searchLike -> null
        f.inForm -> "This submits a form on $host."
        // A field outside any form whose Enter is handled by script: a chat box sends a message.
        f.editable -> "Pressing Enter here may send what was typed on $host."
        else -> null
    }

    /** Pressing [key] in the focused element [f]. */
    fun key(key: String, f: ElementFacts, host: String): String? = when (key) {
        "Enter" -> when {
            f.editable || f.submits -> submit(f, host)
            f.role == "button" || f.role == "link" -> click(f, host)
            else -> null
        }
        "Space" -> if (f.role == "button" || f.submits) click(f, host) else null
        else -> null
    }

    /** Accepting a JavaScript confirm() whose text describes an outward action. */
    fun dialog(type: String, message: String, accept: Boolean): String? =
        if (accept && type == "confirm" && OUTWARD.containsMatchIn(message)) "The page asks: “${message.take(120)}”" else null

    /** Why the agent may not type [text] into [f], or null when it may. */
    fun refuseTyping(f: ElementFacts?, text: String): String? = when {
        CARD_NUMBER.containsMatchIn(text) -> "That looks like a card number. Ask the user to enter it with browser_ask_user."
        f?.sensitive == true -> "This is a password, card or one-time-code field. Only the user enters those: use browser_ask_user."
        else -> null
    }
}
