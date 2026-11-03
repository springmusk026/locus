package dev.locus.core.ondevice.gguf

import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.LlmException
import dev.locus.core.llm.LlmFailure
import dev.locus.core.llm.StopReason
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Part
import dev.locus.core.model.PowerPolicy
import dev.locus.core.model.TokenUsage
import dev.locus.core.ondevice.Accelerator
import dev.locus.core.ondevice.HeatStop
import dev.locus.core.ondevice.LIGHT_CPU_THREADS
import dev.locus.core.ondevice.LIGHT_MAX_OUTPUT_TOKENS
import dev.locus.core.ondevice.OnDeviceEngines
import dev.locus.core.ondevice.TurnSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts llama.cpp to the agent's [ChatModel]. Each step sends the whole transcript, as for a cloud
 * model; llama.cpp renders it with the model's template and reuses the KV cache for the prefix it has
 * already seen (locus_llama.cpp), so a step only prefills what's new. Tools are declared when the
 * model's template takes them ([GgufModel.tools]); the agent loop runs them and their approvals.
 *
 * Generation blocks a thread inside JNI, so it runs on the IO pool while this flow streams the deltas.
 * Stop, an error downstream and critical heat all end it the same way: a flag the native loop and
 * llama.cpp's abort callback check between tokens.
 */
internal class GgufChatModel(private val engines: OnDeviceEngines, private val modelId: String) : ChatModel {

    override val format = ApiFormat.ON_DEVICE

    /** Whether this model's requests declare tools: false for a chat-only model ([GgufModel.tools]). */
    fun sendsTools(): Boolean = engines.ggufSendsTools(modelId)

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        engines.withLlama(modelId) { model, handle ->
            val light = engines.lightMode.value
            val body = GgufWire.request(request, withTools = model.tools).toByteArray(Charsets.UTF_8)
            val maxTokens = if (light) LIGHT_MAX_OUTPUT_TOKENS else model.contextTokens
            val threads = if (light) LIGHT_CPU_THREADS else engines.llamaThreads
            val stoppedHot = AtomicBoolean(false)
            val stopRequested = AtomicBoolean(false)
            var announced = 0

            val reply = coroutineScope {
                val deltas = Channel<Pair<Int, String>>(Channel.UNLIMITED)
                // At critical heat, stop decoding now and keep what was written (PowerPolicy.stopLocalGeneration).
                val heatWatch = launch(Dispatchers.Default) {
                    engines.conditions.state.first(PowerPolicy::stopLocalGeneration)
                    stoppedHot.set(true)
                    LlamaNative.nativeCancel(handle)
                }
                // The native call's failure comes back as a value: a failed async child would cancel this
                // scope first, and the cancellation, not GgufErrors' mapping (a context overflow the agent
                // compacts and retries), would reach the agent.
                val result = async(Dispatchers.IO) {
                    try {
                        runCatching {
                            LlamaNative.nativeGenerate(handle, body, maxTokens, threads) { kind, utf8 ->
                                // nativeGenerate clears the cancel flag as it starts, so a Stop that landed just
                                // before can be lost; the next token then ends it from here.
                                if (stopRequested.get()) throw StopRequested()
                                deltas.trySend(kind to String(utf8, Charsets.UTF_8))
                            }
                        }
                    } finally {
                        deltas.close()
                    }
                }
                try {
                    for ((kind, text) in deltas) {
                        when (kind) {
                            LlamaNative.DELTA_CONTENT -> emit(ChatEvent.TextDelta(text))
                            LlamaNative.DELTA_REASONING -> emit(ChatEvent.ReasoningDelta(text))
                            LlamaNative.DELTA_TOOL -> emit(ChatEvent.ToolCallStarted("call_${++announced}", text))
                        }
                    }
                } finally {
                    // Stop pressed (this flow cancelled) or a collector error: end the native loop now.
                    if (!result.isCompleted) {
                        stopRequested.set(true)
                        LlamaNative.nativeCancel(handle)
                    }
                    heatWatch.cancel()
                }
                val bytes = result.await().getOrElse { e ->
                    throw if (e is LlamaNativeException) GgufErrors.generation(model, e) else e
                }
                GgufWire.reply(String(bytes, Charsets.UTF_8))
            }

            if (reply.parseError != null) android.util.Log.w(TAG, "reply of $modelId (${reply.format}) didn't parse: ${reply.parseError.take(300)}")
            var text = reply.content
            var calls = reply.toolCalls.mapIndexed { i, c -> Part.ToolCall("call_${i + 1}", c.name, c.arguments) }
            if (stoppedHot.get()) {
                // A cut-off tool call isn't run; the note says why the reply ends here.
                val note = HeatStop.note(droppedCalls = calls.size)
                calls = emptyList()
                text += note
                emit(ChatEvent.TextDelta(note))
            } else if (reply.stop == "cancelled") {
                return@withLlama
            } else if (reply.stop == "error") {
                // llama_decode failed mid-reply (locus_llama.cpp): the reply is cut short, not finished.
                throw LlmException("${model.name} stopped with an error mid-reply. Try again.", retryable = true)
            } else {
                // Calls whose names weren't streamed (a parser that only settled at the end) still show as started.
                for (i in announced until calls.size) emit(ChatEvent.ToolCallStarted(calls[i].id, calls[i].name))
                if (text.isBlank() && calls.isEmpty()) {
                    throw LlmException("${model.name} returned an empty reply. Try again, or rephrase.", retryable = true)
                }
            }

            val prefilled = (reply.promptTokens - reply.reusedTokens).coerceAtLeast(0)
            val speed = TurnSpeed(
                backend = Accelerator.CPU,
                prefillTokens = prefilled.takeIf { it > 0 },
                prefillPerSecond = rate(prefilled, reply.prefillMs),
                decodeTokens = reply.generatedTokens.takeIf { it > 0 },
                decodePerSecond = rate(reply.generatedTokens, reply.decodeMs),
                measured = true,
            )
            engines.recordTurn(text, speed)
            android.util.Log.i(
                TAG,
                "turn done: $modelId format=${reply.format} prompt=${reply.promptTokens} reused=${reply.reusedTokens} " +
                    "generated=${reply.generatedTokens} prefill=${reply.prefillMs.toLong()}ms decode=${reply.decodeMs.toLong()}ms " +
                    "stop=${reply.stop} calls=${calls.size} light=$light threads=$threads",
            )
            emit(
                ChatEvent.Completed(
                    AssistantTurn(
                        text = text,
                        toolCalls = calls,
                        stopReason = when {
                            calls.isNotEmpty() -> StopReason.TOOL_USE
                            reply.stop == "length" || reply.stop == "context" -> StopReason.MAX_TOKENS
                            else -> StopReason.END_TURN
                        },
                        format = ApiFormat.ON_DEVICE,
                        rawPayload = null,
                        usage = TokenUsage(input = reply.promptTokens, output = reply.generatedTokens, cacheRead = reply.reusedTokens),
                    ),
                ),
            )
        }
    }.flowOn(engines.dispatcher)

    /** Thrown from the sink to end a generation whose Stop the native cancel flag missed. */
    private class StopRequested : RuntimeException("stopped")

    private fun rate(tokens: Int, millis: Double): Double? = if (tokens <= 0 || millis <= 0.0) null else tokens * 1000.0 / millis

    private companion object {
        const val TAG = "LocusGguf"
    }
}

