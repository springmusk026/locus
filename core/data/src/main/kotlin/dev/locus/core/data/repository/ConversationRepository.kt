package dev.locus.core.data.repository

import dev.locus.core.agent.SystemPrompt
import dev.locus.core.agent.TranscriptStore
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.data.search.ChatSearch
import dev.locus.core.data.search.ChatSearchHit
import dev.locus.core.data.search.SearchSource
import dev.locus.core.data.search.SearchText
import dev.locus.core.database.AuditDao
import dev.locus.core.database.AuditEntity
import dev.locus.core.database.ConversationDao
import dev.locus.core.database.ConversationEntity
import dev.locus.core.database.MessageDao
import dev.locus.core.database.MessageEntity
import dev.locus.core.database.TreeRow
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.ChatThread
import dev.locus.core.model.MessageTree
import dev.locus.core.model.TreeNode
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Serialises writes that read the active leaf and then add to it. */
    private val writes = Mutex()

    /**
     * The branch of the chat that's shown and continued, as a linear transcript, with where its messages
     * have alternatives. Other branches stay stored but are never shown or sent.
     */
    fun observeThread(id: String): Flow<ChatThread> = messages.observeTree(id).map { it.toThread() }

    /** The active branch's messages (see [observeThread]). */
    fun observeMessages(id: String): Flow<List<Message>> = observeThread(id).map { it.messages }

    suspend fun get(id: String): Conversation? = conversations.get(id)?.toModel()

    suspend fun allOnce(): List<Conversation> = all.first()

    /** The active branch as a linear transcript: what the model is sent, recovered, compacted and searched. */
    suspend fun history(id: String): List<Message> = thread(id).messages

    suspend fun thread(id: String): ChatThread = messages.tree(id).toThread()

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
    /**
     * With [replacing] (an earlier user message's id), the new message is an edit of that one: a sibling
     * under the same parent, starting a new branch. Otherwise it's added at the end of the active branch.
     */
    suspend fun appendUserMessage(
        conversationId: String,
        text: String,
        attachments: List<Part.Attachment> = emptyList(),
        modeNote: String? = null,
        replacing: String? = null,
    ): Message {
        val parts = attachments + listOfNotNull(Part.Text(text).takeIf { text.isNotBlank() }, modeNote?.let { Part.Text(dev.locus.core.model.ModeNote.wrap(it)) })
        val message = Message(UUID.randomUUID().toString(), conversationId, Role.USER, parts, System.currentTimeMillis())
        if (replacing == null) {
            append(message)
        } else {
            writes.withLock { messages.insertAsLeaf(message.toEntity(parentId = messages.parentOf(replacing)), message.createdAt) }
        }
        val conversation = conversations.get(conversationId)
        if (conversation != null && conversation.title == "New chat") {
            conversations.rename(conversationId, text.lineSequence().first().take(60).ifBlank { attachments.firstOrNull()?.name ?: "New chat" })
        }
        return message
    }

    suspend fun rename(id: String, title: String) = conversations.rename(id, title)

    // ---- Branches ----------------------------------------------------------------------------------

    /** The last message of the active branch; null for an empty chat. */
    suspend fun activeLeaf(conversationId: String): String? = messages.leaf(conversationId)

    /**
     * Makes the branch end at [messageId] (which may be an inner message, e.g. the user message a
     * regenerated reply will follow). Nothing is removed; later messages stay reachable as a branch.
     */
    suspend fun setActiveLeaf(conversationId: String, messageId: String) = writes.withLock { conversations.setActiveLeaf(conversationId, messageId) }

    /** Shows the branch through [messageId]: its newest message becomes the leaf. Returns false if it isn't in this chat. */
    suspend fun switchBranch(conversationId: String, messageId: String): Boolean = writes.withLock {
        val rows = messages.tree(conversationId)
        val tree = MessageTree(rows.map { TreeNode(it.message.id, it.message.parentId) })
        if (messageId !in tree) return@withLock false
        conversations.setActiveLeaf(conversationId, tree.newestUnder(messageId))
        true
    }

    /**
     * Starts a new chat with the active branch up to [upTo] (included when [inclusive]). It keeps the
     * source's frozen system prompt and per-chat settings, so copied provider payloads stay valid; the
     * copies remember their source ([MessageEntity.copiedFrom]) so their usage isn't counted twice.
     * Returns null when [upTo] isn't on the active branch.
     */
    suspend fun fork(conversationId: String, upTo: String, inclusive: Boolean): Conversation? {
        val source = conversations.get(conversationId) ?: return null
        val path = history(conversationId)
        if (path.none { it.id == upTo }) return null
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val copies = MessageTree.forkCopies(path, upTo, inclusive, id) { UUID.randomUUID().toString() }
        conversations.upsert(
            source.copy(
                id = id, title = forkTitle(source.title), createdAt = now, updatedAt = now, pinned = false,
                parentConversationId = null, parentCallId = null, agentType = null, agentStatus = null, hidden = false,
                activeLeafId = copies.lastOrNull()?.id,
            ),
        )
        messages.insertAll(copies.mapIndexed { i, copy -> copy.toEntity(parentId = copies.getOrNull(i - 1)?.id, copiedFrom = path[i].id) })
        // Forked mid-step: answer calls that have no result yet, as recovery would, so the new chat can continue.
        val missing = dev.locus.core.agent.recoverTranscript(copies).missingResults
        if (missing.isNotEmpty()) append(Message(UUID.randomUUID().toString(), id, Role.TOOL, missing, now))
        return conversations.get(id)?.toModel()
    }

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

    private val chatSearch = ChatSearch(
        object : SearchSource {
            override suspend fun chats() = allOnce()

            override suspend fun messages(match: String, tokens: Int, limit: Int) = this@ConversationRepository.messages.search(match, tokens, limit)

            override suspend fun shownBranch(conversationId: String): Set<String> {
                val links = this@ConversationRepository.messages.links(conversationId)
                val tree = MessageTree(links.map { TreeNode(it.id, it.parentId) })
                return tree.path(tree.leaf(links.firstOrNull()?.activeLeafId)).toSet()
            }
        },
    )

    /**
     * The user's chats matching [query] (words match as prefixes, "quoted phrases" exactly), one per
     * chat with its newest matching message, newest first. Searches every branch's text and the titles;
     * helper chats are left out. See docs/design/search.md.
     */
    suspend fun search(query: String, limit: Int = ChatSearch.DEFAULT_LIMIT, snippetTokens: Int = ChatSearch.SNIPPET_TOKENS): List<ChatSearchHit> =
        chatSearch.search(query, limit, snippetTokens)

    /** Adds [message] at the end of the active branch and makes it the leaf. */
    override suspend fun append(message: Message) = writes.withLock {
        messages.insertAsLeaf(message.toEntity(parentId = messages.leaf(message.conversationId)), message.createdAt)
    }

    override suspend fun record(entry: AuditEntry) = audit.insert(entry.toEntity())

    fun observeAudit(limit: Int = 500): Flow<List<AuditEntry>> = audit.observeRecent(limit).map { list -> list.map { it.toModel() } }

    internal companion object {
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

        /** "Title (fork)", with a long title cut at a word so it doesn't end mid-word ("…prints th (fork)"). */
        fun forkTitle(title: String): String {
            val base = title.removeSuffix(FORK_SUFFIX).trim()
            if (base.length <= FORK_TITLE_MAX) return base + FORK_SUFFIX
            val cut = base.take(FORK_TITLE_MAX).substringBeforeLast(' ').ifBlank { base.take(FORK_TITLE_MAX) }
            return cut.trimEnd(' ', ',', '.', ';', ':', '-') + "…" + FORK_SUFFIX
        }

        private const val FORK_TITLE_MAX = 40

        const val FORK_SUFFIX = " (fork)"

        /** The active path of a chat's tree; only its messages are decoded. */
        fun List<TreeRow>.toThread(): ChatThread {
            if (isEmpty()) return ChatThread(emptyList())
            val tree = MessageTree(map { TreeNode(it.message.id, it.message.parentId) })
            val path = tree.path(tree.leaf(first().activeLeafId))
            val byId = associateBy { it.message.id }
            return ChatThread(path.map { byId.getValue(it).message.toModel() }, tree.branches(path))
        }

        fun Message.toEntity(parentId: String?, copiedFrom: String? = null) = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role.name,
            partsJson = json.encodeToString(partsSerializer, parts),
            createdAt = createdAt,
            format = format?.name,
            rawPayload = rawPayload,
            usageJson = usage?.let { json.encodeToString(dev.locus.core.model.TokenUsage.serializer(), it) },
            parentId = parentId,
            copiedFrom = copiedFrom,
            searchText = SearchText.of(role, parts),
        )

        fun AuditEntry.toEntity() = AuditEntity(id, conversationId, callId, toolName, argumentsJson, decision.name, resultPreview, isError, startedAt, finishedAt)

        fun AuditEntity.toModel() = AuditEntry(id, conversationId, callId, toolName, argumentsJson, ApprovalDecision.valueOf(decision), resultPreview, isError, startedAt, finishedAt)
    }
}
