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
    val maxStepsPerTurn: Int = 40,
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
    /** "password" or "key". */
    val auth: String = "key",
    /** SHA256:… host key fingerprint the user confirmed; connections to any other key are refused. */
    val fingerprint: String = "",
)
