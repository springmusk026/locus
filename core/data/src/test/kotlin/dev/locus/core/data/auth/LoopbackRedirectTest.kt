package dev.locus.core.data.auth

import dev.locus.core.testing.TestContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign-in listener keeps working while another connection dribbles bytes (docs/design/network.md, "The sign-in listener"). */
class LoopbackRedirectTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun open() = LoopbackRedirect.open(TestContext(tmp.newFolder()), port = 0, path = "/callback", anyPortIfBusy = true)!!

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    /** Sends the request line and reads the answer's status line. */
    private fun get(port: Int, target: String): String = Socket(loopback, port).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
        s.getInputStream().bufferedReader().readLine().orEmpty()
    }

    /** Opens a connection and sends one byte every [everyMs], like an app keeping the listener busy. */
    private suspend fun dribble(port: Int, everyMs: Long, bytes: Int) = withContext(Dispatchers.IO) {
        Socket(loopback, port).use { s ->
            repeat(bytes) {
                runCatching { s.getOutputStream().write('G'.code); s.getOutputStream().flush() }
                delay(everyMs)
            }
        }
    }

    @Test
    fun `a slow connection does not hold up the real redirect`() = runBlocking {
        val listener = open()
        listener.use {
            val port = listener.redirectUri.substringAfterLast(':').substringBefore('/').toInt()
            val waiting = async(Dispatchers.IO) { listener.awaitRedirect("s1", waitMs = 20_000) }
            val slow = launch { dribble(port, everyMs = 500, bytes = 30) }
            delay(700)

            val started = System.nanoTime()
            val status = withContext(Dispatchers.IO) { get(port, "/callback?code=abc&state=s1") }
            val params = withTimeout(5_000) { waiting.await() }

            assertTrue(status.startsWith("HTTP/1.1 200"), status)
            assertEquals(mapOf("code" to "abc", "state" to "s1"), params)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "the redirect waited behind the slow connection")
            slow.cancelAndJoin()
        }
    }

    @Test
    fun `a request for the wrong path or state is answered and ignored`() = runBlocking {
        val listener = open()
        listener.use {
            val port = listener.redirectUri.substringAfterLast(':').substringBefore('/').toInt()
            val waiting = async(Dispatchers.IO) { listener.awaitRedirect("s1", waitMs = 20_000) }
            assertTrue(withContext(Dispatchers.IO) { get(port, "/callback?code=abc&state=other") }.contains("404"))
            assertTrue(withContext(Dispatchers.IO) { get(port, "/favicon.ico") }.contains("404"))
            assertTrue(withContext(Dispatchers.IO) { get(port, "/callback?code=ok&state=s1") }.contains("200"))
            assertEquals("ok", withTimeout(5_000) { waiting.await() }!!["code"])
        }
    }

    @Test
    fun `cancelling returns within about a second while a connection is mid-request`() = runBlocking {
        val listener = open()
        listener.use {
            val port = listener.redirectUri.substringAfterLast(':').substringBefore('/').toInt()
            val waiting = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { listener.awaitRedirect("s1", waitMs = 60_000) }
            val slow = launch { dribble(port, everyMs = 200, bytes = 40) }
            delay(600)

            val started = System.nanoTime()
            withTimeout(5_000) { waiting.cancelAndJoin() }
            val ms = (System.nanoTime() - started) / 1_000_000
            slow.cancelAndJoin()
            assertTrue(ms < 1_000, "cancel took $ms ms")
        }
    }

    @Test
    fun `gives up after the wait with nothing received`() = runBlocking {
        open().use { listener -> assertNull(listener.awaitRedirect("s1", waitMs = 400)) }
    }
}
