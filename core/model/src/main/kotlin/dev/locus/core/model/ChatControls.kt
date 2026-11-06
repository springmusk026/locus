package dev.locus.core.model

/**
 * How hard the model thinks, chosen per chat in the composer. Mapped per wire format by [wireValue].
 *
 * Changing it mid-chat is allowed but not free: providers render the effort/thinking setting into
 * the cached prefix, so a change invalidates the cached conversation history once (the tool list and
 * system prompt stay cached on most models), and later turns cache again at the new level.
 */
enum class Effort(val label: String, val description: String) {
    OFF("Off", "Fastest. Little or no reasoning."),
    LOW("Low", "Quick answers and simple tasks."),
    MEDIUM("Medium", "Balanced speed and depth."),
    HIGH("High", "Thorough work on harder tasks."),
    MAX("Max", "Deepest reasoning. Slowest and most tokens."),
    ;

    /**
     * The value to send, or null when [format] has no effort setting (on-device).
     *
     * - Anthropic `output_config.effort` takes low / medium / high / xhigh / max. Current Claude models
     *   (Opus 5.5, Sonnet 5.5) reject `thinking: disabled`, so "Off" is the lowest effort, not a
     *   thinking switch.
     * - OpenAI Responses `reasoning.effort` takes none / minimal / low / medium / high / xhigh / max
     *   (openai-python `ReasoningEffort`); which ones a model accepts varies, and the adapter retries
     *   without reasoning settings when a model rejects them.
     * - Chat Completions `reasoning_effort` (OpenAI, Gemini's OpenAI-compatible endpoint) and OpenRouter's
     *   `reasoning.effort`: minimal / low / medium / high / xhigh here; the adapter fits it to what each host
     *   documents (Gemini tops out at `high`) and only sends it to [CHAT_EFFORT_HOSTS].
     */
    fun wireValue(format: ApiFormat): String? = when (format) {
        ApiFormat.ANTHROPIC_MESSAGES -> when (this) {
            OFF, LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
            MAX -> "max"
        }
        ApiFormat.OPENAI_RESPONSES, ApiFormat.CHATGPT -> when (this) {
            OFF -> "none"
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
            MAX -> "xhigh"
        }
        ApiFormat.OPENAI_CHAT -> when (this) {
            OFF -> "minimal"
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
            MAX -> "xhigh"
        }
        ApiFormat.ON_DEVICE -> null
    }

    companion object {
        /**
         * Chat Completions hosts whose docs define an effort field: OpenAI (`reasoning_effort`, API
         * reference), Gemini's OpenAI-compatible endpoint (`reasoning_effort`, aistudio.google.com/docs/openai)
         * and OpenRouter (`reasoning.effort`, its reasoning tokens guide). Others (Groq, Ollama, local
         * servers) get none: an unknown field can be a 400 there.
         */
        val CHAT_EFFORT_HOSTS = listOf("api.openai.com", "generativelanguage.googleapis.com", "openrouter.ai")

        /** The [CHAT_EFFORT_HOSTS] entry [baseUrl] points at, or null. */
        fun chatEffortHost(baseUrl: String): String? {
            val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }.getOrNull() ?: return null
            return CHAT_EFFORT_HOSTS.firstOrNull { host == it || host.endsWith(".$it") }
        }

        /** Whether a provider of [format] at [baseUrl] takes an effort level at all. */
        fun supports(format: ApiFormat, baseUrl: String = ""): Boolean = when (format) {
            ApiFormat.ON_DEVICE -> false
            ApiFormat.OPENAI_CHAT -> chatEffortHost(baseUrl) != null
            else -> true
        }

        /**
         * The level [model] thinks at when no effort is sent, where its maker documents it; null when it
         * isn't known (Gemini decides per model, OpenRouter per upstream). Anthropic's effort page (read
         * 2026-11-06): "Most Claude models default to high effort …; Claude Opus 5.5 and Claude Haiku 5.5
         * default to medium." OpenAI: `reasoning_effort` defaults to `medium` (Chat Completions reference),
         * as do gpt-5.5, GPT-5.6 and the GPT-6 models (reasoning guide).
         */
        fun modelDefault(format: ApiFormat, baseUrl: String, model: String): Effort? {
            val id = model.trim().lowercase().substringAfterLast('/')
            return when (format) {
                ApiFormat.ANTHROPIC_MESSAGES -> if (MEDIUM_DEFAULT_CLAUDE.any { id == it || id.startsWith("$it-") }) MEDIUM else HIGH
                ApiFormat.OPENAI_RESPONSES, ApiFormat.CHATGPT -> MEDIUM
                ApiFormat.OPENAI_CHAT -> if (chatEffortHost(baseUrl) == "api.openai.com") MEDIUM else null
                ApiFormat.ON_DEVICE -> null
            }
        }

