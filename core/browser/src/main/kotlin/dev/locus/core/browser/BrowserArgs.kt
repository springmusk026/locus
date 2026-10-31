package dev.locus.core.browser

import dev.locus.core.agent.ToolException
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Argument checks for the browser tools, kept pure so they're unit-tested. Errors say how to fix the call. */
object BrowserArgs {
    private val REF = Regex("^e\\d+$")

    fun ref(args: JsonObject, key: String = "ref"): String {
        val raw = args.str(key)?.trim()?.removePrefix("[")?.removeSuffix("]") ?: throw ToolException("Missing '$key'.")
        if (!REF.matches(raw)) throw ToolException("'$raw' is not a ref. Use a ref from the latest snapshot, like \"e12\".")
        return raw
    }

    /** `fields` of browser_fill_form: refs with the value to set. Values may be strings, numbers or booleans. */
    fun formFields(args: JsonObject): List<Pair<String, String>> {
        val fields = args["fields"] as? JsonArray ?: throw ToolException("Missing 'fields': a list of {\"ref\": \"e12\", \"value\": \"…\"}.")
        if (fields.isEmpty()) throw ToolException("'fields' is empty.")
        if (fields.size > MAX_FORM_FIELDS) throw ToolException("At most $MAX_FORM_FIELDS fields per call; split the form into several calls.")
        return fields.mapIndexed { i, el ->
            val o = el as? JsonObject ?: throw ToolException("fields[$i] must be an object with ref and value.")
            val ref = runCatching { ref(o) }.getOrElse { throw ToolException("fields[$i]: ${it.message}") }
            val v = o["value"] as? JsonPrimitive ?: throw ToolException("fields[$i] ($ref) has no value.")
            ref to (v.booleanOrNull?.toString() ?: v.contentOrNull.orEmpty())
        }.also { list ->
            list.groupBy { it.first }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw ToolException("$it appears twice in fields.") }
        }
    }

    data class Wait(val text: String?, val gone: String?, val seconds: Int)

    fun waitSpec(args: JsonObject): Wait {
        val text = args.str("text")?.takeIf { it.isNotBlank() }
        val gone = args.str("gone")?.takeIf { it.isNotBlank() }
        if (text != null && gone != null) throw ToolException("Give either text or gone, not both.")
        val seconds = args.int("seconds")
        if (text == null && gone == null && seconds == null) throw ToolException("Give text to wait for, gone to wait until text disappears, or seconds to pause.")
        val limit = (seconds ?: DEFAULT_WAIT_SECONDS).coerceIn(1, MAX_WAIT_SECONDS)
        return Wait(text, gone, limit)
    }

    /** The key's standard name, accepting common spellings ("esc", "down", "return"); null when not supported. */
    fun key(raw: String): String? {
        val k = raw.trim()
        if (k.length == 1) return k
        return KEYS[k.lowercase().replace(" ", "").replace("_", "")]
    }

    private val KEYS = mapOf(
        "enter" to "Enter", "return" to "Enter", "escape" to "Escape", "esc" to "Escape", "tab" to "Tab",
        "backspace" to "Backspace", "delete" to "Delete", "del" to "Delete", "space" to "Space", "spacebar" to "Space",
        "arrowdown" to "ArrowDown", "down" to "ArrowDown", "arrowup" to "ArrowUp", "up" to "ArrowUp",
        "arrowleft" to "ArrowLeft", "left" to "ArrowLeft", "arrowright" to "ArrowRight", "right" to "ArrowRight",
        "pagedown" to "PageDown", "pageup" to "PageUp", "home" to "Home", "end" to "End",
    )

    const val SUPPORTED_KEYS = "Enter, Escape, Tab, Backspace, Delete, Space, ArrowUp/Down/Left/Right, PageUp, PageDown, Home, End, or one character"

    const val MAX_FORM_FIELDS = 30
    const val DEFAULT_WAIT_SECONDS = 10
    const val MAX_WAIT_SECONDS = 30

    /** The site's host for approval cards: "shop.example.com". */
    fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').ifEmpty { "this page" }
}

/** Turns a WebView load failure into a sentence that tells the model what to do. */
object LoadErrors {
    fun explain(url: String, description: String): String {
        val d = description.removePrefix("net::")
        val local = BrowserArgs.host(url).substringBefore(':').let { it == "localhost" || it == "127.0.0.1" || it == "0.0.0.0" }
        val why = when {
            "NAME_NOT_RESOLVED" in d -> "the address could not be found (check the spelling)"
            "CONNECTION_REFUSED" in d -> "the connection was refused"
            "TIMED_OUT" in d -> "it took too long to answer"
            "INTERNET_DISCONNECTED" in d -> "the phone is offline"
            "CERT" in d || "SSL" in d -> "its security certificate is not valid"
            "CLEARTEXT" in d -> "plain http:// is blocked for this site; try https://"
            else -> d.lowercase().replace('_', ' ')
        }
        val hint = if (local && ("CONNECTION_REFUSED" in d || "TIMED_OUT" in d)) {
            " Is the server running? Check with linux_service_list, or start it with linux_service_start."
        } else {
            ""
        }
        return "Could not load $url: $why ($d).$hint"
    }

    fun httpStatus(url: String, status: Int, reason: String): String =
        "The server answered $status${reason.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()} for $url; the page below is its error page."
}
