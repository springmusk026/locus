package dev.locus.bench

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fixture web server: one loopback port serving every `*.bench.test` host (the bench HTTP client
 * resolves those names to 127.0.0.1, see [BenchHttp]), virtual-hosted by the Host header.
 *
 * - GET serves `site/<host>/<path>` (`index.html` for a folder); `.json` as JSON, `.html` as HTML.
 *   `site/<host>/<path>@<query>`, when it exists, answers `<path>?<query>` (cursor pagination).
 * - `site/<host>/_redirects` lines `from to` answer 301.
 * - Any other method is recorded and answered with `site/<host>/_post/<path>.json` when it exists, or
 *   `{"ok":true}`.
 * - `site/<host>/_faults` makes the first requests to a path fail, for tasks about flaky networks
 *   (see [Fault]).
 * - [llm], when set, answers every request to `llm.bench.test` (the scripted fake model).
 *
 * Every request is recorded in [received] with the status it got, for the `http` check.
 */
class FixtureWeb(private val sites: Map<String, File>, private val llm: ((Request) -> Response)? = null) : AutoCloseable {
    /** [status] is what the server answered (recorded after serving). */
    data class Request(val method: String, val host: String, val path: String, val query: String?, val body: String, val status: Int = 200)
    data class Response(val status: Int, val contentType: String, val body: String, val headers: Map<String, String> = emptyMap())

    /**
     * One line of `site/<host>/_faults`: `METHOD PATH STATUS TIMES [retry-after=SECONDS] [body=FILE]`.
     * The first [times] requests with that method and path (`/p` matches any query; `/p?x=1` only that
     * query) get [status] instead of the page. The body is `site/<host>/FILE` (served with the status
     * as is, e.g. a 200 with truncated JSON), or a short message saying what went wrong. Lines starting
     * with `#` are comments. Counting is per trial, since each trial starts its own server.
     */
    data class Fault(val method: String, val path: String, val status: Int, val times: Int, val retryAfter: Int?, val bodyFile: String?) {
        fun matches(r: Request) = r.method.equals(method, ignoreCase = true) &&
            (if ('?' in path) "${r.path}?${r.query.orEmpty()}" == path else r.path == path)

        companion object {
            fun parse(line: String): Fault? {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return null
                val parts = t.split(Regex("\\s+"))
                require(parts.size >= 4) { "bad _faults line: $line" }
                val opts = parts.drop(4).associate { it.substringBefore('=') to it.substringAfter('=') }
                require(opts.keys.all { it in setOf("retry-after", "body") }) { "bad _faults option in: $line" }
                return Fault(parts[0].uppercase(), parts[1], parts[2].toInt(), parts[3].toInt(), opts["retry-after"]?.toInt(), opts["body"])
            }
        }
    }

    private val faults: Map<String, List<Pair<Fault, AtomicInteger>>> = sites.mapValues { (_, dir) ->
        File(dir, "_faults").takeIf { it.isFile }?.readLines()?.mapNotNull(Fault::parse)?.map { it to AtomicInteger() }.orEmpty()
    }

    val received = CopyOnWriteArrayList<Request>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val port: Int get() = server.address.port

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r, "bench-web").apply { isDaemon = true } }
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (e: Exception) {
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    /** `http://<host>` for a fixture host: the bench HTTP client routes it here whatever the port (see [BenchHttp]). */
    fun base(host: String) = "http://$host"

    private fun handle(exchange: HttpExchange) {
        val host = exchange.requestHeaders.getFirst("Host").orEmpty().substringBefore(':').lowercase()
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val request = Request(exchange.requestMethod.uppercase(), host, exchange.requestURI.path, exchange.requestURI.rawQuery, body)
        val response = if (host == LLM_HOST && llm != null) {
            llm.invoke(request)
        } else {
            serve(request).also { received += request.copy(status = it.status) }
        }
        response.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        if (response.status in 300..399) exchange.responseHeaders.add("Location", response.body)
        val bytes = if (response.status in 300..399) ByteArray(0) else response.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", response.contentType)
        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private fun serve(request: Request): Response {
        val root = sites[request.host] ?: return Response(404, "text/plain", "No such site: ${request.host}")
        faults[request.host].orEmpty().firstOrNull { (f, n) -> f.matches(request) && n.incrementAndGet() <= f.times }
            ?.let { (f, _) -> return fault(root, f) }
        File(root, "_redirects").takeIf { it.isFile }?.readLines()?.map { it.trim().split(Regex("\\s+")) }
            ?.firstOrNull { it.size == 2 && it[0] == request.path }?.let { return Response(301, "text/plain", it[1]) }
        val clean = request.path.trimStart('/')
        if (".." in clean.split('/')) return Response(400, "text/plain", "Bad path")
        if (request.method != "GET" && request.method != "HEAD") {
            val canned = File(root, "_post/$clean.json")
            return if (canned.isFile) Response(200, "application/json", canned.readText()) else Response(200, "application/json", "{\"ok\":true}")
        }
        // A page that depends on its query (`/api/items?cursor=x`) is the file `api/items@cursor=x`.
        val byQuery = request.query?.takeIf { '/' !in it }?.let { File(root, "$clean@$it") }?.takeIf { it.isFile }
        if (byQuery == null && request.query != null) {
            val plain = File(root, clean)
            // The path has query pages, but not this one: an unknown cursor is an error, not the first page.
            if (plain.parentFile?.listFiles().orEmpty().any { it.name.startsWith(plain.name + "@") }) {
                return Response(400, "application/json", "{\"error\": \"unknown query: ${request.query.replace("\"", "")}\"}")
            }
        }
        var file = byQuery ?: File(root, clean)
        if (file.isDirectory) file = File(file, "index.html")
        if (!file.isFile || file.name.startsWith("_")) return Response(404, "text/html", "<html><body><h1>404 Not Found</h1></body></html>")
        return Response(200, contentType(file), file.readText())
    }

    private fun fault(root: File, f: Fault): Response {
        val headers = f.retryAfter?.let { mapOf("Retry-After" to it.toString()) }.orEmpty()
        f.bodyFile?.let { name ->
            val file = File(root, name)
            require(".." !in name && file.isFile) { "fault body $name missing" }
            return Response(f.status, contentType(file), file.readText(), headers)
        }
        val retry = f.retryAfter?.let { " Retry after $it seconds." }.orEmpty()
        val message = when (f.status) {
            429 -> "Too Many Requests.$retry Your request was not processed."
            503 -> "Service Unavailable: the server is temporarily overloaded.$retry Your request was not processed."
            in 500..599 -> "Server error ${f.status}.$retry Your request was not processed."
            else -> "Error ${f.status}."
        }
        return Response(f.status, "application/json", "{\"error\": \"$message\"}", headers)
    }

    private fun contentType(file: File) = when (file.extension) {
        "json" -> "application/json"
        // An API path without an extension (/v1/orders) that holds JSON.
        "" -> if (file.readText().trimStart().let { it.startsWith("{") || it.startsWith("[") }) "application/json" else "text/html; charset=utf-8"
        "txt", "csv" -> "text/plain; charset=utf-8"
        else -> "text/html; charset=utf-8"
    }

    override fun close() = server.stop(0)

    companion object {
        const val LLM_HOST = "llm.bench.test"
    }
}
