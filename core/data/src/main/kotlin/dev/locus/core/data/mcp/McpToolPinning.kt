package dev.locus.core.data.mcp

import androidx.datastore.core.DataStore
import dev.locus.core.agent.ToolCallContext
import dev.locus.core.agent.tools.LineDiff
import dev.locus.core.database.ConversationDao
import dev.locus.core.model.AppSettings
import dev.locus.core.model.HiddenText
import dev.locus.core.model.McpToolPin
import dev.locus.core.model.McpToolPins
import io.modelcontextprotocol.kotlin.sdk.types.Tool as McpTool
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An MCP tool definition as far as pinning is concerned: what reaches the model ([name], [description],
 * [schema]) and what decides whether calls ask ([annotations]: the risk hints, see [McpToolRisk]).
 */
data class McpToolDef(val name: String, val description: String, val schema: JsonObject, val annotations: JsonObject?) {
    /** The canonical form everything is hashed and compared in: object keys sorted at every depth. */
    val schemaText: String get() = McpPinning.canonical(schema).toString()

    /** SHA-256 over the canonical JSON of name, description, schema and annotations. */
    val hash: String get() = McpPinning.hash(this)

    companion object {
        fun of(t: McpTool): McpToolDef = McpToolDef(
            name = t.name,
            description = t.description.orEmpty(),
            schema = t.schemaJson(),
            annotations = t.annotations?.let { a ->
                buildJsonObject {
                    a.title?.let { put("title", it) }
                    a.readOnlyHint?.let { put("readOnlyHint", it) }
                    a.destructiveHint?.let { put("destructiveHint", it) }
                    a.idempotentHint?.let { put("idempotentHint", it) }
                    a.openWorldHint?.let { put("openWorldHint", it) }
                }
            },
        )
    }
}

/** A tool the server offers that isn't approved as it is now: new, changed, or waiting for review. */
data class PendingTool(
    val server: String,
    val change: Change,
    /** What the user approved before; null for a new tool. */
    val before: McpToolPin?,
    val after: McpToolDef,
) {
    enum class Change { NEW, CHANGED, REVIEW }

    val name: String get() = after.name

    /** The description lines that changed, as diff lines ("+ …", "- …", "  …"), hidden characters stripped. */
    fun descriptionDiff(): List<String> = McpPinning.diffLines(
        before?.description?.let(McpPinning::sentences).orEmpty(),
        McpPinning.sentences(after.description),
    )

    /** The input schema's lines that changed, pretty-printed. */
    fun schemaDiff(): List<String> = McpPinning.diffLines(
        before?.schema?.takeIf { it.isNotEmpty() }?.let(McpPinning::pretty).orEmpty(),
        McpPinning.pretty(after.schemaText),
    )

    /**
     * Only the hints changed (read-only, destructive: what decides whether calls ask), since the pin keeps
     * the description and schema but not the hints. Said in the review, since it changes approvals.
     */
    val onlyHintsChanged: Boolean get() =
        change == Change.CHANGED && before != null && before.description == after.description && before.schema == after.schemaText

    /** Hidden characters in the new description, removed from what the model and the user see. */
    val hiddenInDescription: Int get() = HiddenText.count(after.description)
}

/**
 * Pinning rules for MCP tool definitions, without storage (docs/design/mcp-connectors.md, "Tool pinning").
 * A tool reaches the model only when its current definition hashes to the pin the user approved; a new
 * tool, or one whose description, schema or annotations changed, waits for approval in Library →
 * Connectors. An approval applies to chats created after it, so a chat's tools never change under it.
 */
object McpPinning {
    /** Pins for a connector seen for the first time ("trust on first use"), or null when it isn't a first use. */
    fun firstUse(pins: McpToolPins, server: String, defs: List<McpToolDef>): McpToolPins? {
        if (pins.noFirstUse || server in pins.servers) return null
        // approvedAt 0: what the user connected to is usable in every chat, as before pinning existed.
        return pins.copy(servers = pins.servers + (server to defs.associate { it.name to pin(it, approvedAt = 0L) }))
    }

