package dev.locus.core.browser

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.bool
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.tools.SendFile
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.agent.tools.htmlToText
import kotlinx.coroutines.currentCoroutineContext
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tools that drive the agent's in-app browser. Page content is wrapped as untrusted: text on a web
 * page is data, never instructions. Clicks and typing default to Allow so browsing stays fluid, but
 * logins, payments, form submits and anything that looks like paying, sending or deleting always ask
 * first ([SensitiveActions]). See docs/design/browser.md for why each tool exists.
 */
fun browserTools(browser: AgentBrowser, workspace: Workspace): List<Tool> = listOf(
    Open(browser), Snapshot(browser), Read(browser), Click(browser), Type(browser), Select(browser), FillForm(browser),
    PressKey(browser), Scroll(browser), Back(browser), Wait(browser), Tabs(browser), Dialog(browser), Upload(browser, workspace),
    Save(browser, workspace), Logs(browser), AskUser(browser),
)

private const val REF_HELP = "Element ref from the latest snapshot, e.g. \"e12\"."

private fun untrusted(text: String) = Untrusted.wrap(text)

/**
 * The page after an action: only what changed when most of it is the same (saves tokens). Events
 * the page caused (load errors, dialogs, new tabs, downloads) come first; they quote the page, so
 * they sit inside the untrusted block.
 */
private suspend fun AgentBrowser.view(note: String? = null, all: Boolean = false, diff: Boolean = true): ToolResult {
    val events = takeEvents().joinToString("") { "Event: $it\n" }
    // browser_open of a file downloads it without showing a page.
    val snap = if (state.value.hasPage) describe(all, diff) else "(no page is shown)"
    val body = if (events.isEmpty()) snap else "$events\n$snap"
    return ToolResult((note?.let { "$it\n\n" } ?: "") + untrusted(body))
}

private fun AgentBrowser.host() = BrowserArgs.host(state.value.url)

private abstract class BrowserTool(protected val browser: AgentBrowser) : Tool {
    protected fun ref(args: JsonObject) = BrowserArgs.ref(args)

    /** Downloads write into the workspace, so a read-only (background) run's calls can't trigger them. */
    final override suspend fun execute(args: JsonObject): ToolResult {
        browser.allowDownloads = currentCoroutineContext()[ToolCallContext]?.readOnly != true
        return try {
            run(args)
        } finally {
            browser.allowDownloads = true
        }
    }

    protected abstract suspend fun run(args: JsonObject): ToolResult
}

