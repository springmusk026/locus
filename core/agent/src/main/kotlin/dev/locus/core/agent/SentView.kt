package dev.locus.core.agent

import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What Locus sent for one model request, scrubbed ([SecretScrubber]) and split into the sections the
 * viewer shows. Built from the request body the adapter produces, so it reads like the wire format
 * (Anthropic content blocks, Chat Completions messages, Responses input items) rather than Locus's own
 * model. Credentials never appear: they travel in headers, which are not part of the body at all.
 */
data class SentView(
    val format: ApiFormat,
    val provider: String,
    val model: String,
    /** The endpoint's host, e.g. `api.anthropic.com`; null on device. */
    val host: String?,
    /** Top-level request settings other than the content: `max_tokens`, `reasoning`, `stream`, … */
    val params: List<Pair<String, String>>,
    val system: String,
    val tools: List<SentTool>,
    val messages: List<SentMessage>,
    /** Rough per-section counts (about four characters a token); the provider reports only the total. */
    val estimate: Estimate,
    /** Prompt tokens the provider reported for this request, when the reply carried usage. */
    val reportedInputTokens: Int?,
    /** The scrubbed body, pretty-printed and capped ([MAX_COPY_CHARS]); for on-device, the conversation text. */
    val json: String,
) {
    val onDevice: Boolean get() = format == ApiFormat.ON_DEVICE

    data class Estimate(val system: Int, val tools: Int, val messages: Int)

    companion object {
        /** Clipboard transfers go over binder (about 1 MB); keep the copy well under it. */
        const val MAX_COPY_CHARS = 400_000

        /** Longest text shown in one block; the rest is in the copied JSON. */
        const val MAX_BLOCK_CHARS = 20_000

        /** Long text (a system prompt) is shown as several lazy-list items of about this size. */
        const val CHUNK_CHARS = 4_000

        private val pretty = Json { prettyPrint = true }

        fun estimateTokens(chars: Int): Int = (chars + 3) / 4

        /** [text] cut to [max] characters with a note saying how much was left out. */
        fun capped(text: String, max: Int, note: String = "copy as JSON for the rest"): String =
            if (text.length <= max) text else text.take(max) + "\n…[${text.length - max} more characters; $note]"

        /** [text] in pieces of about [size] characters, split at line ends where possible. */
        fun chunks(text: String, size: Int = CHUNK_CHARS): List<String> {
            if (text.length <= size) return listOf(text)
            val out = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = (start + size).coerceAtMost(text.length)
                if (end < text.length) {
                    val newline = text.lastIndexOf('\n', end - 1)
                    if (newline > start + size / 2) end = newline + 1
                }
                out += text.substring(start, end)
                start = end
            }
            return out
        }

        /** A view of an HTTP request [body] built by the adapter for [format]. */
        fun fromBody(
            format: ApiFormat,
            provider: String,
            model: String,
            baseUrl: String,
            body: JsonObject,
            scrubber: SecretScrubber,
            reportedInputTokens: Int? = null,
        ): SentView {
            val clean = scrubber.scrub(body) as JsonObject
            val system: String
            val messages: List<SentMessage>
            when (format) {
                ApiFormat.ANTHROPIC_MESSAGES -> {
                    system = anthropicSystem(clean["system"])
                    messages = clean.array("messages").map(::anthropicMessage)
                }
                ApiFormat.OPENAI_CHAT -> {
                    val all = clean.array("messages")
                    system = all.filter { it.str("role") == "system" }.joinToString("\n\n") { contentText(it["content"]) }
                    messages = all.filter { it.str("role") != "system" }.map(::chatMessage)
                }
                else -> {
                    system = clean.str("instructions").orEmpty()
                    messages = responsesMessages(clean.array("input"))
                }
            }
            val tools = flattenTools(clean.array("tools"))
            val params = clean.filterKeys { it !in CONTENT_KEYS }.map { (k, v) -> k to (if (v is JsonPrimitive) v.content else v.toString()) }
            return SentView(
                format = format,
                provider = provider,
                model = model,
                host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull(),
                params = params,
                system = system,
                tools = tools,
                messages = messages,
                estimate = Estimate(
                    system = estimateTokens(system.length),
                    tools = estimateTokens(upFront(clean["tools"])?.toString()?.length ?: 0),
                    messages = estimateTokens((clean["messages"] ?: clean["input"])?.toString()?.length ?: 0),
                ),
                reportedInputTokens = reportedInputTokens,
                json = capped(pretty.encodeToString(JsonObject.serializer(), clean), MAX_COPY_CHARS, "cut for the clipboard"),
            )
        }

        /**
         * A view of an on-device turn: LiteRT-LM gets a system instruction, tool declarations and the
         * conversation as text, not an HTTP body.
         */
        fun fromOnDevice(
            provider: String,
            model: String,
            system: String,
            tools: List<ToolSpec>,
            turns: List<Pair<String, String>>,
            scrubber: SecretScrubber,
        ): SentView {
            val cleanSystem = scrubber.scrub(system)
            val cleanTurns = turns.map { (role, text) -> role to scrubber.scrub(text) }
            val sentTools = tools.map { SentTool(it.name, scrubber.scrub(it.description), pretty.encodeToString(JsonElement.serializer(), scrubber.scrub(it.inputSchema))) }
            val text = buildString {
                append("[system]\n").append(cleanSystem).append("\n\n")
                if (sentTools.isNotEmpty()) append("[tools] ").append(sentTools.joinToString(", ") { it.name }).append("\n\n")
                cleanTurns.forEach { (role, t) -> append('[').append(role).append("]\n").append(t).append("\n\n") }
            }.trimEnd()
            return SentView(
                format = ApiFormat.ON_DEVICE,
                provider = provider,
                model = model,
                host = null,
                params = emptyList(),
                system = cleanSystem,
                tools = sentTools,
                messages = cleanTurns.map { (role, t) -> SentMessage(role, listOf(SentBlock.Text(t))) },
                estimate = Estimate(
                    system = estimateTokens(cleanSystem.length),
                    tools = estimateTokens(sentTools.sumOf { it.name.length + it.description.length + it.schema.length }),
                    messages = estimateTokens(cleanTurns.sumOf { it.second.length }),
                ),
                reportedInputTokens = null,
                json = capped(text, MAX_COPY_CHARS, "cut for the clipboard"),
            )
        }

        private val CONTENT_KEYS = setOf("system", "messages", "input", "instructions", "tools")

        private fun anthropicSystem(e: JsonElement?): String = when (e) {
            is JsonPrimitive -> e.contentOrNull.orEmpty()
            is JsonArray -> e.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n\n")
            else -> ""
        }

        private fun anthropicMessage(m: JsonObject): SentMessage {
            val content = m["content"]
            val blocks = if (content is JsonArray) content.mapNotNull { it as? JsonObject }.map(::anthropicBlock) else listOf(SentBlock.Text(contentText(content)))
            return SentMessage(m.str("role").orEmpty(), blocks)
        }

        private fun anthropicBlock(b: JsonObject): SentBlock = when (b.str("type")) {
            "text" -> SentBlock.Text(b.str("text").orEmpty())
            "tool_use" -> SentBlock.ToolCall(b.str("name").orEmpty(), b.str("id").orEmpty(), prettyOf(b["input"]))
            "tool_result" -> SentBlock.ToolResult(b.str("tool_use_id").orEmpty(), contentText(b["content"]), (b["is_error"] as? JsonPrimitive)?.contentOrNull == "true")
            "image", "document" -> SentBlock.Attachment((b["source"] as? JsonObject)?.str("data") ?: b.str("type").orEmpty())
            else -> SentBlock.Other(b.str("type") ?: "block", prettyOf(b))
        }

        private fun chatMessage(m: JsonObject): SentMessage {
            val role = m.str("role").orEmpty()
            val blocks = mutableListOf<SentBlock>()
            when (val content = m["content"]) {
                is JsonArray -> content.mapNotNull { it as? JsonObject }.forEach { part ->
                    blocks += when (part.str("type")) {
                        "text" -> SentBlock.Text(part.str("text").orEmpty())
                        "image_url" -> SentBlock.Attachment((part["image_url"] as? JsonObject)?.str("url").orEmpty())
                        else -> SentBlock.Other(part.str("type") ?: "part", prettyOf(part))
                    }
                }
                else -> contentText(content).takeIf { it.isNotEmpty() }?.let {
                    blocks += if (role == "tool") SentBlock.ToolResult(m.str("tool_call_id").orEmpty(), it, it.startsWith("ERROR: ")) else SentBlock.Text(it)
                }
            }
            m.array("tool_calls").forEach { call ->
                val fn = call["function"] as? JsonObject
                blocks += SentBlock.ToolCall(fn?.str("name").orEmpty(), call.str("id").orEmpty(), prettyText(fn?.str("arguments").orEmpty()))
            }
            return SentMessage(role, blocks)
        }

        /** Responses input items, grouped into messages: consecutive items of the same role share one. */
        private fun responsesMessages(items: List<JsonObject>): List<SentMessage> {
            val out = mutableListOf<SentMessage>()
            fun add(role: String, block: SentBlock) {
                val last = out.lastOrNull()
                if (last != null && last.role == role) out[out.lastIndex] = last.copy(blocks = last.blocks + block) else out += SentMessage(role, listOf(block))
            }
            for (item in items) {
                when (item.str("type")) {
                    "message", null -> {
                        val role = item.str("role").orEmpty()
                        when (val content = item["content"]) {
                            is JsonArray -> content.mapNotNull { it as? JsonObject }.forEach { part ->
                                add(role, when (part.str("type")) {
                                    "input_text", "output_text" -> SentBlock.Text(part.str("text").orEmpty())
                                    "input_image" -> SentBlock.Attachment(part.str("image_url").orEmpty())
                                    else -> SentBlock.Other(part.str("type") ?: "part", prettyOf(part))
                                })
                            }
                            else -> add(role, SentBlock.Text(contentText(content)))
                        }
                    }
                    "function_call" -> add("assistant", SentBlock.ToolCall(item.str("name").orEmpty(), item.str("call_id").orEmpty(), prettyText(item.str("arguments").orEmpty())))
                    "function_call_output" -> {
                        val output = contentText(item["output"])
                        add("tool", SentBlock.ToolResult(item.str("call_id").orEmpty(), output, output.startsWith("ERROR: ")))
                    }
                    else -> add("assistant", SentBlock.Other(item.str("type").orEmpty(), prettyOf(item)))
                }
            }
            return out
        }

        /**
         * The tools that enter the model's context up front: deferred ones (`defer_loading`, provider-native
         * tool search) are sent but kept out of it until a search loads them, so they aren't counted; a
         * namespace whose tools are all deferred counts as its name and description.
         */
        internal fun upFront(tools: JsonElement?): JsonElement? {
            val list = tools as? JsonArray ?: return tools
            fun deferred(t: JsonObject) = (t["defer_loading"] as? JsonPrimitive)?.contentOrNull == "true"
            return JsonArray(
                list.mapNotNull { e ->
                    val t = e as? JsonObject ?: return@mapNotNull e
                    when {
                        deferred(t) -> null
                        t.str("type") == "namespace" -> JsonObject(t + ("tools" to JsonArray((t["tools"] as? JsonArray).orEmpty().filter { (it as? JsonObject)?.let(::deferred) != true })))
                        else -> t
                    }
                },
            )
        }

        /** Tools as sent, with a namespace's tools (ChatGPT plan route) listed individually. */
        private fun flattenTools(tools: List<JsonObject>): List<SentTool> = tools.flatMap { t ->
            if (t.str("type") == "namespace") flattenTools((t["tools"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { inner -> JsonObject(inner + ("name" to JsonPrimitive("${t.str("name")}.${inner.str("name")}"))) })
            else {
                val fn = t["function"] as? JsonObject ?: t
                listOf(SentTool(fn.str("name").orEmpty(), fn.str("description").orEmpty(), prettyOf(fn["input_schema"] ?: fn["parameters"])))
            }
        }

        private fun contentText(e: JsonElement?): String = when (e) {
            is JsonPrimitive -> e.contentOrNull.orEmpty()
            is JsonArray -> e.mapNotNull { (it as? JsonObject)?.let { o -> o.str("text") ?: o.str("data") } ?: (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
            null -> ""
            else -> e.toString()
        }

        private fun prettyOf(e: JsonElement?): String = e?.let { pretty.encodeToString(JsonElement.serializer(), it) }.orEmpty()

        private fun prettyText(json: String): String = runCatching { prettyOf(Json.parseToJsonElement(json)) }.getOrDefault(json)

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.array(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}

data class SentTool(val name: String, val description: String, val schema: String)

data class SentMessage(val role: String, val blocks: List<SentBlock>)

sealed interface SentBlock {
    data class Text(val text: String) : SentBlock

    data class ToolCall(val name: String, val id: String, val arguments: String) : SentBlock

    data class ToolResult(val callId: String, val content: String, val isError: Boolean) : SentBlock

    /** An attachment, already replaced by its label (`[image, 1.2 MB]`) or a note. */
    data class Attachment(val label: String) : SentBlock

    /** Anything else the provider gets back verbatim: thinking, reasoning items, compaction. */
    data class Other(val type: String, val json: String) : SentBlock
}
