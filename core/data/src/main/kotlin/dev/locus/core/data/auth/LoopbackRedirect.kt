package dev.locus.core.data.auth

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.locus.core.agent.auth.OAuthRedirect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * A one-shot listener on 127.0.0.1 for a browser sign-in's redirect (RFC 8252 loopback), open for the
 * length of one sign-in. Anything else that connects (preconnects, favicon requests, or a web page or
 * app poking the port) is answered and ignored: only a request to exactly [path] carrying this
 * sign-in's state is taken, and requests are read up to 8 KB, in 3 seconds, each on its own coroutine
 * (docs/design/network.md, "The sign-in listener").
 *
 * The redirect arrives while the browser is in front, and Android blocks network access for apps in
 * the background (the token exchange then fails with "Unable to resolve host"). So the browser is
 * answered at once with a page that returns to Locus ([returnUri]), and the callback is handled only
 * once Locus is on screen again.
 */
internal class LoopbackRedirect private constructor(
    private val server: ServerSocket,
    private val path: String,
    private val returnUri: String,
) : Closeable {

    val redirectUri: String get() = "http://127.0.0.1:${server.localPort}$path"

    /** Waits up to five minutes for the redirect with [state] and hands its query to [onCallback]. */
    suspend fun await(state: String, failure: (String) -> Exception, onCallback: suspend (Map<String, String>) -> Unit) {
        val params = awaitRedirect(state, SIGN_IN_WAIT_MS) ?: throw failure("Sign-in timed out. Try again.")
        val back = withTimeoutOrNull(10 * 60_000L) {
            ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
        }
        if (back == null) throw failure("Sign-in wasn't finished: Locus wasn't reopened. Try again.")
        onCallback(params)
    }

    /**
     * The query of the first request to [path] that carries [state], answered with the page that returns to
     * Locus, or null after [waitMs]. Every connection is read on its own coroutine, so one that sends a
     * byte now and then (any app on the phone, or a program in Linux, can open one) holds up neither the
     * browser's redirect nor a cancel. A request gets [REQUEST_MS] in all, not per read, and at most
     * [MAX_OPEN] are read at a time. Cancelling returns within about a [POLL_MS] poll.
     */
    internal suspend fun awaitRedirect(state: String, waitMs: Long): Map<String, String>? = coroutineScope {
        val found = CompletableDeferred<Map<String, String>>()
        val open = AtomicInteger(0)
        val acceptor = launch(Dispatchers.IO) {
            server.soTimeout = POLL_MS
            while (isActive) {
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (open.incrementAndGet() > MAX_OPEN) {
                    open.decrementAndGet()
                    runCatching { socket.close() }
                    continue
                }
                launch(Dispatchers.IO) {
                    try {
                        serve(socket, state)?.let { found.complete(it) }
                    } finally {
                        runCatching { socket.close() }
                        open.decrementAndGet()
                    }
                }
            }
        }
        try {
            withTimeoutOrNull(waitMs) { found.await() }
        } finally {
            acceptor.cancel()
        }
    }

    /** Reads one request and answers it; the redirect's query if it is the one for [state]. */
    private suspend fun serve(socket: Socket, state: String): Map<String, String>? {
        val target = try {
            readRequestTarget(socket)
        } catch (_: IOException) {
            null
        } ?: return null
        val params = OAuthRedirect.params(target, path)
        if (params == null || params["state"] != state) {
            respond(socket, 404, "Not found.")
            return null
        }
        respondReturn(socket)
        return params
    }

    override fun close() = server.close()

    /**
     * The request target from "GET /path?query HTTP/1.1", reading at most 8 KB in at most [REQUEST_MS] (null
     * when it takes longer). Reads in short polls so a cancel is noticed within about [POLL_MS].
     */
    private suspend fun readRequestTarget(socket: Socket): String? {
        val input = socket.getInputStream()
        val line = StringBuilder()
        val stopAt = System.nanoTime() + REQUEST_MS * 1_000_000L
        socket.soTimeout = POLL_MS
        while (line.length < 8_192) {
            coroutineContext.ensureActive()
            if (System.nanoTime() >= stopAt) return null
            val c = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                continue
            }
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
            "<p style='margin:2em'><a href='$returnUri' style='font:600 18px sans-serif;padding:14px 24px;border-radius:24px;" +
            "background:#1f6f5c;color:#fff;text-decoration:none'>Back to Locus</a></p>" +
            "<script>location.href='$returnUri'</script><p>",
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
        private const val SIGN_IN_WAIT_MS = 5 * 60_000L

        /** All the time one connection gets to send its request line; a browser sends it at once. */
        internal const val REQUEST_MS = 3_000L

        /** How long a blocking accept or read lasts before it checks for a cancel. */
        private const val POLL_MS = 250

        /** Connections read at the same time; more are closed at once. */
        private const val MAX_OPEN = 16

        /**
         * Opens Locus from the browser (an intent filter on MainActivity); it carries no data. Debug
         * builds are a separate app (dev.locus.debug) with their own scheme, so a sign-in in one never
         * opens the other. Matches the returnScheme placeholder in app/build.gradle.kts.
         */
        fun returnUri(packageName: String): String =
            if (packageName.endsWith(".debug")) "locus-debug://signed-in" else "locus://signed-in"

        /**
         * Listens on [port], or on any free port when it's taken and [anyPortIfBusy] (only for flows
         * whose redirect may change port between sign-ins). Null when the port is busy otherwise.
         */
        fun open(context: Context, port: Int, path: String, anyPortIfBusy: Boolean): LoopbackRedirect? {
            val loopback = InetAddress.getByName("127.0.0.1")
            val socket = try {
                ServerSocket(port, 8, loopback)
            } catch (_: BindException) {
                if (!anyPortIfBusy) return null
                ServerSocket(0, 8, loopback)
            }
            return LoopbackRedirect(socket, path, returnUri(context.packageName))
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
