package dev.locus.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * A MAC key Locus keeps where nothing else running as its uid can use it: in the app, an HMAC-SHA256
 * key in the Android Keystore (docs/design/linux-isolation.md, "Sealed permissions").
 */
interface IntegrityKey {
    /** HMAC-SHA256 of [data]. Throws if the key can't be used. */
    fun mac(data: ByteArray): ByteArray

    /**
     * True when the key didn't exist before this process created it. Nothing can have been sealed
     * with it yet, so state found without a seal is trusted once (an update from a version without
     * seals) and sealed. Loads or creates the key.
     */
    fun createdThisRun(): Boolean
}

/** Hex encoding and a constant-time comparison for seals. */
internal object Hex {
    fun of(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

/** The outcome of checking sealed state. */
enum class SealCheck {
    /** Nothing is granted beyond the built-in defaults, so there is nothing to protect. */
    DEFAULTS,

    /** The seal matches. */
    VALID,

    /** No seal, and the key is new: state from a version without seals, trusted once and sealed. */
    FIRST_USE,

    /**
     * Sealed (or left unsealed as defaults) by a Locus that didn't yet seal the Telegram chat id and
     * `since`. Everything else checks out; those two are dropped ([PolicySeal.migrated]) and the
     * settings resealed, without an alert.
     */
    LEGACY,

    /** The seal is missing, wrong or can't be checked: the state was changed outside Locus. */
    TAMPERED,
}

/**
 * The seal over the settings that grant permissions: the per-tool policies and the AI reviewer's
 * settings. Anything that changes them without the key (another process writing settings.json)
 * breaks the seal, and Locus then falls back to [restricted] and tells the user.
 */
object PolicySeal {
    private const val PREFIX = "v1:"
    private const val CONTEXT = "locus-tool-policies-v1"

    /** What is sealed, in a fixed order: tool names sorted, enums by name. */
    fun payload(s: AppSettings): ByteArray = payload(s, legacy = false)

    /**
     * [legacy]: the payload before the Telegram chat id and `since` were sealed, for [check]ing seals
     * made by that version. Settings that never set them give the same payload either way.
     */
    private fun payload(s: AppSettings, legacy: Boolean): ByteArray {
        val obj = JsonObject(
            linkedMapOf(
                "context" to JsonPrimitive(CONTEXT),
                "toolPolicies" to JsonObject(s.toolPolicies.toSortedMap().mapValues { JsonPrimitive(it.value.name) }),
                "reviewerMode" to JsonPrimitive(s.reviewerMode.name),
                "reviewerApprovesOutward" to JsonPrimitive(s.reviewerApprovesOutward),
            ).apply {
                // Only when there are any, so seals made before projects existed still match.
                val projects = s.projectPolicies.filterValues { !it.isEmpty }
                if (projects.isNotEmpty()) put("projectPolicies", JsonObject(projects.toSortedMap().mapValues { (_, p) -> projectPayload(p) }))
                // Likewise remote execution (docs/design/remote-execution.md): it sends a project's files off the phone.
                if (s.projectRemotes.isNotEmpty()) {
                    put(
                        "projectRemotes",
                        JsonObject(
                            s.projectRemotes.toSortedMap().mapValues { (_, r) ->
                                JsonObject(linkedMapOf("hostId" to JsonPrimitive(r.hostId), "folder" to JsonPrimitive(r.folder)))
                            },
                        ),
                    )
                }
                // The automation intents (docs/design/android-bridge.md): which apps may start tasks.
                if (s.automationEnabled) put("automationEnabled", JsonPrimitive(true))
                if (s.automationCallers.isNotEmpty()) put("automationCallers", JsonObject(s.automationCallers.toSortedMap().mapValues { JsonPrimitive(it.value) }))
                // The Telegram remote channel (docs/design/telegram-remote.md): what the bot chat may do.
                // The chat id decides where remote messages go and which chat is read-only; since, which messages count.
                val t = s.telegramRemote
                if (if (legacy) !t.isLegacyDefault else !t.isDefault) {
                    put(
                        "telegramRemote",
                        JsonObject(
                            linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                                "approvals" to JsonPrimitive(t.approvals),
                                "inbound" to JsonPrimitive(t.inbound),
                                "until" to JsonPrimitive(t.until),
                                "widened" to JsonPrimitive(t.widened),
                            ).apply {
                                if (!legacy) {
                                    put("since", JsonPrimitive(t.since))
                                    put("chatId", t.chatId?.let(::JsonPrimitive) ?: JsonNull)
                                }
                            },
                        ),
                    )
                }
                // Remembered approvals (docs/design/remembered-approvals.md): each one allows calls without asking.
                if (s.rememberedApprovals.isNotEmpty()) put("rememberedApprovals", JsonArray(s.rememberedApprovals.sortedBy { it.id }.map(::rulePayload)))
            },
        )
        return obj.toString().encodeToByteArray()
    }

    private fun rulePayload(r: RememberedApproval) = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(r.id),
            "tool" to JsonPrimitive(r.tool),
            "kind" to JsonPrimitive(r.kind.name),
            "value" to JsonPrimitive(r.value),
            "projectId" to (r.projectId?.let(::JsonPrimitive) ?: JsonNull),
            "conversationId" to (r.conversationId?.let(::JsonPrimitive) ?: JsonNull),
            "createdAt" to JsonPrimitive(r.createdAt),
            "expiresAt" to (r.expiresAt?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private fun projectPayload(p: ProjectPolicy) = JsonObject(
        linkedMapOf(
            "toolPolicies" to JsonObject(p.toolPolicies.toSortedMap().mapValues { JsonPrimitive(it.value.name) }),
            "widened" to JsonArray(p.widened.sorted().map(::JsonPrimitive)),
            "allowedConnectors" to (p.allowedConnectors?.let { set -> JsonArray(set.sorted().map(::JsonPrimitive)) } ?: JsonNull),
        ),
    )

    /**
     * True when nothing is granted beyond the defaults: no overrides, reviewer off, no project
     * permissions (even narrowing ones are sealed, so a change to them is noticed).
     */
    fun isDefault(s: AppSettings): Boolean =
        s.toolPolicies.isEmpty() && s.reviewerMode == ReviewerMode.OFF && !s.reviewerApprovesOutward && s.projectPolicies.values.all { it.isEmpty } &&
            s.projectRemotes.isEmpty() && !s.automationEnabled && s.automationCallers.isEmpty() && s.telegramRemote.isDefault &&
            s.rememberedApprovals.isEmpty()

    fun seal(s: AppSettings, key: IntegrityKey): String = PREFIX + Hex.of(key.mac(payload(s)))

    /** The seal the version before the chat id and `since` were sealed would have made. */
    internal fun legacySeal(s: AppSettings, key: IntegrityKey): String = PREFIX + Hex.of(key.mac(payload(s, legacy = true)))

    fun sealed(s: AppSettings, key: IntegrityKey): AppSettings = s.copy(policySeal = seal(s, key))

    /** Whether the Telegram fields only this version seals are set. */
    private fun hasNewlySealed(s: AppSettings) = s.telegramRemote.chatId != null || s.telegramRemote.since != 0L

    fun check(s: AppSettings, key: IntegrityKey): SealCheck = try {
        when {
            isDefault(s) -> SealCheck.DEFAULTS
            s.policySeal.isNotEmpty() && Hex.equal(s.policySeal, seal(s, key)) -> SealCheck.VALID
            // An install from before the chat id was sealed: its seal (or, with nothing else granted,
            // its lack of one) covers everything but the chat id and since, which [migrated] drops.
            hasNewlySealed(s) && s.policySeal.isNotEmpty() && Hex.equal(s.policySeal, legacySeal(s, key)) -> SealCheck.LEGACY
            hasNewlySealed(s) && s.policySeal.isEmpty() && isDefault(s.copy(telegramRemote = TelegramRemoteSettings())) &&
                s.telegramRemote.isLegacyDefault -> SealCheck.LEGACY
            s.policySeal.isEmpty() && key.createdThisRun() -> SealCheck.FIRST_USE
            else -> SealCheck.TAMPERED
        }
    } catch (_: Exception) {
        SealCheck.TAMPERED
    }

    /**
     * Settings that checked out as [SealCheck.LEGACY], with the fields their seal didn't cover made safe:
     * the chat id is dropped (the next remote message makes a new Telegram chat), and while remote mode
     * is on, messages from before [now] are ignored. Replaying an old seal therefore can't point remote
     * messages at another chat or let older messages in.
     */
    fun migrated(s: AppSettings, now: Long): AppSettings {
        val t = s.telegramRemote
        return s.copy(telegramRemote = t.copy(chatId = null, since = if (t.inbound) maxOf(t.since, now) else 0L))
    }

    /**
     * What is kept of state that failed its check: only what restricts. Allow overrides are dropped
     * (those tools go back to their defaults: only reads run without asking), Ask and Deny overrides
     * stay (in projects too, where widenings are dropped), and the reviewer is off, so every action that would ask comes to the user.
     */
    fun restricted(s: AppSettings): AppSettings = s.copy(
        toolPolicies = s.toolPolicies.filterValues { it != ToolPolicy.ALLOW },
        reviewerMode = ReviewerMode.OFF,
        reviewerApprovesOutward = false,
        projectPolicies = s.projectPolicies.mapValues { it.value.restricted() },
        // Back to Linux on the phone: nothing leaves it until the user links a machine again.
        projectRemotes = emptyMap(),
        // Other apps can't start tasks again until the user turns it back on; "never" answers stay.
        automationEnabled = false,
        automationCallers = s.automationCallers.filterValues { !it },
        // The Telegram chat can't approve or start anything until the user turns it on again, and its
        // chat id isn't trusted either: the next remote message makes a new Telegram chat.
        telegramRemote = s.telegramRemote.copy(approvals = false, inbound = false, widened = false, chatId = null, since = 0L),
        // Every remembered approval allows something, so none survives a broken seal.
        rememberedApprovals = emptyList(),
        policySeal = "",
    )
}

/**
 * The seal over where requests and keys go: each provider's address, format and network settings
 * (header names, proxy, trusted certificates), the default proxy and imported certificates, and each
 * SSH machine's address, user and confirmed host key. Changing any of them outside Locus could send an
 * API key and the conversation to another server, or a project to another machine, without a word.
 *
 * It has a Keystore key of its own ([AppSettings.endpointSeal]): the key's existence marks that this
 * install has sealed its endpoints, so an empty seal is trusted only in the run that creates that key
 * (the update from a version without it), never because a file was rolled back. On a broken seal,
 * [restricted] keeps the settings but sends no key until the user confirms each address, drops proxies
 * and certificates, and clears SSH host keys so the next connection asks again.
 */
object EndpointSeal {
    private const val PREFIX = "e1:"
    private const val CONTEXT = "locus-endpoints-v1"

    fun payload(s: AppSettings): ByteArray = JsonObject(
        linkedMapOf(
            "context" to JsonPrimitive(CONTEXT),
            "providers" to JsonArray(s.providers.sortedBy { it.id }.map(::providerPayload)),
            "proxy" to proxyPayload(s.network.proxy),
            "certificates" to JsonArray(
                s.network.certificates.sortedBy { it.id }.map { c ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonPrimitive(c.id),
                            // The PEM itself, not the stored fingerprint, which is only a label.
                            "pem" to JsonPrimitive(ConnectorSealFormat.sha256(c.pem.encodeToByteArray())),
                            "allProviders" to JsonPrimitive(c.allProviders),
                        ),
                    )
                },
            ),
            "sshHosts" to JsonArray(
                s.sshHosts.sortedBy { it.id }.map { h ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonPrimitive(h.id),
                            "host" to JsonPrimitive(h.host),
                            "port" to JsonPrimitive(h.port),
                            "user" to JsonPrimitive(h.user),
                            "fingerprint" to JsonPrimitive(h.fingerprint),
                        ),
                    )
                },
            ),
        ),
    ).toString().encodeToByteArray()

    private fun providerPayload(p: ProviderConfig) = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(p.id),
            "format" to JsonPrimitive(p.format.name),
            "baseUrl" to JsonPrimitive(p.baseUrl),
            "addressUnconfirmed" to JsonPrimitive(p.addressUnconfirmed),
            "headerNames" to JsonArray(p.network.headerNames.sorted().map(::JsonPrimitive)),
            "allowAuthOverride" to JsonPrimitive(p.network.allowAuthOverride),
            "proxyMode" to JsonPrimitive(p.network.proxyMode.name),
            "proxy" to proxyPayload(p.network.proxy),
            "certificateIds" to JsonArray(p.network.certificateIds.sorted().map(::JsonPrimitive)),
        ),
    )

    private fun proxyPayload(p: ProxyConfig?) = p?.let {
        JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive(it.type.name),
                "host" to JsonPrimitive(it.host),
                "port" to JsonPrimitive(it.port),
                "hasCredentials" to JsonPrimitive(it.hasCredentials),
            ),
        )
    } ?: JsonNull

    /** Nothing to protect: no providers, SSH machines, proxy or certificates. */
    fun isDefault(s: AppSettings): Boolean = s.providers.isEmpty() && s.sshHosts.isEmpty() && s.network == NetworkDefaults()

    fun seal(s: AppSettings, key: IntegrityKey): String = PREFIX + Hex.of(key.mac(payload(s)))

    fun sealed(s: AppSettings, key: IntegrityKey): AppSettings = s.copy(endpointSeal = if (isDefault(s)) "" else seal(s, key))

    /**
     * [firstUseAllowed]: no endpoint seal has been written in this process yet. An empty seal is
     * trusted only then, and only when the key is new (an update from a version that didn't seal endpoints).
     */
    fun check(s: AppSettings, key: IntegrityKey, firstUseAllowed: Boolean = true): SealCheck = try {
        when {
            isDefault(s) -> SealCheck.DEFAULTS
            s.endpointSeal.isNotEmpty() && Hex.equal(s.endpointSeal, seal(s, key)) -> SealCheck.VALID
            s.endpointSeal.isEmpty() && firstUseAllowed && key.createdThisRun() -> SealCheck.FIRST_USE
            else -> SealCheck.TAMPERED
        }
    } catch (_: Exception) {
        SealCheck.TAMPERED
    }

    /**
     * Settings whose endpoints failed their check, made safe without losing them: every provider with
     * an address waits for the user to confirm it before its key is sent, proxies and imported
     * certificates are dropped (they could intercept traffic) and so is replacing the credential header,
     * and SSH host keys are cleared, so each machine's key is confirmed again on the next connection.
     */
    fun restricted(s: AppSettings): AppSettings = s.copy(
        providers = s.providers.map { p ->
            p.copy(
                addressUnconfirmed = p.addressUnconfirmed || p.baseUrl.isNotBlank(),
                network = p.network.copy(
                    allowAuthOverride = false,
                    proxyMode = if (p.network.proxyMode == ProxyMode.CUSTOM) ProxyMode.DIRECT else p.network.proxyMode,
                    proxy = null,
                    certificateIds = emptyList(),
                ),
            )
        },
        network = NetworkDefaults(),
        sshHosts = s.sshHosts.map { it.copy(fingerprint = "") },
        endpointSeal = "",
    )
}