    /** What [server]'s tools [defs] give a chat created at [chatStartedAt] (null: no chat, e.g. a settings check). */
    data class Check(
        /** Tools whose approved version is what the server offers and that this chat may use. */
        val usable: Set<String>,
        /** Tools waiting for the user's approval. */
        val pending: List<PendingTool>,
        /** Approved after this chat started: usable in new chats only. */
        val newChatsOnly: Set<String> = emptySet(),
    )

    fun check(pins: McpToolPins, server: String, defs: List<McpToolDef>, chatStartedAt: Long?): Check {
        val approved = pins.servers[server].orEmpty()
        val usable = mutableSetOf<String>()
        val later = mutableSetOf<String>()
        val pending = mutableListOf<PendingTool>()
        defs.forEach { def ->
            val pin = approved[def.name]
            when {
                pin == null -> pending += PendingTool(server, PendingTool.Change.NEW, null, def)
                pin.hash.isEmpty() -> pending += PendingTool(server, PendingTool.Change.REVIEW, pin, def)
                pin.hash != def.hash -> pending += PendingTool(server, PendingTool.Change.CHANGED, pin, def)
                chatStartedAt != null && pin.approvedAt > chatStartedAt -> later += def.name
                else -> usable += def.name
            }
        }
        return Check(usable, pending, later)
    }

    /** [pins] with [defs] of [server] approved at [now]: for chats created after this. */
    fun approve(pins: McpToolPins, server: String, defs: List<McpToolDef>, now: Long): McpToolPins {
        val tools = pins.servers[server].orEmpty() + defs.associate { it.name to pin(it, approvedAt = now) }
        return pins.copy(servers = pins.servers + (server to tools))
    }

    /** [pins] without [server]'s, after the connector was removed: a connector added again is a first connect. */
    fun forget(pins: McpToolPins, server: String): McpToolPins = pins.copy(servers = pins.servers - server)

    private fun pin(def: McpToolDef, approvedAt: Long) = McpToolPin(def.hash, def.description, def.schemaText, approvedAt)

