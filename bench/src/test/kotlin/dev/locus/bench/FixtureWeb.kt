package dev.locus.bench

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The fixture web server: one loopback port serving every `*.bench.test` host (the bench HTTP client
 * resolves those names to 127.0.0.1, see [BenchHttp]), virtual-hosted by the Host header.
 *
 * - GET serves `site/<host>/<path>` (`index.html` for a folder); `.json` as JSON, `.html` as HTML.
 * - `site/<host>/_redirects` lines `from to` answer 301.
 * - Any other method is recorded and answered with `site/<host>/_post/<path>.json` when it exists, or
 *   `{"ok":true}`.
 * - [llm], when set, answers every request to `llm.bench.test` (the scripted fake model).
 *
 * Every request is recorded in [received], for the `http` check.
 */
class FixtureWeb(private val sites: Map<String, File>, private val llm: ((Request) -> Response)? = null) : AutoCloseable {
    data class Request(val method: String, val host: String, val path: String, val query: String?, val body: String)
    data class Response(val status: Int, val contentType: String, val body: String)

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
            received += request
            serve(request)
        }
        if (response.status in 300..399) exchange.responseHeaders.add("Location", response.body)
        val bytes = if (response.status in 300..399) ByteArray(0) else response.body.toByteArray()
        exchange.responseHeaders.add("Content-Type", response.contentType)
        exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private fun serve(request: Request): Response {
        val root = sites[request.host] ?: return Response(404, "text/plain", "No such site: ${request.host}")
        File(root, "_redirects").takeIf { it.isFile }?.readLines()?.map { it.trim().split(Regex("\\s+")) }
            ?.firstOrNull { it.size == 2 && it[0] == request.path }?.let { return Response(301, "text/plain", it[1]) }
        val clean = request.path.trimStart('/')
        if (".." in clean.split('/')) return Response(400, "text/plain", "Bad path")
        if (request.method != "GET" && request.method != "HEAD") {
            val canned = File(root, "_post/$clean.json")
            return if (canned.isFile) Response(200, "application/json", canned.readText()) else Response(200, "application/json", "{\"ok\":true}")
        }
        var file = File(root, clean)
        if (file.isDirectory) file = File(file, "index.html")
        if (!file.isFile || file.name.startsWith("_")) return Response(404, "text/html", "<html><body><h1>404 Not Found</h1></body></html>")
        return Response(200, contentType(file), file.readText())
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
