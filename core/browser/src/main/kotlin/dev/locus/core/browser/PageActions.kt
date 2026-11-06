package dev.locus.core.browser

import dev.locus.core.agent.PageAction
import dev.locus.core.agent.str
import dev.locus.core.model.LocalNetwork
import kotlinx.serialization.json.JsonObject

/**
 * Which browser calls act on the open page, for taint's named-site rule ([dev.locus.core.agent.Tool.pageAction],
 * docs/design/taint.md, "Browsing a site you named"). [url] is the browser's own record of the calling
 * chat's page, never anything the model or the page wrote.
 *
 * browser_upload isn't one: files leave the phone, so it keeps asking on every site. Navigation
 * (browser_open, browser_back) and reads are observations, which taint gates by address instead.
 */
object PageActions {
    /** Calls that pick among what the page offers: no text the model wrote reaches the page. */
    private val CHOOSING = setOf("browser_click", "browser_select", "browser_press_key")

    /** Calls that write text the model chose into the page. */
    private val TYPING = setOf("browser_type", "browser_fill_form")

    fun of(toolName: String, args: JsonObject, url: String): PageAction? {
        val carriesText = when (toolName) {
            in CHOOSING -> false
            in TYPING -> true
            // Answering a prompt() dialog sends its text to the page; OK or Cancel alone doesn't.
            "browser_dialog" -> args.str("text") != null
            else -> return null
        }
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        val host = LocalNetwork.hostOf(url) ?: return null
        return PageAction(host, carriesText)
    }
}
