package dev.locus.core.data.auth

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.locus.core.agent.auth.OAuthRedirect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
 * sign-in's state is taken, and requests are read up to 8 KB.
 *
 * The redirect arrives while the browser is in front, and Android blocks network access for apps in
 * the background (the token exchange then fails with "Unable to resolve host"). So the browser is
 * answered at once with a page that returns to Locus ([RETURN_URI]), and the callback is handled only
 * once Locus is on screen again.
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
                respondReturn(s)
                s.close()
                val back = withTimeoutOrNull(10 * 60_000L) {
                    ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
                }
                if (back == null) throw failure("Sign-in wasn't finished: Locus wasn't reopened. Try again.")
                onCallback(params)
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

    /** Sends the browser back to Locus, with a button in case the automatic switch is blocked. */
    private fun respondReturn(socket: Socket) = respond(
        socket,
        200,
        "Almost done. Locus finishes signing in when you return to it.</p>" +
            "<p style='margin:2em'><a href='$RETURN_URI' style='font:600 18px sans-serif;padding:14px 24px;border-radius:24px;" +
            "background:#1f6f5c;color:#fff;text-decoration:none'>Back to Locus</a></p>" +
            "<script>location.href='$RETURN_URI'</script><p>",
    )

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
        /** Opens Locus from the browser (an intent filter on MainActivity); it carries no data. */
        const val RETURN_URI = "locus://signed-in"

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
