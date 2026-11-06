package dev.locus.core.model

import kotlinx.serialization.Serializable

/**
 * An onboarding step that Quick start skipped (docs/design/onboarding-quick-start.md). Each one comes back
 * on Home as a setup card until it's set up or dismissed, in this order: the agent first, then what it
 * can reach.
 */
@Serializable
enum class SetupItem {
    /** The agent's name, tone and what it knows about the user (the persona files). */
    Identity,

    /** Linux on the phone, installed from the Workspace. */
    Linux,

    /** An SSH machine the agent can run commands on. */
    Ssh,

    /** A built-in integration's key, or a connector (MCP or HTTP). */
    Connectors,
}
