package dev.locus.core.llm

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readUTF8Line
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

internal data class SseEvent(val event: String?, val data: String)

/**
 * How long a model stream may go without a single byte before Locus treats the connection as dead and
 * the run's retry takes over (docs/design/stream-timeouts.md). Any line counts, including SSE comments
 * and keepalive events, so a model thinking silently on a provider that keeps the line alive is never
 * cut off.
 *
 * - [silentMs] (5 minutes) until the stream has shown that it sends keepalives: the stream idle timeout
 *   of Codex (`DEFAULT_STREAM_IDLE_TIMEOUT_MS = 300_000`) and Claude Code's body idle timeout for
 *   providers other than Anthropic. Chat Completions servers (OpenAI, local servers) may send nothing
 *   while a reasoning model thinks or a long prompt is read.
 * - [keepaliveMs] (3 minutes) once it has: an Anthropic `ping`, an OpenAI Responses `keepalive`, or an
 *   SSE comment line (OpenRouter's `: OPENROUTER PROCESSING`). Claude Code's byte-level watchdog uses
 *   180 s on the Anthropic API, which sends pings.
 *
 * [clock] times the limit: real time by default, whatever dispatcher the caller is on, and the read
 * stays on the caller's dispatcher. A coroutine timeout would follow the caller's clock, which in a test
 * on virtual time fires at once while a mock engine answers on a real thread; tests of the timeout
 * itself pass a clock on their test scheduler.
 */
data class StreamIdle(
    val silentMs: Long = SILENT_MS,
    val keepaliveMs: Long = KEEPALIVE_MS,
    val clock: IdleClock = IdleClock.RealTime,
) {
    companion object {
        const val SILENT_MS = 300_000L
        const val KEEPALIVE_MS = 180_000L
    }
}

/** Runs an action after a delay unless cancelled first; see [StreamIdle.clock]. */
fun interface IdleClock {
    /** Runs [action] after [ms]; the returned function cancels it. */
    fun after(ms: Long, action: () -> Unit): () -> Unit

    companion object {
        private val timers by lazy {
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "locus-stream-idle").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }
        }

        /** Wall-clock time, on one shared daemon thread. */
        val RealTime = IdleClock { ms, action ->
            val task = timers.schedule(action, ms, TimeUnit.MILLISECONDS)
            return@IdleClock { task.cancel(false) }
        }
    }
}

/**
 * A stream that sent nothing for [idleMs]. An [java.io.IOException], so the agent loop retries it like a
 * dropped connection.
 */
class StreamIdleException(val idleMs: Long) :
    java.io.IOException("no data from the provider for ${idleMs / 1000} s, so the connection was treated as lost")

/**
 * How much of one model stream Locus holds in memory. Real streams stay far below these (a long answer
 * is well under 1 MB; a large tool call, a whole file written in one go, a few MB), so they only stop a
 * broken or hostile server or proxy that sends one endless line or event, or a model stuck emitting
 * the same tool argument, before it runs the phone out of memory. Over a limit, the stream fails with
 * [streamTooLarge], which the run retries like other transient provider failures and then reports.
 */
data class StreamLimits(
    /** One SSE line, in UTF-8 bytes. */
    val lineBytes: Int = 8 * MB,
    /** One event's `data:` lines together, in characters. */
    val eventChars: Int = 16 * MB,
    /** Everything one stream sends, in characters: bounds the providers' own lists of finished items. */
    val streamChars: Long = 64L * MB,
    /** One tool call's streamed arguments. */
    val toolArgsChars: Int = 8 * MB,
    /** One text or thinking block (or a turn's text, where the format has no blocks). */
    val textChars: Int = 16 * MB,
) {
    companion object {
        const val MB = 1024 * 1024
        val Default = StreamLimits()
    }
}

/** The error for a stream past one of [StreamLimits]: [what] ("a line", "a tool call's arguments") was over [limit] units. */
internal fun streamTooLarge(what: String, limit: Long, unit: String = "characters"): LlmException = LlmException(
    "The model's stream sent $what over ${limit / StreamLimits.MB} MB ($limit $unit), more than any real reply, so Locus stopped reading it.",
    retryable = true,
)