        private val MEDIUM_DEFAULT_CLAUDE = listOf("claude-opus-5-5", "claude-haiku-5-5")

        /** The level a provider's saved effort means: [fromWire] when set, else [modelDefault] (high when unknown). */
        fun ofProvider(value: String?, format: ApiFormat, baseUrl: String, model: String): Effort =
            if (value.isNullOrBlank()) modelDefault(format, baseUrl, model) ?: HIGH else fromWire(value)

        /** How a provider's own effort setting reads, e.g. "Medium, the model's own default" or "High, set for this provider". */
        fun describeProviderDefault(value: String?, format: ApiFormat, baseUrl: String, model: String): String =
            if (value.isNullOrBlank()) {
                modelDefault(format, baseUrl, model)?.let { "${it.label}, the model's own default." } ?: "The model's own default."
            } else {
                "${fromWire(value).label}, the provider's own setting."
            }

        /** The level a provider's saved effort string corresponds to, for showing the default. */
        fun fromWire(value: String?): Effort = when (value?.lowercase()) {
            "none", "minimal" -> OFF
            "low" -> LOW
            "medium" -> MEDIUM
            "xhigh", "max" -> MAX
            else -> HIGH
        }
    }
}

/**
 * Groups of tools the user can switch off for one chat from the composer's tools menu. Switching off
 * never removes a tool from the request (that would break the provider's cached prefix); the tools
 * stay declared and refuse to run, like a tool set to Deny.
 */
enum class ToolGroup(val label: String, val description: String) {
    WEB("Web search", "Search and read the web, Wikipedia, weather"),
    BROWSER("Browser", "Open and use websites"),
    LINUX("Linux shell", "Run commands and services on the phone"),
    SSH("SSH machines", "Run commands and copy files on your machines"),
    PHONE("Phone apps", "Calendar, contacts, photos, alarms, messages and other apps"),
    EMAIL("Email", "Read and send mail"),
    GITHUB("GitHub", "Repos, issues and files"),
    MEMORY("Memory and skills", "Recall and save what it learns"),
    ;

    /** The key stored in a conversation's disabled set. */
    val key: String get() = "group:${name.lowercase()}"

    companion object {
        fun of(toolName: String): ToolGroup? = when {
            toolName in setOf("web_search", "web_read", "fetch_url", "wikipedia", "weather") -> WEB
            toolName.startsWith("browser_") -> BROWSER
            toolName == "run_command" || toolName.startsWith("linux_") || toolName.startsWith("git_") -> LINUX
            toolName.startsWith("ssh_") || toolName.startsWith("sftp_") -> SSH
            toolName.startsWith("device_") || toolName == "notify_me" || DeviceIntegrations.isIntegration(toolName) -> PHONE
            toolName.startsWith("email_") -> EMAIL
            toolName.startsWith("github_") -> GITHUB
            toolName.startsWith("memory_") || toolName.startsWith("skill_") -> MEMORY
            else -> null
        }

        /** The key under which one connector (HTTP or MCP) is switched off for a chat. */
        fun connectorKey(connectorId: String) = "connector:$connectorId"

        /** Tools that address a connector by a `connector` argument. */
        val CONNECTOR_TOOLS = setOf("connector_call", "mcp_call", "mcp_list_tools")
    }
}

/** One item of the agent's checklist (`todo_write`). */
data class TodoItem(val content: String, val status: TodoStatus, val activeForm: String? = null) {
    /** What to show: the present-tense form while it's being worked on. */
    val shown: String get() = if (status == TodoStatus.IN_PROGRESS && !activeForm.isNullOrBlank()) activeForm else content
}

enum class TodoStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    ;

    companion object {
        fun parse(value: String?): TodoStatus? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** A plan the agent proposed in plan mode (`plan_propose`). */
data class ProposedPlan(val title: String, val steps: List<String>, val notes: String? = null)

/** Names of the tools behind the checklist and plan cards; the chat renders them specially. */
object AgentControlTools {
    const val TODO_WRITE = "todo_write"
    const val PLAN_PROPOSE = "plan_propose"
    const val ASK_USER = "ask_user"

    /** The message sent when the user taps "Approve and run" on a proposed plan. */
    const val PLAN_APPROVED = "Approved. Go ahead with the plan."
}