private class Open(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.NETWORK
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_open",
        "Open a URL in Locus's browser (the user can watch it live). Returns a snapshot of the page: headings and interactive elements with refs like [e12] to use with the other browser tools. " +
            "Open the current URL again to reload. Files (PDF, ZIP…) are saved to the workspace's downloads/ folder.",
        ObjectSchema()
            .string("url", "Absolute http(s) URL. Search with e.g. https://duckduckgo.com/html/?q=your+query")
            .boolean("new_tab", "Open in a new tab and keep the current page. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Open ${args.str("url")}" + if (args.bool("new_tab") == true) " in a new tab" else ""

    override suspend fun isObservation(args: JsonObject) = true

    // A long query string can smuggle data out to a site. Ask when a URL carries a lot of it.
    override suspend fun mustConfirm(args: JsonObject): String? {
        val query = args.str("url").orEmpty().substringAfter('?', "")
        return if (query.length > 300) "This address carries a lot of data in it." else null
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val url = args.str("url").orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolException("Only http(s) URLs can be opened.")
        browser.open(url, newTab = args.bool("new_tab") == true)
        return browser.view()
    }
}

private class Snapshot(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "browser_snapshot",
        "Describe the current page: title, URL, scroll position, headings and interactive elements with refs like [e12]. " +
            "Elements marked * are new since the last snapshot. Shadow DOM and same-origin frames are included. " +
            "Use find to search the whole page for an element or text instead of reading a long snapshot.",
        ObjectSchema()
            .string("mode", "\"interactive\" (default: elements near the screen) or \"full\" (whole page).", required = false)
            .string("find", "Only list elements and text passages containing this text, from the whole page.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = args.str("find")?.let { "Look for “$it” on the page" } ?: "Look at the page"

    override suspend fun run(args: JsonObject): ToolResult {
        args.str("find")?.takeIf { it.isNotBlank() }?.let { return ToolResult(untrusted(browser.find(it))) }
        return browser.view(all = args.str("mode") == "full", diff = false)
    }
}

private class Read(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "browser_read",
        "Read the current page's main text as Markdown (articles, documentation, search results). Cheaper than snapshots for reading.",
        ObjectSchema().integer("max_chars", "Truncate to this many characters. Defaults to 15000.").build(),
    )

    override fun describe(args: JsonObject) = "Read the page"

    override suspend fun run(args: JsonObject): ToolResult {
        val max = (args.int("max_chars") ?: 15_000).coerceIn(500, 100_000)
        val text = htmlToText(browser.html(), browser.state.value.url)
        return ToolResult(untrusted(if (text.length > max) text.take(max) + "\n…[truncated; raise max_chars to read more]" else text))
    }
}

private class Click(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_click",
        "Click (tap) an element by ref. It is scrolled into view first; if something covers it, the error says what. Returns what changed on the page.",
        ObjectSchema().string("ref", REF_HELP).build(),
    )

    override fun describe(args: JsonObject) = "Click ${args.str("ref")}"

    override suspend fun mustConfirm(args: JsonObject): String? {
        // Fails closed: no answer from the page (or a tampered one) means the user confirms.
        return SensitiveActions.clickGate(browser.inspect(ref(args)), browser.host())
    }

    override suspend fun run(args: JsonObject): ToolResult {
        browser.click(ref(args))?.let { return ToolResult.error(it) }
        return browser.view("Clicked ${ref(args)}.")
    }
}

private class Type(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_type",
        "Replace the text in a field by ref. Set submit to press Enter / submit its form afterwards. Never type passwords or card numbers: use browser_ask_user so the user enters them.",
        ObjectSchema()
            .string("ref", REF_HELP)
            .string("text", "Text to enter.")
            .boolean("submit", "Press Enter / submit the form afterwards. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Type “${args.str("text")?.take(60)}” into ${args.str("ref")}" + if (args.bool("submit") == true) " and submit" else ""

    override suspend fun mustConfirm(args: JsonObject): String? {
        if (args.bool("submit") != true) return null
        return SensitiveActions.submitGate(browser.inspect(ref(args)), browser.host())
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val text = args.str("text").orEmpty()
        SensitiveActions.refuseTyping(browser.inspect(ref(args)), text)?.let { throw ToolException(it) }
        browser.type(ref(args), text, args.bool("submit") == true)?.let { return ToolResult.error(it) }
        return browser.view("Typed into ${ref(args)}." + if (args.bool("submit") == true) " Submitted." else "")
    }
}

private class Select(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_select",
        "Choose an option in a dropdown (<select>) by its visible text or value.",
        ObjectSchema().string("ref", REF_HELP).string("value", "Option text or value.").build(),
    )

    override fun describe(args: JsonObject) = "Choose “${args.str("value")}” in ${args.str("ref")}"

    override suspend fun run(args: JsonObject): ToolResult {
        browser.select(ref(args), args.str("value").orEmpty())?.let { return ToolResult.error(it) }
        return browser.view("Selected.")
    }
}

