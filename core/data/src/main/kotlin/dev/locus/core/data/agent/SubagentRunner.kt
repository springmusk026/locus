package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ToolException
import dev.locus.core.agent.home.AgentHome
import dev.locus.core.agent.tools.AgentProfile
import dev.locus.core.agent.tools.ChildRequest
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
import dev.locus.core.llm.ChatModels
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.SubagentStatus
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
)

/**
 * The app side of helpers (sub-agents): each one gets a hidden chat linked to the parent's call, a
 * model (the chat's, or the helper model), and the parent's exact tool registry. The orchestration
 * (limits, leases, reports) is [SubagentEngine] in core/agent.
 */
@Singleton
class SubagentRunner @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val http: HttpClient,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val tools: ToolCatalog,
    private val helper: HelperModel,
    private val home: AgentHome,
    private val workspace: Workspace,
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
            request.helperModel -> helper.providerFor(chat)?.let { h -> h.copy(maxOutputTokens = current.provider(h.id)?.maxOutputTokens ?: chat.maxOutputTokens) } ?: chat
            profile.model != AgentProfile.MODEL_SAME && profile.model != AgentProfile.MODEL_HELPER -> chat.copy(model = profile.model)
            else -> chat
        }
        // On-device models run one request at a time and are slow; helpers would stall the chat.
        if (provider.format == ApiFormat.ON_DEVICE) {
            throw ToolException("Helpers need a cloud model, and this chat runs on the on-device model. Do the work yourself.")
        }
        // A fork keeps the chat's effort (it's part of the cached prefix); fresh helpers use their profile's.
        if (!request.fork) profile.effort?.wireValue(provider.format)?.let { provider = provider.copy(effort = it) }
        val key = settings.apiKey(provider.id)
        if (provider.requiresKey && key.isNullOrBlank()) throw ToolException("${provider.name} has no API key.")

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
        )
        val now = System.currentTimeMillis()
        val taskText = if (request.fork) {
            "[From the main agent, not the user] You are now a forked helper (${request.label}). Everything above is the main chat so far, " +
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
            model = ChatModels.create(http, provider, key),
            modelName = provider.model,
            providerName = provider.name,
            providerBaseUrl = provider.baseUrl,
            tools = tools.registry,
            userPolicy = { tool -> tools.policy(tool, current) },
            store = conversations,
        )
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

    // ---- For the chat -----------------------------------------------------------------------------

    /** The helper started by the parent's call [parentCallId]: live while it works, from its saved chat afterwards. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeByCall(parentCallId: String): Flow<SubagentView?> {
        val live = engine.states.map { all -> all.values.firstOrNull { it.parentCallId == parentCallId } }.distinctUntilChanged()
        val saved = conversations.observeHelperByCall(parentCallId).flatMapLatest { row ->
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
                    it.waitingApproval, it.background,
                )
            } ?: s
        }.distinctUntilChanged()
    }

    /** A helper's chat, read-only. */
    fun observeTranscript(agentId: String): Flow<List<Message>> = conversations.observeMessages(agentId)

    /** The user's Stop on a helper row. */
    fun stop(agentId: String) = engine.stop(agentId)

    /** How many helpers work for [parentConversationId] now. */
    fun activeFor(parentConversationId: String): Int = engine.activeFor(parentConversationId)
}
