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
    /**
     * Onboarding steps skipped with Quick start that Home still offers as setup cards, until each is set
     * up or dismissed (docs/design/onboarding-quick-start.md). Empty by default, so people who set up
     * before Quick start existed, or went through every step, never see a card.
     */
    val setupLater: Set<SetupItem> = emptySet(),
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
    /**
     * How long one helper (sub-agent) run may take before it stops and hands back what it had, in
     * minutes (one of [HelperTimeLimit.CHOICES]). Waiting for the user's approval counts.
     */
    val helperTimeoutMinutes: Int = HelperTimeLimit.DEFAULT_MINUTES,
    /** Models starred in the model sheet; listed first there, in the order starred. */
    val favoriteModels: List<ModelRef> = emptyList(),
    /** Models picked lately in the model sheet, newest first, at most [ModelRefs.MAX_RECENT]. */
    val recentModels: List<ModelRef> = emptyList(),
    /**
     * Fill in context sizes and capabilities from the public models.dev catalogue, fetched at most
     * once a day. Off until the user turns it on: it's the one request to a host they didn't add.
     */
    val modelCatalog: Boolean = false,
    /**
     * Ask GitHub about once a day, on Wi-Fi, whether a newer Locus release exists (docs/design/updates.md).
     * Off until the user turns it on in onboarding or Settings → About & privacy. Not sealed: it grants
     * the agent nothing, and anything that could flip it can make its own requests anyway.
     */
    val updateCheck: Boolean = false,
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
    /** Settings → Battery & heat (docs/design/battery-thermal.md). */
    val power: PowerSettings = PowerSettings(),
    /** Settings → Voice: read-aloud speed and voice mode's behaviour (docs/design/voice-mode.md). */
    val voice: VoiceSettings = VoiceSettings(),
    /**
     * Projects whose commands run on one of the user's SSH machines instead of Linux on the phone (by
     * project id; docs/design/remote-execution.md). Sealed with the permissions: it sends the project's
     * files off the phone, so a change made outside Locus must not be able to switch it on.
     */
    val projectRemotes: Map<String, ProjectRemote> = emptyMap(),
    /**
     * Whether other apps (Tasker, Automate…) may ask Locus to start tasks through its automation
     * intents (docs/design/android-bridge.md). Off by default; sealed with the permissions.
     */
    val automationEnabled: Boolean = false,
    /**
     * Apps the user answered for once, by package: true "always allow", false "never". An app not
     * listed is asked about each time. Sealed, so a change made outside Locus can't allow an app.
     */
    val automationCallers: Map<String, Boolean> = emptyMap(),
    /**
     * The Telegram remote channel (docs/design/telegram-remote.md): approvals by button and remote
     * mode. Off by default; sealed with the permissions, since both let a Telegram chat act for the user.
     */
    val telegramRemote: TelegramRemoteSettings = TelegramRemoteSettings(),
    /**
     * "Allow for this chat" and "Always allow" answers to approval cards, each for one tool and one
     * site, folder, recipient or command prefix (docs/design/remembered-approvals.md). Sealed with the
     * permissions, so a change made outside Locus can't add one.
     */
    val rememberedApprovals: List<RememberedApproval> = emptyList(),
    /**
     * HMAC of [toolPolicies], [projectPolicies] and the reviewer settings under a Keystore key ([PolicySeal]). Only the
     * settings store writes it; settings changed outside Locus fail the check and fall back to
     * [PolicySeal.restricted].
     */
    val policySeal: String = "",
    /**
     * HMAC of where requests and keys go: provider addresses and network settings, the default proxy
     * and imported certificates, and SSH machines with their host keys ([EndpointSeal]), under a Keystore
     * key of its own. Only the settings store writes it.
     */
    val endpointSeal: String = "",
) {
    /**
     * Whether to open onboarding at launch. People who set up a provider before onboarding existed
     * aren't sent through it; they can still re-run it from Settings.
     */
    val needsOnboarding: Boolean get() = !onboardingDone && providers.isEmpty()

    fun provider(id: String?): ProviderConfig? =
        providers.firstOrNull { it.id == id } ?: providers.firstOrNull { it.id == defaultProviderId } ?: providers.firstOrNull()
}

/**
 * Settings → Integrations → Telegram → Remote (docs/design/telegram-remote.md). The bot token and chat
 * live in the vault; this only says what that chat may do.
 */
@Serializable
data class TelegramRemoteSettings(
    /** Send approvals of tasks running in the background to the bot chat with Allow and Deny buttons. */
    val approvals: Boolean = false,
    /** Remote mode: messages to the bot start tasks in the Telegram chat. Only until [until]. */
    val inbound: Boolean = false,
    /** When remote mode switched on (epoch ms); messages sent before it are ignored. */
    val since: Long = 0,
    /** When remote mode switches itself off (epoch ms). */
    val until: Long = 0,
    /**
     * Off (the default): tasks from Telegram run under a scheduled run's rules (read-only, nothing that
     * asks). On: they run as a chat does, with approvals; tainted ones still need a tap in Locus.
     */
    val widened: Boolean = false,
    /** The chat ("Telegram") remote messages go to; created on the first one. */
    val chatId: String? = null,
) {
    /** Whether remote mode is on at [now]: switched on and not yet timed out. */
    fun inboundOn(now: Long): Boolean = inbound && now < until

    /**
     * When remote mode switches itself off, or null while it isn't switched on. A screen showing "On
     * until …" ticks against this (it may already be past), rather than reading the clock once.
     */
    val switchesOffAt: Long? get() = until.takeIf { inbound }

    /** Whether [conversationId] is the Telegram chat and runs under the read-only rules. */
    fun lockedChat(conversationId: String): Boolean = chatId != null && chatId == conversationId && !widened

    /**
     * Nothing set that the seal protects. The chat id counts: it decides which chat remote messages
     * go to and which chat is read-only, so it is sealed too, and so is [since].
     */
    val isDefault: Boolean get() = !approvals && !inbound && !widened && chatId == null && since == 0L

    /** [isDefault] as Locus judged it before the chat id and [since] were sealed. */
    internal val isLegacyDefault: Boolean get() = !approvals && !inbound && !widened
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
