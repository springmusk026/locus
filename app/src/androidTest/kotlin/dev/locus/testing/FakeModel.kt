package dev.locus.testing

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dev.locus.core.data.di.NetworkModule
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.testing.ScriptedProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Singleton

/**
 * The model provider every instrumentation test talks to: a Chat Completions provider at llm.test,
 * answered in-process by a [Script]. Nothing reaches the network and no key is needed. Requests to
 * any other host get a 404, so a test can never call a real service by accident.
 *
 * It's static, one per test process; the orchestrator gives each test its own process, and
 * [LocusTestRule] resets it before each test anyway.
 */
object FakeModel {
    /** The provider the tests save in Settings; the same one the JVM tests use ([ScriptedProvider]). */
    val provider: ProviderConfig = ScriptedProvider.config.copy(contextWindow = 1_000_000)

    /** Decides the reply to one chat request: an SSE body from [text] or [toolCall]. May suspend (see [Gate]). */
    fun interface Script {
        suspend fun reply(request: FakeRequest): String
    }

    @Volatile private var script: Script = Script { text("OK") }
    private val received = CopyOnWriteArrayList<FakeRequest>()

    /** Every chat request so far, oldest first. */
    val requests: List<FakeRequest> get() = received.toList()

    fun reset() {
        script = Script { text("OK") }
        received.clear()
    }

    fun script(script: Script) {
        this.script = script
    }

    fun text(text: String): String = ScriptedProvider.text(text)

    fun toolCall(id: String, tool: String, argumentsJson: String): String = ScriptedProvider.toolCall(id, tool, argumentsJson)

    internal suspend fun handle(scope: MockRequestHandleScope, data: HttpRequestData): HttpResponseData = with(scope) {
        if (data.url.host != "llm.test" || !data.url.encodedPath.endsWith("/chat/completions")) {
            return respond("", HttpStatusCode.NotFound)
        }
        val body = (data.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        val request = FakeRequest(Json.parseToJsonElement(body) as JsonObject)
        received += request
        val reply = script.reply(request)
        respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    /**
     * A task of [total] tool steps, each writing its own file (`steps/step-N.txt`), then a final [answer].
     * Each step's call id is `call_N`, from the tool results already in the chat, so a turn that's
     * continued after a restart picks up where it stopped and never repeats an id. [gates] hold the
     * request made after that many steps until the test opens them. Requests that aren't the task's
     * (a side question, marked with [SIDE_MARK]) get [sideAnswer]. [stepDelayMs] stands in for the
     * model's thinking time, so a test can act while the task runs.
     */
    fun steps(total: Int, answer: String = "All $total steps done.", gates: Map<Int, Gate> = emptyMap(), sideAnswer: String = "Side answer.", stepDelayMs: Long = 0): Script = Script { r ->
        if (r.isSideQuestion) return@Script text(sideAnswer)
        val done = r.toolResults
        gates[done]?.pass()
        if (stepDelayMs > 0) delay(stepDelayMs)
        if (done < total) {
            val n = done + 1
            toolCall("call_$n", "write_file", """{"path":"steps/step-$n.txt","content":"step $n\n"}""")
        } else {
            text(answer)
        }
    }

    /** Put in a side question's text so [steps] answers it as one. */
    const val SIDE_MARK = "[side]"
}

/** One chat request, parsed. */
class FakeRequest(val json: JsonObject) {
    val messages: List<JsonObject> = (json["messages"] as? JsonArray).orEmpty().map { it as JsonObject }

    /** Tool results in the history: how many steps the task has taken. */
    val toolResults: Int = messages.count { (it["role"] as? JsonPrimitive)?.content == "tool" }

    /** The newest user message's text. */
    val lastUserText: String = messages.lastOrNull { (it["role"] as? JsonPrimitive)?.content == "user" }?.get("content")?.toString().orEmpty()

    val isSideQuestion: Boolean get() = FakeModel.SIDE_MARK in lastUserText

    /** The whole request body, for "did the model see X" checks. */
    override fun toString(): String = json.toString()
}

/**
 * Holds the model's reply at a point the test chooses: [pass] (called by the script) marks it
 * [reached] and waits until the test calls [open].
 */
class Gate {
    private val reachedSignal = CompletableDeferred<Unit>()
    private val openSignal = CompletableDeferred<Unit>()

    val reached: Boolean get() = reachedSignal.isCompleted

    suspend fun pass() {
        reachedSignal.complete(Unit)
        openSignal.await()
    }

    fun open() {
        openSignal.complete(Unit)
    }

    /** Blocks the test thread until the script reaches this gate. */
    fun awaitReached(timeoutMs: Long = 30_000) = runBlocking { withTimeout(timeoutMs) { reachedSignal.await() } }
}

/**
 * Replaces the app's HTTP clients for every test in this source set
 * (https://developer.android.com/training/dependency-injection/hilt-testing#testinstallin).
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkModule::class])
object FakeNetworkModule {
    /** Providers with their own network settings derive engines from it; the tests' provider has none. */
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun httpClient(): HttpClient = HttpClient(MockEngine { request -> FakeModel.handle(this, request) }) { expectSuccess = false }
}
