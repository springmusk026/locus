package dev.locus.core.agent.publish

import dev.locus.core.agent.SecretScrubber
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/**
 * The safe parts of a published page (docs/design/publish.md): escaping, the HTML allowlist for rendered
 * Markdown, the page around it, and the secret checks. Pure, so every rule is unit-tested.
 */
object PublishPage {

    /** Text as HTML: `& < > " '` escaped, so nothing in it can become markup. */
    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /**
     * Rendered Markdown, cut down to an allowlist (jsoup's relaxed list plus `del`, `s` and `hr`): no
     * scripts, styles, frames, forms, event handlers or `javascript:` links. Links keep http, https and
     * mailto and get `rel="nofollow noopener noreferrer"`. An image whose source isn't a `data:` URL
     * becomes its alt text, so the page never loads anything from elsewhere.
     */
    fun sanitize(fragment: String): String {
        val cleaned = Jsoup.clean(fragment, "", SAFELIST, Document.OutputSettings().prettyPrint(false))
        val doc = Jsoup.parseBodyFragment(cleaned)
        doc.outputSettings().prettyPrint(false)
        doc.select("img").forEach { img ->
            val src = img.attr("src")
            if (!src.startsWith("data:image/")) img.replaceWith(TextNode(img.attr("alt").ifBlank { "image" }.let { "[$it]" }))
        }
        doc.select("a[href]").forEach { it.attr("rel", "nofollow noopener noreferrer") }
        return doc.body().html()
    }

    private val SAFELIST: Safelist = Safelist.relaxed()
        .addTags("del", "s", "hr")
        .removeProtocols("img", "src", "http", "https")
        .addProtocols("img", "src", "data")
        .removeProtocols("a", "href", "ftp")

    /**
     * Content-Security-Policy of a chat page: nothing from the network, no scripts at all, inline styles
     * and `data:` images only. A second wall behind [sanitize].
     */
    const val CHAT_CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"

    /**
     * A whole chat page: [title], one muted [subtitle] line and [body] (already escaped or sanitised). Neutral
     * type, light and dark through `prefers-color-scheme`, readable on a phone, no script.
     */
    fun chatDocument(title: String, subtitle: String, body: String, lang: String = "en"): String = buildString {
        append("<!doctype html>\n<html lang=\"").append(escape(lang)).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(CHAT_CSP).append("\">\n")
        append("<meta name=\"referrer\" content=\"no-referrer\">\n")
        append("<meta name=\"robots\" content=\"noindex\">\n")
        append("<meta name=\"color-scheme\" content=\"light dark\">\n")
        append("<title>").append(escape(title)).append("</title>\n<style>").append(CSS).append("</style>\n</head>\n<body>\n<main>\n")
        append("<header><h1>").append(escape(title)).append("</h1><p class=\"meta\">").append(escape(subtitle)).append("</p></header>\n")
        append(body)
        append("\n</main>\n</body>\n</html>\n")
    }

    /**
     * An artifact as a page: HTML that is already a document goes as it is; a fragment or an SVG gets the
     * smallest wrapper that makes it one (doctype, charset, viewport, title).
     */
    fun artifactDocument(content: String, svg: Boolean, title: String): String {
        val head = content.trimStart().take(512).lowercase()
        if (!svg && (head.startsWith("<!doctype html") || head.startsWith("<html"))) return content
        return buildString {
            append("<!doctype html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<title>").append(escape(title)).append("</title>\n")
            if (svg) append("<style>body{margin:0;display:flex;justify-content:center;background:#fff}svg{max-width:100%;height:auto}</style>\n")
            append("</head>\n<body>\n").append(content).append("\n</body>\n</html>\n")
        }
    }