    fun hash(def: McpToolDef): String {
        val obj = buildJsonObject {
            put("name", def.name)
            put("description", def.description)
            put("inputSchema", canonical(def.schema))
            put("annotations", def.annotations?.let(::canonical) ?: JsonObject(emptyMap()))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(obj.toString().encodeToByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** [e] with every object's keys sorted, so the same schema always gives the same text. */
    fun canonical(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(e.map(::canonical))
        else -> e
    }

    private val prettyJson = Json { prettyPrint = true }

    fun pretty(schemaText: String): List<String> = runCatching {
        prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(schemaText)).lines()
    }.getOrElse { listOf(schemaText) }

    /** A description cut into sentences and lines, hidden characters removed, so a diff shows what changed. */
    fun sentences(text: String): List<String> =
        HiddenText.strip(text).lines().flatMap { it.split(SENTENCE) }.map { it.trim() }.filter { it.isNotEmpty() }

    /** "+ ", "- " or "  " before each line of the edit script from [before] to [after]. */
    fun diffLines(before: List<String>, after: List<String>): List<String> = LineDiff.diff(before, after).map { line ->
        when (line.op) {
            LineDiff.Op.INSERT -> "+ ${line.text}"
            LineDiff.Op.DELETE -> "- ${line.text}"
            LineDiff.Op.EQUAL -> "  ${line.text}"
        }
    }

    private val SENTENCE = Regex("(?<=[.!?])\\s+")
}

/**
 * The pins in the settings store and what's waiting for approval, for [McpClients] and Library.
 * Reading a tool list goes through [filter]: the first list of a connector is pinned, later changes
 * are held back and published in [pending].
 */
@Singleton
class McpToolPinStore @Inject constructor(
    private val store: DataStore<AppSettings>,
    private val conversations: ConversationDao,
) {
    private val _pending = MutableStateFlow<Map<String, List<PendingTool>>>(emptyMap())

    /** Tools waiting for approval, by connector id, from each connector's latest list. */
    val pending: StateFlow<Map<String, List<PendingTool>>> = _pending

    /** The pins last read, for [cachedFilter], which can't wait for the store. */
    @Volatile private var last: McpToolPins? = null

    /**
     * [tools] of [server] that the current chat may use. Pins them if this is the connector's first list;
     * records what's waiting. Outside a chat (no [ToolCallContext]), approval time doesn't matter.
     */
    suspend fun filter(server: String, tools: List<McpTool>, now: Long = System.currentTimeMillis()): List<McpTool> {
        val check = check(server, tools, now)
        return tools.filter { it.name in check.usable }
    }

    /** Why [name] of [server] can't be called in this chat, or null when it can (or isn't listed at all). */
    suspend fun refusal(server: String, tools: List<McpTool>, name: String): String? {
        if (tools.none { it.name == name }) return null
        val check = check(server, tools, System.currentTimeMillis())
        return when {
            name in check.usable -> null
            name in check.newChatsOnly ->
                "“$server” changed “$name” and you approved the new version after this chat started. Approvals apply to new chats, so start a new chat to use it."
            check.pending.any { it.name == name && it.change == PendingTool.Change.NEW } ->
                "“$server” added “$name” after it was connected, so it's held back until the user approves it in Library → Connectors."
            else -> "“$server” changed “$name” since the user approved it (its description, arguments or hints), so it's held back until the user reviews the change in Library → Connectors."
        }
    }

    private suspend fun check(server: String, tools: List<McpTool>, now: Long): McpPinning.Check {
        val defs = tools.map(McpToolDef::of)
        var pins = store.data.first().mcpToolPins
        if (McpPinning.firstUse(pins, server, defs) != null) {
            pins = store.updateData { s -> McpPinning.firstUse(s.mcpToolPins, server, defs)?.let { s.copy(mcpToolPins = it) } ?: s }.mcpToolPins
        }
        last = pins
        val check = McpPinning.check(pins, server, defs, chatStartedAt())
        _pending.update { if (check.pending.isEmpty()) it - server else it + (server to check.pending) }
        return check
    }

    /** [tools] whose approved version matches, from the pins last read; never waits. Used for provider-side tool search. */
    fun cachedFilter(server: String, tools: List<McpTool>): List<McpTool> {
        val pins = last ?: return emptyList()
        val usable = McpPinning.check(pins, server, tools.map(McpToolDef::of), chatStartedAt = null).usable
        return tools.filter { it.name in usable }
    }

    /** Approves [server]'s waiting tools named [names] (all of them when null), for chats created from now on. */
    suspend fun approve(server: String, names: Set<String>? = null, now: Long = System.currentTimeMillis()) {
        val waiting = _pending.value[server].orEmpty().filter { names == null || it.name in names }
        if (waiting.isEmpty()) return
        val updated = store.updateData { s -> s.copy(mcpToolPins = McpPinning.approve(s.mcpToolPins, server, waiting.map { it.after }, now)) }
        last = updated.mcpToolPins
        _pending.update { map ->
            val left = map[server].orEmpty().filterNot { p -> waiting.any { it.name == p.name } }
            if (left.isEmpty()) map - server else map + (server to left)
        }
    }

    /** Drops [server]'s pins (the connector was removed). */
    suspend fun forget(server: String) {
        last = store.updateData { s -> s.copy(mcpToolPins = McpPinning.forget(s.mcpToolPins, server)) }.mcpToolPins
        _pending.update { it - server }
    }

    /**
     * When the chat running this call started: its root chat's creation time, since a helper shares its
     * parent's tools. Null outside a chat.
     */
    private suspend fun chatStartedAt(): Long? {
        var id = coroutineContext[ToolCallContext]?.conversationId ?: return null
        repeat(MAX_DEPTH) {
            val chat = runCatching { conversations.get(id) }.getOrNull() ?: return null
            id = chat.parentConversationId ?: return chat.createdAt
        }
        return null
    }

    private companion object {
        const val MAX_DEPTH = 8
    }
}
