package dev.locus.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val defaultProviderId: String? = null,
    /** Per-tool overrides; tools not listed use their risk's default policy. */
    val toolPolicies: Map<String, ToolPolicy> = emptyMap(),
    /** Appended to the system prompt of new conversations: persona, preferences, house rules. */
    val customInstructions: String = "",
    val sshHosts: List<SshHost> = emptyList(),
    /** After a longer task, one short extra model call may save lessons to memory. */
    val reflectAfterTasks: Boolean = true,
    /** A cheap, fast model for side jobs (titles, suggestions, compaction); null means each chat's own. */
    val helperProviderId: String? = null,
    val helperModel: String = "",
    val autoTitle: Boolean = true,
    val suggestFollowUps: Boolean = true,
    /** Set when the user finishes (or skips through) first-run onboarding. */
    val onboardingDone: Boolean = false,
    /** The AI safety reviewer for tool calls; runs on the helper model. Off until the user turns it on. */
    val reviewerMode: ReviewerMode = ReviewerMode.OFF,
    /**
     * Whether the reviewer may approve actions that reach outside on their own (calls that always
     * confirm, such as sending an email or deploying). Off: those always come to the user.
     */
    val reviewerApprovesOutward: Boolean = false,
    /**
     * When a background helper (sub-agent) finishes after the chat's turn ended, add its report to the
     * chat and let the agent continue. Off: the report waits until the agent asks for it.
     */
    val subagentReportsContinue: Boolean = true,
    /** Models starred in the model sheet; listed first there, in the order starred. */
    val favoriteModels: List<ModelRef> = emptyList(),
    /** Models picked lately in the model sheet, newest first, at most [ModelRefs.MAX_RECENT]. */
    val recentModels: List<ModelRef> = emptyList(),
    /**
     * Fill in context sizes and capabilities from the public models.dev catalogue, fetched at most
     * once a day. Off until the user turns it on: it's the one request to a host they didn't add.
     */
    val modelCatalog: Boolean = false,
    /** The user's own helper prompts (see [HelperPrompts]); null means the built-in default. */
    val titlePrompt: String? = null,
    val suggestionsPrompt: String? = null,
    val compactionPrompt: String? = null,
    /** The default proxy for model providers and the imported CA certificates (Settings → Network). */
    val network: NetworkDefaults = NetworkDefaults(),
    /**
     * The default budget for each task (a user message and the helpers it starts): tokens, estimated
     * cost and time. Off by default; a chat can set its own in the composer's options.
     */
    val taskBudget: TaskBudget = TaskBudget(),
    /**
     * Built-in integrations (ids) the user keeps on their built-in tools in new chats even when the
     * vendor's MCP server is connected too (Settings → Integrations, "Use the built-in tools instead").
     */
    val builtInOverMcp: Set<String> = emptySet(),
    /**
     * Backup models, tried in order when a chat's own model can't answer: it's out of quota, its key
     * is refused, or it keeps failing with rate limits or overload after retries (Settings → Models →
     * Backup models). Empty: no fallback. See [FallbackChain] and docs/design/fallbacks.md.
     */
    val fallbackModels: List<ModelRef> = emptyList(),
    /**
     * Each project's permissions (by project id): an overlay on [toolPolicies] that can only narrow
     * them unless the user widened a tool explicitly (docs/design/projects.md). Sealed with
     * [toolPolicies], so a change made outside Locus can't grant a project anything.
     */
    val projectPolicies: Map<String, ProjectPolicy> = emptyMap(),
    /**
     * HMAC of [toolPolicies], [projectPolicies] and the reviewer settings under a Keystore key ([PolicySeal]). Only the
     * settings store writes it; settings changed outside Locus fail the check and fall back to
     * [PolicySeal.restricted].
     */
    val policySeal: String = "",
) {
    /**
     * Whether to open onboarding at launch. People who set up a provider before onboarding existed
     * aren't sent through it; they can still re-run it from Settings.
     */
    val needsOnboarding: Boolean get() = !onboardingDone && providers.isEmpty()

    fun provider(id: String?): ProviderConfig? =
        providers.firstOrNull { it.id == id } ?: providers.firstOrNull { it.id == defaultProviderId } ?: providers.firstOrNull()
}

/** A machine the user allows the agent to reach over SSH. Credentials live in the vault. */
@Serializable
data class SshHost(
    val id: String,
    val alias: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    /** [SshAuth.id]: "key", "password" or "none". Kept as a string so older saved hosts load unchanged. */
    val auth: String = SshAuth.KEY.id,
    /** SHA256:… host key fingerprint the user confirmed; connections to any other key are refused. */
    val fingerprint: String = "",
) {
    val authMethod: SshAuth get() = SshAuth.of(auth)
}

/** How Locus logs in to an [SshHost]. */
enum class SshAuth(val id: String) {
    KEY("key"),
    PASSWORD("password"),

    /**
     * No credentials: SSH's "none" method, then keyboard-interactive answered with empty responses.
     * For throwaway and sandbox machines that accept anyone; the host key is still pinned.
     */
    NONE("none"),
    ;

    companion object {
        /** Unknown or missing values fall back to [KEY], the old default, which also tries a saved password. */
        fun of(id: String?): SshAuth = entries.firstOrNull { it.id == id } ?: KEY
    }
}
