package dev.locus.core.browser

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
    /**
     * Whether these facts came from the helper that ran at document start, before the page's scripts
     * ([PageReply.verified]). Unverified facts can't clear an action: the gates ask instead.
     */
    val verified: Boolean = false,
    /** The document they describe ([PageReply.doc]); an action passes it so a changed page is refused. */
    val doc: String? = null,
) {
    companion object {
        private val BOOLEANS = listOf("sensitive", "submits", "inForm", "searchLike", "formHasPassword", "formHasPayment", "pageHasPassword", "editable")

        /**
         * Facts from the page script's answer, or null when any field is missing or of the wrong type: an
         * answer we can't fully read is no answer, and the gates fail closed on it. [reply] says whether
         * it can be trusted and which document it describes.
         */
        fun parse(o: JsonObject, reply: PageReply? = null): ElementFacts? {
            val label = o.str("label") ?: return null
            val role = o.str("role") ?: return null
            val tag = o.str("tag") ?: return null
            val inputType = when (val t = o["inputType"]) {
                null, JsonNull -> null
                is JsonPrimitive -> if (t.isString) t.content else return null
                else -> return null
            }
            val b = BOOLEANS.associateWith { key -> o.bool(key) ?: return null }
            return ElementFacts(
                label = label, role = role, tag = tag, inputType = inputType,
                sensitive = b.getValue("sensitive"), submits = b.getValue("submits"), inForm = b.getValue("inForm"),
                searchLike = b.getValue("searchLike"), formHasPassword = b.getValue("formHasPassword"),
                formHasPayment = b.getValue("formHasPayment"), pageHasPassword = b.getValue("pageHasPassword"),
                editable = b.getValue("editable"),
                verified = reply?.verified == true,
                doc = reply?.doc,
            )
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
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

    /**
     * Shown when the page script gave no usable facts about the element: it didn't answer, answered
     * garbage, or the element is gone. The gate fails closed, so the user confirms instead.
     */
    fun unverified(host: String) = "Locus couldn't check what this does on $host (the page didn't answer the check), so it needs your OK."

    /**
     * Shown when the facts didn't come from Locus's own page helper: it couldn't run before this page's
     * scripts (an older WebView), so the page could have answered the check itself.
     */
    fun untrusted(host: String) = "Locus can't verify what this does on $host (this phone's WebView can't protect the page check), so it needs your OK."

    /** [click] for facts that may be missing or unverified (the user confirms then). */
    fun clickGate(f: ElementFacts?, host: String): String? = if (f == null || !f.verified) trusted(f, host) else click(f, host)

    /** [submit] for facts that may be missing or unverified (the user confirms then). */
    fun submitGate(f: ElementFacts?, host: String): String? = if (f == null || !f.verified) trusted(f, host) else submit(f, host)

    /** [key] for facts that may be missing or unverified. Only keys that can activate or submit fail closed. */
    fun keyGate(key: String, f: ElementFacts?, host: String): String? = when {
        f != null && f.verified -> key(key, f, host)
        key == "Enter" || key == "Space" -> trusted(f, host)
        else -> null
    }

    /**
     * Typing (without submit) into [f]: free on a verified field. With missing or unverified facts the
     * user confirms, since the field could be a password or card field the page disguised.
     */
    fun typeGate(f: ElementFacts, host: String): String? = trusted(f, host)

    /** Null when [f] is present and verified; otherwise why the user must confirm. */
    private fun trusted(f: ElementFacts?, host: String): String? = when {
        f == null -> unverified(host)
        !f.verified -> untrusted(host)
        else -> null
    }

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

    /**
     * Why the agent may not type [text] into [f], or null when it may. Fails closed: with no facts (the
     * element is gone, or the page didn't answer) nothing is typed. Facts that say the field is a
     * password, card or one-time-code field are believed even when unverified.
     */
    fun refuseTyping(f: ElementFacts?, text: String): String? = refuseText(text) ?: when {
        f == null -> "Locus couldn't check this field (it may be gone, or the page didn't answer). Take a new snapshot and try again; for a password or card field, use browser_ask_user."
        f.sensitive -> SENSITIVE_FIELD
        else -> null
    }

    /** Why the agent may not enter [text] anywhere (a dialog's prompt answer, say), or null. */
    fun refuseText(text: String): String? =
        if (CARD_NUMBER.containsMatchIn(text)) "That looks like a card number. Ask the user to enter it with browser_ask_user." else null

    const val SENSITIVE_FIELD = "This is a password, card or one-time-code field. Only the user enters those: use browser_ask_user."
}
