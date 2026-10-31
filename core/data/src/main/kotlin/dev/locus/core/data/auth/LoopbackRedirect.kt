package dev.locus.core.data.auth

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.locus.core.agent.auth.OAuthRedirect
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * A one-shot listener on 127.0.0.1 for a browser sign-in's redirect (RFC 8252 loopback), open for the
 * length of one sign-in. Anything else that connects (preconnects, favicon requests, or a web page or
 * app poking the port) is answered and ignored: only a request to exactly [path] carrying this
 * sign-in's state is taken, requests are read up to 8 KB, and "Signed in" is shown only once the
 * callback has been handled.
 */
internal class LoopbackRedirect private constructor(private val server: ServerSocket, private val path: String) : Closeable {

    val redirectUri: String get() = "http://127.0.0.1:${server.localPort}$path"

    /** Waits up to five minutes for the redirect with [state] and hands its query to [onCallback]. */
    suspend fun await(state: String, failure: (String) -> Exception, onCallback: suspend (Map<String, String>) -> Unit) {
        server.soTimeout = 1_000
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use { s ->
                val target = try {
                    s.soTimeout = 3_000
                    readRequestTarget(s)
                } catch (_: IOException) {
                    null
                } ?: return@use
                val params = OAuthRedirect.params(target, path)
                if (params == null || params["state"] != state) {
                    respond(s, 404, "Not found.")
                    return@use
                }
                val outcome = runCatching { onCallback(params) }
                respond(s, 200, if (outcome.isSuccess) "Signed in. You can go back to Locus." else "Sign-in failed. Go back to Locus for details.")
                outcome.getOrThrow()
                return
            }
        }
        throw failure("Sign-in timed out. Try again.")
    }

    override fun close() = server.close()

    /** The request target from "GET /path?query HTTP/1.1", reading at most 8 KB. */
    private fun readRequestTarget(socket: Socket): String? {
        val input = socket.getInputStream()
        val line = StringBuilder()
        while (line.length < 8_192) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
        }
        return line.split(' ').getOrNull(1)
    }

    private fun respond(socket: Socket, status: Int, message: String) {
        val body = "<!doctype html><meta name=viewport content='width=device-width'><title>Locus</title><p style='font:18px sans-serif;margin:2em'>$message</p>"
        val reason = if (status == 200) "OK" else "Not Found"
        runCatching {
            socket.getOutputStream().write(
                ("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$body").toByteArray(),
            )
        }
    }

    companion object {
        /**
         * Listens on [port], or on any free port when it's taken and [anyPortIfBusy] (only for flows
         * whose redirect may change port between sign-ins). Null when the port is busy otherwise.
         */
        fun open(port: Int, path: String, anyPortIfBusy: Boolean): LoopbackRedirect? {
            val loopback = InetAddress.getByName("127.0.0.1")
            val socket = try {
                ServerSocket(port, 8, loopback)
            } catch (_: BindException) {
                if (!anyPortIfBusy) return null
                ServerSocket(0, 8, loopback)
            }
            return LoopbackRedirect(socket, path)
        }

        fun openBrowser(context: Context, url: String) {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun bringAppBack(context: Context) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                ?.let { runCatching { context.startActivity(it) } }
        }
    }
}
