package dev.locus.core.data.repository

import dev.locus.core.agent.SystemPrompt
import dev.locus.core.agent.TranscriptStore
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.database.AuditDao
import dev.locus.core.database.AuditEntity
import dev.locus.core.database.ConversationDao
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageDao
import dev.locus.core.database.MessageEntity
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversations: ConversationDao,
    private val messages: MessageDao,
    private val audit: AuditDao,
    private val settings: SettingsRepository,
    private val home: AgentHome,
) : TranscriptStore {

    /** The user's chats; helper (sub-agent) chats are hidden. */
    val all: Flow<List<Conversation>> = conversations.observeVisible().map { list -> list.map { it.toModel() } }

    /** Every chat, helpers included, for screens that account for work (Activity, Insights). */
    val allWithHelpers: Flow<List<Conversation>> = conversations.observeAll().map { list -> list.map { it.toModel() } }

    /** Ids of chats with no messages yet (opened and left), so lists can hide them. */
    val emptyIds: Flow<Set<String>> = conversations.observeEmptyIds().map { it.toSet() }

    fun observe(id: String): Flow<Conversation?> = conversations.observe(id).map { it?.toModel() }

    fun observeMessages(id: String): Flow<List<Message>> = messages.observe(id).map { list -> list.map { it.toModel() } }

    suspend fun get(id: String): Conversation? = conversations.get(id)?.toModel()

    suspend fun allOnce(): List<Conversation> = all.first()

    suspend fun history(id: String): List<Message> = messages.all(id).map { it.toModel() }

    /** Starts a conversation, freezing today's system prompt, instructions, memory index and catalogs into it. */
    suspend fun create(providerId: String?): Conversation {
        val now = System.currentTimeMillis()
        val custom = settings.current().customInstructions
        // A few recent titles, so "our last chat" makes sense; frozen like the rest of the prompt.
        val recent = allOnce().filter { it.title != "New chat" }.take(8).joinToString("\n") { c ->
            "- “${c.title}” (id ${c.id}, last active ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(c.updatedAt))})"
        }
        val entity = ConversationEntity(UUID.randomUUID().toString(), "New chat", providerId, SystemPrompt.build(custom, home.snapshot(), recentChats = recent), now, now)
        conversations.upsert(entity)
        return entity.toModel()
    }

    /** [modeNote], when set, rides on the message for the model only (see [dev.locus.core.model.ModeNote]). */
    suspend fun appendUserMessage(conversationId: String, text: String, attachments: List<Part.Attachment> = emptyList(), modeNote: String? = null): Message {
        val parts = attachments + listOfNotNull(Part.Text(text).takeIf { text.isNotBlank() }, modeNote?.let { Part.Text(dev.locus.core.model.ModeNote.wrap(it)) })
        val message = Message(UUID.randomUUID().toString(), conversationId, Role.USER, parts, System.currentTimeMillis())
        append(message)
        val conversation = conversations.get(conversationId)
        if (conversation != null && conversation.title == "New chat") {
            conversations.rename(conversationId, text.lineSequence().first().take(60).ifBlank { attachments.firstOrNull()?.name ?: "New chat" })
        }
        return message
    }

    suspend fun rename(id: String, title: String) = conversations.rename(id, title)

    // ---- Helper (sub-agent) chats ------------------------------------------------------------------

    /** A hidden chat for a helper, linked to the parent's call that started it. */
    suspend fun createHelper(
        id: String,
        parentConversationId: String,
        parentCallId: String,
        agentType: String,
        title: String,
        providerId: String?,
        model: String?,
        systemPrompt: String,
    ): Conversation {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = id, title = title, providerId = providerId, systemPrompt = systemPrompt, createdAt = now, updatedAt = now,
            modelOverride = model, parentConversationId = parentConversationId, parentCallId = parentCallId, agentType = agentType,
            agentStatus = dev.locus.core.model.SubagentStatus.RUNNING.wire, hidden = true,
        )
        conversations.upsert(entity)
        return entity.toModel()
    }

    suspend fun helpers(parentConversationId: String): List<Conversation> = conversations.children(parentConversationId).map { it.toModel() }

    fun observeHelperByCall(parentCallId: String): Flow<Conversation?> = conversations.observeByParentCall(parentCallId).map { it?.toModel() }

    suspend fun helperByCall(parentCallId: String): Conversation? = conversations.byParentCall(parentCallId)?.toModel()

    suspend fun helpersWithStatus(status: dev.locus.core.model.SubagentStatus): List<Conversation> =
        conversations.helpersWithStatus(status.wire).map { it.toModel() }

    suspend fun countHelpers(parentConversationId: String, agentType: String): Int = conversations.countChildren(parentConversationId, agentType)

    suspend fun setHelperStatus(id: String, status: dev.locus.core.model.SubagentStatus) = conversations.setAgentStatus(id, status.wire)

    /** Later turns use this provider; earlier turns stay as they were (providers replay what they can). */
    suspend fun setProvider(id: String, providerId: String) = conversations.setProvider(id, providerId)

    /** Switches provider and model together; a null [model] means the provider's own default. */
    suspend fun setModel(id: String, providerId: String, model: String?) {
        conversations.setProvider(id, providerId)
        conversations.setModelOverride(id, model?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun setEffort(id: String, effort: dev.locus.core.model.Effort?) = conversations.setEffort(id, effort?.name)

    suspend fun setPlanMode(id: String, on: Boolean) = conversations.setPlanMode(id, on)

    suspend fun setDisabledTools(id: String, keys: Set<String>) = conversations.setDisabledTools(id, keys.sorted().joinToString("\n"))

    suspend fun delete(id: String) = conversations.delete(id)

    suspend fun setPinned(id: String, pinned: Boolean) = conversations.setPinned(id, pinned)

    /** Ids of chats whose title or messages mention [query], case-insensitively (SQLite LIKE). */
    suspend fun search(query: String): Set<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptySet()
        fun like(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        // Messages are stored as JSON: match the query as it's encoded there, after a text field's
        // opening, so JSON keys like "text" or "kind" never match.
        val jsonEncoded = q.replace("\\", "\\\\").replace("\"", "\\\"")
        return conversations.search(titlePattern = "%${like(q)}%", messagePattern = "%\"text\":\"%${like(jsonEncoded)}%").toSet()
    }

    override suspend fun append(message: Message) {
        messages.insert(message.toEntity())
        conversations.touch(message.conversationId, message.createdAt)
    }

    override suspend fun record(entry: AuditEntry) = audit.insert(entry.toEntity())

    fun observeAudit(limit: Int = 500): Flow<List<AuditEntry>> = audit.observeRecent(limit).map { list -> list.map { it.toModel() } }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }
        val partsSerializer = ListSerializer(Part.serializer())

        fun ConversationEntity.toModel() = Conversation(
            id, title, providerId, systemPrompt, createdAt, updatedAt, pinned,
            modelOverride = modelOverride?.takeIf { it.isNotBlank() },
            effort = effort?.let { e -> dev.locus.core.model.Effort.entries.firstOrNull { it.name == e } },
            planMode = planMode,
            disabledTools = disabledTools.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            parentConversationId = parentConversationId,
            parentCallId = parentCallId,
            agentType = agentType,
            agentStatus = dev.locus.core.model.SubagentStatus.parse(agentStatus),
            hidden = hidden,
        )

        fun MessageEntity.toModel() = Message(
            id = id,
            conversationId = conversationId,
            role = Role.valueOf(role),
            parts = json.decodeFromString(partsSerializer, partsJson),
            createdAt = createdAt,
            format = format?.let(ApiFormat::valueOf),
            rawPayload = rawPayload,
            usage = usageJson?.let { runCatching { json.decodeFromString(dev.locus.core.model.TokenUsage.serializer(), it) }.getOrNull() },
        )

        fun Message.toEntity() = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role.name,
            partsJson = json.encodeToString(partsSerializer, parts),
            createdAt = createdAt,
            format = format?.name,
            rawPayload = rawPayload,
            usageJson = usage?.let { json.encodeToString(dev.locus.core.model.TokenUsage.serializer(), it) },
        )

        fun AuditEntry.toEntity() = AuditEntity(id, conversationId, callId, toolName, argumentsJson, decision.name, resultPreview, isError, startedAt, finishedAt)

        fun AuditEntity.toModel() = AuditEntry(id, conversationId, callId, toolName, argumentsJson, ApprovalDecision.valueOf(decision), resultPreview, isError, startedAt, finishedAt)
    }
}
