package dev.locus.feature.library

import android.content.res.Resources
import dev.locus.core.data.repository.ConnectorEntry
import dev.locus.core.data.repository.KeyState

/**
 * What a remote connector's card says about its key or sign-in. A draft never reads "Signed in" or
 * "Saved": a key that exists was saved for something the user already set up, so the card says
 * whose it is and where it goes (docs/design/mcp-connectors.md, "Keys belong to an address").
 */
internal fun connectorKeyNote(resources: Resources, entry: ConnectorEntry): String? {
    val c = entry.connector
    val key = entry.key
    if (c.auth.type == "none") return null
    if (c.auth.type == "oauth") {
        return when {
            key is KeyState.Elsewhere -> resources.getString(
                if (entry.draft) R.string.library_connectors_key_signin_elsewhere_draft else R.string.library_connectors_key_signin_elsewhere,
                key.where.joinToString(), c.host,
            )
            key != KeyState.Ready ->
                resources.getString(if (entry.draft) R.string.library_connectors_key_signin_needed_draft else R.string.library_connectors_key_signin_needed)
            entry.draft -> resources.getString(R.string.library_connectors_key_signin_draft_ready, c.host)
            else -> resources.getString(R.string.library_connectors_key_signed_in)
        }
    }
    // The header or query parameter it goes in is named as the connector spells it.
    val how = if (c.auth.type == "bearer") resources.getString(R.string.library_connectors_key_bearer) else "${c.auth.type} ${c.auth.name}"
    val shared = entry.sharedWith.takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.library_connectors_key_shared, it.joinToString()) }
    val state = when (key) {
        KeyState.Missing -> listOf(resources.getString(R.string.library_connectors_key_missing))
        KeyState.Unconfirmed -> listOfNotNull(resources.getString(R.string.library_connectors_key_unconfirmed, c.host), shared)
        is KeyState.Elsewhere -> listOf(resources.getString(R.string.library_connectors_key_elsewhere, key.where.joinToString()))
        KeyState.Ready -> listOfNotNull(
            if (entry.draft) resources.getString(R.string.library_connectors_key_draft_ready, c.host) else resources.getString(R.string.library_connectors_key_saved),
            shared,
        )
    }
    return (listOf(resources.getString(R.string.library_connectors_key_intro, c.auth.secret, c.host, how)) + state).joinToString(" ")
}