/**
 * The seal over which connectors the user approved: each active connector's id and the SHA-256 of
 * its file. A connector file added or changed outside Locus doesn't match, and is sent back to
 * drafts for review.
 */
object ConnectorSealFormat {
    private const val CONTEXT = "locus-connectors-v1"

    fun sha256(bytes: ByteArray): String = Hex.of(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun payload(connectors: Map<String, String>): ByteArray = JsonObject(
        linkedMapOf(
            "context" to JsonPrimitive(CONTEXT),
            "connectors" to JsonObject(connectors.toSortedMap().mapValues { JsonPrimitive(it.value) }),
        ),
    ).toString().encodeToByteArray()

    fun mac(connectors: Map<String, String>, key: IntegrityKey): String = Hex.of(key.mac(payload(connectors)))

    fun matches(connectors: Map<String, String>, mac: String, key: IntegrityKey): Boolean =
        runCatching { Hex.equal(mac, mac(connectors, key)) }.getOrDefault(false)
}

/**
 * The seal over what instructs the agent and that the user approved: active skills, helper
 * definitions and the persona files (docs/design/linux-isolation.md, "Sealed instructions"). Each
 * set has its own [context], so a manifest sealed for one set can't be replayed as another's.
 */
object InstructionSealFormat {
    fun payload(context: String, items: Map<String, String>): ByteArray = JsonObject(
        linkedMapOf(
            "context" to JsonPrimitive(context),
            "items" to JsonObject(items.toSortedMap().mapValues { JsonPrimitive(it.value) }),
        ),
    ).toString().encodeToByteArray()

    fun mac(context: String, items: Map<String, String>, key: IntegrityKey): String = Hex.of(key.mac(payload(context, items)))

    fun matches(context: String, items: Map<String, String>, mac: String, key: IntegrityKey): Boolean =
        runCatching { Hex.equal(mac, mac(context, items, key)) }.getOrDefault(false)
}
