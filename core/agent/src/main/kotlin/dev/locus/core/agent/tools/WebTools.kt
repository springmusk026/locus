package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

fun webTools(http: HttpClient): List<Tool> = listOf(FetchUrl(http))

private class FetchUrl(private val http: HttpClient) : Tool {
    override val risk = ToolRisk.NETWORK
    override val spec = ToolSpec(
        "fetch_url",
        "Make an HTTP request. HTML pages come back as readable Markdown-like text with links kept; JSON and plain text come back as-is. " +
            "Use for reading articles and documentation or calling web APIs. For pages that need JavaScript or interaction, use the browser tools.",
        ObjectSchema()
            .string("url", "Absolute http(s) URL.")
            .string("method", "GET (default), POST, PUT, PATCH or DELETE.", required = false)
            .obj("headers", "Extra request headers, e.g. {\"Accept\": \"application/json\"}.")
            .string("body", "Request body for POST/PUT/PATCH.", required = false)
            .integer("max_chars", "Truncate the returned text to this many characters. Defaults to 20000.")
            .build(),
    )

    override fun describe(args: JsonObject): String = "${args.str("method")?.uppercase() ?: "GET"} ${args.str("url")}"

    override suspend fun isObservation(args: JsonObject) = (args.str("method") ?: "GET").equals("GET", ignoreCase = true)

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args.str("url").orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw ToolException("Only http(s) URLs are supported.")
        val method = HttpMethod.parse((args.str("method") ?: "GET").uppercase())
        val max = (args.int("max_chars") ?: 20_000).coerceIn(500, 200_000)
        val response = http.request(url) {
            this.method = method
            header("User-Agent", USER_AGENT)
            (args["headers"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { header(k, it) } }
            args.str("body")?.let { setBody(it) }
        }
        val type = response.contentType()?.toString().orEmpty()
        val raw = response.bodyAsText()
        val text = if ("html" in type) htmlToText(raw, url) else raw
        val header = "HTTP ${response.status.value} · ${type.ifEmpty { "unknown type" }} · ${raw.length} bytes\n\n"
        val body = if (text.length > max) text.take(max) + "\n…[truncated; ${text.length - max} more characters, raise max_chars to see more]" else text
        return ToolResult(header + body, isError = response.status.value >= 400)
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 17) Locus/0.1 (+https://github.com/locus-agent)"
    }
}

/** Readable text from HTML: headings, paragraphs, lists and links survive; scripts, styles and chrome don't. */
fun htmlToText(html: String, baseUrl: String): String {
    val doc = Jsoup.parse(html, baseUrl)
    doc.select("script, style, noscript, svg, iframe, template, form, nav, footer, [aria-hidden=true]").remove()
    val root = doc.selectFirst("main, article, [role=main]") ?: doc.body()
    val out = StringBuilder()
    doc.title().takeIf { it.isNotBlank() }?.let { out.append("# ").append(it.trim()).append("\n\n") }
    render(root, out)
    return out.toString().replace(Regex("\n{3,}"), "\n\n").replace(Regex("[ \t]+\n"), "\n").trim()
}

private fun render(node: Node, out: StringBuilder) {
    when (node) {
        is TextNode -> out.append(node.text().replace(Regex("\\s+"), " "))
        is Element -> {
            val tag = node.tagName()
            when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    out.append("\n\n").append("#".repeat(tag[1].digitToInt())).append(' ').append(node.text().trim()).append("\n\n")
                    return
                }
                "a" -> {
                    val text = node.text().trim()
                    val href = node.absUrl("href")
                    if (text.isNotEmpty() && href.startsWith("http")) out.append('[').append(text).append("](").append(href).append(')') else out.append(text)
                    return
                }
                "li" -> out.append("\n- ")
                "br" -> out.append('\n')
                "pre" -> {
                    out.append("\n```\n").append(node.wholeText().trimEnd()).append("\n```\n")
                    return
                }
                "img" -> {
                    node.attr("alt").takeIf { it.isNotBlank() }?.let { out.append("[image: ").append(it).append(']') }
                    return
                }
            }
            val block = tag in BLOCK_TAGS
            if (block) out.append('\n')
            node.childNodes().forEach { render(it, out) }
            if (block) out.append('\n')
        }
    }
}

private val BLOCK_TAGS = setOf("p", "div", "section", "article", "main", "ul", "ol", "table", "tr", "blockquote", "header", "aside", "dl", "dt", "dd", "figure")
