package dev.locus.core.agent.tools

import dev.locus.core.agent.ObjectSchema
import dev.locus.core.agent.Tool
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.ToolResult
import dev.locus.core.agent.UntrustedContent
import dev.locus.core.agent.int
import dev.locus.core.agent.str
import dev.locus.core.model.LocalNetwork
import dev.locus.core.model.ToolRisk
import dev.locus.core.model.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Where fetch_url may connect. [resolve] looks a host name up (the system resolver in the app, a fake in
 * tests); [allowedHosts] are hosts the user set up in Locus themselves (a model provider such as Ollama on
 * the LAN, an MCP server), the only local addresses the agent may reach.
 */
class FetchNetwork(
    val resolve: suspend (String) -> List<InetAddress> = { host -> withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() } },
    val allowedHosts: suspend () -> Set<String> = { emptySet() },
)

fun webTools(http: HttpClient, network: FetchNetwork = FetchNetwork()): List<Tool> = listOf(FetchUrl(http, network))

/**
 * Which addresses fetch_url refuses: anything that isn't the public internet. Loopback reaches Locus's own
 * listeners (the OAuth sign-in redirect, servers in Linux), and the LAN reaches the user's router and
 * other devices, both classic server-side request forgery targets for a prompt-injected agent.
 */
object FetchGuard {
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val b = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> b[0] == 0 || (b[0] == 100 && b[1] in 64..127) || b.all { it == 255 }
            // Unique local fc00::/7; IPv4-mapped addresses come back from Java as Inet4Address.
            is Inet6Address -> b[0] and 0xfe == 0xfc
            else -> false
        }
    }

    /**
     * Why [url] may not be fetched, or null when it may. Only http(s); a host that is (or resolves to) a
     * private address must be one of [allowed]. A name that doesn't resolve is left to the request to fail.
     */
    suspend fun refusal(url: Url, allowed: Set<String>, resolve: suspend (String) -> List<InetAddress>): String? {
        if (url.protocol.name != "http" && url.protocol.name != "https") return "Only http(s) URLs are supported."
        val host = url.host.lowercase().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return "The URL has no host."
        if (host in allowed) return null
        val local = LocalNetwork.isLocalHost(host) || host == "localhost" || host.endsWith(".localhost")
        val addresses = if (local) emptyList() else runCatching { resolve(host) }.getOrDefault(emptyList())
        if (!local && addresses.none(::isPrivate)) return null
        return "Blocked: $host is a local or private network address. fetch_url only reaches the public internet, plus local " +
            "hosts the user set up in Locus (a model provider or MCP server). To check a server you started in Linux, use " +
            "run_command with curl; otherwise ask the user."
    }
}

private class FetchUrl(private val http: HttpClient, private val network: FetchNetwork) : Tool {
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

    /** Redirects are followed here, one hop at a time, so each new address is checked like the first. */
    private val noRedirects: HttpClient by lazy { http.config { followRedirects = false } }

    override fun describe(args: JsonObject): String = "${args.str("method")?.uppercase() ?: "GET"} ${args.str("url")}"

    override suspend fun isObservation(args: JsonObject) = (args.str("method") ?: "GET").equals("GET", ignoreCase = true)

    private class Redirect(val status: Int, val to: Url)

    private class Fetched(val status: Int, val type: String, val bytes: ByteArray, val complete: Boolean, val charset: java.nio.charset.Charset, val url: Url)

    override suspend fun execute(args: JsonObject): ToolResult {
        val start = runCatching { Url(args.str("url").orEmpty().trim()) }.getOrNull()
            ?.takeIf { args.str("url").orEmpty().trim().let { u -> u.startsWith("http://") || u.startsWith("https://") } }
            ?: throw ToolException("Only http(s) URLs are supported.")
        val max = (args.int("max_chars") ?: 20_000).coerceIn(500, 200_000)
        val extra = (args["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.orEmpty()
        // A total limit in real time (on the IO dispatcher), so a server that trickles bytes can't hold the run.
        val fetched = withContext(Dispatchers.IO) {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                fetch(start, HttpMethod.parse((args.str("method") ?: "GET").uppercase()), args.str("body"), extra)
            }
        } ?: throw ToolException("No complete answer within ${TOTAL_TIMEOUT_MS / 1000} s.")
        val raw = String(fetched.bytes, fetched.charset)
        val text = if ("html" in fetched.type) htmlToText(raw, fetched.url.toString()) else raw
        val size = if (fetched.complete) "${fetched.bytes.size} bytes" else "stopped reading at ${MAX_BODY_BYTES / 1_000_000} MB"
        val moved = if (fetched.url != start) " · from ${fetched.url}" else ""
        val header = "HTTP ${fetched.status} · ${fetched.type.ifEmpty { "unknown type" }} · $size$moved\n\n"
        val body = if (text.length > max) text.take(max) + "\n…[truncated; ${text.length - max} more characters, raise max_chars to see more]" else text
        // The response is written by whoever runs the site, so it's marked like web pages (see UntrustedContent).
        return ToolResult(header + UntrustedContent.wrap(body, UntrustedContent.note("from ${fetched.url.host}")), isError = fetched.status >= 400)
    }

    private suspend fun fetch(start: Url, firstMethod: HttpMethod, firstBody: String?, extra: List<Pair<String, String>>): Fetched {
        val allowed = network.allowedHosts().map { it.lowercase() }.toSet()
        var url = start
        var method = firstMethod
        var body = firstBody
        repeat(MAX_REDIRECTS + 1) {
            FetchGuard.refusal(url, allowed, network.resolve)?.let { throw ToolException(it) }
            val sameHost = url.host.equals(start.host, ignoreCase = true)
            val outcome = noRedirects.prepareRequest(url) {
                this.method = method
                header("User-Agent", USER_AGENT)
                // The caller's headers (an API key, say) only ever go to the host it named.
                if (sameHost) extra.forEach { (k, v) -> header(k, v) }
                body?.let { setBody(it) }
            }.execute { response ->
                val status = response.status.value
                val location = response.headers[HttpHeaders.Location]
                if (status in 300..399 && location != null) return@execute Redirect(status, URLBuilder(url).takeFrom(location).build())
                val channel = response.bodyAsChannel()
                // Never more than the cap in memory, whatever the server sends or claims.
                val bytes = channel.readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
                val type = response.contentType()
                Fetched(
                    status, type?.toString().orEmpty(), bytes.copyOf(minOf(bytes.size, MAX_BODY_BYTES)), bytes.size <= MAX_BODY_BYTES,
                    type?.charset() ?: Charsets.UTF_8, url,
                )
            }
            if (outcome is Fetched) return outcome
            val next = (outcome as Redirect).to
            if (url.protocol.name == "https" && next.protocol.name == "http") throw ToolException("Blocked: $url redirects to an insecure http:// address ($next).")
            // 301/302/303 turn into a GET without a body, as browsers do; 307/308 keep both.
            if (outcome.status in 301..303 && method != HttpMethod.Get && method != HttpMethod.Head) {
                method = HttpMethod.Get
                body = null
            }
            url = next
        }
        throw ToolException("Too many redirects (more than $MAX_REDIRECTS).")
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 17) Locus/0.1 (+https://github.com/locus-agent)"

        /** The most of a response body kept in memory; HTML is several times longer than its text. */
        const val MAX_BODY_BYTES = 2_000_000
        const val MAX_REDIRECTS = 5
        const val TOTAL_TIMEOUT_MS = 60_000L
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
