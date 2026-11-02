package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.tools.AgentProfile
import dev.locus.core.agent.tools.ChildRequest
import dev.locus.core.agent.tools.ContinueRequest
import dev.locus.core.agent.tools.HelperRecord
import dev.locus.core.agent.tools.PreparedChild
import dev.locus.core.agent.tools.SubagentBackend
import dev.locus.core.agent.tools.SubagentEngine
import dev.locus.core.agent.tools.SubagentPrompts
import dev.locus.core.agent.tools.SubagentState
import dev.locus.core.agent.tools.Workspace
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.ToolCatalog
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What [SubagentRunner] needs from the runner that owns chats ([AgentRunner]). */
interface SubagentParent {
    /** Queues a helper's approval request in the parent chat; true when the user allows it. */
    suspend fun requestApproval(parentConversationId: String, request: ApprovalRequest): Boolean

    /** A background helper finished after (or while) its parent's turn ran and its report wasn't collected. */
    fun backgroundFinished(parentConversationId: String)

    /** Steering [notes] the user sent helper [label], which finished before reading them. */
    fun notesUndelivered(parentConversationId: String, label: String, notes: List<String>) {}
}

/** A helper as its row in the parent's steps card shows it. */
data class SubagentView(
    val agentId: String,
    val label: String,
    val type: String,
    val model: String,
    val providerName: String,
    val providerBaseUrl: String,
    val status: SubagentStatus,
    val steps: Int,
    val currentTool: String?,
    val tokens: Long,
    val waitingApproval: Boolean,
    val background: Boolean,
    /** Steering notes it hasn't read yet. */
    val pendingNotes: Int = 0,
)

/**
 * The app side of helpers (sub-agents): each one gets a hidden chat linked to the parent's call, a
 * model (the chat's, or the helper model), and the parent's exact tool registry. The orchestration
 * (limits, leases, reports) is [SubagentEngine] in core/agent.
 */
