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
    // In every shipped language (SensitiveWords), not just English: a "Finalizar compra" button or a
    // confirm('¿Eliminar cuenta?') asks too.
    private val OUTWARD = Regex(SensitiveWords.OUTWARD, RegexOption.IGNORE_CASE)
    private val LOGIN = Regex(SensitiveWords.LOGIN, RegexOption.IGNORE_CASE)
    // A run of digit groups split by single spaces or dashes; [looksLikeCard] decides on each.
    private val DIGIT_RUN = Regex("\\d+(?:[ -]\\d+)*")

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
        if (containsCardNumber(text)) "That looks like a card number. Ask the user to enter it with browser_ask_user." else null

    /**
     * Whether [text] holds a payment card number: 13 to 19 digits, written together or in groups split by
     * single spaces or dashes, that start with a card network's prefix and pass the Luhn check. Any run of
     * whole groups inside a longer run counts, so "4111 1111 1111 1111 12/27" is still a card. Timestamps,
     * tracking and order numbers and phone numbers rarely pass both checks (#318).
     */
    internal fun containsCardNumber(text: String): Boolean = DIGIT_RUN.findAll(text).any { run ->
        val groups = run.value.split(' ', '-')
        groups.indices.any { from ->
            val digits = StringBuilder()
            var to = from
            var found = false
            while (!found && to < groups.size && digits.length + groups[to].length <= 19) {
                digits.append(groups[to++])
                found = digits.length >= 13 && looksLikeCard(digits.toString())
            }
            found
        }
    }

    internal fun looksLikeCard(digits: String): Boolean = knownPrefix(digits) && luhn(digits)

    /** A prefix (IIN range) of a card network that issues numbers of this length. */
    private fun knownPrefix(d: String): Boolean {
        val n = d.length
        fun p(len: Int) = d.take(len).toInt()
        return when {
            d[0] == '4' -> true // Visa, 13 to 19 digits
            p(2) in 51..55 || p(4) in 2221..2720 -> n == 16 // Mastercard
            p(2) == 34 || p(2) == 37 -> n == 15 // American Express
            p(3) in 300..305 || p(4) == 3095 || p(2) == 36 || p(2) in 38..39 -> n >= 14 // Diners Club
            p(2) == 35 -> n >= 16 // JCB, RuPay
            p(4) in 2200..2204 -> n >= 16 // Mir
            p(2) == 50 || p(2) in 56..58 || d[0] == '6' -> true // Maestro, Discover, UnionPay, RuPay
            p(2) == 81 || p(2) == 82 -> n >= 16 // UnionPay, RuPay
            else -> false
        }
    }

    private fun luhn(d: String): Boolean {
        var sum = 0
        d.reversed().forEachIndexed { i, c ->
            val v = c - '0'
            sum += if (i % 2 == 1) (v * 2).let { if (it > 9) it - 9 else it } else v
        }
        return sum % 10 == 0
    }

    const val SENSITIVE_FIELD = "This is a password, card or one-time-code field, which you never type into. For a sign-in, use browser_login if the snapshot says a saved login is available; otherwise hand over with browser_ask_user."
}