private class FillForm(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_fill_form",
        "Fill several form fields in one call: text fields, dropdowns (option text or value) and checkboxes/radios (\"true\" or \"false\"). " +
            "Doesn't submit: click the form's button afterwards. Never fill passwords or card numbers: use browser_ask_user.",
        ObjectSchema()
            .array(
                "fields",
                "The fields to fill, in order.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("ref") { put("type", "string"); put("description", REF_HELP) }
                        putJsonObject("value") { put("type", "string"); put("description", "Text, option, or \"true\"/\"false\" for checkboxes.") }
                    }
                    putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("ref")); add(kotlinx.serialization.json.JsonPrimitive("value")) }
                    put("additionalProperties", false)
                },
            )
            .build(),
    )

    override fun describe(args: JsonObject) = "Fill ${(args["fields"] as? JsonArray)?.size ?: 0} fields"

    override suspend fun run(args: JsonObject): ToolResult {
        val fields = BrowserArgs.formFields(args)
        for ((ref, value) in fields) {
            SensitiveActions.refuseTyping(browser.inspect(ref), value)?.let { throw ToolException("$ref: $it") }
        }
        val failed = browser.fill(fields)
        val note = if (failed.isEmpty()) "Filled ${fields.size} fields." else "Filled ${fields.size - failed.size} of ${fields.size} fields. Problems:\n" + failed.joinToString("\n") { "- $it" }
        return browser.view(note).let { if (failed.isNotEmpty() && failed.size == fields.size) it.copy(isError = true) else it }
    }
}

private class PressKey(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_press_key",
        "Press a key in the focused element: Enter (submits like a browser), Escape, Tab (next field), Space, Backspace, arrows, PageUp/PageDown, Home, End.",
        ObjectSchema().string("key", "Key name.").build(),
    )

    override fun describe(args: JsonObject) = "Press ${args.str("key")}"

    private fun key(args: JsonObject) = BrowserArgs.key(args.str("key").orEmpty())
        ?: throw ToolException("Unsupported key '${args.str("key")}'. Use ${BrowserArgs.SUPPORTED_KEYS}.")

    override suspend fun mustConfirm(args: JsonObject): String? {
        return SensitiveActions.keyGate(key(args), browser.inspectFocused(), browser.host())
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val key = key(args)
        browser.pressKey(key)
        return browser.view("Pressed $key.")
    }
}

private class Scroll(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "browser_scroll",
        "Scroll the page (or a scrollable element) and return the new snapshot.",
        ObjectSchema()
            .string("direction", "down (default), up, top or bottom.", required = false)
            .string("ref", "Scroll inside this element instead of the page.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Scroll ${args.str("direction") ?: "down"}"

    override suspend fun run(args: JsonObject): ToolResult {
        val direction = (args.str("direction") ?: "down").lowercase()
        if (direction !in setOf("down", "up", "top", "bottom")) throw ToolException("direction must be down, up, top or bottom.")
        val ref = args.str("ref")?.let { BrowserArgs.ref(args) }
        val note = browser.scroll(direction, ref)
        return browser.view(note, diff = false)
    }
}

private class Back(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec("browser_back", "Go back to the previous page.", ObjectSchema().build())

    override fun describe(args: JsonObject) = "Go back"

    override suspend fun run(args: JsonObject): ToolResult {
        if (!browser.back()) return ToolResult.error("There is no previous page in this tab.")
        return browser.view()
    }
}

private class Wait(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "browser_wait",
        "Wait for text to appear on the page (or, with gone, to disappear), up to seconds (default 10, max 30). With only seconds, just pause. " +
            "Use it when a page loads results, finishes a job or shows a message after a delay.",
        ObjectSchema()
            .string("text", "Wait until this text is on the page.", required = false)
            .string("gone", "Wait until this text is no longer on the page.", required = false)
            .integer("seconds", "Longest wait, 1 to 30.")
            .build(),
    )

    override fun describe(args: JsonObject) = args.str("text")?.let { "Wait for “$it”" } ?: args.str("gone")?.let { "Wait for “$it” to go" } ?: "Wait ${args.int("seconds")}s"

    override suspend fun run(args: JsonObject): ToolResult {
        val spec = BrowserArgs.waitSpec(args)
        val met = browser.waitFor(spec.text, spec.gone, spec.seconds)
        val note = when {
            spec.text == null && spec.gone == null -> "Waited ${spec.seconds}s."
            met -> spec.text?.let { "“$it” is on the page." } ?: "“${spec.gone}” is gone."
            else -> "Still waiting after ${spec.seconds}s: " + (spec.text?.let { "“$it” has not appeared." } ?: "“${spec.gone}” is still there.")
        }
        return browser.view(note).let { if (!met) it.copy(isError = true) else it }
    }
}