/** Appends [s], or fails with [streamTooLarge] when that would take this past [limit] characters. */
internal fun StringBuilder.appendCapped(s: String, limit: Int, what: String): StringBuilder {
    if (length.toLong() + s.length > limit) throw streamTooLarge(what, limit.toLong())
    return append(s)
}

/**
 * Reads a `text/event-stream` body line by line. Done by hand rather than with Ktor's SSE plugin
 * because LLM APIs stream from POST requests and return JSON error bodies on non-2xx statuses,
 * which we want to surface verbatim.
 */
internal suspend fun HttpResponse.forEachSseEvent(
    idle: StreamIdle = StreamIdle(),
    limits: StreamLimits = StreamLimits.Default,
    onEvent: suspend (SseEvent) -> Unit,
) = bodyAsChannel().forEachSseEvent(idle, limits, onEvent)

/** [HttpResponse.forEachSseEvent] on the body itself; split out so the idle timeout is testable on virtual time. */
internal suspend fun ByteReadChannel.forEachSseEvent(
    idle: StreamIdle = StreamIdle(),
    limits: StreamLimits = StreamLimits.Default,
    onEvent: suspend (SseEvent) -> Unit,
) {
    var event: String? = null
    val data = StringBuilder()
    var keepalives = false
    var total = 0L
    while (true) {
        val limit = if (keepalives) idle.keepaliveMs else idle.silentMs
        val line = try {
            readLineWithin(limit, idle.clock, limits.lineBytes)
        } catch (_: TooLongLineException) {
            throw streamTooLarge("a single line", limits.lineBytes.toLong(), "bytes")
        } ?: break
        total += line.length
        if (total > limits.streamChars) throw streamTooLarge("in all", limits.streamChars)
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    if (isKeepalive(event, data)) keepalives = true
                    onEvent(SseEvent(event, data.toString()))
                }
                event = null
                data.clear()
            }
            line.startsWith(":") -> keepalives = true
            line.startsWith("event:") -> event = line.substringAfter(":").trim()
            line.startsWith("data:") -> {
                val value = line.substringAfter(":").removePrefix(" ")
                if (data.length.toLong() + value.length + 1 > limits.eventChars) throw streamTooLarge("one event", limits.eventChars.toLong())
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }
    if (data.isNotEmpty()) onEvent(SseEvent(event, data.toString()))
}

/**
 * The next line, or null at the end of the body; [StreamIdleException] when nothing arrives within
 * [limitMs]. The timer cancels only this read (its own scope), never the caller's coroutine.
 */
private suspend fun ByteReadChannel.readLineWithin(limitMs: Long, clock: IdleClock, maxBytes: Int): String? {
    val timedOut = AtomicBoolean(false)
    return try {
        coroutineScope {
            val read = coroutineContext.job
            val cancel = clock.after(limitMs) {
                timedOut.set(true)
                read.cancel()
            }
            try {
                readUTF8Line(maxBytes)
            } finally {
                cancel()
            }
        }
    } catch (e: CancellationException) {
        if (!timedOut.get()) throw e
        // The caller itself may have been cancelled meanwhile; that wins.
        currentCoroutineContext().ensureActive()
        throw StreamIdleException(limitMs)
    }
}

/** Anthropic's `ping` event and OpenAI Responses' `keepalive`, named by `event:` or by the data's `type`. */
private fun isKeepalive(event: String?, data: CharSequence): Boolean =
    event == "ping" || event == "keepalive" || (data.length < 100 && KEEPALIVE_TYPE.containsMatchIn(data))

private val KEEPALIVE_TYPE = Regex(""""type"\s*:\s*"(ping|keepalive)"""")


internal suspend fun HttpResponse.throwIfFailed(provider: String) {
    val code = status.value
    if (code in 200..299) return
    val body = runCatching { bodyAsText() }.getOrDefault("").take(2000)
    throw httpError(provider, code, body, retryAfterMs())
}

/** The wait this response asked for ([retryAfterMs]); headers are case-insensitive in Ktor. */
internal fun HttpResponse.retryAfterMs(): Long? = retryAfterMs(headers["retry-after-ms"], headers["retry-after"])
