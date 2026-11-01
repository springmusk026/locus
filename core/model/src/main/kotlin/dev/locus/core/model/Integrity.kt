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
    fun payload(s: AppSettings): ByteArray {
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
            },
        )
        return obj.toString().encodeToByteArray()
    }

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
        s.toolPolicies.isEmpty() && s.reviewerMode == ReviewerMode.OFF && !s.reviewerApprovesOutward && s.projectPolicies.values.all { it.isEmpty }

    fun seal(s: AppSettings, key: IntegrityKey): String = PREFIX + Hex.of(key.mac(payload(s)))

    fun sealed(s: AppSettings, key: IntegrityKey): AppSettings = s.copy(policySeal = seal(s, key))

    fun check(s: AppSettings, key: IntegrityKey): SealCheck = try {
        when {
            isDefault(s) -> SealCheck.DEFAULTS
            s.policySeal.isNotEmpty() && Hex.equal(s.policySeal, seal(s, key)) -> SealCheck.VALID
            s.policySeal.isEmpty() && key.createdThisRun() -> SealCheck.FIRST_USE
            else -> SealCheck.TAMPERED
        }
    } catch (_: Exception) {
        SealCheck.TAMPERED
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
        policySeal = "",
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