private class Tabs(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "browser_tabs",
        "List, switch or close the browser's tabs. Pages open pop-ups (sign-in windows, links that open in a new tab) as tabs, " +
            "and browser_open with new_tab adds one. Other browser tools act on the active tab.",
        ObjectSchema()
            .string("action", "\"list\" (default), \"select\" or \"close\".", required = false)
            .integer("index", "Tab number from the list (1 = first) for select or close. Close defaults to the active tab.")
            .build(),
    )

    override fun describe(args: JsonObject) = when (args.str("action")) {
        "select" -> "Switch to tab ${args.int("index")}"
        "close" -> "Close tab ${args.int("index") ?: ""}".trim()
        else -> "List tabs"
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val index = args.int("index")?.minus(1)
        when (val action = args.str("action") ?: "list") {
            "list" -> Unit
            "select" -> browser.selectTab(index ?: throw ToolException("Give the index of the tab to switch to."))
            "close" -> browser.closeTab(index ?: browser.state.value.activeTab)
            else -> throw ToolException("Unknown action '$action'. Use list, select or close.")
        }
        val state = browser.state.value
        if (state.tabs.isEmpty()) return ToolResult("No tabs are open. Use browser_open.")
        val list = state.tabs.mapIndexed { i, t ->
            "${i + 1}. ${SnapshotFormat.clip(t.title.ifBlank { "(untitled)" }, 80)} · ${t.url}" + if (i == state.activeTab) " (active)" else ""
        }
        return ToolResult(untrusted(list.joinToString("\n")))
    }
}

private class Dialog(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_dialog",
        "Answer the page's JavaScript dialog (confirm or prompt), which pauses the page until answered. " +
            "Alerts are dismissed automatically and reported.",
        ObjectSchema()
            .boolean("accept", "true for OK, false for Cancel.", required = true)
            .string("text", "Answer for a prompt; its default when left out.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = if (args.bool("accept") == true) "Answer OK to the page's dialog" else "Cancel the page's dialog"

    override suspend fun mustConfirm(args: JsonObject): String? {
        val d = browser.state.value.dialog ?: return null
        return SensitiveActions.dialog(d.type, d.message, args.bool("accept") == true)
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val accept = args.bool("accept") ?: throw ToolException("Missing 'accept'.")
        val text = args.str("text")
        if (text != null) SensitiveActions.refuseTyping(null, text)?.let { throw ToolException(it) }
        val answered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { browser.answerDialog(accept, text) }
        if (!answered) return ToolResult.error("No dialog is open.")
        kotlinx.coroutines.delay(300)
        return browser.view(if (accept) "Answered OK." else "Cancelled the dialog.")
    }
}

private class Upload(browser: AgentBrowser, private val workspace: Workspace) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ASK
    override val spec = ToolSpec(
        "browser_upload",
        "Upload workspace files to the page: give the file input (role file-upload) or the button that opens the file picker. " +
            "The user always confirms, since the files leave the phone.",
        ObjectSchema()
            .string("ref", REF_HELP)
            .array("paths", "Workspace paths of the files to upload.", buildJsonObject { put("type", "string") })
            .build(),
    )

    override fun describe(args: JsonObject) = "Upload ${paths(args).joinToString { it.substringAfterLast('/') }}"

    private fun paths(args: JsonObject): List<String> =
        (args["paths"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    private fun files(args: JsonObject): List<java.io.File> {
        val paths = paths(args)
        if (paths.isEmpty()) throw ToolException("Give the workspace paths of the files to upload in 'paths'.")
        if (paths.size > Transfers.MAX_UPLOAD_FILES) throw ToolException("At most ${Transfers.MAX_UPLOAD_FILES} files at a time.")
        val files = paths.map { p ->
            workspace.existing(SendFile.normalize(p)).also { if (it.isDirectory) throw ToolException("'$p' is a folder. Zip it first, or pick the files.") }
        }
        val total = files.sumOf { it.length() }
        if (total > Transfers.MAX_UPLOAD_BYTES) {
            throw ToolException("These files are ${SendFile.readableSize(total)}; uploads are limited to ${SendFile.readableSize(Transfers.MAX_UPLOAD_BYTES)}.")
        }
        return files
    }

    // Files leave the phone: always confirm, naming them and the site.
    override suspend fun mustConfirm(args: JsonObject): String {
        val names = runCatching { files(args).joinToString { "${it.name} (${SendFile.readableSize(it.length())})" } }.getOrElse { paths(args).joinToString() }
        return "Uploads $names to ${browser.host()}."
    }

    override suspend fun run(args: JsonObject): ToolResult {
        val note = browser.upload(ref(args), files(args))
        return browser.view(note)
    }
}

private class Save(browser: AgentBrowser, private val workspace: Workspace) : BrowserTool(browser) {
    override val risk = ToolRisk.WRITE
    override val defaultPolicy = ToolPolicy.ALLOW
    override val spec = ToolSpec(
        "browser_save",
        "Save the current page into the workspace: \"markdown\" (its main text), \"mhtml\" (a complete offline copy) or \"png\" (a screenshot of what the browser shows). " +
            "Give the file to the user with send_file.",
        ObjectSchema()
            .string("format", "markdown, mhtml or png.")
            .string("path", "Workspace path to save to. Defaults to browser/<site>-<time>.<ext>.", required = false)
            .build(),
    )

    override fun describe(args: JsonObject) = "Save the page as ${args.str("format")}"

    override suspend fun run(args: JsonObject): ToolResult {
        val format = args.str("format")?.lowercase()?.takeIf { it in SavePaths.FORMATS }
            ?: throw ToolException("format must be markdown, mhtml or png.")
        val url = browser.state.value.url
        val path = args.str("path")?.takeIf { it.isNotBlank() }?.let { SavePaths.withExtension(SendFile.normalize(it), format) }
            ?: SavePaths.default(url, format, java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()))
        val file = workspace.resolve(path)
        if (file.isDirectory) throw ToolException("'$path' is a folder; give a file path.")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            file.parentFile?.mkdirs()
            // Reversible by default: an overwritten file's old contents go to the trash.
            if (file.exists()) workspace.backup(file, dev.locus.core.agent.tools.TrashEntry.Reason.OVERWRITTEN)
        }
        when (format) {
            "markdown" -> {
                val text = htmlToText(browser.html(), url)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { file.writeText("<!-- Saved from $url -->\n\n$text\n") }
            }
            "mhtml" -> browser.saveArchive(file)
            "png" -> browser.screenshot(file)
        }
        return ToolResult("Saved ${workspace.relative(file)} (${SendFile.readableSize(file.length())}). Use send_file to give it to the user.")
    }
}

