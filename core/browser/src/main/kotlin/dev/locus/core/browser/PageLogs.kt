package dev.locus.core.browser

/**
 * Console messages and failed requests of one tab since its last navigation, for debugging pages
 * (typically a dev server started in the Linux workspace). A bounded ring, newest last.
 */
class PageLogs(private val capacity: Int = 200) {
    enum class Kind { CONSOLE, NETWORK }

    data class Entry(val kind: Kind, val level: String, val text: String)

    private val entries = ArrayDeque<Entry>()
    private var dropped = 0

    @Synchronized
    fun add(entry: Entry) {
        if (entries.size >= capacity) { entries.removeFirst(); dropped++ }
        entries.addLast(entry)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        dropped = 0
    }

    @Synchronized
    fun snapshot(): Pair<List<Entry>, Int> = entries.toList() to dropped

    companion object {
        const val MAX_LINES = 100

        /** Lines for the model: [kind] "console", "network" or "all"; [errorsOnly] keeps errors and warnings. */
        fun format(entries: List<Entry>, dropped: Int, kind: String, errorsOnly: Boolean): String {
            val wanted = entries.filter { e ->
                (kind == "all" || e.kind.name.equals(kind, ignoreCase = true)) &&
                    (!errorsOnly || e.kind == Kind.NETWORK || e.level == "error" || e.level == "warning")
            }
            if (wanted.isEmpty()) {
                val what = when (kind) { "console" -> "console messages"; "network" -> "failed requests"; else -> "console messages or failed requests" }
                return "No ${if (errorsOnly) "error " else ""}$what since this page loaded."
            }
            val shown = wanted.takeLast(MAX_LINES)
            return buildList {
                if (dropped > 0 || wanted.size > shown.size) add("(older entries not shown)")
                shown.forEach { e ->
                    val tag = if (e.kind == Kind.NETWORK) "network" else e.level
                    add("[$tag] ${SnapshotFormat.clip(e.text, 300)}")
                }
            }.joinToString("\n")
        }
    }
}

/** Where browser_save puts a page when no path is given, and how a given path is checked. */
object SavePaths {
    val FORMATS = mapOf("markdown" to ".md", "mhtml" to ".mhtml", "png" to ".png")

    /** `browser/<host>-<yyyyMMdd-HHmmss>.<ext>` from the page's URL. */
    fun default(url: String, format: String, stamp: String): String {
        val host = BrowserArgs.host(url).substringBefore(':').removePrefix("www.").replace(Regex("[^A-Za-z0-9.-]"), "-").ifBlank { "page" }
        return "browser/$host-$stamp${FORMATS.getValue(format)}"
    }

    /** [path] with the format's extension added when it has none. */
    fun withExtension(path: String, format: String): String {
        val ext = FORMATS.getValue(format)
        val name = path.substringAfterLast('/')
        return if ('.' in name) path else path + ext
    }
}