@Singleton
class SubagentRunner @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val access: ProviderAccess,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val tools: ToolCatalog,
    private val helper: HelperModel,
    private val home: AgentHome,
    private val workspace: Workspace,
    private val usage: dev.locus.core.data.repository.UsageLog,
    private val directory: dev.locus.core.data.repository.ModelDirectory,
) : SubagentBackend {
    val engine = SubagentEngine(this, scope)

    @Volatile
    var parent: SubagentParent? = null

    override fun profile(type: String): AgentProfile? =
        AgentProfile.BUILT_IN.firstOrNull { it.type == type } ?: home.agent(type)?.let(AgentProfile::from)

    override fun profiles(): List<AgentProfile> = AgentProfile.BUILT_IN + home.agents().mapNotNull(AgentProfile::from)

    override suspend fun childCount(parentConversationId: String, type: String): Int = conversations.countHelpers(parentConversationId, type)

    override suspend fun prepare(request: ChildRequest): PreparedChild {
        val parentChat = conversations.get(request.parentConversationId) ?: throw ToolException("This chat no longer exists.")
        val current = settings.current()
        val base = current.provider(parentChat.providerId) ?: throw ToolException("No model provider is set up.")
        val chat = chatProvider(base, parentChat)
        val profile = request.profile
        var provider = when {
            request.fork -> chat
            request.helperModel -> helper.providerFor(chat, parentChat.id)?.let { h -> h.copy(maxOutputTokens = current.provider(h.id)?.maxOutputTokens ?: chat.maxOutputTokens) } ?: chat
            profile.model != AgentProfile.MODEL_SAME && profile.model != AgentProfile.MODEL_HELPER -> chat.copy(model = profile.model)
            else -> chat
        }
        // A helper always calls tools. When the helper model, or the profile's own model, is known not to,
        // the helper runs on the chat's model instead (a fork already does).
        if (provider !== chat && !servesHelper(directory.capabilities(provider, provider.model))) provider = chat
        // On-device models run one request at a time and are slow; helpers would stall the chat.
        if (provider.format == ApiFormat.ON_DEVICE) {
            throw ToolException("Helpers need a cloud model, and this chat runs on the on-device model. Do the work yourself.")
        }
        // A fork keeps the chat's effort (it's part of the cached prefix); fresh helpers use their profile's.
        if (!request.fork) profile.effort?.wireValue(provider.format)?.let { provider = provider.copy(effort = it) }
        access.missing(provider)?.let { throw ToolException(it) }

        val system = if (request.fork) parentChat.systemPrompt else SubagentPrompts.system(profile)
        val child = conversations.createHelper(
            id = request.agentId,
            parentConversationId = request.parentConversationId,
            parentCallId = request.parentCallId,
            agentType = profile.type,
            title = request.label,
            providerId = provider.id,
            model = provider.model,
            systemPrompt = system,
            mcpFirst = parentChat.mcpFirst,
            toolDiscovery = parentChat.toolDiscovery,
            // The helper is part of its chat's project: the same permissions apply to it.
            projectId = parentChat.projectId,
            projectMemory = parentChat.projectMemory,
        )
        val now = System.currentTimeMillis()
        val taskText = if (request.fork) {
            "$FORK_INTRO (${request.label}). Everything above is the main chat so far, " +
                "for context only. Do just the task below, then reply with your report.\n\n${SubagentPrompts.system(profile)}\n\n<task>\n${request.task}\n</task>"
        } else {
            request.task
        }
        val task = Message(UUID.randomUUID().toString(), child.id, Role.USER, listOf(Part.Text(taskText)), now)
        // Only the task is kept in the helper's chat; a fork's copy of the parent transcript lives in memory.
        conversations.append(task)
        val prefix = if (request.fork) forkPrefix(request) else emptyList()
        return PreparedChild(
            conversation = child,
            history = prefix + task,
            model = access.chatModel(provider),
            modelName = provider.model,
            providerName = provider.name,
            providerBaseUrl = provider.baseUrl,
            // The parent chat's list (the helper chat copies its choice), so a fork shares its cached prefix.
            tools = tools.forChat(child),
            userPolicy = { tool -> tools.policy(tool, current, parentChat.projectId) },
            store = conversations,
            forkPrefix = prefix,
            compaction = compactionSetup(provider, current),
            spill = tools.outputSpill,
        )
    }

    override fun compacted(state: SubagentState, event: dev.locus.core.agent.AgentEvent.Compacted) {
        scope.launch {
            usage.record(dev.locus.core.model.UsageSource.COMPACTION, state.agentId, event.providerId, event.model, event.usage, event.durationMs)
        }
    }

    override suspend fun helper(agentId: String): HelperRecord? {
        val row = conversations.get(agentId)?.takeIf { it.hidden && it.parentConversationId != null } ?: return null
        val history = conversations.history(agentId)
        return HelperRecord(
            agentId = row.id,
            parentConversationId = row.parentConversationId,
            parentCallId = row.parentCallId,
            type = row.agentType.orEmpty(),
            label = row.title,
            status = row.agentStatus,
            fork = history.firstOrNull { it.role == Role.USER }?.parts?.filterIsInstance<Part.Text>()?.firstOrNull()?.text?.startsWith(FORK_INTRO) == true,
            steps = history.count { it.role == Role.ASSISTANT },
            tokens = history.sumOf { m -> m.usage?.let { (it.input + it.output).toLong() } ?: 0L },
        )
    }

    override suspend fun prepareContinue(request: ContinueRequest): PreparedChild {
        val helperChat = conversations.get(request.helper.agentId) ?: throw ToolException("That helper's chat no longer exists.")
        val previous = request.previous
        val current = settings.current()
        // In this process: the very model the helper ran on. After a restart: rebuilt from its saved chat.
        val model: dev.locus.core.llm.ChatModel
        val modelName: String
        val providerName: String
        val providerBaseUrl: String
        val compaction: dev.locus.core.agent.CompactionSetup?
        if (previous != null) {
            compaction = previous.compaction
            model = previous.model
            modelName = previous.modelName
            providerName = previous.providerName
            providerBaseUrl = previous.providerBaseUrl
        } else {
            if (request.helper.fork) throw ToolException("A forked helper can't be continued after Locus restarted.")
            val provider = savedProvider(helperChat, request)
            model = access.chatModel(provider)
            modelName = provider.model
            providerName = provider.name
            providerBaseUrl = provider.baseUrl
            compaction = compactionSetup(provider, current)
        }
        // The follow-up becomes the new leaf of the helper's own chat: its transcript only grows. Marked
        // running first, so Recovery marks it interrupted if Locus is killed mid-run.
        conversations.setHelperStatus(helperChat.id, SubagentStatus.RUNNING)
        conversations.append(Message(UUID.randomUUID().toString(), helperChat.id, Role.USER, request.parts, System.currentTimeMillis()))
        val saved = conversations.history(helperChat.id)
        // Once the helper compacted, its summary covers the fork prefix too: the view starts there.
        val compacted = saved.any { m -> m.parts.any { it is Part.Checkpoint } }
        val prefix = if (compacted) emptyList() else previous?.forkPrefix.orEmpty()
        return PreparedChild(
            conversation = helperChat,
            history = prefix + modelContext(saved),
            model = model,
            modelName = modelName,
            providerName = providerName,
            providerBaseUrl = providerBaseUrl,
            tools = tools.forChat(helperChat),
            userPolicy = { tool -> tools.policy(tool, current, helperChat.projectId) },
            store = conversations,
            forkPrefix = prefix,
            compaction = compaction,
            spill = tools.outputSpill,
        )
    }

    /** A saved helper's provider and model, with its profile's effort (as [prepare] chose them). */
    private suspend fun savedProvider(helperChat: dev.locus.core.model.Conversation, request: ContinueRequest): dev.locus.core.model.ProviderConfig {
        val current = settings.current()
        val base = current.provider(helperChat.providerId) ?: throw ToolException("The helper's model provider was removed. Start a new helper.")
        val parentChat = conversations.get(request.parentConversationId)
        // On the chat's own provider, the chat's effort applies (as when it started); a separate helper provider keeps its own.
        var provider = if (parentChat != null && parentChat.providerId == base.id) chatProvider(base, parentChat) else base
        provider = provider.copy(model = helperChat.modelOverride ?: provider.model)
        request.profile.effort?.wireValue(provider.format)?.let { provider = provider.copy(effort = it) }
        if (provider.format == ApiFormat.ON_DEVICE) {
            throw ToolException("Helpers need a cloud model, and this helper's model is on-device. Do the work yourself.")
        }
        access.missing(provider)?.let { throw ToolException(it) }
        return provider
    }

    /** The parent's transcript as the model sees it, with its open calls answered so the request is valid. */
    private suspend fun forkPrefix(request: ChildRequest): List<Message> {
        val history = modelContext(conversations.history(request.parentConversationId))
        val answered = history.flatMap { it.toolResults }.map { it.callId }.toSet()
        val open = history.flatMap { it.toolCalls }.filter { it.id !in answered }
        if (open.isEmpty()) return history
        val results = open.map { c ->
            Part.ToolResult(c.id, c.name, if (c.id == request.parentCallId) "You are the helper started by this call; your task follows." else "Not run in this fork.")
        }
        return history + Message(UUID.randomUUID().toString(), request.agentId, Role.TOOL, results, System.currentTimeMillis())
    }

    override suspend fun requestApproval(parentConversationId: String, request: ApprovalRequest): Boolean =
        parent?.requestApproval(parentConversationId, request) ?: false

    override suspend fun finished(state: SubagentState, report: String) {
        conversations.setHelperStatus(state.agentId, state.status)
    }

    override suspend fun saveFullReport(state: SubagentState, report: String): String? = withContext(Dispatchers.IO) {
        val path = "helpers/${state.label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-${state.agentId.takeLast(8)}.md"
        val file: File = workspace.resolve(path)
        file.parentFile?.mkdirs()
        file.writeText("# ${state.label} report\n\n$report\n")
        path
    }

    override fun backgroundFinished(parentConversationId: String) {
        parent?.backgroundFinished(parentConversationId)
    }

    override fun notesUndelivered(state: SubagentState, notes: List<String>) {
        parent?.notesUndelivered(state.parentConversationId, state.label, notes)
    }

    // ---- For the chat -----------------------------------------------------------------------------

    /** The helper started by the parent's call [parentCallId]: live while it works, from its saved chat afterwards. */
    fun observeByCall(parentCallId: String): Flow<SubagentView?> =
        observeHelper(conversations.observeHelperByCall(parentCallId)) { it.parentCallId == parentCallId }

    /** Helper [agentId] (an `agent_send` row): live while it works, from its saved chat afterwards. */
    fun observeById(agentId: String): Flow<SubagentView?> =
        observeHelper(conversations.observe(agentId).map { row -> row?.takeIf { it.hidden && it.parentConversationId != null } }) { it.agentId == agentId }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeHelper(savedRow: Flow<dev.locus.core.model.Conversation?>, isIt: (SubagentState) -> Boolean): Flow<SubagentView?> {
        val live = engine.states.map { all -> all.values.firstOrNull(isIt) }.distinctUntilChanged()
        val saved = savedRow.flatMapLatest { row ->
            if (row == null) {
                flowOf(null)
            } else {
                combine(conversations.observeMessages(row.id), settings.settings) { messages, s ->
                    val provider = s.providers.firstOrNull { it.id == row.providerId }
                    SubagentView(
                        agentId = row.id,
                        label = row.title,
                        type = row.agentType.orEmpty(),
                        model = row.modelOverride ?: provider?.model.orEmpty(),
                        providerName = provider?.name.orEmpty(),
                        providerBaseUrl = provider?.baseUrl.orEmpty(),
                        status = row.agentStatus ?: SubagentStatus.INTERRUPTED,
                        steps = messages.count { it.role == Role.ASSISTANT },
                        currentTool = null,
                        tokens = messages.sumOf { m -> m.usage?.let { (it.input + it.output).toLong() } ?: 0L },
                        waitingApproval = false,
                        background = false,
                    )
                }
            }
        }
        return combine(live, saved) { l, s ->
            l?.let {
                SubagentView(
                    it.agentId, it.label, it.type, it.model, it.providerName, it.providerBaseUrl, it.status, it.steps, it.currentTool, it.tokens,
                    it.waitingApproval, it.background, it.pendingNotes,
                )
            } ?: s
        }.distinctUntilChanged()
    }

    /** A helper's chat, read-only. */
    fun observeTranscript(agentId: String): Flow<List<Message>> = conversations.observeMessages(agentId)

    /** The user's Stop on a helper row. */
    fun stop(agentId: String) = engine.stop(agentId)

    /** Steer: the user's [note] for working helper [agentId], read at its next step boundary. False when it isn't working. */
    fun steer(agentId: String, note: String): Boolean = engine.steer(agentId, note)

    /** How many helpers work for [parentConversationId] now. */
    fun activeFor(parentConversationId: String): Int = engine.activeFor(parentConversationId)

    private companion object {
        /** How a forked helper's first message starts; also how [helper] tells a fork from its saved chat. */
        const val FORK_INTRO = "[From the main agent, not the user] You are now a forked helper"
    }
}

/**
 * Whether a model with [caps] can run a helper: helpers always call tools. Unknown counts as yes, like
 * everywhere else (docs/design/model-capabilities.md); only a model known to lack tool calling is refused.
 */
internal fun servesHelper(caps: dev.locus.core.llm.ModelCapabilities): Boolean =
    caps.fit(dev.locus.core.llm.ModelRequirements(tools = true)).usable