    // Neutral: system fonts, greys, one link colour; dark follows the reader's system.
    private const val CSS = ":root{color-scheme:light dark;--bg:#fff;--fg:#1d1d1f;--muted:#6b6b70;--line:#e3e3e6;--soft:#f5f5f7;--link:#2f6f4f}" +
        "@media (prefers-color-scheme:dark){:root{--bg:#141416;--fg:#ececef;--muted:#a1a1a8;--line:#2c2c31;--soft:#1e1e22;--link:#8cc7a5}}" +
        "*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}" +
        "body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}" +
        "main{max-width:46rem;margin:0 auto;padding:2rem 1rem 4rem}" +
        "header{border-bottom:1px solid var(--line);margin-bottom:1.5rem;padding-bottom:1rem}" +
        "h1{font-size:1.5rem;line-height:1.3;margin:0 0 .25rem}.meta{color:var(--muted);margin:0;font-size:.875rem}" +
        ".turn{margin:1.5rem 0}.who{font-weight:600;font-size:.875rem;color:var(--muted);margin:0 0 .25rem}" +
        ".user .body{background:var(--soft);border-radius:12px;padding:.75rem 1rem}" +
        ".body>:first-child{margin-top:0}.body>:last-child{margin-bottom:0}" +
        "a{color:var(--link)}img{max-width:100%;height:auto;border-radius:8px}" +
        "pre{background:var(--soft);border:1px solid var(--line);border-radius:8px;padding:.75rem;overflow-x:auto;font-size:.8125rem;line-height:1.5}" +
        "code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.875em}pre code{font-size:inherit}" +
        "blockquote{margin:0;padding-left:1rem;border-left:3px solid var(--line);color:var(--muted)}" +
        "table{border-collapse:collapse;display:block;overflow-x:auto}th,td{border:1px solid var(--line);padding:.375rem .625rem}" +
        "details{border:1px solid var(--line);border-radius:8px;padding:.5rem .75rem;margin:.5rem 0;font-size:.875rem}" +
        "summary{cursor:pointer;color:var(--muted)}details pre{margin:.5rem 0 0}" +
        ".steps{color:var(--muted);font-size:.875rem;margin:.5rem 0;padding-left:1.25rem}" +
        ".files{color:var(--muted);font-size:.875rem}.note{color:var(--muted);font-style:italic}" +
        "footer{margin-top:3rem;color:var(--muted);font-size:.8125rem;border-top:1px solid var(--line);padding-top:1rem}"

    /** What scrubbing a page found. [removed] counts the values replaced with `[secret]`. */
    data class Scrubbed(val text: String, val removed: Int)

    /**
     * Replaces secrets in [text] before it becomes HTML: the vault's values and the shapes of keys and tokens
     * ([SecretScrubber], which also covers private key blocks, JWTs and AWS key ids). Counts what it replaced.
     */
    class Scrubber(vault: Collection<String>) {
        private val inner = SecretScrubber(vault)

        fun scrub(text: String): Scrubbed {
            if (text.isEmpty()) return Scrubbed(text, 0)
            val before = text.occurrences(LABEL)
            val out = inner.scrub(text)
            return Scrubbed(out, (out.occurrences(LABEL) - before).coerceAtLeast(0))
        }

        private companion object {
            const val LABEL = SecretScrubber.SECRET
        }
    }

    /**
     * Defence in depth after scrubbing: whether any of [texts] (the files about to be uploaded) still holds a
     * vault value, as it is, HTML-escaped or URL-encoded. True blocks publishing.
     */
    fun leaksVaultValue(texts: Collection<String>, vault: Collection<String>): Boolean {
        val needles = vault.flatMap { SecretScrubber.candidates(it) }.flatMap { listOf(it, escape(it), java.net.URLEncoder.encode(it, "UTF-8")) }
            .filter { it.isNotBlank() }.distinct()
        return texts.any { text -> needles.any { text.contains(it) } }
    }

    private fun String.occurrences(needle: String): Int {
        var n = 0
        var i = indexOf(needle)
        while (i >= 0) {
            n++
            i = indexOf(needle, i + needle.length)
        }
        return n
    }
}