private class Logs(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "browser_logs",
        "The page's console messages and failed or error (4xx/5xx) requests since it loaded. Use it to debug a web app, for example a dev server you started.",
        ObjectSchema()
            .string("kind", "\"all\" (default), \"console\" or \"network\".", required = false)
            .boolean("errors_only", "Only errors and warnings. Defaults to false.")
            .build(),
    )

    override fun describe(args: JsonObject) = "Check the page's ${args.str("kind")?.takeIf { it != "all" } ?: "console and network"} logs"

    override suspend fun run(args: JsonObject): ToolResult {
        val kind = (args.str("kind") ?: "all").lowercase()
        if (kind !in setOf("all", "console", "network")) throw ToolException("kind must be all, console or network.")
        return ToolResult(untrusted(browser.logs(kind, args.bool("errors_only") == true)))
    }
}

private class AskUser(browser: AgentBrowser) : BrowserTool(browser) {
    override val risk = ToolRisk.READ
    override val spec = ToolSpec(
        "browser_ask_user",
        "Hand the browser to the user and wait until they are done. Use for logging in, passwords, CAPTCHAs, payment details, or anything only the user should do. Say clearly what they should do.",
        ObjectSchema().string("reason", "What the user should do, e.g. \"Log in to your account, then tap Done\".").build(),
    )

    override fun describe(args: JsonObject) = "Ask you to: ${args.str("reason")}"

    override suspend fun run(args: JsonObject): ToolResult {
        browser.handOff(args.str("reason").orEmpty())
        return browser.view("The user finished and handed the browser back.", diff = false)
    }
}
