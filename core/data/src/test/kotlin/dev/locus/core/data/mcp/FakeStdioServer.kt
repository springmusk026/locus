package dev.locus.core.data.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A fake MCP server behind a fake process: newline-delimited JSON-RPC over in-memory pipes, as the
 * stdio transport specifies. It answers initialize, tools/list and tools/call for:
 * - `echo` (read-only): returns its `text` argument,
 * - `env`: returns the value of the environment variable named by `name`,
 * - `crash`: prints [crashMessage] to stderr and exits with code 3 without answering.
 */
class FakeStdioServer(
    /** The first [failStarts] launches print to stderr and exit before the handshake. */
    var failStarts: Int = 0,
    /** The first [crashOnList] tools/list requests crash the process. */
    var crashOnList: Int = 0,
    var crashMessage: String = "boom: fatal error",
) : StdioLauncher {
    val launches = CopyOnWriteArrayList<LocalLaunch>()
    val processes = CopyOnWriteArrayList<Proc>()

    /** Every line the client wrote, across processes. */
    val received = CopyOnWriteArrayList<String>()
    private val listCount = AtomicInteger()

    override suspend fun launch(launch: LocalLaunch): StdioProcess {
        launches += launch
        return Proc(launch.env, failing = launches.size <= failStarts).also { processes += it; it.start() }
    }

    inner class Proc(private val env: Map<String, String>, private val failing: Boolean) : StdioProcess {
        private val toServer = Pipe.open()
        private val toClient = Pipe.open()
        private val errPipe = Pipe.open()
        override val stdin: OutputStream = Channels.newOutputStream(toServer.sink())
        override val stdout: InputStream = Channels.newInputStream(toClient.source())
        override val stderr: InputStream = Channels.newInputStream(errPipe.source())
        private val out = Channels.newOutputStream(toClient.sink())
        private val err = Channels.newOutputStream(errPipe.sink())

        @Volatile private var exit: Int? = null
        @Volatile var stoppedByClient = false
        override val isAlive get() = exit == null
        override val exitCode get() = exit

        override suspend fun stop() {
            stoppedByClient = true
            runCatching { stdin.close() }
            exit(exit ?: 143)
        }

        fun start() {
            thread(isDaemon = true) { serve() }
        }

        @Synchronized
        private fun exit(code: Int) {
            if (exit != null) return
            exit = code
            runCatching { out.close() }
            runCatching { err.close() }
            runCatching { toServer.source().close() }
        }

        private fun stderr(text: String) = runCatching { err.write((text + "\n").toByteArray()); err.flush() }

        private fun send(obj: JsonObject) {
            val line = obj.toString()
            check('\n' !in line)
            synchronized(out) {
                out.write((line + "\n").toByteArray())
                out.flush()
            }
        }

        private fun serve() {
            stderr("fake server starting (stderr is just logging)")
            if (failing) {
                stderr("npm ERR! network request failed")
                exit(1)
                return
            }
            val reader = Channels.newInputStream(toServer.source()).bufferedReader()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line
                    val msg = Json.parseToJsonElement(line).jsonObject
                    val id = msg["id"] ?: continue // a notification
                    when (msg["method"]?.jsonPrimitive?.content) {
                        "initialize" -> send(result(id) {
                            put("protocolVersion", msg["params"]!!.jsonObject["protocolVersion"]!!)
                            putJsonObject("capabilities") { putJsonObject("tools") {} }
                            putJsonObject("serverInfo") { put("name", "fake"); put("version", "1.0") }
                        })
                        "tools/list" -> {
                            if (listCount.incrementAndGet() <= crashOnList) {
                                stderr(crashMessage)
                                exit(3)
                                return
                            }
                            send(result(id) {
                                putJsonArray("tools") {
                                    add(tool("echo", readOnly = true))
                                    add(tool("env", readOnly = false))
                                    add(tool("crash", readOnly = false))
                                }
                            })
                        }
                        "tools/call" -> {
                            val params = msg["params"]!!.jsonObject
                            val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                            when (params["name"]?.jsonPrimitive?.content) {
                                "crash" -> {
                                    stderr(crashMessage + " " + env.values.joinToString(" "))
                                    exit(3)
                                    return
                                }
                                "env" -> send(text(id, env[args["name"]?.jsonPrimitive?.content].orEmpty()))
                                else -> send(text(id, args["text"]?.jsonPrimitive?.content.orEmpty()))
                            }
                        }
                        else -> send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", id)
                            putJsonObject("error") { put("code", -32601); put("message", "Method not found") }
                        })
                    }
                }
                exit(0) // stdin closed: exit promptly, as the spec asks
            } catch (e: Exception) {
                exit(exit ?: 0)
            }
        }
    }

    private fun result(id: JsonElement, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", buildJsonObject(body))
    }

    private fun text(id: JsonElement, text: String) = result(id) {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
    }

    private fun tool(name: String, readOnly: Boolean) = buildJsonObject {
        put("name", name)
        put("description", "$name tool")
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
        }
        putJsonObject("annotations") { put("readOnlyHint", JsonPrimitive(readOnly)) }
    }
}
