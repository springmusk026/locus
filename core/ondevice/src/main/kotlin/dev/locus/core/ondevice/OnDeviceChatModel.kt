package dev.locus.core.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.benchmark
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import com.google.ai.edge.litertlm.Conversation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one LiteRT-LM engine loaded (loading takes seconds and gigabytes) and hands out chat
 * models that use it. Only one model is loaded at a time.
 *
 * Every LiteRT-LM call (initialize, createConversation, sendMessageAsync, close) runs on [dispatcher],
 * one dedicated background thread, so engine work never lands on the main thread or ties up the
 * shared IO pool, and native calls are serialised.
 */
@Singleton
class OnDeviceEngines @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val models: OnDeviceModels,
) {
    private val lock = Mutex()
    private var loaded: LoadedEngine? = null

    private class LoadedEngine(val modelId: String, val engine: Engine, val backend: Accelerator)

    internal val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "locus-litert").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val tracker = EngineStatusTracker()

    /** Which model is loaded, on which backend (only as actually created), and how fast it ran. */
    val status: StateFlow<EngineStatus> = tracker.status

    /** Speeds of recent replies by their trimmed text, for the line under an on-device reply. Memory only. */
    val recentTurns: StateFlow<Map<String, TurnSpeed>> = tracker.recentTurns

    /**
     * The conversation left over from the last finished turn, kept so the next agent step only has to
     * prefill the new message instead of the whole transcript. Only touched while [lock] is held.
     */
    internal var live: LiveConversation? = null

    fun chatModel(modelId: String): ChatModel = OnDeviceChatModel(this, modelId)

    internal suspend fun <T> withEngine(modelId: String, block: suspend (Engine, Accelerator) -> T): T = lock.withLock {
        val current = loaded?.takeIf { it.modelId == modelId } ?: load(modelId)
        tracker.busy(true)
        try {
            block(current.engine, current.backend)
        } finally {
            tracker.busy(false)
        }
    }

    internal fun recordTurn(reply: String, speed: TurnSpeed) = tracker.turn(reply, speed)

    private fun load(modelId: String): LoadedEngine {
        val model = models.model(modelId) ?: throw LlmException("Unknown on-device model $modelId.")
        if (!models.isReady(modelId)) throw LlmException("${model.name} isn't downloaded yet. Download it in Settings → Providers.")
        closeLoaded()
        val ramWarning = models.ramCheck(modelId)?.let { MemoryGate.warning(model.name, it) }
        if (ramWarning != null) android.util.Log.w(TAG, "loading $modelId: $ramWarning")
        tracker.loading(modelId)
        val path = models.file(model).path
        val memoryBefore = processMemoryBytes()
        val started = System.nanoTime()
        // GPU is much faster where the driver supports it; fall back to CPU otherwise. The backend
        // reported is the one the engine was created with, never assumed.
        val result = try {
            BackendLoader.load { accelerator -> createEngine(path, accelerator) }
        } catch (e: Throwable) {
            tracker.loadFailed(modelId, BackendLoader.reason(e, 200))
            if (e !is Exception) throw e
            throw LlmException("Couldn't load ${model.name}: ${e.message}" + (ramWarning?.let { " $it" } ?: ""), cause = e)
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        val memory = processMemoryBytes()?.let { after -> memoryBefore?.let { after - it } }
        android.util.Log.i(
            TAG,
            "loaded $modelId on ${result.backend} in $millis ms, ≈${memory?.div(1_000_000)} MB" +
                (result.gpuFailure?.let { " (GPU failed: $it)" } ?: "") + " (thread ${Thread.currentThread().name})",
        )
        tracker.loaded(modelId, result, millis, memory)
        return LoadedEngine(modelId, result.engine, result.backend).also { loaded = it }
    }

    /** A fully initialised engine on [accelerator], or an exception with nothing left allocated. */
    private fun createEngine(path: String, accelerator: Accelerator): Engine {
        refuseGpuIfForcedOff(accelerator)
        // The vision encoder is loaded on demand, so declaring it costs nothing until a photo is sent.
        val config = EngineConfig(
            modelPath = path,
            backend = backendFor(accelerator),
            visionBackend = backendFor(accelerator),
            maxNumTokens = MAX_TOKENS,
            cacheDir = context.cacheDir.path,
        )
        val engine = Engine(config)
        try {
            engine.initialize()
        } catch (e: Throwable) {
            runCatching { engine.close() }
            throw e
        }
        // The label comes from what the engine holds, so it can't say GPU for a CPU engine.
        check((engine.engineConfig.backend is Backend.GPU) == (accelerator == Accelerator.GPU)) { "Engine backend doesn't match the request." }
        return engine
    }

    private fun closeLoaded() {
        live?.conversation?.closeQuietly()
        live = null
        loaded?.engine?.let { runCatching { it.close() } }
        loaded = null
    }

    /**
     * Frees the loaded engine. Returns false, leaving it loaded, while a reply is being generated; an
     * agent run that is between steps simply loads it again for its next step.
     */
    suspend fun unload(): Boolean = withContext(dispatcher) {
        if (!lock.tryLock()) return@withContext false
        try {
            closeLoaded()
            tracker.unloaded()
            true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Runs LiteRT-LM's own benchmark on [modelId] with a fixed short prompt: [BENCH_PREFILL] prefill and
     * [BENCH_DECODE] decode tokens, GPU first and CPU if that fails. The chat engine is unloaded first
     * so two copies never share the memory. The result stays in memory on the phone.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun runBenchmark(modelId: String): BenchmarkResult = withContext(dispatcher) {
        val model = models.model(modelId) ?: throw LlmException("Unknown on-device model $modelId.")
        if (!models.isReady(modelId)) throw LlmException("${model.name} isn't downloaded yet.")
        if (!lock.tryLock()) throw LlmException("A reply is being generated on this phone. Try again when it finishes.")
        tracker.benchmarkStarted()
        try {
            closeLoaded()
            tracker.unloaded()
            val path = models.file(model).path
            val run = BackendLoader.load { accelerator ->
                refuseGpuIfForcedOff(accelerator)
                benchmark(
                    path, backendFor(accelerator), null, null, BENCH_PREFILL, BENCH_DECODE,
                    context.cacheDir.path, Contents.of(BENCH_PROMPT), null, null, MAX_TOKENS,
                )
            }
            val info = run.engine
            BenchmarkResult(
                modelId = modelId,
                backend = run.backend,
                gpuFailure = run.gpuFailure,
                loadSeconds = info.initTimeInSecond,
                prefillTokens = info.lastPrefillTokenCount,
                prefillPerSecond = info.lastPrefillTokensPerSecond,
                decodeTokens = info.lastDecodeTokenCount,
                decodePerSecond = info.lastDecodeTokensPerSecond,
            ).also {
                android.util.Log.i(TAG, "benchmark $modelId: $it")
                tracker.benchmarkDone(it)
            }
        } catch (e: Exception) {
            tracker.benchmarkFailed(BackendLoader.reason(e, 200))
            throw e
        } finally {
            lock.unlock()
        }
    }

    /**
     * Testing hook for the CPU fallback path, debuggable builds only: with a file named [FORCE_CPU_FILE]
     * in the app's files directory, GPU creation fails as a real GPU failure would. Create it with
     * `adb shell run-as <package> touch files/force-cpu`.
     */
    private fun refuseGpuIfForcedOff(accelerator: Accelerator) {
        if (accelerator != Accelerator.GPU) return
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && java.io.File(context.filesDir, FORCE_CPU_FILE).exists()) {
            throw IllegalStateException("GPU turned off for testing ($FORCE_CPU_FILE)")
        }
    }

    /** This process's proportional memory, including graphics memory Android attributes to it. */
    private fun processMemoryBytes(): Long? = runCatching {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        info.totalPss * 1024L
    }.getOrNull()?.takeIf { it > 0 }

    private companion object {
        const val TAG = "LocusOnDevice"
        const val MAX_TOKENS = 16_384
        const val FORCE_CPU_FILE = "force-cpu"
        const val BENCH_PREFILL = 256
        const val BENCH_DECODE = 128
        const val BENCH_PROMPT = "Explain in three short sentences why the sky is blue."

        fun backendFor(accelerator: Accelerator): Backend = when (accelerator) {
            Accelerator.GPU -> Backend.GPU()
            Accelerator.CPU -> Backend.CPU()
        }
    }
}

/** A LiteRT-LM conversation that has seen exactly [consumed] under the system prompt and tools in [key]. */
internal class LiveConversation(val conversation: Conversation, val key: String, val consumed: List<TurnKey>)

internal fun Conversation.closeQuietly() {
    if (isAlive) runCatching { close() }
}

/**
 * Adapts LiteRT-LM to the agent's [ChatModel]. LiteRT-LM conversations are stateful, but the agent
 * sends the whole transcript every step. When the previous step's conversation has seen all but the
 * newest message it is reused (see [OnDeviceEngines.live]); otherwise a conversation is rebuilt from
 * the history (all but the last message) and the last message is sent. Tools are declared from their JSON
 * schemas; automatic tool calling is off so the agent loop runs tools and approvals itself.
 */
internal class OnDeviceChatModel(private val engines: OnDeviceEngines, private val modelId: String) : ChatModel {

    override val format = ApiFormat.ON_DEVICE

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        engines.withEngine(modelId) { engine, backend ->
            // Photos go to the model only with the newest user message; older ones are referenced by path.
            val lastUser = request.messages.indexOfLast { it.role == Role.USER }
            val tools = request.tools.filter { it.name in ON_DEVICE_TOOLS }
            val key = configKey(request.system, tools)
            val turns = request.messages.map(TurnKey::of)

            // Reuse the conversation from the previous step when it has seen exactly this transcript
            // except the newest message: only that message is prefilled, the rest is already in its
            // KV cache. Anything else (an edit, a retry, another chat, other tools) rebuilds it.
            val cached = engines.live
            engines.live = null
            val reuse = cached?.takeIf { it.key == key && it.conversation.isAlive && TranscriptPrefix.continues(it.consumed, turns) }
            if (cached != null && reuse == null) cached.conversation.closeQuietly()

            val conversation: Conversation
            val last: com.google.ai.edge.litertlm.Message
            if (reuse != null) {
                conversation = reuse.conversation
                last = toLiteRt(request.messages.last(), withImages = request.messages.lastIndex == lastUser).single()
            } else {
                val history = request.messages.flatMapIndexed { i, m -> toLiteRt(m, withImages = i == lastUser) }
                last = history.lastOrNull() ?: throw LlmException("Nothing to send.")
                val config = ConversationConfig(
                    systemInstruction = Contents.of(request.system),
                    initialMessages = history.dropLast(1),
                    tools = tools.map { spec -> tool(SchemaTool(spec.name, spec.description, spec.inputSchema)) },
                    automaticToolCalling = false,
                )
                conversation = engine.createConversation(config)
            }

            var keep = false
            try {
                // Tokens already in the KV cache, so this turn's prefill can be estimated afterwards.
                val kvBefore = runCatching { conversation.getTokenCount() }.getOrNull()
                val started = System.nanoTime()
                var firstTokenMs = -1L
                var pieces = 0
                val text = StringBuilder()
                val calls = mutableListOf<Part.ToolCall>()
                val channels = linkedMapOf<String, StringBuilder>()
                // Calls recovered from a LiteRT parse error (see GemmaCallRepair); the turn then ends there.
                var repaired: List<GemmaCallRepair.Call> = emptyList()
                callbackFlow {
                    val finished = AtomicBoolean(false)
                    conversation.sendMessageAsync(last, object : MessageCallback {
                        override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                            trySend(message)
                        }

                        override fun onDone() {
                            finished.set(true)
                            close()
                        }

                        override fun onError(throwable: Throwable) {
                            finished.set(true)
                            val recovered = GemmaCallRepair.fromError(throwable.message)
                            if (recovered.isNotEmpty()) {
                                repaired = recovered
                                close()
                            } else {
                                close(LlmException("On-device model error: ${throwable.message}", cause = throwable))
                            }
                        }
                    })
                    // Cancelled mid-reply (user pressed stop): stop decoding instead of letting it run on.
                    awaitClose { if (!finished.get()) runCatching { conversation.cancelProcess() } }
                }.buffer(Channel.UNLIMITED).collect { message ->
                    if (firstTokenMs < 0) firstTokenMs = (System.nanoTime() - started) / 1_000_000
                    pieces++
                    val delta = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        emit(ChatEvent.TextDelta(delta))
                    }
                    message.channels.forEach { (name, value) -> channels.getOrPut(name) { StringBuilder() }.append(value) }
                    message.toolCalls.forEach { call ->
                        val id = "call_${calls.size + 1}"
                        calls += Part.ToolCall(id, call.name, toJson(call.arguments).toString())
                        emit(ChatEvent.ToolCallStarted(id, call.name))
                    }
                }
                repaired.forEach { call ->
                    val id = "call_${calls.size + 1}"
                    calls += Part.ToolCall(id, call.name, call.arguments.toString())
                    emit(ChatEvent.ToolCallStarted(id, call.name))
                }
                if (repaired.isNotEmpty()) android.util.Log.w(TAG, "repaired ${repaired.size} malformed tool call(s): ${repaired.map { it.name }}")
                val totalMs = (System.nanoTime() - started) / 1_000_000
                val kvAfter = runCatching { conversation.getTokenCount() }.getOrNull()
                val speed = runtimeSpeed(conversation, backend)
                    ?: SpeedMath.estimate(backend, pieces, firstTokenMs, totalMs, kvBefore, kvAfter)
                android.util.Log.i(
                    TAG,
                    "turn done: backend=$backend reused=${reuse != null} history=${request.messages.size} firstToken=${firstTokenMs}ms " +
                        "total=${totalMs}ms kvTokens=$kvBefore→$kvAfter pieces=$pieces speed=$speed " +
                        "text=${text.length} calls=${calls.size} channels=${channels.mapValues { it.value.length }}",
                )
                if (text.isBlank() && calls.isEmpty()) {
                    // Some model builds route the answer to a named channel; use it unless it's reasoning.
                    val reasoning = listOf("thought", "think", "analysis", "reason")
                    val answer = channels.filterKeys { name -> reasoning.none { name.contains(it, true) } }.values.joinToString("") { it }.trim()
                    if (answer.isEmpty()) throw LlmException("The on-device model returned an empty reply. Try again, or rephrase.", retryable = true)
                    text.append(answer)
                    emit(ChatEvent.TextDelta(answer))
                }
                val reply = text.toString()
                engines.recordTurn(reply, speed)
                // Keep the conversation for the next step: the agent will send this transcript plus our
                // reply (as it builds it from the turn) plus one new message.
                // After a repaired call LiteRT's own state is unreliable; start fresh next step.
                if (repaired.isEmpty()) {
                    engines.live = LiveConversation(conversation, key, turns + TurnKey.assistant(reply, calls))
                    keep = true
                }
                emit(
                    ChatEvent.Completed(
                        AssistantTurn(
                            text = reply,
                            toolCalls = calls,
                            stopReason = if (calls.isEmpty()) StopReason.END_TURN else StopReason.TOOL_USE,
                            format = ApiFormat.ON_DEVICE,
                            rawPayload = null,
                        ),
                    ),
                )
            } finally {
                // An error, cancellation or empty reply leaves the conversation in an unknown state.
                if (!keep) conversation.closeQuietly()
            }
        }
    }.flowOn(engines.dispatcher)

    /**
     * LiteRT-LM's own token counts and rates for the turn, when the runtime records them (it may only
     * do so with its experimental benchmark mode on, which Locus leaves off for chats). Null otherwise.
     */
    @OptIn(ExperimentalApi::class)
    private fun runtimeSpeed(conversation: Conversation, backend: Accelerator): TurnSpeed? = runCatching {
        val info = conversation.getBenchmarkInfo()
        SpeedMath.fromRuntime(backend, info.lastPrefillTokenCount, info.lastPrefillTokensPerSecond, info.lastDecodeTokenCount, info.lastDecodeTokensPerSecond)
    }.getOrNull()

    /** Everything besides the messages that shapes the conversation; a change means it must be rebuilt. */
    private fun configKey(system: String, tools: List<dev.locus.core.model.ToolSpec>): String =
        buildString {
            append(system)
            tools.forEach { append('\u0000').append(it.name).append('\u0000').append(it.description).append('\u0000').append(it.inputSchema) }
        }

    private fun toLiteRt(message: Message, withImages: Boolean): List<com.google.ai.edge.litertlm.Message> = when (message.role) {
        Role.USER -> {
            val (images, others) = message.attachments.partition { withImages && it.mimeType.startsWith("image/") && java.io.File(it.path).isFile }
            val note = (others.map { "[Attached file: ${it.workspacePath} (${it.mimeType}). It is in the workspace.]" } +
                images.map { "[Photo ${it.workspacePath} is shown above.]" } + message.text).joinToString("\n")
            listOf(
                if (images.isEmpty()) com.google.ai.edge.litertlm.Message.user(note)
                else com.google.ai.edge.litertlm.Message.user(Contents.of(images.map { Content.ImageFile(it.path) } + Content.Text(note))),
            )
        }
        Role.ASSISTANT -> listOf(
            com.google.ai.edge.litertlm.Message.model(
                Contents.of(message.text),
                message.toolCalls.map { ToolCall(it.name, fromJson(it.argumentsJson)) },
                emptyMap(),
            ),
        )
        Role.TOOL -> listOf(
            com.google.ai.edge.litertlm.Message.tool(
                Contents.of(message.toolResults.map { Content.ToolResponse(it.toolName, if (it.isError) "Error: ${it.content}" else it.content) }),
            ),
        )
    }

    private companion object {
        const val TAG = "LocusOnDevice"

        /**
         * Small on-device models handle a short tool list far better, and every declared tool costs
         * prefill time on each step. Local models don't use prompt caching, so trimming is free.
         */
        val ON_DEVICE_TOOLS = setOf(
            "list_files", "read_file", "write_file", "edit_file", "search_files",
            "run_command", "fetch_url", "browser_open", "browser_read",
            "memory_save", "memory_read", "memory_search", "device_calendar_event", "device_alarm",
            "weather", "wikipedia", "web_search", "chat_history_search", "chat_history_read",
        )
    }

    /** Declares a tool to the model by its JSON schema; never executed here (automatic calling is off). */
    private class SchemaTool(private val name: String, private val description: String, private val schema: JsonObject) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String =
            JsonObject(mapOf("name" to JsonPrimitive(name), "description" to JsonPrimitive(description), "parameters" to schema)).toString()

        override fun execute(paramsJsonString: String): String = "{\"error\":\"not executed on device\"}"
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is Iterable<*> -> JsonArray(value.map(::toJson))
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJson(json: String): Map<String, Any?> {
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapValues { (_, v) -> plain(v) }
    }

    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
        is JsonObject -> e.mapValues { plain(it.value) }
        is JsonArray -> e.map(::plain)
    }
}