/** What each llama.cpp failure means to the user. */
internal object GgufErrors {
    fun load(model: GgufModel, e: LlamaNativeException): LlmException {
        val detail = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim()?.take(200)
        val message = when (e.kind) {
            LlamaNativeException.Kind.ARCH -> "Locus's llama.cpp can't run ${model.name} (architecture \"${model.architecture}\")."
            LlamaNativeException.Kind.CORRUPT -> "${model.name} looks damaged and couldn't be loaded. Delete it in Settings → Models and import it again."
            LlamaNativeException.Kind.MEMORY ->
                "Not enough memory to load ${model.name} with a ${model.contextTokens / 1024}K context. Close other apps, or use a smaller quantisation."
            LlamaNativeException.Kind.TEMPLATE -> "${model.name}'s chat template couldn't be used."
            else -> "Couldn't load ${model.name}."
        }
        return LlmException(if (detail != null && e.kind != LlamaNativeException.Kind.MEMORY) "$message ($detail)" else message, cause = e)
    }

    fun generation(model: GgufModel, e: LlamaNativeException): LlmException = when (e.kind) {
        LlamaNativeException.Kind.CONTEXT -> LlmException(
            "This chat no longer fits ${model.name}'s ${model.contextTokens / 1024}K context.",
            cause = e,
            kind = LlmFailure.CONTEXT_OVERFLOW,
        )
        LlamaNativeException.Kind.TEMPLATE -> LlmException("${model.name}'s chat template failed on this chat: ${e.message?.take(200)}", cause = e)
        else -> LlmException("On-device model error: ${e.message?.take(300)}", cause = e)
    }
}
